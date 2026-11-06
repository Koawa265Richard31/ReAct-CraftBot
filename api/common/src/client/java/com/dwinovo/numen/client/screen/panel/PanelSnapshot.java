package com.dwinovo.numen.client.screen.panel;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 面板要显示的一份快照——{@link NumenPanelScreen} 渲染路径唯一的数据入口。
 *
 * <h2>读什么</h2>
 * <ul>
 *   <li>{@code brain.json}:外接脑心跳插件自报的名片(profile / preset / model / stateFile…),
 *       路径<b>由配置给</b>——{@code config/numen/mcp_server.json} 的 {@code brain.bridgeFile}
 *       (见 {@link McpConfig#brainBridgeFile()});没配就当没接外接脑。</li>
 *   <li>{@code body-live.json}:身体状态表。mod 一个路径都不认识:从 brain.json 的
 *       {@code stateFile} 字段拿(心跳插件和它同目录、同原子写)。brain.json 没给就不读。</li>
 * </ul>
 * 两个文件都是对方写的,这里<b>只读、永不写</b>;缺失/损坏一律当"无数据",不抛不挡启动。
 *
 * <h2>采样与渲染分层(照 hud/YuukaStatusHud 的做法)</h2>
 * 字符串只在 {@link #refresh()} 里算(面板开着时每秒一次,由屏幕的 tick 调),算完存进
 * {@link #BODY_LINES} / {@link #BRAIN_LINES} 这些静态字段;渲染路径只读现成引用,零分配。
 * 全部主线程专用(与 {@code ClientPrefs} 同一口径)。
 *
 * <h2>通知环形缓冲</h2>
 * 上限 {@link #NOTIFY_CAP} 条,固定数组 + 头指针,新的顶掉最老的;落盘在
 * {@code config/numen/notify-log.json}——与 {@code ui.json} 同一个目录(目录一律运行时从
 * 游戏目录拼出,源码里不出现绝对路径)。推进来的时机由调用方定(现在的 toast 源接进来时
 * 调 {@link #pushNotify}),这里只管存、读、清。
 */
public final class PanelSnapshot {

    /** 通知环形缓冲的容量:满后再推进来就顶掉最老的一条。 */
    public static final int NOTIFY_CAP = 200;
    /** 状态表多久算"过期":超过它角色页/任务页该提示链断了(心跳正常 1s 一写)。 */
    private static final long STALE_MS = 10_000;
    /** 展示行上限:每边最多摆这么多行,超出截断(快照是给人扫一眼的,不是日志)。 */
    private static final int LINE_CAP = 8;

    // ---- 采样出的只读快照:渲染只读,零分配。null/0 之后的位置是空的 ----

    /** body-live.json 的展示行(已按"label = 值"拼好)。渲染只读 [0, bodyLineCount)。 */
    public static final String[] BODY_LINES = new String[LINE_CAP];
    public static int bodyLineCount;
    /** brain.json 的展示行,同上。 */
    public static final String[] BRAIN_LINES = new String[LINE_CAP];
    public static int brainLineCount;

    /** 快照里那两份文件的写入时刻(文件里的 {@code ts});0 = 没读到。 */
    private static long bodyTs, brainTs;
    /** 本体各页有没有数据——左栏据此置灰。渲染只读。 */
    public static boolean hasBody, hasBrain, hasGoal;

    /**
     * 左栏七格各自的"有没有数据"(渲染只读;refresh() 时重算)。
     * 槽位与 {@link NumenPanelScreen} 的 PAGE_* 常量一一对应:0 角色 1 任务 2 对话 3 技能 4 用量 5 通知 6 设置。
     */
    public static final boolean[] RAIL_AVAILABLE = new boolean[7];

    /** 上次 refresh 的时刻(屏幕的 tick 拿它节流)。 */
    private static long lastRefreshMs;

    private PanelSnapshot() {}

    // ---- 采样(主线程;所有分配只发生在这里) ----

    /** 重新读盘、重算快照。面板开着时由屏幕每秒调一次,别进渲染帧。 */
    public static void refresh() {
        lastRefreshMs = System.currentTimeMillis();
        readBrain();
        readBody();
        sampleCompanionFlags();
        sampleRailAvailability();
    }

    /** 距上次采样过了多久(屏幕 tick 拿它决定要不要再采样)。 */
    public static long sinceRefreshMs() {
        return System.currentTimeMillis() - lastRefreshMs;
    }

    /** body-live.json 距它的 {@code ts} 过了多久;Long.MAX_VALUE = 没有快照。 */
    public static long bodyStalenessMs(long now) {
        return bodyTs <= 0 ? Long.MAX_VALUE : Math.max(0, now - bodyTs);
    }

    /** brain.json 距它的 {@code ts} 过了多久;Long.MAX_VALUE = 没有快照。 */
    public static long brainStalenessMs(long now) {
        return brainTs <= 0 ? Long.MAX_VALUE : Math.max(0, now - brainTs);
    }

    /** 状态表是不是新鲜的(链没断)。 */
    public static boolean bodyFresh(long now) {
        return bodyStalenessMs(now) <= STALE_MS;
    }

    // ---- brain.json(路径由配置给;只读) ----

    /** brain.json 在磁盘上的位置;null/空 = 配置里没接外接脑,整块不显示。 */
    public static Path brainFile() {
        String p;
        try {
            McpConfig cfg = McpMode.instance().config();
            p = cfg == null ? null : cfg.brainBridgeFile();
        } catch (Exception e) {
            return null;    // 外脑模块没起来:等价于没接
        }
        if (p == null || p.isBlank()) return null;
        return Path.of(p);
    }

    private static void readBrain() {
        brainLineCount = 0;
        brainTs = 0;
        Path f = brainFile();
        if (f == null || !Files.isRegularFile(f)) {
            hasBrain = false;
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            brainTs = o.has("ts") ? o.get("ts").getAsLong() : 0;
            brainLine("preset", str(o, "presetInUse"));
            brainLine("model", str(o, "model"));
            brainLine("profile", str(o, "profile"));
            JsonArray tools = o.has("tools") && o.get("tools").isJsonArray() ? o.getAsJsonArray("tools") : null;
            brainLine("tools", tools == null ? null : String.valueOf(tools.size()));
            hasBrain = brainLineCount > 0;
        } catch (Exception e) {
            hasBrain = false;
            Constants.LOG.warn("[numen-panel] brain.json 读不了({}): {}", f, e.toString());
        }
    }

    // ---- body-live.json(路径 = brain.json 的 stateFile 字段;只读) ----

    private static void readBody() {
        bodyLineCount = 0;
        bodyTs = 0;
        hasBody = false;
        Path f = bodyFile();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            bodyTs = o.has("ts") ? o.get("ts").getAsLong() : 0;
            bodyLine("activity", str(o, "activity"));
            bodyLine("hp", pair(o, "hp", "maxHp"));
            bodyLine("food", num(o, "food"));
            bodyLine("dim", str(o, "dim"));
            bodyLine("program", objStr(o, "program", "name"));
            hasBody = bodyLineCount > 0;
        } catch (Exception e) {
            hasBody = false;
            Constants.LOG.warn("[numen-panel] body-live.json 读不了({}): {}", f, e.toString());
        }
    }

    /** body-live.json 在磁盘上的位置;来源只有 brain.json 的 {@code stateFile}——mod 不猜路径。 */
    private static Path bodyFile() {
        Path f = brainFile();
        if (f == null) return null;
        try {
            if (!Files.isRegularFile(f)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            String sf = str(o, "stateFile");
            return sf == null || sf.isBlank() ? null : Path.of(sf);
        } catch (Exception e) {
            return null;
        }
    }

    /** 左栏置灰判据里属于"同伴"的那几条:有同伴、她有目标。 */
    private static void sampleCompanionFlags() {
        List<NumenRoster.Entry> roster = NumenRoster.instance().entries();
        hasGoal = false;
        if (roster.isEmpty()) return;
        var loop = AgentLoopRegistry.get(roster.get(0).uuid()).orElse(null);
        if (loop != null && loop.goal() != null && loop.goal().objective() != null
                && !loop.goal().objective().isBlank()) {
            hasGoal = true;
        }
    }

    /** 左栏七格的置灰判据,refresh() 时算进 {@link #RAIL_AVAILABLE}(渲染路径不调它,零分配)。 */
    private static void sampleRailAvailability() {
        boolean roster = !NumenRoster.instance().entries().isEmpty();
        RAIL_AVAILABLE[0] = roster || hasBody;                                           // 角色
        RAIL_AVAILABLE[1] = hasGoal || hasBody;                                          // 任务
        RAIL_AVAILABLE[2] = roster;                                                      // 对话
        RAIL_AVAILABLE[3] = com.dwinovo.numen.agent.skill.SkillRegistry.instance().size() > 0;   // 技能
        RAIL_AVAILABLE[4] = roster;                                                      // 用量(账本在 loop 里,页面文件接)
        RAIL_AVAILABLE[5] = notifyCount() > 0;                                           // 通知
        RAIL_AVAILABLE[6] = true;                                                        // 设置永远在
    }

    // ---- 通知环形缓冲(固定数组 + 头指针,顶掉最老;落 ui.json 旁边) ----

    /** 一条通知:入队后不可变。 */
    public record Notice(long at, String title, String body, boolean read) {}

    private static final Notice[] RING = new Notice[NOTIFY_CAP];
    /** 下一条要写入的槽位;size 是缓冲里的实际条数(≤ cap)。 */
    private static int ringHead;
    private static int ringSize;
    private static boolean loaded;

    /** 缓冲里的条数。 */
    public static int notifyCount() {
        ensureLoaded();
        return ringSize;
    }

    /** 未读条数(左栏「通知」的角标)。 */
    public static int unreadCount() {
        ensureLoaded();
        int n = 0;
        for (int i = 0; i < ringSize; i++) {
            if (!RING[wrap(headIndex() - i)].read()) n++;
        }
        return n;
    }

    /** 第 i 新的一条(0 = 最新);越界返回 null。 */
    public static Notice notifyAt(int i) {
        ensureLoaded();
        if (i < 0 || i >= ringSize) return null;
        return RING[wrap(headIndex() - i)];
    }

    /** 整份只读视图(给通知页一次拿走;拷贝只发生在调用方显式要的时候)。 */
    public static List<Notice> notifications() {
        ensureLoaded();
        List<Notice> out = new ArrayList<>(ringSize);
        for (int i = ringSize - 1; i >= 0; i--) out.add(RING[wrap(i)]);
        return out;
    }

    /** 推一条(没读过的)。满了顶掉最老的;顺手落盘,推送是低频事件,写得起。 */
    public static void pushNotify(String title, String body) {
        ensureLoaded();
        RING[ringHead] = new Notice(System.currentTimeMillis(),
                title == null ? "" : title, body == null ? "" : body, false);
        ringHead = wrap(ringHead + 1);
        if (ringSize < NOTIFY_CAP) ringSize++;
        persist();
    }

    /** 全部已读。 */
    public static void markAllRead() {
        ensureLoaded();
        for (int i = 0; i < ringSize; i++) {
            Notice n = RING[wrap(i)];
            if (n != null && !n.read()) RING[wrap(i)] = new Notice(n.at(), n.title(), n.body(), true);
        }
        persist();
    }

    /** 清空(环形缓冲归零,文件一并删掉)。 */
    public static void clearNotifies() {
        ensureLoaded();
        java.util.Arrays.fill(RING, null);
        ringHead = 0;
        ringSize = 0;
        Path f = notifyFile();
        if (f != null) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception e) {
                Constants.LOG.warn("[numen-panel] notify-log.json 删不掉: {}", e.toString());
            }
        }
    }

    private static int headIndex() {
        return wrap(ringHead - 1 + NOTIFY_CAP);
    }

    private static int wrap(int i) {
        return i % NOTIFY_CAP;
    }

    // ---- 落盘(config/numen/notify-log.json,与 ui.json 同目录;目录运行时求值) ----

    private static Path notifyFile() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return null;
        return mc.gameDirectory.toPath()
                .resolve("config")
                .resolve(com.dwinovo.numen.Constants.CONFIG_ROOT)
                .resolve("notify-log.json");
    }

    /** 首次访问时从盘上装回(缺文件/损坏 = 空缓冲,不阻断)。 */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = notifyFile();
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            JsonArray a = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
            for (var el : a) {
                if (ringSize >= NOTIFY_CAP) break;
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                RING[ringHead] = new Notice(
                        o.has("at") ? o.get("at").getAsLong() : 0,
                        o.has("title") ? o.get("title").getAsString() : "",
                        o.has("body") ? o.get("body").getAsString() : "",
                        o.has("read") && o.get("read").getAsBoolean());
                ringHead = wrap(ringHead + 1);
                ringSize++;
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen-panel] notify-log.json 读不了,当空缓冲: {}", e.toString());
        }
    }

    /** 全量重写(≤200 条的小文件,低频,与 ClientPrefs.persist 同一口径)。 */
    private static void persist() {
        Path f = notifyFile();
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            JsonArray a = new JsonArray();
            for (int i = 0; i < ringSize; i++) {
                Notice n = RING[wrap(i)];
                if (n == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("at", n.at());
                o.addProperty("title", n.title());
                o.addProperty("body", n.body());
                o.addProperty("read", n.read());
                a.add(o);
            }
            Files.writeString(f, a.toString());
        } catch (Exception e) {
            Constants.LOG.warn("[numen-panel] notify-log.json 写不了,通知没保存", e);
        }
    }

    // ---- 行拼装的小工具(只在 refresh/入队路径用,允许分配) ----

    private static void bodyLine(String key, String val) {
        appendLine(BODY_LINES, key, val);
    }

    private static void brainLine(String key, String val) {
        appendLine(BRAIN_LINES, key, val);
    }

    private static void appendLine(String[] sink, String key, String val) {
        if (val == null || val.isBlank()) return;
        for (int i = 0; i < LINE_CAP; i++) {
            if (sink[i] == null) {
                sink[i] = key + " = " + val;
                if (sink == BODY_LINES) bodyLineCount = i + 1; else brainLineCount = i + 1;
                return;
            }
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsJsonPrimitive().getAsString() : null;
    }

    private static String pair(JsonObject o, String a, String b) {
        String va = num(o, a), vb = num(o, b);
        return va == null ? null : va + (vb == null ? "" : " / " + vb);
    }

    private static String objStr(JsonObject o, String obj, String key) {
        return o.has(obj) && o.get(obj).isJsonObject() ? str(o.getAsJsonObject(obj), key) : null;
    }
}
