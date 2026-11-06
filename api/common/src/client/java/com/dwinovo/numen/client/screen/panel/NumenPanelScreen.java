package com.dwinovo.numen.client.screen.panel;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.data.ClientPrefs;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.SafeUi;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Numen 面板(F9 开关):全屏暗幕上左栏七格 + 右栏内容区,Embeddium/Sodium 那套版式。
 *
 * <h2>本文件只做骨架</h2>
 * 版式、路由、键位、二次确认、置灰判据在这里;七个页面的内容(角色/任务/对话/技能/用量/
 * 通知/设置)由后面的两个页面文件填——每页一个 {@link Page},经 {@link #setPage} 挂进
 * 对应槽位;没挂上的页面右栏显示占位句,左栏照常按 {@link PanelSnapshot#RAIL_AVAILABLE} 置灰。
 *
 * <h2>版式(Embeddium 那套)</h2>
 * 全屏底色 rgba(0,0,0,.55);左列固定 {@link #RAIL_W}=132 宽、分类行 {@link #CAT_H}=24 高、
 * 缝 {@link #CAT_GAP}=3,选中行 rgba(255,255,255,.12) + 左侧 2px 白条;右栏顶部 20px 分组
 * 标题 + 右端「重置」;行 24 高、标签左、控件右 132(常量 {@link #ROW_H} / {@link #CONTROL_W},
 * 页面照这两个常量摆行);右下角「完成」96×20。左栏底部:常驻小条开关(偏好落
 * {@code ui.json},经 {@link ClientPrefs})+「叫停」(危险色,二次确认)。
 *
 * <h2>键位</h2>
 * F9 关面板(打开走 NumenKeys 的 F9 键,无屏时开);Esc 先退二次确认、再关面板;
 * Tab 循环切页(跳过没数据的格);1~7 直达对应格(没数据的格不接)。面板一关,本屏没有任何
 * 残留监听——键位全是 Screen 生命周期内的 {@code keyPressed} 覆写,快照刷新只发生在
 * {@code tick()}(屏幕开着才被调),关屏即停;控件也不注册到任何全局处,全是本屏 UiRoot 里的死对象。
 *
 * <h2>渲染零分配</h2>
 * 字符串在开屏的 {@link #sampleChrome()} 与每秒一次的 {@code tick()} 采样里备好,存静态
 * 字段;渲染回调 {@link #draw()} 是静态的、经非捕获方法引用进 SafeUi(照 YuukaStatusHud),
 * 只读现成引用、画矩形,不 new 不拼串。唯一的例外是每帧一只 {@link McDrawSurface} 适配壳
 * ——它的 GuiGraphics 字段是 final 的没法换目标,而控件库只认 IDrawSurface;本树自己的渲染
 * 路径(SettingsView 的各 render、NumenScreen 的模态卡)全走同一做法。
 *
 * <p>屏幕同一时刻至多一只(open() 只在无屏时开新),所以状态全收静态字段,
 * 开屏 {@code init()} 时重置。
 */
public final class NumenPanelScreen extends Screen {

    // ---- 页面槽位(顺序即左栏顺序;PanelSnapshot.RAIL_AVAILABLE 的下标与之一一对应) ----
    public static final int PAGE_CHARACTER = 0;
    public static final int PAGE_TASKS = 1;
    public static final int PAGE_CHAT = 2;
    public static final int PAGE_SKILLS = 3;
    public static final int PAGE_USAGE = 4;
    public static final int PAGE_NOTIFY = 5;
    public static final int PAGE_SETTINGS = 6;
    public static final int PAGE_COUNT = 7;

    // ---- 版式常量(照 Embeddium/Sodium 那套,hud-replace-draft §三) ----
    public static final int RAIL_W = 132;           // 左列固定宽
    public static final int CAT_H = 24;             // 分类行高
    public static final int CAT_GAP = 3;            // 分类行之间的缝
    public static final int TITLE_H = 20;           // 右栏分组标题行高
    public static final int ROW_H = 24;             // 内容区一行的标准高(页面照此摆行)
    public static final int CONTROL_W = 132;        // 行右端控件的固定宽
    private static final int DONE_W = 96;           // 「完成」钮
    private static final int DONE_H = 20;
    private static final int PAD = 8;               // 内边距
    private static final int FOOTER_H = 20 + PAD;   // 右栏底部「完成」占的高度
    private static final int RAIL_TOP = 6;          // 第一格分类行的顶边

    // 版式规格里点名的颜色(主题无关值,静态常量,渲染路径直接读)
    private static final int DIM_BG = 0x8C000000;   // rgba(0,0,0,.55)
    private static final int SEL_FILL = 0x1FFFFFFF; // rgba(255,255,255,.12) 选中底
    private static final int SEL_BAR = 0xFFFFFFFF;  // 选中行左侧 2px 白条

    /** 叫停的二次确认保持多久;超时自动退回。 */
    private static final long ABORT_ARM_MS = 4000;
    /** 快照多久重采一次(面板开着时)。 */
    private static final int REFRESH_EVERY_TICKS = 20;

    // ---- 页面注册:后面的两个页面文件把自己的 Page 挂进来 ----

    /**
     * 一页 = {@link #render(Rect)} + 可选的输入钩子。骨架保证:render 给你的矩形是右栏
     * 内容区(已扣掉标题行与底行);鼠标坐标用 {@link #mouseX()}/{@link #mouseY()} 读,
     * 画布用 {@link #frame()} 读,主题色用 {@link #colors()} 读,字体用 {@link #font()} 读——
     * 都是指向本帧现成对象的静态引用,别自己缓存 GuiGraphics。
     *
     * <p>行版式照规格:行高 {@link #ROW_H}=24、右端控件宽 {@link #CONTROL_W}=132;控件一律用
     * ui 模块的 {@code Toggle/Slider/Dropdown/ScrollBox/Label/Button/UiRoot},不用原版
     * OptionsList;控件只建一次,滚动照 SettingsView 的 ScrollBox 套路。
     */
    public interface Page {
        /** 画本页。rect 是可复用对象,别留着下帧用。 */
        void render(Rect rect);

        /** 可选:本页的键(已过 UiRoot 的焦点控件;返回 true = 吃掉)。 */
        default boolean keyPressed(int keyCode) { return false; }

        /** 可选:右栏「重置」钮;返回 false = 本页没有可重置的(骨架不做事)。 */
        default boolean onReset() { return false; }

        /** 可选:点击(先过骨架自己的控件;返回 true = 吃掉)。 */
        default boolean mouseClicked(double mx, double my, int button) { return false; }

        /** 可选:滚轮。 */
        default boolean mouseScrolled(double mx, double my, double delta) { return false; }

        /** 可选:字符输入(焦点文本框的字)。 */
        default boolean charTyped(char ch) { return false; }
    }

    /** 页面矩形:一个对象全场复用,每帧覆写字段,零分配。 */
    public static final class Rect {
        public int x, y, w, h;

        Rect set(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }
    }

    private static final Page[] PAGES = new Page[PAGE_COUNT];

    /** 后面的页面文件这样挂:NumenPanelScreen.setPage(NumenPanelScreen.PAGE_X, new XPage())。 */
    public static void setPage(int slot, Page page) {
        PAGES[slot] = page;
    }

    public static Page page(int slot) {
        return PAGES[slot];
    }

    // ---- 静态状态(单例屏;开屏 init 重置) ----

    /** 本帧画布;渲染入口置上、finally 摘掉,不跨帧攥着。 */
    private static GuiGraphics frame;
    private static int frameW, frameH;
    /** 本帧字体(开屏置上;字体实例整个运行期是同一只)。 */
    private static Font sharedFont;
    /** 本帧指针(页面读)。 */
    private static int mouseX, mouseY;

    /** 左栏文案,开屏采样一次。渲染只读。 */
    private static final String[] RAIL_LABELS = new String[PAGE_COUNT];
    private static String minibarLabel, abortLabel, abortConfirmLabel, resetLabel, doneLabel, pendingLabel;
    /** 通知角标,tick 里按值变了才重拼。渲染只读。 */
    private static String notifyBadge = "";
    private static int notifyBadgeShown = -1;

    private static final UiRoot ui = new UiRoot();
    private static Toggle minibarToggle;
    private static Button abortButton;
    private static int selected = PAGE_CHARACTER;
    private static long abortArmedAt;
    private static int tickCounter;
    /** 内容区矩形,复用实例。 */
    private static final Rect pageRect = new Rect();

    public NumenPanelScreen() {
        super(Component.translatable(ModLanguageData.Keys.PANEL_TITLE));
    }

    /**
     * 打开面板的入口。调用方是 NumenKeys 的 F9 分支({@code NumenKeys.tick()} 里的
     * {@code STATUS_HUD.consumeClick()}):无屏时按 F9 走到这里;面板开着时 F9 归本屏的
     * {@code keyPressed},是关——一开一关成对。左下角的常驻小条开关(控制右上角
     * Yuuka 状态条,偏好落 {@code ClientPrefs.statusHud})在本屏内,不占全局键位。
     */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null) {
            mc.setScreen(new NumenPanelScreen());
        }
    }

    // ---- 页面用的静态只读入口 ----

    public static GuiGraphics frame() { return frame; }

    public static Font font() { return sharedFont; }

    public static int mouseX() { return mouseX; }

    public static int mouseY() { return mouseY; }

    /** 主题色(宿主主题 → NumenUI 令牌的桥,按主题记忆化,渲染帧零分配)。 */
    public static NumenTheme.Colors colors() { return HostThemeColors.current(); }

    /** 内容区一行的标准高(页面摆行用)。 */
    public static int rowHeight() { return ROW_H; }

    /** 行右端控件的固定宽(页面摆行用)。 */
    public static int controlWidth() { return CONTROL_W; }

    // ---- 生命周期 ----

    @Override
    protected void init() {
        sharedFont = this.font;
        frameW = this.width;
        frameH = this.height;
        sampleChrome();
        CharacterPage.register();
        SettingsPage.register();
        PanelSnapshot.refresh();
        buildWidgets();
    }

    /** 左栏与底行的全部文案,开屏采样一次(语言在屏内不会变;换语言下次开屏重采)。 */
    private static void sampleChrome() {
        RAIL_LABELS[PAGE_CHARACTER] = I18n.get(ModLanguageData.Keys.PANEL_NAV_CHARACTER);
        RAIL_LABELS[PAGE_TASKS] = I18n.get(ModLanguageData.Keys.PANEL_NAV_TASKS);
        RAIL_LABELS[PAGE_CHAT] = I18n.get(ModLanguageData.Keys.PANEL_NAV_CHAT);
        RAIL_LABELS[PAGE_SKILLS] = I18n.get(ModLanguageData.Keys.PANEL_NAV_SKILLS);
        RAIL_LABELS[PAGE_USAGE] = I18n.get(ModLanguageData.Keys.PANEL_NAV_USAGE);
        RAIL_LABELS[PAGE_NOTIFY] = I18n.get(ModLanguageData.Keys.PANEL_NAV_NOTIFY);
        RAIL_LABELS[PAGE_SETTINGS] = I18n.get(ModLanguageData.Keys.PANEL_NAV_SETTINGS);
        minibarLabel = I18n.get(ModLanguageData.Keys.PANEL_MINIBAR);
        abortLabel = I18n.get(ModLanguageData.Keys.PANEL_ABORT);
        abortConfirmLabel = I18n.get(ModLanguageData.Keys.PANEL_ABORT_CONFIRM);
        resetLabel = I18n.get(ModLanguageData.Keys.PANEL_RESET);
        doneLabel = I18n.get(ModLanguageData.Keys.PANEL_DONE);
        pendingLabel = I18n.get(ModLanguageData.Keys.PANEL_PAGE_PENDING);
    }

    /** 控件只建一次(init 会随窗口缩放重跑,clear 后重建)。不进 renderables:画面归控件库。 */
    private static void buildWidgets() {
        ui.clear();
        // 常驻小条开关控制右上角 Yuuka 状态条的显隐:YuukaStatusHud 渲染/采样都看
        // ClientPrefs.statusHud(hud/YuukaStatusHud.java:69,81),这是它唯一的用户开关
        // ——F9 改开面板后,旧的翻开关职责挪到这只 Toggle 上。
        minibarToggle = ui.add(new Toggle(ClientPrefs.statusHud(), ClientPrefs::setStatusHud));
        abortButton = ui.add(new Button(abortLabel, Button.Style.DANGER, NumenPanelScreen::onAbortClicked));
        ui.add(new Button(resetLabel, Button.Style.LINK, NumenPanelScreen::onResetClicked));
        ui.add(new Button(doneLabel, Button.Style.NORMAL, NumenPanelScreen::closePanel));
        layoutWidgets();
        ui.setViewportHeight(frameH);
    }

    private static void layoutWidgets() {
        int railBottom = frameH - PAD;
        // 「叫停」:左栏底行,占满左栏宽(两端内缩 PAD)
        abortButton.setBounds(PAD, railBottom - DONE_H, RAIL_W - PAD * 2, DONE_H);
        // 常驻小条:叫停上面一行,标签在手画路径,开关靠右
        int miniY = railBottom - DONE_H - 6 - CAT_H;
        minibarToggle.setBounds(RAIL_W - PAD - 26, miniY + (CAT_H - 14) / 2, 26, 14);
        // 右栏顶行的「重置」(纯字钮)与右下角的「完成」;按加入顺序取第 3、4 只
        int resetW = sharedFont.width(resetLabel) + 12;
        ui.widgetsView().get(2).setBounds(frameW - PAD - resetW, (TITLE_H - 14) / 2 + 1, resetW, 14);
        ui.widgetsView().get(3).setBounds(frameW - PAD - DONE_W, frameH - PAD - DONE_H, DONE_W, DONE_H);
    }

    @Override
    public void tick() {
        tickCounter++;
        // 快照采样:每秒一次,tick 路径允许分配;关屏时 tick 不再被调,采样自然停。
        if (tickCounter % REFRESH_EVERY_TICKS == 0) {
            PanelSnapshot.refresh();
        }
        // 通知角标:值变了才重拼字符串
        int unread = PanelSnapshot.unreadCount();
        if (unread != notifyBadgeShown) {
            notifyBadgeShown = unread;
            notifyBadge = unread > 0 ? " · " + unread : "";
        }
        // 二次确认超时自动退回
        if (abortArmedAt != 0 && System.currentTimeMillis() - abortArmedAt > ABORT_ARM_MS) {
            disarmAbort();
        }
    }

    @Override
    public void removed() {
        disarmAbort();
        frame = null;   // 与 YuukaStatusHud 同一条:不跨帧攥着画布
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- 动作 ----

    private static void closePanel() {
        disarmAbort();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(null);
        }
    }

    /** 叫停:第一次点进入待确认,第二次点才真的停;Esc 或超时退回。 */
    private static void onAbortClicked() {
        if (abortArmedAt == 0) {
            abortArmedAt = System.currentTimeMillis();
            abortButton.setLabel(abortConfirmLabel);
            return;
        }
        disarmAbort();
        abortCurrentCompanion();
    }

    private static void disarmAbort() {
        abortArmedAt = 0;
        if (abortButton != null) abortButton.setLabel(abortLabel);
    }

    /** 停她手上这一轮:准星指着的那位优先,否则名册第一只(与 YuukaStatusHud 的挑选同一口径)。 */
    private static void abortCurrentCompanion() {
        java.util.UUID pick = pickCompanionUuid();
        if (pick == null) return;
        AgentLoopRegistry.get(pick).ifPresent(EntityAgentLoop::abort);
    }

    private static java.util.UUID pickCompanionUuid() {
        var body = CompanionChatScreen.crosshairCompanion();
        var entries = NumenRoster.instance().entries();
        if (body != null) {
            for (NumenRoster.Entry e : entries) {
                if (e.uuid().equals(body.getUUID())) return e.uuid();
            }
        }
        return entries.isEmpty() ? null : entries.get(0).uuid();
    }

    private static void onResetClicked() {
        Page p = PAGES[selected];
        if (p != null) p.onReset();
    }

    /** 切页;切页顺带把二次确认退掉——确认是贴在叫停钮上的,换页就该重来。 */
    private static void selectPage(int slot) {
        if (slot == selected) return;
        selected = slot;
        disarmAbort();
        ui.setViewportHeight(frameH);
    }

    private static boolean available(int slot) {
        return PanelSnapshot.RAIL_AVAILABLE[slot];
    }

    // ---- 键位 ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F9) {          // F9 关面板(开在 NumenKeys)
            closePanel();
            return true;
        }
        if (keyCode == KeyCodes.ESCAPE) {           // Esc:先退二次确认,再关面板
            if (abortArmedAt != 0) {
                disarmAbort();
            } else {
                closePanel();
            }
            return true;
        }
        if (ui.keyPressed(keyCode, modifiers)) return true;   // 焦点控件(如输入框)先吃
        Page p = PAGES[selected];
        if (p != null && p.keyPressed(keyCode)) return true;
        if (keyCode == KeyCodes.TAB) {              // Tab 循环切页,跳过没数据的格
            for (int step = 1; step < PAGE_COUNT; step++) {
                int next = (selected + step) % PAGE_COUNT;
                if (available(next)) {
                    selectPage(next);
                    return true;
                }
            }
            return true;    // 全都没数据:键照吃,页不动
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_7) {   // 1~7 直达
            int slot = keyCode - GLFW.GLFW_KEY_1;
            if (available(slot)) selectPage(slot);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (ui.charTyped(ch)) return true;
        Page p = PAGES[selected];
        return p != null && p.charTyped(ch);
    }

    // ---- 指针 ----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        updateMouse(mx, my);
        if (ui.mouseClicked(mx, my, button)) return true;    // 控件(开关/叫停/重置/完成)先吃
        int slot = railAt(mx, my);
        if (slot >= 0) {
            if (available(slot)) selectPage(slot);
            return true;
        }
        Page p = PAGES[selected];
        return p != null && p.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return ui.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        updateMouse(mx, my);
        return ui.mouseDragged(mx, my, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        Page p = PAGES[selected];
        return p != null && p.mouseScrolled(mx, my, delta);
    }

    private static void updateMouse(double mx, double my) {
        mouseX = (int) mx;
        mouseY = (int) my;
    }

    /** 指针落在左栏哪一格上;不在任何格上是 -1。与 drawRail 用同一套常量。 */
    private static int railAt(double mx, double my) {
        if (mx < 0 || mx >= RAIL_W) return -1;
        int top = RAIL_TOP;
        for (int i = 0; i < PAGE_COUNT; i++) {
            if (my >= top && my < top + CAT_H) return i;
            top += CAT_H + CAT_GAP;
        }
        return -1;
    }

    // ---- 渲染(零分配:只读静态字段、画矩形;非捕获方法引用进 SafeUi) ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame = g;
        frameW = this.width;
        frameH = this.height;
        updateMouse(mouseX, mouseY);
        try {
            SafeUi.run("numen-panel-render", NumenPanelScreen::draw);
        } finally {
            frame = null;   // 不跨帧攥着上一帧的画布(照 YuukaStatusHud)
        }
    }

    private static void draw() {
        GuiGraphics g = frame;
        if (g == null) {
            return;
        }
        UiTheme t = UiTheme.current();
        // 全屏暗幕 + 右栏面板底
        g.fill(0, 0, frameW, frameH, DIM_BG);
        g.fill(RAIL_W, 0, frameW, frameH, t.band());

        drawRail(g, t);
        drawTitleRow(g, t);
        drawPage(g, t);
        drawWidgets(g);
    }

    private static void drawRail(GuiGraphics g, UiTheme t) {
        int y = RAIL_TOP;
        for (int i = 0; i < PAGE_COUNT; i++) {
            boolean sel = i == selected;
            boolean avail = available(i);
            boolean hot = !sel && avail && mouseX >= 0 && mouseX < RAIL_W && mouseY >= y && mouseY < y + CAT_H;
            if (sel) {
                g.fill(0, y, RAIL_W, y + CAT_H, SEL_FILL);
                g.fill(0, y, 2, y + CAT_H, SEL_BAR);      // 左侧 2px 白条
            } else if (hot) {
                g.fill(0, y, RAIL_W, y + CAT_H, t.over());
            }
            int color = !avail ? t.faint() : sel ? t.text() : t.textDim();
            Nb.text(g, sharedFont, RAIL_LABELS[i], PAD, y + (CAT_H - sharedFont.lineHeight) / 2 + 1, color);
            // 通知角标:只在通知格、有未读时画
            if (i == PAGE_NOTIFY && notifyBadgeShown > 0) {
                int labelW = sharedFont.width(RAIL_LABELS[i]);
                Nb.text(g, sharedFont, notifyBadge, PAD + labelW,
                        y + (CAT_H - sharedFont.lineHeight) / 2 + 1, t.cta());
            }
            y += CAT_H + CAT_GAP;
        }
        // 左栏底部两格的手画部分:常驻小条的标签(开关是控件,统一在 drawWidgets 画)
        int abortY = frameH - PAD - DONE_H;
        int miniY = abortY - 6 - CAT_H;
        Nb.text(g, sharedFont, minibarLabel, PAD, miniY + (CAT_H - sharedFont.lineHeight) / 2 + 1, t.textDim());
    }

    private static void drawTitleRow(GuiGraphics g, UiTheme t) {
        // 分组标题 = 左栏选中的那格名(Embeddium 的分栏抬头)
        Nb.text(g, sharedFont, RAIL_LABELS[selected], RAIL_W + PAD,
                (TITLE_H - sharedFont.lineHeight) / 2 + 1, t.text());
        // 标题行下面一道发丝线,把内容区和标题分开
        g.fill(RAIL_W, TITLE_H, frameW, TITLE_H + 1, t.surfaceBorder());
    }

    private static void drawPage(GuiGraphics g, UiTheme t) {
        pageRect.set(RAIL_W + PAD, TITLE_H + PAD, frameW - RAIL_W - PAD * 2,
                frameH - TITLE_H - PAD - FOOTER_H);
        Page p = PAGES[selected];
        if (p == null) {
            // 页面还没接进来:占位句居中
            int w = sharedFont.width(pendingLabel);
            Nb.text(g, sharedFont, pendingLabel, pageRect.x + (pageRect.w - w) / 2,
                    pageRect.y + pageRect.h / 2 - sharedFont.lineHeight / 2, t.faint());
            return;
        }
        p.render(pageRect);
    }

    /** 控件库的钮和开关压在手画 chrome 之上。唯一每帧分配:一只 McDrawSurface 适配壳(见类注释)。 */
    private static void drawWidgets(GuiGraphics g) {
        McDrawSurface s = new McDrawSurface(g, sharedFont);
        ui.render(s, HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
    }
}
