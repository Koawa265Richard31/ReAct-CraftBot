package com.dwinovo.numen.client.live;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * /live 只读小门:回环上的第二个 HTTP 端点(8766),<b>与 MCP 服务器完全平行</b>——
 * 不调 {@code McpMode.touch()},不进 {@code dispatchToolCall},结构上就续不了租约:
 * 本类对 {@code McpMode} 只做只读查询({@code McpConfig} 的 live 段与 {@code McpMode.enabled()}),
 * 没有任何写路径。
 *
 * <h2>线程与数据边界</h2>
 * 两个守护线程专管 HTTP;它们能碰到的只有 {@link LiveState#snapshotJson()} 发布的定型
 * JSON 文本,摸不到 Minecraft。/health 与 /live 同理。
 *
 * <h2>鉴权——独立密钥,头传,查询串永不接受</h2>
 * {@code X-Numen-Live} 头必须等于 {@code live.token};查询参数一律不看(设计稿 §10.1-2:
 * 查询串会落进代理日志、浏览器历史、进程诊断输出,回环也一样)。MCP 服务器为桌面桥留的
 * {@code ?token=} 先例( {@code McpServer.java:212-213})在这里<b>不跟</b>。
 *
 * <h2>绑定</h2>
 * 绑定地址<b>写死回环</b>,配置里的 {@code live.host} 只做校验用——写别的值直接拒绝启动并记
 * ERROR(设计稿 §五/§九)。绑定失败(端口被占等)记 ERROR 带原因,不静默。
 *
 * <p>HTTP 服务器照 {@code McpServer.start()} 的创建方式({@code com.sun.net.httpserver},
 * 同 jar 里 8765 已稳定运行)。
 */
public final class LiveServer {

    /** 鉴权头名:独立于 MCP 的 Authorization。 */
    static final String AUTH_HEADER = "X-Numen-Live";

    private static volatile HttpServer http;

    private LiveServer() {}

    /**
     * 客户端 init 时调一次:读 {@link McpMode#config()} 的 live 段,开着就起服。
     * 起不来(端口被占/host 非回环)记 ERROR,不静默、不崩客户端——小门是加餐,不是主食。
     */
    public static synchronized void startFromConfig() {
        // 若日后有「配置热改后重读」之类的二次调用,先掐掉旧服,别让 8766 上挂着
        // 一份旧配置的僵尸(当前只有两个 loader 在 client init 各调一次);
        // stop() 幂等,首次调用这里只是空过一遍。
        stop();
        McpConfig cfg = McpMode.instance().config();
        if (!cfg.liveEnabled()) {
            Constants.LOG.info("[numen-live] 只读小门未开启(config/numen/mcp_server.json 的 live.enabled=false)");
            return;
        }
        String host = cfg.liveHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
            Constants.LOG.error("[numen-live] live.host=\"{}\" 不是回环地址,只允许 127.0.0.1/localhost "
                    + "—— 8766 拒绝启动(绑定地址写死回环,这个值只做校验)", host);
            return;
        }
        String token = cfg.liveToken();
        if (token == null || token.isBlank()) {
            // load() 会补生成并回写;走到这里说明回写失败了,如实报,别裸奔。
            Constants.LOG.error("[numen-live] live.token 为空且补生成失败——8766 拒绝启动(不做无鉴权只读口)");
            return;
        }
        if (token.equals(cfg.token())) {
            Constants.LOG.warn("[numen-live] live.token 与 MCP token 相同——两把钥匙应当分开,"
                    + "MCP token 能执行动作,泄露面更大;建议改掉其中一把");
        }
        int port = cfg.livePort();
        if (port < 1 || port > 65535) {
            Constants.LOG.error("[numen-live] live.port={} 非法(1-65535)——8766 拒绝启动", port);
            return;
        }
        try {
            // 照 McpServer.start() 的创建方式:com.sun.net.httpserver,同一个 jar 里 8765 在用。
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/live", ex -> handle(ex, token, true));
            server.createContext("/health", ex -> handle(ex, token, false));
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "numen-live-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            http = server;
            Constants.LOG.info("[numen-live] 只读小门已开启 http://127.0.0.1:{}/live "
                    + "(鉴权: {} 头,只读,不续租约)", port, AUTH_HEADER);
        } catch (IOException ex) {
            Constants.LOG.error("[numen-live] 绑定 127.0.0.1:{} 失败 — {} "
                    + "(端口被占?netstat -ano | findstr {} 查占用者)", port, ex.toString(), port);
        }
    }

    /** 关门;幂等——没起过或已关过时再调是无害空操作。 */
    public static synchronized void stop() {
        HttpServer s = http;
        http = null;
        if (s != null) {
            s.stop(0);
            Constants.LOG.info("[numen-live] 只读小门已关闭");
        }
    }

    // ---- HTTP 处理(HTTP 线程;能碰的只有 LiveState 发布的定型文本) ----

    private static void handle(HttpExchange ex, String token, boolean state) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            if (state && !authorized(ex, token)) {
                respond(ex, 401, "");
                return;
            }
            if (!state) {
                // /health:探活用,不含任何状态字段。
                respond(ex, 200, "{\"ok\":true}");
                return;
            }
            String snap = LiveState.snapshotJson();
            if (snap == null) {
                respond(ex, 200, "{\"t\":" + System.currentTimeMillis()
                        + ",\"self\":null,\"reason\":\"not in world\"}");
                return;
            }
            respond(ex, 200, snap);
        } catch (RuntimeException fatal) {
            Constants.LOG.warn("[numen-live] request failed: {}", fatal.toString());
            try {
                respond(ex, 500, "");
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    /**
     * 只认 {@code X-Numen-Live} 头,常量时间比较;查询参数<b>不接受</b>——即使带了正确的
     * {@code ?token=} 也 401(设计稿 §10.1-2 的硬口径)。
     */
    private static boolean authorized(HttpExchange ex, String token) {
        String got = ex.getRequestHeaders().getFirst(AUTH_HEADER);
        if (got == null) {
            return false;
        }
        return MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
