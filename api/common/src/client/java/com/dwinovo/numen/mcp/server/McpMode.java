package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;

import java.nio.file.Path;

/**
 * 「外接大脑」模式的唯一状态源——UI、内置大脑闸门、HTTP 服务器三方共读的一处真相。
 *
 * <h2>模式 = 服务器开关</h2>
 * 不另立"模式"概念:{@link McpConfig#enabled} 开着就是模式开着,服务器就在跑。
 * 设置里拨动开关 → {@link #setEnabled} 即时起停服务器并写回配置文件,下次进游戏
 * 自动恢复同一状态。
 *
 * <h2>谁在驱动这具身体:{@link #driving()}</h2>
 * "两个大脑抢一具身体"的总闸口径:外接大脑模式开着,内置大脑就一轮都不开
 * (循环内核每次推进都现问它,在它手里时停牌 {@code EXTERNAL})。<b>这个判断永远只看模式开关</b>——
 * 外脑安静、失联、甚至从没接进来,内置大脑都不接管。主人在游戏里照样说话——话进
 * 事件队列,由外脑经 {@code get_events} 取走并用 {@code say} 回话;弹幕/QQ 桥接
 * 送进来的消息也走同一条线。身体层面的互斥另有 {@code TaskDispatch} 的
 * "一具身体一件活"闸门兜底,两者各管一层。
 *
 * <h2>外脑在不在场:{@link #leaseOk()} 租约</h2>
 * 唯一的在场判据是租约:距上一次成功的 {@code tools/call} 不超过
 * {@code lease_seconds}({@link McpConfig#leaseSeconds()})就算在场;有一次调用正在跑
 * ({@link #noteCallStart()} 到 {@link #noteCallEnd(boolean)} 之间)同样算在场——一次
 * 长程序调用可以让 {@code tools/call} 挂上几分钟,那期间外脑明明就在线上。
 * 租约过期 → 身体进<b>安全待机</b>:叫停在跑的程序与身体任务,原地不动、不丢东西;
 * 外部请求照常受理,成功一次就续约(下一次 client tick 解除待机)。
 *
 * <p>旧配置里的 {@code quiet_fallback}(失联回退:外脑一安静就让内置大脑接管)已被租约
 * 取代:字段还读、还写,但不再改变任何行为,读到开着只打一行"已废弃"。
 * 服务端闸门读不到本类,读的是主人客户端从 {@link #driving()} 推过去的那份镜像
 * ({@code DrivingMirror}),两处永远是同一口径。
 *
 * <h2>线程</h2>
 * {@link #enabled()} 被渲染线程和游戏主线程高频读,故用 volatile 裸字段;
 * 租约时刻由 HTTP 线程写、主线程读,volatile;在飞调用计数用原子量。
 * 握手信息由 HTTP 线程写入、渲染线程读。现场缓冲见 {@link McpTranscript}。
 */
public final class McpMode {

    private static final McpMode INSTANCE = new McpMode();

    private volatile boolean enabled;
    private volatile String clientName;      // initialize 握手报的对方名字,null = 还没人连过
    private volatile long lastActivityMs;
    /** 上一次续约(成功 tools/call)的时刻,0 = 从未续约。租约的唯一时间源。 */
    private volatile long lastRenewedMs;
    /** 正在执行的 tools/call 数:有调用挂着本身就证明外脑在场(长程序调用会挂几分钟)。 */
    private final java.util.concurrent.atomic.AtomicInteger inFlightCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile String lastError;       // 起服失败的原因,给 UI 显示
    /** 安全待机已播报并执行过(边沿检测,只在主线程碰)。 */
    private boolean standbyAnnounced;
    /** 已废弃的 quiet_fallback 提示过没有(只提示一行,不刷屏)。 */
    private boolean deprecatedQuietWarned;

    private Path configFile;
    /** 设置页/开关在主线程改,租约查询与 HTTP 侧也读它——volatile 保证改完立即可见。 */
    private volatile McpConfig config = McpConfig.disabledDefault();
    private McpServer server;

    private McpMode() {}

    public static McpMode instance() {
        return INSTANCE;
    }

    // ---- 生命周期 ----

    /**
     * 客户端启动时装载配置:记住配置文件位置,enabled 则立刻起服。
     * 只调一次(两个 loader 的 client 入口各自调本平台的)。
     */
    void bootstrap(Path file, McpConfig loaded) {
        this.configFile = file;
        this.config = loaded;
        if (loaded.enabled()) {
            startServer();
        } else {
            this.enabled = false;
            Constants.LOG.info("[numen-mcp] 外接大脑模式关闭(config/numen/mcp_server.json)");
        }
    }

    /**
     * 拨动开关:即时起停服务器并写回配置文件。
     *
     * @return 是否达成目标状态(起服失败时返回 false,原因见 {@link #lastError()})
     */
    public boolean setEnabled(boolean on) {
        if (on == enabled) return true;
        if (on) {
            if (!startServer()) return false;
        } else {
            stopServer();
        }
        config = config.withEnabled(enabled);
        if (configFile != null) config.save(configFile);
        return true;
    }

    /**
     * 保存设置页改动,租约秒数保持当前值。见带租约的完整版
     * {@link #applySettings(String, int, int, java.util.List, String, int)}。
     */
    public boolean applySettings(String host, int port, int callTimeoutSeconds,
                                 java.util.List<String> hiddenTools, String token) {
        return applySettings(host, port, callTimeoutSeconds, hiddenTools, token, config.leaseSeconds());
    }

    /**
     * 保存设置页改动(含租约秒数,照 {@link McpConfig#withLeaseSeconds} 的规矩夹进合法区间)。
     * 服务器在跑就<b>原地重开</b>——端点变了,旧的那个还占着老端口。
     *
     * <p>为什么是"保存"而不是边改边生效:端口是一个字符一个字符敲进来的,{@code 8}→{@code 87}
     * →{@code 876}→{@code 8765},即时生效等于每敲一下真做一次 {@code bind()},前三次几乎必然
     * 失败,错误提示被刷成噪音。占系统资源的字段只能攒着一次落地。
     *
     * @return 起服失败时 {@code false},原因见 {@link #lastError()}
     */
    public boolean applySettings(String host, int port, int callTimeoutSeconds,
                                 java.util.List<String> hiddenTools, String token, int leaseSeconds) {
        boolean wasRunning = enabled;
        if (wasRunning) {
            stopServer();
        }
        config = config.withEndpoint(host, port, callTimeoutSeconds)
                .withHiddenTools(hiddenTools)
                .withToken(token)
                .withLeaseSeconds(leaseSeconds);
        boolean ok = true;
        if (wasRunning) {
            ok = startServer();
        }
        config = config.withEnabled(enabled);
        if (configFile != null) config.save(configFile);
        return ok;
    }

    /** 当前配置的只读快照——设置页据此填初值。 */
    public McpConfig config() {
        return config;
    }

    private boolean startServer() {
        lastError = null;
        McpServer s = new McpServer(config);
        try {
            s.start();
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            Constants.LOG.error("[numen-mcp] 起服失败 {}:{} — {}", config.host(), config.port(), ex.toString());
            enabled = false;
            return false;
        }
        server = s;
        enabled = true;
        Constants.LOG.info("[numen-mcp] 外接大脑模式已开启,端点 {} — 外部客户端经 `npx mcp-remote {}` 接入",
                endpoint(), endpoint());
        return true;
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        enabled = false;
        clientName = null;      // 连接状态随服务器一起归零(现场缓冲留着:那是主人看过的对话)
        // 租约随连接作废:下次开启从"从未续约"重新算,不会捡回上一轮的在场时刻。
        lastRenewedMs = 0;
        inFlightCalls.set(0);
        standbyAnnounced = false;
        Constants.LOG.info("[numen-mcp] 外接大脑模式已关闭,内置大脑重新接管");
    }

    // ---- 状态查询(UI / 闸门) ----

    /** 模式(=服务器)是否开启——面板状态、设置页读这个;两脑仲裁读 {@link #driving()}。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 外脑此刻是否驱动着身体——内置大脑的开轮闸门、聊天区形态、现场缓冲挂点
     * 全读这一处口径。<b>只看模式开关</b>:模式开着即驱动,内置大脑永远停牌。
     * 外脑在不在场是另一个问题,由 {@link #leaseOk()} 回答——租约过期只让身体
     * 进安全待机,绝不把驾驶席交还内脑(那是旧 quiet_fallback 的行为,已删除)。
     */
    public boolean driving() {
        return enabled;
    }

    /**
     * 租约是否有效 = 外脑此刻在场。<b>唯一判据</b>:
     * <ul>
     *   <li>有 {@code tools/call} 正在执行 → 在场(长程序调用挂着时外脑就在线上);</li>
     *   <li>否则看距上一次成功调用是否短于 {@code lease_seconds}。</li>
     * </ul>
     * 模式关着没有租约可言(恒 false)；从没成功调用过也 false。
     */
    public boolean leaseOk() {
        if (!enabled) {
            return false;
        }
        return leaseOkAt(System.currentTimeMillis(), lastRenewedMs, leaseMillis(), inFlightCalls.get());
    }

    /**
     * 租约判据的纯函数(与时钟、单例分开,单测直接验边界):有调用在飞就算在场;否则看距
     * 上一次成功调用是否短于租约。{@code renewedMs == 0} = 从未成功调用过,不算在场。
     */
    static boolean leaseOkAt(long nowMs, long renewedMs, long leaseMs, int inFlight) {
        if (inFlight > 0) {
            return true;
        }
        return renewedMs > 0 && nowMs - renewedMs < leaseMs;
    }

    /** 租约还剩几秒(向上取整;不在场/模式关是 0)。排障用的只读值。 */
    public long leaseRemainingSeconds() {
        if (!enabled || inFlightCalls.get() > 0) {
            return enabled ? config.leaseSeconds() : 0;
        }
        long renewed = lastRenewedMs;
        if (renewed <= 0) {
            return 0;
        }
        long left = leaseMillis() - (System.currentTimeMillis() - renewed);
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** 上一次续约的时刻(毫秒),0 = 从未。排障用的只读值。 */
    public long lastRenewedMs() {
        return lastRenewedMs;
    }

    /** 此刻正在执行的 tools/call 数。排障用的只读值。 */
    public int inFlightCalls() {
        return inFlightCalls.get();
    }

    private long leaseMillis() {
        return config.leaseSeconds() * 1000L;
    }

    /**
     * 每 client tick 一次(两个 loader 的 client 入口驱动):租约到期/续上的边沿检测,
     * 以及驾驶席口径向服务端的镜像推送。
     *
     * <p>到期那一刻做<b>安全待机</b>:给每具身体发两道闸——停当前程序(不认编号,服务端自己起的
     * 也停)+ 叫停身体活,原地不动、不丢东西。只在翻转的那一下做一次、打一行日志;
     * 外脑一回来租约续上,再打一行,不刷屏。
     */
    public void clientTick() {
        syncDrivingMirror();
        // 标题画面上没有身体可待机,也没有包可发:不进这套判据。
        if (!enabled || net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            standbyAnnounced = false;
            return;
        }
        boolean ok = leaseOk();
        if (!ok && !standbyAnnounced) {
            standbyAnnounced = true;
            int stopped = standBy();
            Constants.LOG.info("[numen-mcp] 租约过期(超过 {} 秒没有成功的 tools/call,共 {} 具身体)— "
                    + "身体进安全待机:叫停在跑的程序与任务,原地不动", config.leaseSeconds(), stopped);
        } else if (ok && standbyAnnounced) {
            standbyAnnounced = false;
            Constants.LOG.info("[numen-mcp] 租约续上 — 外脑回来了,安全待机解除");
        }
    }

    /**
     * 安全待机:主人名下每具在场的同伴各发两道闸——程序那一道不认编号(服务端自己起的、
     * 重启重放的也能停),身体那一道不依赖客户端的任务镜像(刚进世界时镜像可能还没到,
     * 不能因此漏停)。幂等;没有在跑的东西时服务端各自空转。不遣散、不移动、不动背包。
     *
     * @return 发出待机的身体数
     */
    private int standBy() {
        int count = 0;
        for (com.dwinovo.numen.client.agent.NumenRoster.Entry entry
                : com.dwinovo.numen.client.agent.NumenRoster.instance().entries()) {
            try {
                com.dwinovo.numen.network.NumenNetwork.sendToServer(
                        new com.dwinovo.numen.network.payload.StopCurrentProgramPayload(entry.uuid(), true));
                com.dwinovo.numen.network.NumenNetwork.sendToServer(
                        new com.dwinovo.numen.network.payload.CancelTasksPayload(entry.uuid()));
                count++;
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-mcp] 待机时叫停 {} 失败:{}", entry.name(), ex.toString());
            }
        }
        return count;
    }

    /**
     * 拨"失联后内脑接管"开关——<b>已废弃</b>:写进配置文件只为兼容旧设置页/旧调用,
     * 不再改变任何行为(内置大脑不再接管,见 {@link #driving()})。
     */
    @Deprecated
    public void setQuietFallback(boolean on) {
        config = config.withQuietFallback(on);
        if (configFile != null) config.save(configFile);
        if (on && !deprecatedQuietWarned) {
            deprecatedQuietWarned = true;
            Constants.LOG.warn("[numen-mcp] quiet_fallback 已废弃并被忽略——内置大脑不再接管;"
                    + "外脑在不在场只由租约(lease_seconds={})判定", config.leaseSeconds());
        }
    }

    // ---- 服务端镜像 ----

    /** 这次连接里推过没有:进世界第一推,断开重连后重推(服务端断线时也清了它的那份)。 */
    private boolean mirrorSynced;
    /** 上一次推给服务端镜像的口径值——只用来找翻转的边沿。 */
    private boolean mirrorSent;

    /**
     * 驾驶席口径推给服务端(边沿一次):服务端闸门(重放、反射层、{@code /numen drive} 的
     * 二次确认)读不到本类,读那份镜像——镜像只有这一位写入方,口径不会分叉。
     */
    private void syncDrivingMirror() {
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            mirrorSynced = false;
            return;
        }
        boolean driving = driving();
        if (!mirrorSynced || driving != mirrorSent) {
            mirrorSynced = true;
            mirrorSent = driving;
            com.dwinovo.numen.network.NumenNetwork.sendToServer(
                    new com.dwinovo.numen.network.payload.DrivingMirrorPayload(driving));
        }
    }

    public String endpoint() {
        return "http://" + config.host() + ":" + config.port() + "/mcp";
    }

    public String token() {
        return config.token();
    }

    /**
     * 打码后的令牌,给 UI 显示——明文 token 不上屏(截图/录屏泄露过一次就永久泄露),
     * 要用整份的地方走复制按钮。
     */
    public String maskedToken() {
        String t = config.token();
        if (t == null || t.isBlank()) return "";
        return t.length() <= 8 ? "•".repeat(t.length()) : t.substring(0, 8) + "•".repeat(6);
    }

    /** 最近一次 initialize 握手报上来的客户端名字,或 null(还没人接入)。 */
    public String clientName() {
        return clientName;
    }

    /** 最近一次收到请求的时刻(毫秒),0 = 从未。 */
    public long lastActivityMs() {
        return lastActivityMs;
    }

    /** 最近一次起服失败的原因,或 null。 */
    public String lastError() {
        return lastError;
    }

    /**
     * 「复制接入提示词」的内容:内嵌当前端点与令牌,用户复制后发给自己的 AI,
     * 由那个 AI 去配本机 MCP。含明文令牌,故只走剪贴板、不上屏。
     */
    public String accessPrompt() {
        return McpAccessPrompt.build(endpoint(), token());
    }

    // ---- HTTP 线程回调 ----

    /** 收到任何请求都刷新活跃时刻(ping 也算——面板的"多久前活跃"靠它)。 */
    void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    /**
     * 一次 {@code tools/call} 开始执行(HTTP 线程):从这一刻起外脑算在场——
     * 一次长程序调用可以让这个请求挂上几分钟,期间没有别的请求能续约。
     * 不在这里改 {@code lastRenewedMs}:续约以"成功"为准,失败/超时不算。
     */
    void noteCallStart() {
        inFlightCalls.incrementAndGet();
    }

    /**
     * 一次 {@code tools/call} 结束(HTTP 线程)。{@code ok} 为真才续约;失败的调用
     * 不续约(它证明不了外脑还在按预期工作),但执行期间仍算在场。
     */
    void noteCallEnd(boolean ok) {
        inFlightCalls.updateAndGet(n -> n > 0 ? n - 1 : 0);
        if (ok) {
            lastRenewedMs = System.currentTimeMillis();
        }
    }

    /** initialize 握手:记下对方是谁("Claude Desktop 1.2.3")。 */
    void handshake(String name) {
        clientName = name;
        touch();
    }
}
