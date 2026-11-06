package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.data.ClientPrefs;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.mcp.server.McpTranscript;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 右上角的「她在干嘛」小面板(开关在 Numen 面板左下角的常驻小条 Toggle,
 * 偏好落 {@code ClientPrefs.statusHud},默认开):三四行小字,纯文本、
 * 不打贴图、不建图层、不发任何包——所有数据都是客户端已有的本地状态。
 *
 * <h2>采样与渲染分层</h2>
 * 字符串只在 {@link #tick()} 的 4Hz 采样里算(每 5 个 client tick 一次),算完存进
 * {@link #LINES};渲染回调只读这几个缓存的 String 引用和量一次宽度——
 * 渲染路径零分配。全部在主线程,{@code McpTranscript.view()} 那类会拷贝会排序
 * 的读取也只进采样,绝不进逐帧路径。
 *
 * <h2>四行的来源</h2>
 * <ol>
 *   <li>驾驶态:{@link McpMode} 的三态——模式关=内置脑;开着且租约在=外部驱动(带剩余秒);
 *       开着但租约过期=未连接(安全待机)。判据只用它的公开方法,不自拼。</li>
 *   <li>在做什么:{@code EntityAgentLoop.status().activity()}——服务端推来的人话镜像;
 *       忙着但没具体动作写「思考中」,否则「待机」。程序已耗时不显示:任务镜像的
 *       起点存在私有字段里,没有公开入口,不用反射硬掏。</li>
 *   <li>长期目标:{@code EntityAgentLoop.goal()};正文截 40 字,带公开的目标已耗时。没目标跳行。</li>
 *   <li>最近一句:外脑模式读 {@link McpTranscript} 的最后一条 SAY(稳定日志);
 *       内置脑读 {@link SpeechBubbles} 的正文(头顶气泡,7~22 秒会过期,过期就跳行——现状如此)。</li>
 * </ol>
 */
public final class YuukaStatusHud {

    /** 采样周期:5 个 client tick 一次 = 4Hz,渲染帧不再碰任何数据源。 */
    private static final int SAMPLE_EVERY_TICKS = 5;
    /** 行数上限:驾驶态 / 在做什么 / 目标 / 最近一句。 */
    private static final int MAX_LINES = 4;
    /** 最近一句与目标正文都截到这么多字,超了加省略号。 */
    private static final int MAX_CHARS = 40;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_BG = 0x90000000;
    private static final int PAD_X = 5;
    private static final int PAD_Y = 3;
    private static final int MARGIN = 4;

    /** 4Hz 采样出的只读快照:渲染只读它,零分配。null 之后的位置是空的。 */
    private static final String[] LINES = new String[MAX_LINES];
    private static int lineCount;

    private static int tickCounter;

    private YuukaStatusHud() {}

    /** 每个客户端 tick 调一次(两个 loader 的 client tick 钩子),每 5 tick 采样一次。 */
    public static void tick() {
        tickCounter++;
        if (tickCounter % SAMPLE_EVERY_TICKS != 0) {
            return;
        }
        if (!ClientPrefs.statusHud()) {
            lineCount = 0;      // 关着连采样都省
            return;
        }
        sample();
    }

    /** 本帧画布:渲染与采样同在主线程,暂存静态字段 + 非捕获方法引用,逐帧零分配。 */
    private static GuiGraphics frame;

    /** loader 的 HUD 层每帧调:只读缓存字符串;开关关着第一行就 return。 */
    public static void render(GuiGraphics g) {
        if (!ClientPrefs.statusHud() || lineCount <= 0) {
            return;
        }
        frame = g;
        try {
            com.dwinovo.numen.client.ui.SafeUi.run("yuuka-status", YuukaStatusHud::draw);
        } finally {
            frame = null;   // 不跨帧攥着上一帧的画布
        }
    }

    // ---- 采样(主线程,4Hz;所有分配只发生在这里) ----

    private static void sample() {
        lineCount = 0;      // 重算快照:先清行数再逐行填,数据消失的行自然消失(渲染只读 [0, lineCount))
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;         // 标题画面/未进世界:面板整个不画
        }
        long now = System.currentTimeMillis();
        NumenRoster.Entry her = pick();
        line(herName(her) + " · " + driveState());
        if (her != null) {
            var loop = AgentLoopRegistry.get(her.uuid()).orElse(null);
            line(doingLine(her, loop));
            line(goalLine(loop, now));
            line(lastWordLine(her.uuid()));
        }
    }

    /** 缓存一行;null/空 = 该行没数据,跳过不占位。 */
    private static void line(String text) {
        if (text == null || text.isEmpty() || lineCount >= MAX_LINES) {
            return;
        }
        LINES[lineCount++] = text;
    }

    /** 「Yuuka」是谁:准星指着的那只优先,没指着取名册第一只;名册空 = 没召唤,只画第 1 行。 */
    private static NumenRoster.Entry pick() {
        AbstractClientPlayer body = CompanionChatScreen.crosshairCompanion();
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        if (body != null) {
            for (NumenRoster.Entry e : entries) {
                if (e.uuid().equals(body.getUUID())) {
                    return e;
                }
            }
        }
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** 名字用名册给的;不在册就叫「Yuuka」。 */
    private static String herName(NumenRoster.Entry her) {
        if (her == null) {
            return "Yuuka";
        }
        String n = NumenRoster.instance().name(her.uuid());
        return n == null || n.isBlank() ? "Yuuka" : n;
    }

    /** 第 1 行后半句:模式开关与租约的三态,口径全照 McpMode 的公开方法(不自拼判据)。 */
    private static String driveState() {
        McpMode mcp = McpMode.instance();
        if (!mcp.enabled()) {
            return "内置脑";
        }
        if (!mcp.leaseOk()) {
            return "未连接";
        }
        long left = mcp.leaseRemainingSeconds();
        // 有调用在飞时它按设计返回整段租约(在飞=在场),照显示,不自己再算倒计时。
        return left > 0 ? "外部驱动 " + left + "s" : "外部驱动";
    }

    /** 第 2 行:在做什么。死了先报复活倒计时;活着的按状态镜像:活动文案 → 思考中 → 待机。 */
    private static String doingLine(NumenRoster.Entry her, EntityAgentLoop loop) {
        if (her.dead()) {
            long left = her.remainingMs();
            return left < 0 ? "等待复活" : "等待复活 " + (left + 999) / 1000 + "s";
        }
        if (loop == null) {
            return "待机";
        }
        var status = loop.status();
        if (status.activity() != null) {
            return status.activity();
        }
        return status.busy() ? "思考中" : "待机";
    }

    /** 第 3 行:长期目标 + 目标已耗时(公开的 {@code GoalState.elapsedMs});没目标跳行。 */
    private static String goalLine(EntityAgentLoop loop, long now) {
        if (loop == null) {
            return null;
        }
        var goal = loop.goal();
        if (goal == null) {
            return null;
        }
        return "目标: " + clamp(goal.objective(), MAX_CHARS) + "(" + fmtDur(goal.elapsedMs(now)) + ")";
    }

    /** 第 4 行:她最近一句。外脑模式读现场缓冲的 SAY;都不在才退到头顶气泡(会过期,过期跳行)。 */
    private static String lastWordLine(UUID herUuid) {
        String said = null;
        if (McpMode.instance().enabled()) {
            said = lastSay(herUuid);
        }
        if (said == null) {
            SpeechBubbles.View v = SpeechBubbles.view(herUuid);
            if (v != null && v.hasText()) {
                said = v.text();
            }
        }
        return said == null ? null : "「" + clamp(said, MAX_CHARS) + "」";
    }

    /** 现场缓冲里最后一条她说的话——快照的拷贝与排序只发生在这次 4Hz 采样里。 */
    private static String lastSay(UUID herUuid) {
        List<McpTranscript.Line> view = McpTranscript.view(herUuid);
        for (int i = view.size() - 1; i >= 0; i--) {
            McpTranscript.Line l = view.get(i);
            if (l.kind() == McpTranscript.Kind.SAY) {
                return l.text();
            }
        }
        return null;
    }

    // ---- 绘制(每帧,只读缓存) ----

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = frame;
        if (g == null || mc.options.hideGui || mc.player == null) {
            return;
        }
        Font font = mc.font;
        int textW = 0;
        for (int i = 0; i < lineCount; i++) {
            textW = Math.max(textW, font.width(LINES[i]));
        }
        int lineH = font.lineHeight + 1;
        int width = textW + PAD_X * 2;
        int height = lineCount * lineH + PAD_Y * 2 - 1;
        int left = g.guiWidth() - MARGIN - width;
        int top = vanillaToastBottom() + 2;     // 让开原版右上角的通知
        g.fill(left, top, left + width, top + height, COLOR_BG);
        for (int i = 0; i < lineCount; i++) {
            g.drawString(font, LINES[i], left + PAD_X, top + PAD_Y + i * lineH, COLOR_TEXT, true);
        }
    }

    /** 原版 toast 此刻占着右上角多高——照 {@code NumenHudToasts} 的同款口径。 */
    private static int vanillaToastBottom() {
        return ((com.dwinovo.numen.mixin.ToastComponentAccessor) Minecraft.getInstance().getToasts())
                .numen$occupiedSlots().length()
                * net.minecraft.client.gui.components.toasts.Toast.SLOT_HEIGHT;
    }

    // ---- 小工具 ----

    /** 压成单行并截到 max 字,超了加省略号。 */
    private static String clamp(String s, int max) {
        String t = s.replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 秒/分/时的紧凑时长。 */
    private static String fmtDur(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        return m < 60 ? m + "m" + s % 60 + "s" : m / 60 + "h" + m % 60 + "m";
    }
}
