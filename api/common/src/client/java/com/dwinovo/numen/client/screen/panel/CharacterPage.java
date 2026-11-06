package com.dwinovo.numen.client.screen.panel;

import com.dwinovo.numen.agent.request.BodySnapshot;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpMode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 角色页(M1,<b>只读</b>)——面板左栏第一格的内容,版式照 {@code docs/hud-replace-draft.md} §三:
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
 * <h2>只读</h2>
 * 本页<b>没有任何写操作</b>:不实现 {@link NumenPanelScreen.Page#mouseClicked}(默认不吃点击,
 * 拖拽/穿脱是 M2/M3 的事),唯一的输入是滚轮滚动;悬停只渲染 tooltip,不选中、不改物品。
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
     */
    public static void setGear(List<GearCell> cells) {
        GEAR.clear();
        if (cells != null) GEAR.addAll(cells);
    }

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
    /** 状态条行高与它的发丝线。 */
    private static final int STATUS_H = 15;
    /** 节头行进距离。 */
    private static final int HEAD_H = 13;
    /** 穿戴格横向间距与每格占高(格 18 + 槽名 9 + 只读标 9)。 */
    private static final int GEAR_PITCH = 46, GEAR_ROW_H = 40;
    /** 细血条的长与厚。 */
    private static final int BAR_W = 44, BAR_H = 3;

    // ---- 滚动(照 ProfilePage:像素趋近 + 末端夹取) ----

    private float scroll, scrollTarget;
    private int contentH, viewH, viewX, viewW;
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

        int x = rect.x, y = rect.y, w = rect.w, h = rect.h;
        viewX = x;
        viewW = w;
        viewH = h;
        scrollTarget = Mth.clamp(scrollTarget, 0f, Math.max(0, contentH - viewH));
        scroll = Anim.approach(scroll, scrollTarget, 16f, dt);
        int mx = NumenPanelScreen.mouseX();
        int my = NumenPanelScreen.mouseY();
        int iw = w - PAD * 2;
        ItemStack[] hover = {ItemStack.EMPTY};

        // 这一页是谁:准星指着的优先,否则名册第一只(与 NumenPanelScreen.pickCompanionUuid 同口径)
        UUID who = pickCompanion();
        BodySnapshot snap = who == null ? null : ClientNumenState.get(who).orElse(null);
        AbstractClientPlayer body = who == null ? null : ClientNumenLookup.resolve(who);
        EntityAgentLoop loop = who == null ? null : AgentLoopRegistry.get(who).orElse(null);

        // ---- 可滚内容:裁剪在状态条下面,滚上去也不会盖住状态条 ----
        g.enableScissor(x, y + STATUS_H + 1, x + w, y + h);
        int cy = y + STATUS_H + 8 - Math.round(scroll);

        cy = drawGear(g, f, t, x, y + h, iw, cy, mx, my, hover);
        cy = drawInventory(g, f, t, x, y + h, iw, cy, mx, my, hover, snap);
        cy = drawExtra(g, f, t, x, y + h, iw, cy);

        contentH = cy + Math.round(scroll) - y;
        // 滚动条:内容超出一屏才画
        if (contentH > viewH) {
            int th = Math.max(12, viewH * viewH / contentH);
            int ty = y + Math.round((viewH - th) * (scroll / Math.max(1, contentH - viewH)));
            g.fill(x + w - 3, ty, x + w - 1, ty + th, (t.textDim() & 0xFFFFFF) | 0x60000000);
        }
        g.disableScissor();

        // ---- 状态条最后画,压在滚动内容之上:它常驻,不跟着滚 ----
        drawStatusBar(g, f, t, x, y, w, body, snap, loop);

        // 悬停的原版物品 tooltip(整页画完再画,免得被格子盖住——ProfilePage 同一条)
        if (!hover[0].isEmpty()) {
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

    // ---- 穿戴 ----

    /** 穿戴分组:遍历 {@link #GEAR} 照序画,不排序;{@code writable=false} 虚线格 + 「只读」标。 */
    private int drawGear(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy,
                         int mx, int my, ItemStack[] hover) {
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
            drawGearCell(g, f, t, GEAR.get(i), sx, rowTop, mx, my, hover);
        }
        return rowTop + GEAR_ROW_H + 6;
    }

    /** 一格穿戴:格底(可写实底凹槽,只读虚线框)+ 物品 + 槽名(+「只读」)。 */
    private void drawGearCell(GuiGraphics g, Font f, UiTheme t, GearCell cell, int x, int y,
                              int mx, int my, ItemStack[] hover) {
        int edge = UiTheme.mix(t.band(), t.border(), cell.writable() ? 0.62f : 0.55f);
        if (cell.writable()) {
            slotBg(g, t, x, y);
        } else {
            dashedFrame(g, x, y, SLOT, SLOT, edge);
        }
        ItemStack st = cell.item() == null ? ItemStack.EMPTY : cell.item();
        collect(g, st, x + 1, y + 1, mx, my, hover);
        Nb.text(g, f, Nb.clip(f, cell.name() == null ? "?" : cell.name(), GEAR_PITCH - 6),
                x, y + SLOT + 2, t.faint());
        if (!cell.writable()) {
            Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_READONLY), x, y + SLOT + 11, t.fail());
        }
    }

    // ---- 背包 ----

    /** 主背包 27 格(物品 9..35)+ 快捷栏 9 格(物品 0..8),画法照 ProfilePage.drawItems。 */
    private int drawInventory(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy,
                              int mx, int my, ItemStack[] hover, BodySnapshot snap) {
        boolean ready = snap != null && snap.loaded() && snap.items() != null && !snap.items().isEmpty();
        cy = head(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_BACKPACK), "27 + 9", x, cy, iw);
        if (!ready) {
            return hint(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_BACKPACK_EMPTY), x, cy) + 4;
        }
        List<ItemStack> items = snap.items();
        int gx = x;
        for (int i = 9; i < 36 && i < items.size(); i++) {
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int sx = gx + col * SLOT, sy = cy + row * SLOT;
            slotBg(g, t, sx, sy);
            if (sy <= clipBottom) collect(g, items.get(i), sx + 1, sy + 1, mx, my, hover);
        }
        cy += 3 * SLOT + 5;
        if (cy <= clipBottom) {
            Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_HOTBAR), gx, cy, t.faint());
        }
        cy += 10;
        for (int i = 0; i < 9 && i < items.size(); i++) {
            int sx = gx + i * SLOT, sy = cy;
            slotBg(g, t, sx, sy);
            if (sy <= clipBottom) collect(g, items.get(i), sx + 1, sy + 1, mx, my, hover);
        }
        return cy + SLOT + 8;
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

    /** 画物品并记下悬停的那件(ProfilePage.collect 同款;本页只看不点)。 */
    private void collect(GuiGraphics g, ItemStack st, int x, int y, int mx, int my, ItemStack[] hover) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mx >= x && mx < x + 16 && my >= y && my < y + 16) hover[0] = st;
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
