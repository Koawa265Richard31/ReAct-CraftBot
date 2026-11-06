package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server config at {@code config/numen/mcp_server.json}. Plain Gson-over-file,
 * created with defaults on first launch.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default false; owner opts in);</li>
 *   <li>{@code host}/{@code port} — where the MCP HTTP endpoint binds. Loopback
 *       by default; an external agent reaches it through the {@code mcp-remote}
 *       stdio bridge in {@code claude_desktop_config.json};</li>
 *   <li>{@code token} — optional bearer token; when set, requests must present it
 *       (Authorization header or {@code ?token=}). Empty = no auth (fine on
 *       loopback);</li>
 *   <li>{@code call_timeout_seconds} — how long one {@code tools/call} waits for a
 *       body action to finish before reporting a timeout;</li>
 *   <li>{@code hidden_tools} — tools NOT exposed to the external agent (by default
 *       none: the engine's tools are the script tool and the skill tool, and they are the
 *       external brain's as much as the built-in one's);</li>
 *   <li>{@code lease_seconds} — 外脑租约:距上一次成功的 {@code tools/call} 超过这么多秒,
 *       身体就进安全待机(叫停在跑的程序与任务,原地不动),外部请求一来立刻续约。默认
 *       {@value #DEFAULT_LEASE_SECONDS},夹在 {@value #MIN_LEASE_SECONDS}~{@value #MAX_LEASE_SECONDS};</li>
 *   <li>{@code quiet_fallback} — <b>已废弃,只为兼容旧配置文件保留</b>:读到了也只是日志提示,
 *       不再改变任何行为。失联回退(外脑一安静就让内置大脑接管)已被租约取代——内置大脑在
 *       模式开启期间永远停牌,见 {@link McpMode#driving()};</li>
 *   <li>{@code live} — 只读小门(/live)的配置段:{@code enabled}(默认 false)、{@code port}
 *       (默认 {@value #DEFAULT_LIVE_PORT})、{@code token}(独立于 MCP token 的另一把钥匙,
 *       空则启动时生成并回写)、{@code host}(只接受回环,写别的值 8766 拒绝启动;绑定地址
 *       在 {@code LiveServer} 里写死回环,这个值只用于校验);</li>
 *   <li>{@code brain} — 外接脑桥接配置段:{@code bridgeFile}——DSH 心跳插件写的
 *       {@code brain.json} 的路径;空 = 面板不显示 host 区块(等价于"没接外接脑")。</li>
 * </ul>
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        String token,
        int callTimeoutSeconds,
        List<String> hiddenTools,
        int leaseSeconds,
        boolean quietFallback,
        boolean liveEnabled,
        String liveHost,
        int livePort,
        String liveToken,
        String brainBridgeFile) {

    private static final List<String> DEFAULT_HIDDEN = List.of();

    /** 租约默认 90 秒:足够盖住一次 get_events 长停靠(最长 50 秒,且停靠期间那次调用算在场)。 */
    public static final int DEFAULT_LEASE_SECONDS = 90;
    /** 下限 30 秒:再短,一次长轮询的正常间隔就会被误判成失联。 */
    public static final int MIN_LEASE_SECONDS = 30;
    /** 上限 600 秒:再长,外脑真没了身体也要空等十分钟才待机。 */
    public static final int MAX_LEASE_SECONDS = 600;

    /** /live 默认关着:多开一个端口是面,主人点头才开。 */
    public static final boolean DEFAULT_LIVE_ENABLED = false;
    /** /live 的默认端口:8765 被本模式的 MCP 端点占着,小门用 8766。 */
    public static final int DEFAULT_LIVE_PORT = 8766;
    /** /live 只认回环:写别的值 LiveServer 拒绝启动并记 ERROR。(字面量,别前引下面的 LOOPBACK) */
    public static final String DEFAULT_LIVE_HOST = "127.0.0.1";

    /** 把租约秒数夹进合法区间。 */
    public static int clampLeaseSeconds(int seconds) {
        return Math.max(MIN_LEASE_SECONDS, Math.min(MAX_LEASE_SECONDS, seconds));
    }

    /**
     * 读配置;没有就播一份默认的。
     *
     * <p>默认<b>带一个现生成的令牌</b>。空令牌只在回环上无害,而这个文件是可以手改的——
     * 一旦有人把 host 改成 0.0.0.0(那是 issue 里被要求过的能力),空令牌就是无鉴权全开。
     * 与其指望他改地址时记得再设一个,不如一开始就有。
     */
    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpConfig def = new McpConfig(false, LOOPBACK, 8765, mintToken(), 300, DEFAULT_HIDDEN,
                    DEFAULT_LEASE_SECONDS, false,
                    DEFAULT_LIVE_ENABLED, DEFAULT_LIVE_HOST, DEFAULT_LIVE_PORT, mintLiveToken(), "");
            writeDefault(file, def);
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int lease = DEFAULT_LEASE_SECONDS;
            if (o.has("lease_seconds")) {
                int raw = o.get("lease_seconds").getAsInt();
                lease = clampLeaseSeconds(raw);
                if (lease != raw) {
                    Constants.LOG.warn("[numen-mcp] lease_seconds={} 超出 {}-{} 秒,按 {} 秒算",
                            raw, MIN_LEASE_SECONDS, MAX_LEASE_SECONDS, lease);
                }
            }
            boolean quietFallback = o.has("quiet_fallback") && o.get("quiet_fallback").getAsBoolean();
            if (quietFallback) {
                // 旧配置里开着失联回退:读到了只提示一句,行为已经由租约接管——内置大脑不再抢身体。
                Constants.LOG.warn("[numen-mcp] quiet_fallback 已废弃并被忽略:外脑安静时不再交还内置大脑,"
                        + "身体进安全待机,外部请求回来时自动续约(lease_seconds={})", lease);
            }
            JsonObject live = objOr(o, "live");
            McpConfig cfg = new McpConfig(
                    o.has("enabled") ? o.get("enabled").getAsBoolean() : false,
                    strOr(o, "host", "127.0.0.1"),
                    o.has("port") ? o.get("port").getAsInt() : 8765,
                    strOr(o, "token", ""),
                    o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 300,
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                    lease,
                    quietFallback,
                    boolOr(live, "enabled", DEFAULT_LIVE_ENABLED),
                    strOr(live, "host", DEFAULT_LIVE_HOST),
                    intOr(live, "port", DEFAULT_LIVE_PORT),
                    strOr(live, "token", ""),
                    strOr(objOr(o, "brain"), "bridgeFile", ""));
            // 缺的段/字段就地补齐并回写:只在缺失时补,不重排、不覆盖玩家手改过的值。
            String mintedLiveToken = patchMissing(file, o, cfg);
            if (mintedLiveToken != null) {
                cfg = cfg.withLiveToken(mintedLiveToken);
            }
            return cfg;
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp] unreadable config {} — server disabled: {}", file, ex.toString());
            return new McpConfig(false, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN, DEFAULT_LEASE_SECONDS, false,
                    DEFAULT_LIVE_ENABLED, DEFAULT_LIVE_HOST, DEFAULT_LIVE_PORT, "", "");
        }
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    /** 只绑回环。默认值,也是"没开局域网"时的地址。 */
    public static final String LOOPBACK = "127.0.0.1";
    /** 绑所有网卡——局域网里别的机器就能连上来了。 */
    public static final String ANY_HOST = "0.0.0.0";

    /** 配置文件还没读到时的占位(模式关闭),避免 {@link McpMode} 持 null 配置。 */
    static McpConfig disabledDefault() {
        return new McpConfig(false, LOOPBACK, 8765, "", 300, DEFAULT_HIDDEN, DEFAULT_LEASE_SECONDS, false,
                DEFAULT_LIVE_ENABLED, DEFAULT_LIVE_HOST, DEFAULT_LIVE_PORT, "", "");
    }

    /** 只改开关的副本——设置面板拨动开关时用,其余字段保持用户手改的值。 */
    McpConfig withEnabled(boolean on) {
        return new McpConfig(on, host, port, token, callTimeoutSeconds, hiddenTools, leaseSeconds, quietFallback,
                liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    /** 改端点与超时的副本(设置页保存时用)。 */
    McpConfig withEndpoint(String host, int port, int callTimeoutSeconds) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, leaseSeconds, quietFallback,
                liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    McpConfig withToken(String token) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, leaseSeconds, quietFallback,
                liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    /** 只改 /live 令牌的副本——load 补生成令牌后把新值带回内存用。 */
    McpConfig withLiveToken(String liveToken) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, leaseSeconds, quietFallback,
                liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    McpConfig withHiddenTools(List<String> hiddenTools) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, List.copyOf(hiddenTools),
                leaseSeconds, quietFallback, liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    /** 改租约秒数的副本(夹进合法区间)。 */
    McpConfig withLeaseSeconds(int seconds) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools,
                clampLeaseSeconds(seconds), quietFallback, liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    /** 改已废弃的 {@code quiet_fallback} 的副本——只为旧设置页/旧调用继续能写,不再影响行为。 */
    McpConfig withQuietFallback(boolean on) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, leaseSeconds, on,
                liveEnabled, liveHost, livePort, liveToken, brainBridgeFile);
    }

    /** 绑的是所有网卡吗——局域网里别人够得着。 */
    public boolean lanExposed() {
        return !LOOPBACK.equals(host) && !"localhost".equalsIgnoreCase(host);
    }

    /**
     * 令牌为空且对局域网开放 = 无鉴权裸奔。
     *
     * <p>本类注释里那句 "Empty = no auth (fine on loopback)" 的前提是<b>回环</b>;地址一放开,
     * 那个 fine 就不成立了。设置页据此拦住保存。
     */
    public boolean unguarded() {
        return lanExposed() && (token == null || token.isBlank());
    }

    /**
     * 生成一个新令牌。
     *
     * <p>用 {@link java.security.SecureRandom}——这串东西是外部 AI 操控同伴的唯一凭据,
     * 拿可预测的随机数生成等于没有。
     */
    public static String mintToken() {
        byte[] raw = new byte[18];
        new java.security.SecureRandom().nextBytes(raw);
        return "numen-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 生成 /live 的独立令牌——与 {@link #mintToken()} 的 MCP 令牌<b>互相独立</b>:
     * MCP token 能执行动作,只读小门绝不能拿它当钥匙(泄露面不同,一把开门多一把锁)。
     * 同一套 SecureRandom,前缀区分,肉眼可辨哪把是哪把。
     */
    public static String mintLiveToken() {
        byte[] raw = new byte[18];
        new java.security.SecureRandom().nextBytes(raw);
        return "live-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 写回配置文件:游戏内拨的开关要跨会话记住。 */
    void save(Path file) {
        write(file, this, "saved config");
    }

    private static void writeDefault(Path file, McpConfig def) {
        write(file, def, "wrote default config");
    }

    private static void write(Path file, McpConfig cfg, String what) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfg.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        o.addProperty("token", cfg.token());
        o.addProperty("call_timeout_seconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        o.addProperty("lease_seconds", cfg.leaseSeconds());
        // 旧字段照写不误:别的工具/旧版本可能读它。写它不代表它还有用(见类注释)。
        o.addProperty("quiet_fallback", cfg.quietFallback());
        JsonObject live = new JsonObject();
        live.addProperty("enabled", cfg.liveEnabled());
        live.addProperty("host", cfg.liveHost());
        live.addProperty("port", cfg.livePort());
        live.addProperty("token", cfg.liveToken());
        o.add("live", live);
        JsonObject brain = new JsonObject();
        brain.addProperty("bridgeFile", cfg.brainBridgeFile());
        o.add("brain", brain);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            Constants.LOG.info("[numen-mcp] {} {}", what, file);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to write config {}: {}", file, ex.toString());
        }
    }

    /**
     * 把缺的 {@code live}/{@code brain} 段(或段里缺的字段、空的 live token)就地补进刚解析出的
     * 对象并回写。<b>只在缺失时补,不重排、不覆盖已有值</b>——这个文件玩家手改过,整份重建会
     * 把键序和注释之外的私有安排抹掉(Gson 解析保留键序,补的字段落在文件末尾)。
     *
     * @return 本次补生成出来的 live token(没补过则 {@code null})——调用方要把它带回内存,
     *         否则 record 里还是那个空值
     */
    private static String patchMissing(Path file, JsonObject o, McpConfig cfg) {
        boolean changed = false;
        String mintedLiveToken = null;
        JsonObject live;
        if (o.has("live") && o.get("live").isJsonObject()) {
            live = o.getAsJsonObject("live");
        } else {
            live = new JsonObject();
            o.add("live", live);
            changed = true;
        }
        if (!live.has("enabled")) { live.addProperty("enabled", cfg.liveEnabled()); changed = true; }
        if (!live.has("host")) { live.addProperty("host", cfg.liveHost()); changed = true; }
        if (!live.has("port")) { live.addProperty("port", cfg.livePort()); changed = true; }
        String liveToken = strOr(live, "token", "");
        if (liveToken.isBlank()) {
            // 空令牌 = 生成并回写:小门虽只读,也不能没钥匙地开着。
            mintedLiveToken = mintLiveToken();
            live.addProperty("token", mintedLiveToken);
            changed = true;
        }
        JsonObject brain;
        if (o.has("brain") && o.get("brain").isJsonObject()) {
            brain = o.getAsJsonObject("brain");
        } else {
            brain = new JsonObject();
            o.add("brain", brain);
            changed = true;
        }
        if (!brain.has("bridgeFile")) { brain.addProperty("bridgeFile", cfg.brainBridgeFile()); changed = true; }
        if (!changed) {
            return null;
        }
        try {
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            Constants.LOG.info("[numen-mcp] 补齐缺失配置段(live/brain){}", file);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to patch config {}: {}", file, ex.toString());
        }
        return mintedLiveToken;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) out.add(el.getAsString());
        }
        return List.copyOf(out);
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }

    /** 缺键/类型不对就给默认值的 int 读取——live 段里的 port 用,坏值不值得让它炸掉整个 load。 */
    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static boolean boolOr(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException notABool) {
            return fallback;
        }
    }

    /** 取子对象;缺键或不是对象就给一个空对象(不 null,调用方统一走 has/缺省路径)。 */
    private static JsonObject objOr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }
}
