package com.dwinovo.numen.client.screen.panel;

import com.dwinovo.numen.agent.request.BodySnapshot;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.client.data.ClientPrefs;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.GearSwapRequest;
import com.dwinovo.numen.network.payload.GearSwapResult;
import com.dwinovo.numen.network.payload.RequestStatePayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 角色页(M1 只读 + M2 穿脱交互)——面板左栏第一格的内容,版式照 {@code docs/hud-replace-draft.md} §三:
 * <ol>
 *   <li><b>顶部状态条,常驻不滚</b>:血量(+细血条) / 饥饿 / 效果 / 活动 / 外接脑三态。
 *       血量缺省(她没被客户端追踪到)这一格显示「未知」,<b>绝不填 0</b>;饥饿同理。</li>
 *   <li><b>穿戴</b>:遍历 {@link #GEAR} 列表照序渲染、不自己排序(口径照 §四的 GearView:
 *       {@code name / item / writable});{@code writable=false} 画虚线格并在槽名下标「只读」。
 *       GearView 类本身由服务端补丁往 {@code NumenStatePayload} 里加(本树还没有它,见
 *       {@link #setGear}),载荷处理器落地后调 {@link #setGear} 把列表喂进来。</li>
 *   <li><b>背包</b>:主背包 27 格 + 快捷栏 9 格,来自 {@link ClientNumenState} 的
 *       {@link BodySnapshot};画法照 {@code ProfilePage} 的物品格——{@code g.renderItem} +
 *       {@code g.renderItemDecorations},悬停给原版 tooltip。</li>
 *   <li><b>额外空间</b>:只画两个占位格 + 「M4」小字;嵌套容器是 M4 的事,这里不做。</li>
 * </ol>
 *
 * <h2>写权限与穿脱(M2)</h2>
 * 页头(状态条下面)一行常驻的写权限总开关「允许我动她的背包」(偏好落
 * {@link ClientPrefs#gearWriteAllowed},默认关;开关控件由 {@link NumenPanelScreen} 摆,
 * 本页只画标签行)。开关关着:槽位不可点,悬停提示「写权限关着」。开着:
 * <ul>
 *   <li><b>点穿戴槽 = 卸下</b>——仅当该槽 writable 且格里有物品;发
 *       {@link GearSwapRequest}(dir=1,expectWorn=槽里那件,expectOwner=空)。</li>
 *   <li><b>点背包格 = 给她穿</b>——目标槽:物品的原版穿戴部位只对应一类槽就选它;定不出部位
 *       就挑现有槽里第一个空位;都定不下来不发、页头给一句提示。</li>
 *   <li><b>不做乐观更新</b>——一次只飞一笔;在飞的那格画「处理中」(半透明 + 虚线亮框);
 *       回执({@link GearSwapResult},经 {@code ClientPayloadSink.gearSwap} 进
 *       {@link #acceptGearSwapResult})到达后以 wornAfter/ownerAfter 为准重画,失败把
 *       reason 原样放到页头一行;成功落地顺带要一份最新状态({@link RequestStatePayload})
 *       把背包那半也对齐(卸下时服务端不背背包端,ownerAfter 恒空)。5 秒没等到回执就不再
 *       挂着「处理中」并提示一句——不承诺退回,显示等状态推送带回。</li>
 * </ul>
 * 回执落下的真值存在 {@link #GEAR_TRUTH}/{@link #PACK_TRUTH} 里盖在快照上画;服务端推来
 * 新的整份穿戴({@link #setGear})或新的背包快照时,真值作废——服务端的推送是更新的真值。
 *
 * <h2>选取哪位同伴</h2>
 * 与宿主「叫停」同一口径(NumenPanelScreen.pickCompanionUuid):准星指着的优先,否则名册第一只。
 *
 * <h2>渲染纪律</h2>
 * 页面级字符串按帧现拼——照 {@code ProfilePage} 渲染路径的既有做法(ProfilePage.java:186 起),
 * 不另设采样层;整页裁剪、滚动趋近、tooltip 后置全部照抄它的套路。
 */
public final class CharacterPage implements NumenPanelScreen.Page {

    /**
     * 一格穿戴在面板上的只读视图。字段口径照 hud-replace-draft §四的
     * {@code GearView(String name, ItemStack item, boolean writable)}——{@code name} 是
     * {@code GearSlot#name()} 原样(如 {@code head}、{@code curios:ring});{@code writable=false}
     * 表示来源登记里没有它,面板只读显示。
     *
     * <p>为什么是本文件里的记录而不是直接用 GearView:GearView 由服务端补丁新增,写本页时
     * 源码树里还没有这个类;等补丁落地,载荷处理器把 {@code List<GearView>} 逐条映射成
     * {@link GearCell} 再调 {@link #setGear},字段一一对应,不丢口径。
     */
    public record GearCell(String name, ItemStack item, boolean writable) {}

    /** 当前这份穿戴快照;只由 {@link #setGear} 整体替换,渲染路径只读。 */
    private static final List<GearCell> GEAR = new ArrayList<>();

    /**
     * 载荷处理器每收到一份带 gear 的状态包就整体替换一次(顺序 = GearSource#slots() 的顺序,
     * 客户端照序渲染);传 null 等于清空。清空前调用方自己确认数据是新鲜的。
     *
     * <p>服务端推来的整份穿戴是最新的真值:一回执落的 {@link #GEAR_TRUTH} 在这里作废。
     */
    public static void setGear(List<GearCell> cells) {
        GEAR.clear();
        if (cells != null) GEAR.addAll(cells);
        GEAR_TRUTH.clear();
    }

    // ---- 在飞的一笔(M2 穿脱):一次只飞一笔,不做乐观更新 ----

    /** 进程内单调递增的 txId:同一笔不复用(全部收发都在客户端主线程,静态单实例页,无并发)。 */
    private static long nextTxId = System.currentTimeMillis();
    /** 在飞的 txId;0 = 没有在飞的。 */
    private static long pendingTx;
    private static long pendingSentMs;
    /** 在飞的格:穿戴槽在 {@link #GEAR} 里的下标(卸下 = 源格,穿上 = 目标格),只给遮罩用;-1 = 无。 */
    private static int pendingGearIdx = -1;
    /** 在飞的槽名(回执真值按它落键,不随在飞中 setGear 换形而漂移);null = 无。 */
    private static String pendingGearName;
    /** 在飞的这笔是替谁办的(回执落地后向服务端要一份最新状态);null = 无。 */
    private static UUID pendingCompanion;
    /** 在飞的背包格下标(穿上时服务端按「首个全等」钉的那格);-1 = 无。 */
    private static int pendingPackIdx = -1;
    /** 发出时那格背包里的东西(回执到达后按服务端同一口径重新钉格)。 */
    private static ItemStack pendingExpectOwner = ItemStack.EMPTY;

    /** 页头提示行:i18n 键(淡色)或服务端回执原话(失败色);都 null = 没有提示。 */
    private static String messageKey, messageRaw;

    // ---- 回执落的真值(盖在快照上画;服务端推送新数据即作废) ----

    /** 槽名 → 回执的 wornAfter。同类多格同名,键与 {@code GearSwapService} 的解析粒度一致。 */
    private static final HashMap<String, ItemStack> GEAR_TRUTH = new HashMap<>();
    /** 背包下标 → 回执的 ownerAfter;null = 没有真值(EMPTY = 那格已空,有真值)。 */
    private static final ItemStack[] PACK_TRUTH = new ItemStack[36];
    /** {@link #PACK_TRUTH} 是针对哪一份背包快照落的:新快照实例一来就整表作废。 */
    private static BodySnapshot packTruthSource;

    // ---- 命中矩形(渲染时记下,点击时查;渲染只读现成引用,数组复用不重分配) ----

    private static int[] gearHitX = new int[16], gearHitY = new int[16];
    private static int gearHitCount;
    private static final int[] packHitX = new int[36], packHitY = new int[36];
    /** 命中矩形 → 背包物品下标(主背包 9..35、快捷栏 0..8),与矩形一一对应。 */
    private static final int[] packHitIdx = new int[36];
    private static int packHitCount;

    /** 把本页挂进面板的角色页槽位;幂等(init 随窗口缩放重跑,不换实例、不丢滚动位置)。 */
    public static void register() {
        if (NumenPanelScreen.page(NumenPanelScreen.PAGE_CHARACTER) == null) {
            NumenPanelScreen.setPage(NumenPanelScreen.PAGE_CHARACTER, new CharacterPage());
        }
    }

    // ---- 版式常量 ----

    private static final int PAD = 8;
    /** 原版物品格边长(格底 18,物品 16 缩进 1),与 ProfilePage.SLOT 同值同义。 */
    private static final int SLOT = 18;
    /** 状态条行高与它的发丝线。包内可见:NumenPanelScreen 给写权限 Toggle 定界要用。 */
    static final int STATUS_H = 15;
    /** 写权限行高(标签在手画路径,开关控件 14 高居中)。 */
    private static final int WRITE_H = 16;
    /** 页头提示行高(回执失败的原话 / 本地提示)。 */
    private static final int MSG_H = 14;
    /** 一笔在飞多久没等到回执就退回原状并提示(服务端正常下一两个 tick 就回)。 */
    private static final long TX_TIMEOUT_MS = 5000;
    /** 节头行进距离。 */
    private static final int HEAD_H = 13;
    /** 穿戴格横向间距与每格占高(格 18 + 槽名 9 + 只读标 9)。 */
    private static final int GEAR_PITCH = 46, GEAR_ROW_H = 40;
    /** 细血条的长与厚。 */
    private static final int BAR_W = 44, BAR_H = 3;

    // ---- 滚动(照 ProfilePage:像素趋近 + 末端夹取) ----

    private float scroll, scrollTarget;
    /** 本帧页面矩形的几何;viewY 供点击路径算「常驻区以下才可点」的地板线。 */
    private int contentH, viewH, viewX, viewY, viewW;
    private long lastFrameMs;

    /** 渲染期现成的字体(collect 里画物品角标用)。 */
    private Font font;

    public CharacterPage() {
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollTarget = Mth.clamp(scrollTarget - (float) (delta * 20), 0f, Math.max(0, contentH - viewH));
        return true;
    }

    // ---- 点击(M2 穿脱):点穿戴槽 = 卸下,点背包格 = 给她穿;开关关着或一笔在飞时都不发 ----
    // 已知取舍:点击时按同一口径(准星优先)重新挑同伴——渲染与点击之间准星换人,点的是
    // 换人后那位同位置的格子。请求内部自洽(谁的槽配谁的物品),口径与叫停钮同源。

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        // 常驻区(状态条+写权限行+提示行)滚不下去,滚上去的格会被它盖住——盖住的部分点不着
        double hitFloor = viewY + STATUS_H + WRITE_H + (hasMessage() ? MSG_H : 0);
        if (my < hitFloor) return false;
        for (int r = 0; r < gearHitCount && r < GEAR.size(); r++) {
            if (overCell(mx, my, gearHitX[r], gearHitY[r])) return gearClicked(r);
        }
        for (int r = 0; r < packHitCount; r++) {
            if (overCell(mx, my, packHitX[r], packHitY[r])) return packClicked(packHitIdx[r]);
        }
        return false;
    }

    /** 卸下:开关开着、槽 writable、格里确实有东西,才发 dir=1。 */
    private boolean gearClicked(int idx) {
        if (!ClientPrefs.gearWriteAllowed()) {
            hintWriteLocked();
            return true;        // 关着时不可点:吃掉点击,页头说明原因
        }
        GearCell cell = GEAR.get(idx);
        if (!cell.writable()) return true;              // 只读槽:登记里没有它,点了也不发
        if (gearShown(cell).isEmpty()) return true;     // 空槽没东西可卸
        UUID who = pickCompanion();
        if (who == null || busy()) return true;
        sendRemove(who, idx);
        return true;
    }

    /** 给她穿:开关开着、那格有物品、目标槽定得下来,才发 dir=0。 */
    private boolean packClicked(int itemsIdx) {
        UUID who = pickCompanion();
        if (who == null) return true;
        if (!ClientPrefs.gearWriteAllowed()) {
            hintWriteLocked();
            return true;
        }
        BodySnapshot snap = ClientNumenState.get(who).orElse(null);
        if (snap == null || !snap.loaded() || snap.items() == null || itemsIdx >= snap.items().size()) return true;
        ItemStack piece = packShown(snap.items(), itemsIdx);
        if (piece.isEmpty()) return true;
        if (busy()) return true;
        // 「这件能穿哪」的判据走她本体上原版的同一问法(与 core Wardrobe.handFor 同一路径);
        // 身体不在视野里就问不成,按定不下来处理,不发。
        AbstractClientPlayer body = ClientNumenLookup.resolve(who);
        GearCell target = body == null ? null : chooseWearSlot(piece, body);
        if (target == null) {
            messageKey = ModLanguageData.Keys.PANEL_CHAR_WRITE_NOSLOT;
            messageRaw = null;
            return true;        // 定不下目标槽:不发,页头给一句提示
        }
        sendWear(who, target, piece);
        return true;
    }

    // ---- 渲染 ----

    @Override
    public void render(NumenPanelScreen.Rect rect) {
        GuiGraphics g = NumenPanelScreen.frame();
        Font f = NumenPanelScreen.font();
        if (g == null || f == null) return;
        UiTheme t = UiTheme.current();
        this.font = f;
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        checkPendingTimeout(now);

        int x = rect.x, y = rect.y, w = rect.w, h = rect.h;
        viewX = x;
        viewY = y;
        viewW = w;
        viewH = h;
        scrollTarget = Mth.clamp(scrollTarget, 0f, Math.max(0, contentH - viewH));
        scroll = Anim.approach(scroll, scrollTarget, 16f, dt);
        int mx = NumenPanelScreen.mouseX();
        int my = NumenPanelScreen.mouseY();
        int iw = w - PAD * 2;
        ItemStack[] hover = {ItemStack.EMPTY};
        String[] tip = {null};

        // 这一页是谁:准星指着的优先,否则名册第一只(与 NumenPanelScreen.pickCompanionUuid 同口径)
        UUID who = pickCompanion();
        BodySnapshot snap = who == null ? null : ClientNumenState.get(who).orElse(null);
        AbstractClientPlayer body = who == null ? null : ClientNumenLookup.resolve(who);
        EntityAgentLoop loop = who == null ? null : AgentLoopRegistry.get(who).orElse(null);
        syncPackTruth(snap);

        // ---- 可滚内容:裁剪在常驻区(状态条 + 写权限行 + 提示行)下面,滚上去不会盖住它们 ----
        int fixedH = STATUS_H + WRITE_H + (hasMessage() ? MSG_H : 0);
        gearHitCount = 0;
        packHitCount = 0;
        g.enableScissor(x, y + fixedH + 1, x + w, y + h);
        int cy = y + fixedH + 8 - Math.round(scroll);

        cy = drawGear(g, f, t, x, y + h, iw, cy, mx, my, hover, tip);
        cy = drawInventory(g, f, t, x, y + h, iw, cy, mx, my, hover, tip, snap);
        cy = drawExtra(g, f, t, x, y + h, iw, cy);

        contentH = cy + Math.round(scroll) - y;
        // 滚动条:内容超出一屏才画
        if (contentH > viewH) {
            int th = Math.max(12, viewH * viewH / contentH);
            int ty = y + Math.round((viewH - th) * (scroll / Math.max(1, contentH - viewH)));
            g.fill(x + w - 3, ty, x + w - 1, ty + th, (t.textDim() & 0xFFFFFF) | 0x60000000);
        }
        g.disableScissor();

        // ---- 常驻区最后画,压在滚动内容之上:它们不跟着滚 ----
        drawStatusBar(g, f, t, x, y, w, body, snap, loop);
        drawWriteRow(g, f, t, x, y + STATUS_H, w);
        if (hasMessage()) {
            drawMessageRow(g, f, t, x, y + STATUS_H + WRITE_H, w);
        }

        // 悬停提示(整页画完再画,免得被格子盖住——ProfilePage 同一条):
        // 写权限关着的提示优先于物品 tooltip——关着时读物品提示没有意义,该说的是为什么点不了
        if (tip[0] != null) {
            g.renderTooltip(f, Component.literal(tip[0]), mx, my);
        } else if (!hover[0].isEmpty()) {
            g.renderTooltip(f, hover[0], mx, my);
        }
    }

    /** 顶部状态条:血量(+细血条)/饥饿/效果/活动/外接脑三态,一段接一段往右排;超宽被裁剪,不换行。 */
    private void drawStatusBar(GuiGraphics g, Font f, UiTheme t, int x, int y, int w,
                               AbstractClientPlayer body, BodySnapshot snap, EntityAgentLoop loop) {
        g.fill(x, y, x + w, y + STATUS_H, t.surface());
        g.fill(x, y + STATUS_H, x + w, y + STATUS_H + 1, t.surfaceBorder());
        int ty = y + (STATUS_H - f.lineHeight) / 2 + 1;
        int cx = x + PAD;

        // 血量:拿不到就「未知」,绝不填 0;拿到了才画细血条
        cx = seg(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_HP), cx, ty, t.textDim());
        if (body != null) {
            String hp = fmt(body.getHealth()) + "/" + fmt(body.getMaxHealth());
            cx = seg(g, f, hp, cx, ty, t.text());
            g.fill(cx, y + STATUS_H / 2, cx + BAR_W, y + STATUS_H / 2 + BAR_H,
                    UiTheme.mix(t.band(), t.border(), 0.5f));
            float max = body.getMaxHealth();
            int fw = max > 0 ? Math.round(BAR_W * Mth.clamp(body.getHealth() / max, 0f, 1f)) : 0;
            if (fw > 0) {
                g.fill(cx, y + STATUS_H / 2, cx + fw, y + STATUS_H / 2 + BAR_H, t.fail());
            }
            cx += BAR_W + 10;
        } else {
            cx = seg(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_UNKNOWN), cx, ty, t.textDim());
        }

        // 饥饿:背包快照没加载(她睡了/不归我们)同样是「未知」
        cx = seg(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_FOOD), cx, ty, t.textDim());
        if (snap != null && snap.loaded()) {
            cx = seg(g, f, snap.foodLevel() + "/20", cx, ty, t.text());
        } else {
            cx = seg(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_UNKNOWN), cx, ty, t.textDim());
        }

        // 效果:名字口径照 RuntimeState.renderEffects(注册名去命名空间,等级 = amplifier+1,秒数按收到时刻折算)
        if (snap != null && snap.loaded() && !snap.effects().isEmpty()) {
            String effects = effectText(snap, now());
            if (!effects.isEmpty()) {
                cx = seg(g, f, "✦ ", cx, ty, t.textDim());
                cx = seg(g, f, Nb.clip(f, effects, 160), cx, ty, t.accent());
            }
        }

        // 活动:她在干的那句(没有就整段不出现,不占位)
        if (loop != null && loop.status().activity() != null && !loop.status().activity().isBlank()) {
            cx = seg(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_ACTIVITY), cx, ty, t.textDim());
            cx = seg(g, f, Nb.clip(f, loop.status().activity(), 120), cx, ty, t.text());
        }

        // 外接脑三态(draft §6.9 的同一套):关=内置脑 / 开而租约失效=未连接 / 开且在场=外部驱动 Ns
        McpMode mcp = McpMode.instance();
        String brain;
        int color;
        if (!mcp.enabled()) {
            brain = I18n.get(ModLanguageData.Keys.PANEL_CHAR_BRAIN_BUILTIN);
            color = t.textDim();
        } else if (!mcp.leaseOk()) {
            brain = I18n.get(ModLanguageData.Keys.PANEL_CHAR_BRAIN_OFFLINE);
            color = t.fail();
        } else {
            brain = I18n.get(ModLanguageData.Keys.PANEL_CHAR_BRAIN_LEASE, mcp.leaseRemainingSeconds());
            color = t.cta();
        }
        seg(g, f, brain, x + w - PAD - f.width(brain), ty, color);
    }

    /** 一段状态文字;返回下一段的左边。 */
    private static int seg(GuiGraphics g, Font f, String s, int x, int y, int color) {
        Nb.text(g, f, s, x, y, color);
        return x + f.width(s) + 10;
    }

    /**
     * 写权限行(常驻,不滚):标签在手画路径,开关本体是 {@link NumenPanelScreen} 摆在行右端的
     * Toggle 控件(偏好落 {@link ClientPrefs#gearWriteAllowed});这里只负责行底与标签。
     */
    private static void drawWriteRow(GuiGraphics g, Font f, UiTheme t, int x, int y, int w) {
        g.fill(x, y, x + w, y + WRITE_H, t.surface());
        g.fill(x, y + WRITE_H, x + w, y + WRITE_H + 1, t.surfaceBorder());
        Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_WRITE), x + PAD,
                y + (WRITE_H - f.lineHeight) / 2 + 1, t.textDim());
    }

    /** 页头提示行(有话才占地方):i18n 提示用淡色,服务端回执失败的原话用失败色、原样显示。 */
    private static void drawMessageRow(GuiGraphics g, Font f, UiTheme t, int x, int y, int w) {
        g.fill(x, y, x + w, y + MSG_H, t.surface());
        g.fill(x, y + MSG_H, x + w, y + MSG_H + 1, t.surfaceBorder());
        String s = messageKey != null ? I18n.get(messageKey) : messageRaw;
        if (s == null || s.isEmpty()) return;
        Nb.text(g, f, Nb.clip(f, s, w - PAD * 2), x + PAD,
                y + (MSG_H - f.lineHeight) / 2 + 1, messageKey != null ? t.faint() : t.fail());
    }

    // ---- 穿戴 ----

    /** 穿戴分组:遍历 {@link #GEAR} 照序画,不排序;{@code writable=false} 虚线格 + 「只读」标。 */
    private int drawGear(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy,
                         int mx, int my, ItemStack[] hover, String[] tip) {
        cy = head(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_GEAR),
                GEAR.isEmpty() ? "" : String.valueOf(GEAR.size()), x, cy, iw);
        if (GEAR.isEmpty()) {
            cy = hint(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_GEAR_EMPTY), x, cy);
            return cy + 4;
        }
        int perRow = Math.max(1, iw / GEAR_PITCH);
        int rowTop = cy;
        for (int i = 0; i < GEAR.size(); i++) {
            int col = i % perRow;
            if (col == 0 && i > 0) rowTop += GEAR_ROW_H;
            int sx = x + col * GEAR_PITCH;
            if (rowTop > clipBottom) break;              // 滚出裁剪区就别画了
            drawGearCell(g, f, t, GEAR.get(i), i, sx, rowTop, mx, my, hover, tip);
        }
        return rowTop + GEAR_ROW_H + 6;
    }

    /** 一格穿戴:格底(可写实底凹槽,只读虚线框)+ 物品 + 槽名(+「只读」);在飞的那格画「处理中」。 */
    private void drawGearCell(GuiGraphics g, Font f, UiTheme t, GearCell cell, int idx, int x, int y,
                              int mx, int my, ItemStack[] hover, String[] tip) {
        int edge = UiTheme.mix(t.band(), t.border(), cell.writable() ? 0.62f : 0.55f);
        if (cell.writable()) {
            slotBg(g, t, x, y);
        } else {
            dashedFrame(g, x, y, SLOT, SLOT, edge);
        }
        ItemStack st = gearShown(cell);
        collect(g, st, x + 1, y + 1, mx, my, hover);
        if (!ClientPrefs.gearWriteAllowed() && overCell(mx, my, x, y)) {
            tip[0] = I18n.get(ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED);
        }
        if (pendingTx != 0 && pendingGearIdx == idx) {
            pendingVeil(g, t, x, y);
        }
        noteGearHit(idx, x, y);
        Nb.text(g, f, Nb.clip(f, cell.name() == null ? "?" : cell.name(), GEAR_PITCH - 6),
                x, y + SLOT + 2, t.faint());
        if (!cell.writable()) {
            Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_READONLY), x, y + SLOT + 11, t.fail());
        }
    }

    // ---- 背包 ----

    /** 主背包 27 格(物品 9..35)+ 快捷栏 9 格(物品 0..8),画法照 ProfilePage.drawItems。 */
    private int drawInventory(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy,
                              int mx, int my, ItemStack[] hover, String[] tip, BodySnapshot snap) {
        boolean ready = snap != null && snap.loaded() && snap.items() != null && !snap.items().isEmpty();
        cy = head(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_BACKPACK), "27 + 9", x, cy, iw);
        if (!ready) {
            return hint(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_BACKPACK_EMPTY), x, cy) + 4;
        }
        List<ItemStack> items = snap.items();
        boolean locked = !ClientPrefs.gearWriteAllowed();
        int gx = x;
        for (int i = 9; i < 36 && i < items.size(); i++) {
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int sx = gx + col * SLOT, sy = cy + row * SLOT;
            slotBg(g, t, sx, sy);
            if (sy <= clipBottom) drawPackCell(g, f, t, items, i, sx, sy, mx, my, hover, tip, locked);
        }
        cy += 3 * SLOT + 5;
        if (cy <= clipBottom) {
            Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_HOTBAR), gx, cy, t.faint());
        }
        cy += 10;
        for (int i = 0; i < 9 && i < items.size(); i++) {
            int sx = gx + i * SLOT, sy = cy;
            slotBg(g, t, sx, sy);
            if (sy <= clipBottom) drawPackCell(g, f, t, items, i, sx, sy, mx, my, hover, tip, locked);
        }
        return cy + SLOT + 8;
    }

    /** 一格背包:物品 + 悬停提示 + 在飞「处理中」遮罩 + 命中矩形。 */
    private void drawPackCell(GuiGraphics g, Font f, UiTheme t, List<ItemStack> items, int i,
                              int x, int y, int mx, int my, ItemStack[] hover, String[] tip, boolean locked) {
        ItemStack st = packShown(items, i);
        collect(g, st, x + 1, y + 1, mx, my, hover);
        if (locked && overCell(mx, my, x, y)) {
            tip[0] = I18n.get(ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED);
        }
        if (pendingTx != 0 && pendingPackIdx == i) {
            pendingVeil(g, t, x, y);
        }
        notePackHit(i, x, y);
    }

    // ---- 额外空间(M4 占位) ----

    /** 两个占位格 + 「M4」小字;不做嵌套容器,点了也没有(M4 才接物品自带容器)。 */
    private int drawExtra(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy) {
        cy = head(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_EXTRA), "", x, cy, iw);
        for (int i = 0; i < 2; i++) {
            dashedFrame(g, x + i * (SLOT + 6), cy, SLOT, SLOT, UiTheme.mix(t.band(), t.border(), 0.4f));
        }
        if (cy <= clipBottom) {
            Nb.text(g, f, "M4", x + 2 * (SLOT + 6) + 4, cy + (SLOT - f.lineHeight) / 2 + 1, t.faint());
        }
        return cy + SLOT + 8;
    }

    // ---- 小件(照 ProfilePage 的同款做法) ----

    /** 节头:强调色小字,右端可带一个淡色的数。返回下面内容的顶边。(ProfilePage.sectionHead 同款) */
    private static int head(GuiGraphics g, Font f, UiTheme t, String title, String count, int x, int y, int w) {
        Nb.text(g, f, title, x, y, t.accent());
        if (!count.isEmpty()) {
            Nb.text(g, f, count, x + w - f.width(count), y, t.faint());
        }
        return y + HEAD_H;
    }

    /** 空态提示一行。返回下一行顶边。 */
    private static int hint(GuiGraphics g, Font f, UiTheme t, String s, int x, int y) {
        Nb.text(g, f, s, x, y, t.faint());
        return y + 12;
    }

    /** 统一凹槽:从窗口底色向边框色压暗两档。(ProfilePage.slot 原样) */
    private static void slotBg(GuiGraphics g, UiTheme t, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, UiTheme.mix(t.band(), t.border(), 0.62f));
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, UiTheme.mix(t.band(), t.border(), 0.34f));
    }

    /** 虚线框:四边各画 3 像素段、空 3 像素——只读格的记号,和实底凹槽一眼分得开。 */
    private static void dashedFrame(GuiGraphics g, int x, int y, int w, int h, int color) {
        for (int i = x; i < x + w; i += 6) {
            g.fill(i, y, Math.min(i + 3, x + w), y + 1, color);
            g.fill(i, y + h - 1, Math.min(i + 3, x + w), y + h, color);
        }
        for (int j = y; j < y + h; j += 6) {
            g.fill(x, j, x + 1, Math.min(j + 3, y + h), color);
            g.fill(x + w - 1, j, x + w, Math.min(j + 3, y + h), color);
        }
    }

    /** 画物品并记下悬停的那件(ProfilePage.collect 同款)。 */
    private void collect(GuiGraphics g, ItemStack st, int x, int y, int mx, int my, ItemStack[] hover) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mx >= x && mx < x + 16 && my >= y && my < y + 16) hover[0] = st;
    }

    // ---- 穿脱交互的数据与收发(M2) ----

    /** 指针压在这格(SLOT 见方)上吗。 */
    private static boolean overCell(double mx, double my, int x, int y) {
        return mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
    }

    /** 这格穿戴现在该显示的那件:回执落的真值优先,没有就快照里的(两处空栈都归 EMPTY)。 */
    private static ItemStack gearShown(GearCell cell) {
        ItemStack truth = GEAR_TRUTH.get(cell.name());
        if (truth != null) return truth;
        return cell.item() == null ? ItemStack.EMPTY : cell.item();
    }

    /** 这格背包现在该显示的那件:回执落的真值优先,没有就快照里的。 */
    private static ItemStack packShown(List<ItemStack> items, int i) {
        if (i >= 0 && i < PACK_TRUTH.length && PACK_TRUTH[i] != null) return PACK_TRUTH[i];
        return i >= 0 && i < items.size() && items.get(i) != null ? items.get(i) : ItemStack.EMPTY;
    }

    /**
     * 回执真值是落在哪一份背包快照上的:新快照实例一来(服务端又推了整份背包)旧真值整表作废。
     * 与 {@link #setGear} 清 {@link #GEAR_TRUTH} 同一条:服务端的推送是更新的真值。
     */
    private static void syncPackTruth(BodySnapshot snap) {
        if (snap == packTruthSource) return;
        packTruthSource = snap;
        java.util.Arrays.fill(PACK_TRUTH, null);
    }

    private static boolean hasMessage() {
        return messageKey != null || messageRaw != null;
    }

    /**
     * 一笔在飞超时(5s,早于服务端 10s 的走失 sweep):放开格子不再挂着「处理中」。
     * 面板本地不动任何真值——服务端那笔最迟 10s 内仍可能完成(迟到的回执在
     * {@link #acceptGearSwapResult} 处因 txId 不认而被忽略),所以提示措辞只说「没等到」,
     * 不承诺已退回;服务端变化自会经状态推送把显示带回来。
     */
    private static void checkPendingTimeout(long now) {
        if (pendingTx != 0 && now - pendingSentMs > TX_TIMEOUT_MS) {
            pendingTx = 0;
            pendingGearIdx = -1;
            pendingPackIdx = -1;
            pendingGearName = null;
            pendingCompanion = null;
            pendingExpectOwner = ItemStack.EMPTY;
            messageKey = ModLanguageData.Keys.PANEL_CHAR_WRITE_TIMEOUT;
            messageRaw = null;
        }
    }

    /** 一次只飞一笔:还有在飞的就不发,页头提示一句。 */
    private static boolean busy() {
        if (pendingTx == 0) return false;
        messageKey = ModLanguageData.Keys.PANEL_CHAR_WRITE_PENDING;
        messageRaw = null;
        return true;
    }

    private static void hintWriteLocked() {
        messageKey = ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED;
        messageRaw = null;
    }

    private static void noteGearHit(int idx, int x, int y) {
        if (idx >= gearHitX.length) {
            int cap = Math.max(idx + 1, GEAR.size()) + 8;
            gearHitX = java.util.Arrays.copyOf(gearHitX, cap);
            gearHitY = java.util.Arrays.copyOf(gearHitY, cap);
        }
        gearHitX[idx] = x;
        gearHitY[idx] = y;
        gearHitCount = Math.max(gearHitCount, idx + 1);
    }

    private static void notePackHit(int itemsIdx, int x, int y) {
        if (itemsIdx < 0 || itemsIdx >= packHitIdx.length) return;
        packHitX[itemsIdx] = x;
        packHitY[itemsIdx] = y;
        packHitIdx[itemsIdx] = itemsIdx;
        packHitCount = Math.max(packHitCount, itemsIdx + 1);
    }

    /** 「处理中」的样子:半透明压一层 + 强调色虚线亮框——看得见在等,又看得出底下是哪件。 */
    private static void pendingVeil(GuiGraphics g, UiTheme t, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, 0x50000000);
        dashedFrame(g, x, y, SLOT, SLOT, t.cta());
    }

    /**
     * 给这件挑目标槽:原版穿戴部位(甲类)有登记的同名槽就锁死那一类;定不出部位就只能在现有
     * writable 槽里挑第一个空位。甲类全占 = 换装意图明确,交给服务端如实回答;非甲类全占 =
     * 得顶掉东西,算定不下来。返回 null = 不发。
     *
     * <p>「能不能当甲穿」问的是她本体的原版判据({@code getEquipmentSlotForItem},与
     * {@code core/gear/Wardrobe.handFor} 同一条路径);问到非甲格({@code MAINHAND}/{@code OFFHAND})
     * 就当定不出部位处理——原版登记的那扇门({@code VanillaArmor})只登记四件甲。
     */
    private static GearCell chooseWearSlot(ItemStack piece, AbstractClientPlayer body) {
        if (GEAR.isEmpty()) return null;
        EquipmentSlot eq = body.getEquipmentSlotForItem(piece);
        // 甲格判定只用枚举常量比较(与 VanillaArmor 登记的那四个同源),不碰本树没出现过的别的 API
        boolean armor = eq == EquipmentSlot.HEAD || eq == EquipmentSlot.CHEST
                || eq == EquipmentSlot.LEGS || eq == EquipmentSlot.FEET;
        List<GearCell> candidates = new ArrayList<>();
        for (GearCell c : GEAR) {
            if (!c.writable()) continue;
            if (armor && (c.name() == null || !c.name().equals(eq.getName()))) continue;
            candidates.add(c);
        }
        if (candidates.isEmpty()) return null;
        for (GearCell c : candidates) {
            if (gearShown(c).isEmpty()) return c;   // 一类选它,多类用现有槽里第一个空位
        }
        return armor ? candidates.get(0) : null;
    }

    /**
     * 卸下:expectWorn = 槽里那件,expectOwner = 空,dir = 1(GearSwapRequest.REMOVE)。
     * 摘下的东西服务端经 {@code inv.add} 落进她背包(Wardrobe.remove),客户端无从预知哪格——
     * 背包那半不靠这份回执对齐,靠回执落地时顺势要的一份最新状态。
     */
    private static void sendRemove(UUID who, int gearIdx) {
        GearCell cell = GEAR.get(gearIdx);
        long tx = nextTxId++;
        pendingTx = tx;
        pendingSentMs = System.currentTimeMillis();
        pendingGearIdx = gearIdx;
        pendingGearName = cell.name();
        pendingCompanion = who;
        pendingPackIdx = -1;
        pendingExpectOwner = ItemStack.EMPTY;
        NumenNetwork.sendToServer(new GearSwapRequest(tx, who, cell.name(),
                gearShown(cell), ItemStack.EMPTY, GearSwapRequest.REMOVE));
    }

    /** 给她穿:expectWorn = 目标槽现在那件,expectOwner = 那格背包里的东西,dir = 0(WEAR)。 */
    private static void sendWear(UUID who, GearCell target, ItemStack piece) {
        long tx = nextTxId++;
        pendingTx = tx;
        pendingSentMs = System.currentTimeMillis();
        pendingGearIdx = GEAR.indexOf(target);
        pendingGearName = target.name();
        pendingCompanion = who;
        pendingExpectOwner = piece;
        pendingPackIdx = packTruthSource == null ? -1
                : firstMatch(packTruthSource.items(), piece);
        NumenNetwork.sendToServer(new GearSwapRequest(tx, who, target.name(),
                gearShown(target), piece, GearSwapRequest.WEAR));
    }

    /** 背包里第一格与 expect 全等({@code ItemStack.matches})的下标;没有/没给是 -1。与服务端 pinSlot 同口径。 */
    private static int firstMatch(List<ItemStack> items, ItemStack expect) {
        if (items == null || expect == null || expect.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (ItemStack.matches(items.get(i), expect)) return i;
        }
        return -1;
    }

    /**
     * 服务端回执到了({@code ClientPayloadSink.gearSwap} 挂的这里;客户端主线程)。认领在飞的
     * 那一笔,以回执的 wornAfter/ownerAfter 为准重画;失败把 reason 原样放到页头。旁牌/迟到的
     * 回执不认,也不动任何状态。
     *
     * <p>槽侧真值按<b>发送时记下的槽名</b>落键——在飞中 {@link #setGear} 换形时,同下标可能
     * 已是另一个槽,下标键会把真值盖到错的槽名下。卸下这笔服务端不背背包端(expectOwner=EMPTY
     * 钉不到格,ownerAfter 恒空),所以成功落地后顺势发一份 {@link RequestStatePayload},把
     * 「摘下的东西进了哪格」也一并对齐——服务端的背包变化推送(CompanionStateWatch,0.5s
     * 节流)通常更早到,这份请求只是把顺序颠倒、推送丢失等缝隙也补上。
     */
    public static void acceptGearSwapResult(GearSwapResult p) {
        if (p == null || pendingTx == 0 || p.txId() != pendingTx) return;
        pendingTx = 0;
        String gearName = pendingGearName;
        UUID who = pendingCompanion;
        int packIdx = firstMatch(packTruthSource == null ? null : packTruthSource.items(),
                pendingExpectOwner);
        pendingGearIdx = -1;
        pendingPackIdx = -1;
        pendingGearName = null;
        pendingCompanion = null;
        pendingExpectOwner = ItemStack.EMPTY;
        if (!p.ok()) {
            messageKey = null;
            messageRaw = p.reason();
            return;
        }
        messageKey = null;
        messageRaw = null;
        if (gearName != null) {
            GEAR_TRUTH.put(gearName, p.wornAfter());
        }
        if (who != null) {
            NumenNetwork.sendToServer(new RequestStatePayload(who));
        }
        if (packIdx >= 0 && packIdx < PACK_TRUTH.length) {
            PACK_TRUTH[packIdx] = p.ownerAfter() == null ? ItemStack.EMPTY : p.ownerAfter();
        }
    }

    // ---- 数据小件 ----

    /** 准星指着的同伴优先,否则名册第一只;一个都没有就是 null(整页降级为空态)。 */
    private static UUID pickCompanion() {
        AbstractClientPlayer aim = CompanionChatScreen.crosshairCompanion();
        if (aim != null) return aim.getUUID();
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        return entries.isEmpty() ? null : entries.get(0).uuid();
    }

    /**
     * 效果拼一行:{@code 名字 [等级] Ns}。名字与等级口径照 RuntimeState.renderEffects
     * (注册名去命名空间、{@code amplifier+1});秒数用 {@link BodySnapshot#remainingTicks}
     * 的确定性折算,已走完的不出现。
     */
    private static String effectText(BodySnapshot snap, long nowMs) {
        StringBuilder out = new StringBuilder();
        for (MobEffectInstance effect : snap.effects()) {
            int left = snap.remainingTicks(effect, nowMs);
            if (left == 0) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(effectName(effect));
            if (effect.getAmplifier() > 0) out.append(' ').append(effect.getAmplifier() + 1);
            out.append(' ').append(left < 0 ? "∞" : (left / 20) + "s");
        }
        return out.toString();
    }

    /** 效果的注册名(不带命名空间)。照 RuntimeState.effectName 的读法,那边是私有的,不跨包引。 */
    private static String effectName(MobEffectInstance effect) {
        var key = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
        return key == null ? "unknown" : key.getPath();
    }

    /** 整数不带小数点,带小数留一位(18.5/20 那种口径)。 */
    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
