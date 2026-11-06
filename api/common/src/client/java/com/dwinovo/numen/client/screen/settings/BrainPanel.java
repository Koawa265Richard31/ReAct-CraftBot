package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.ConfirmDialog;
import com.dwinovo.numen.client.ui.widget.Disclosure;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ScrollBox;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpMode;
import net.minecraft.client.Minecraft;
import com.dwinovo.numen.client.ui.mc.Sprites;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 外接大脑分区——一页说完,高级的收在倒三角后面。
 *
 * <h2>默认只有三件事</h2>
 * 地址、令牌、复制接入提示词。接一个外部 AI 只需要这三样,新手看到的就该只有这三样。
 * 再往上是状态四行(开着没、谁接上了、多久前有请求、租约还剩几秒)和「立即停止」——
 * 看她在不在,不对劲就停;端口、局域网、调用超时、租约秒数、不暴露的工具——这些收进
 * 「高级设置」,想细调的人点开就是。装不下就滚动({@link ScrollBox}),不再藏一层子页。
 *
 * <h2>两层:滚的与不滚的</h2>
 * 总开关那一行、话筒那一行、收尾的复制提示词按钮固定不动,其余的行在滚动层里。
 * 手绘的几条(地址与令牌的值、局域网警示)也在滚动层,所以要减掉 {@link ScrollBox#offset()}。
 *
 * <h2>版式</h2>
 * 照 Telegram 设置行({@link SettingsRows}):一行一项,左标签右值或右开关,组与组之间一道宽缝——
 * 总开关、状态四行、立即停止、接入信息、高级设置,各是一组。
 *
 * <h2>草稿在字段上,不在控件上</h2>
 * 端口/局域网/超时/租约/不暴露的工具是草稿,保存才落地;草稿记在本类字段上,折叠、展开、
 * 重建都不会把没保存的输入弄丢。保存成功后清成 null = 交回配置当真源。
 */
public final class BrainPanel {

    /** 图标钮的方块热区:图标 12 格,四周各留一格好点。 */
    private static final int ICON_BTN = Sprites.SIZE + 2;
    /** 两枚图标并排时的步进。 */
    private static final int ICON_PITCH = ICON_BTN + 1;
    private static final int SAVE_W = 96;
    /** 高级设置里数值框的宽;所有行的右边沿都对齐在 {@code x + w - RIGHT_INSET}。 */
    private static final int NUM_FIELD_W = 60;
    private static final int RIGHT_INSET = 2;
    /** 「不暴露的工具」那一行:标签留这么宽,余下的都给输入框。 */
    private static final int HIDDEN_LABEL_W = 100;
    /** 状态区四行的标签宽:标签只有四五个字,右侧整段都留给每帧现画的值。 */
    private static final int STATUS_LABEL_W = 70;

    /** 会滚的行。 */
    private final UiRoot ui = new UiRoot();
    /** 不滚的:抬头、回执胶囊、收尾按钮。 */
    private final UiRoot fixedUi = new UiRoot();
    private final ScrollBox scroll = new ScrollBox();
    /** 页面级回执(已复制/已保存):跨 build 持久,重建不吞在途消息。 */
    private final InlineAlert notice = new InlineAlert();
    private final ConfirmDialog confirm = new ConfirmDialog();

    /** 高级设置开着没有——状态在这儿,控件只是照着画。 */
    private boolean advanced;
    /** 五份草稿;null = 跟着配置走(首次进来、以及保存成功之后)。 */
    private String portText, timeoutText, hiddenText, leaseText;
    private Boolean lanOn;
    private TextField portField;
    private Button saveButton;
    /** 本页所有图标钮:图标不写字,悬停得说得出自己是干嘛的(宿主按命中取 tooltip)。 */
    private final List<Button> iconButtons = new ArrayList<>();
    private int x, y, w, h;
    private int dimX, dimY, dimW, dimH;

    /** 手绘几条的基线(滚动层坐标,画的时候减 {@link ScrollBox#offset()})。 */
    private int endpointRow, tokenRow, lanRow;
    /** 状态区四行的顶边(滚动层坐标):行里只躺标签,右侧的值每帧照 {@link McpMode} 现画。 */
    private int statusRunRow, statusClientRow, statusActivityRow, statusLeaseRow;
    /** 租约输入行的顶边(滚动层坐标):那句「多久算不在」的说明画在这行下面。 */
    private int leaseRow;
    /** 话筒那一行的顶边(固定层):起服失败与回执胶囊落在这儿,平时空着。 */
    private int msgRow;
    /** 收尾行的顶边与复制提示词按钮的宽:那句令牌提醒紧挨着它画。 */
    private int footerRow, promptW;

    public BrainPanel() {
        Minecraft mc = Minecraft.getInstance();
        for (UiRoot root : List.of(ui, fixedUi)) {
            root.setClipboard(() -> mc.keyboardHandler.getClipboard(),
                    s -> mc.keyboardHandler.setClipboard(s));
            // 文本编辑交给真 EditBox(只收事件、不自绘),画面仍归 NumenUI。
            // 这是输入法辅助模组能认出这些框的前提——见 McTextInput。
            root.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
        }
    }

    public void build(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        ui.clear();
        fixedUi.clear();
        portField = null;
        saveButton = null;
        iconButtons.clear();
        McpMode mcp = McpMode.instance();
        McpConfig cfg = mcp.config();
        Font font = Minecraft.getInstance().font;

        // ---- 固定层 ----
        // 这一页叫什么在面板抬头上;第一行是一条设置:左边说开关管什么,右边是开关
        SettingsRows.label(fixedUi, t("numen.brain.enable"), x, y, w - 140);
        // 开关回调只写配置,绝不在此重建——重建会 new 出滑块已在终点的新 Toggle,
        // 滑动动画连起步都来不及(真机教训:大脑区开关瞬时切换的病根)。
        // 开关本身就是"开着还是关着",这一行不另写一句;开着时开关左边报接上没接上(见 render)。
        Toggle tog = fixedUi.add(new Toggle(mcp.enabled(), McpMode.instance()::setEnabled));
        tog.setBounds(x + w - 24, SettingsRows.controlY(y, 11), 22, 11);
        // 总开关自成一组,和下面接入要用的东西之间一道宽缝
        SettingsRows.gap(fixedUi, x, y + SettingsRows.ROW_H, w);

        int footer = NumenStyle.footerTop(y, h);
        msgRow = footer - 5 - 9;
        String promptLabel = t("numen.brain.copy_prompt");
        footerRow = footer;
        promptW = font.width(promptLabel) + 14;
        Button prompt = fixedUi.add(new Button(promptLabel, Button.Style.ACCENT,
                () -> copy(McpMode.instance().accessPrompt())));
        prompt.setBounds(x, footer, promptW, NumenStyle.CONTROL_H);
        fixedUi.add(notice).setBounds(x, msgRow - 3, w, 15);

        // ---- 滚动层 ----
        int top = y + SettingsRows.ROW_H + SettingsRows.GAP_H;
        int ry = top;

        // 状态区,只读四行:开着没、谁接上了、多久前有请求、租约还剩几秒。
        // 行里只放标签;右侧的值是秒级跳动的东西,跟地址/令牌一样在 render 里现画,不进草稿。
        statusRunRow = ry;
        SettingsRows.label(ui, "运行状态", x, ry, STATUS_LABEL_W);
        ry += SettingsRows.ROW_H;
        statusClientRow = ry;
        SettingsRows.label(ui, "接入客户端", x, ry, STATUS_LABEL_W);
        ry += SettingsRows.ROW_H;
        statusActivityRow = ry;
        SettingsRows.label(ui, "上次请求", x, ry, STATUS_LABEL_W);
        ry += SettingsRows.ROW_H;
        statusLeaseRow = ry;
        SettingsRows.label(ui, "租约剩余", x, ry, STATUS_LABEL_W);
        ry += SettingsRows.ROW_H;
        SettingsRows.gap(ui, x, ry, w);
        ry += SettingsRows.GAP_H;

        // 「立即停止」紧挨着状态:看一眼不对劲,手边就得有这颗钮,不该先去开高级设置。
        // 停的是名下每具身体上正在跑的程序与活——MCP stop 工具走的同一条路(NumenActuator.stop)。
        Button stop = ui.add(new Button("立即停止", Button.Style.DANGER, this::stopEverything));
        int stopW = font.width("立即停止") + 14;
        stop.setBounds(x, SettingsRows.controlY(ry, NumenStyle.CONTROL_H), stopW, NumenStyle.CONTROL_H);
        Label stopHint = ui.add(new Label("叫停每具身体上正在跑的程序与活", Label.Role.MUTED));
        stopHint.setBounds(x + stopW + 10, SettingsRows.controlY(ry, 9), w - stopW - 14, 9);
        ry += SettingsRows.ROW_H;
        SettingsRows.gap(ui, x, ry, w);
        ry += SettingsRows.GAP_H;

        // 一行一项,左标签右值(Telegram 设置行):地址、令牌的值是强调色的字,在 render 里右对齐画,
        // 紧挨着它们的是作用在它们身上的图标钮——值右对齐贴着图标,动作就贴着它作用的那个东西。
        endpointRow = ry;
        SettingsRows.label(ui, t("numen.brain.endpoint"), x, ry, labelW());
        iconButton(Sprites.COPY, t("numen.brain.copy"), x + w - ICON_BTN, ry,
                () -> copy(McpMode.instance().endpoint()));
        ry += SettingsRows.ROW_H;

        tokenRow = ry;
        SettingsRows.label(ui, t("numen.brain.token"), x, ry, labelW());
        // 没令牌时复制没得复制:留在原地置灰,这一行的排布不跟着跳。
        iconButton(Sprites.COPY, t("numen.brain.copy"), x + w - ICON_BTN - ICON_PITCH, ry,
                () -> copy(McpMode.instance().token())).setEnabled(!mcp.token().isBlank());
        iconButton(Sprites.REFRESH, t("numen.brain.regenerate"), x + w - ICON_BTN, ry,
                this::askRegenerate);
        ry += SettingsRows.ROW_H;
        SettingsRows.gap(ui, x, ry, w);
        ry += SettingsRows.GAP_H;

        Disclosure adv = ui.add(new Disclosure(t("numen.brain.advanced"), advanced, () -> {
            advanced = !advanced;
            scroll.toTop();     // 换了一份内容,上一份滚到哪儿了与这份无关
            build(this.x, this.y, this.w, this.h);
        }));
        adv.setBounds(x, SettingsRows.controlY(ry, NumenStyle.CONTROL_H), w, NumenStyle.CONTROL_H);
        int bottom = ry + SettingsRows.ROW_H;

        if (advanced) {
            ry += SettingsRows.ROW_H;
            // 端口与「允许局域网」= 上面那条地址的两截。后者是 host 的人话面:关=127.0.0.1,
            // 开=0.0.0.0。玩家不必知道那五个字符,想绑具体网卡的高级用户改
            // config/numen/mcp_server.json —— 配置文件就是逃生舱。
            SettingsRows.label(ui, t("numen.brain.port"), x, ry, w - NUM_FIELD_W - 8);
            portText = portText != null ? portText : String.valueOf(cfg.port());
            portField = ui.add(new TextField(portText, v -> {
                portText = v;
                refreshSaveState();
            }).numeric().underlined(true));
            portField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                    NUM_FIELD_W, NumenStyle.CONTROL_H);
            ry += SettingsRows.ROW_H;

            lanRow = ry;
            SettingsRows.label(ui, t("numen.brain.lan"), x, ry, w - 28);
            Toggle lan = ui.add(new Toggle(lanNow(), on -> {
                lanOn = on;
                refreshSaveState();
            }));
            lan.setBounds(x + w - 24, SettingsRows.controlY(ry, 11), 22, 11);
            ry = noteBelow(lanRow) + 9 + 3;

            SettingsRows.label(ui, t("numen.brain.timeout"), x, ry, w - NUM_FIELD_W - 8);
            timeoutText = timeoutText != null ? timeoutText
                    : String.valueOf(cfg.callTimeoutSeconds());
            TextField timeoutField = ui.add(new TextField(timeoutText, v -> timeoutText = v)
                    .numeric().underlined(true));
            timeoutField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                    NUM_FIELD_W, NumenStyle.CONTROL_H);
            ry += SettingsRows.ROW_H;

            // 租约:外脑在不在场的唯一判据(距上次成功的 tools/call 超过这么多秒 = 不在)。
            // 保存时照 McpConfig.clampLeaseSeconds 夹进 30~600;范围写进下面那句说明里。
            SettingsRows.label(ui, "租约(秒)", x, ry, w - NUM_FIELD_W - 8);
            leaseText = leaseText != null ? leaseText : String.valueOf(cfg.leaseSeconds());
            TextField leaseField = ui.add(new TextField(leaseText, v -> leaseText = v)
                    .numeric().underlined(true));
            leaseField.setBounds(x + w - RIGHT_INSET - NUM_FIELD_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                    NUM_FIELD_W, NumenStyle.CONTROL_H);
            leaseRow = ry;
            ry = noteBelow(leaseRow) + 9 + 3;

            SettingsRows.label(ui, t("numen.brain.hidden_tools"), x, ry, HIDDEN_LABEL_W);
            hiddenText = hiddenText != null ? hiddenText : String.join(", ", cfg.hiddenTools());
            int hiddenW = w - RIGHT_INSET - HIDDEN_LABEL_W - 8;
            TextField hiddenField = ui.add(new TextField(hiddenText, v -> hiddenText = v)
                    .placeholder(t("numen.brain.hidden_hint")).underlined(true));
            hiddenField.setBounds(x + w - RIGHT_INSET - hiddenW, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                    hiddenW, NumenStyle.CONTROL_H);
            ry += SettingsRows.ROW_H;

            // 旧失联回退的开关已经撤掉;只有旧配置文件里还留着 true 的才给一句说明——
            // 字段照读照写(为兼容旧配置),但不再改变任何行为:在不在场只由租约判定。
            if (cfg.quietFallback()) {
                Label stale = ui.add(new Label("该选项已废弃:现在由租约决定,不再由内置脑接管。",
                        Label.Role.MUTED));
                stale.setBounds(x, SettingsRows.controlY(ry, 9), w, 9);
                ry += SettingsRows.ROW_H;
            }

            saveButton = ui.add(new Button(saveLabel(), Button.Style.NORMAL, this::save));
            saveButton.setBounds(x + w - RIGHT_INSET - SAVE_W, SettingsRows.controlY(ry, NumenStyle.CONTROL_H),
                    SAVE_W, NumenStyle.CONTROL_H);
            refreshSaveState();
            ry += SettingsRows.ROW_H;
            bottom = ry;
        }

        // 视口到话筒那一行为止;内容装不下就滚,装得下连拇指都不画。
        // 视口左右各放宽一圈内边距:组间宽缝铺满整页宽,不能被裁在内容边上。
        scroll.measure(ui, x - NumenStyle.PAD, top, w + NumenStyle.PAD * 2, msgRow - 4 - top, bottom - top);
    }

    /** 地址、令牌两行标签的宽:值从右往左排,标签只要自己那几个字。 */
    private int labelW() {
        Font font = Minecraft.getInstance().font;
        return Math.max(font.width(t("numen.brain.endpoint")), font.width(t("numen.brain.token")));
    }

    /** 要解释的行下面那一句小灰字的顶边。 */
    private static int noteBelow(int row) { return SettingsRows.controlY(row, NumenStyle.CONTROL_H) + NumenStyle.CONTROL_H + 1; }

    private boolean lanNow() {
        return lanOn != null ? lanOn : McpMode.instance().config().lanExposed();
    }

    /** 遮罩范围由宿主给——确认卡要盖住整个设置面板,不是只盖这个分区。 */
    public void setDimBounds(int dimX, int dimY, int dimW, int dimH) {
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
    }

    /** 端点改了且服务在跑 → 这次保存要重开服务,按钮如实说。 */
    private String saveLabel() {
        McpConfig cfg = McpMode.instance().config();
        boolean endpointChanged = portValue() != cfg.port() || lanNow() != cfg.lanExposed();
        return t(McpMode.instance().enabled() && endpointChanged
                ? "numen.brain.save_restart" : "numen.brain.save");
    }

    /** 草稿里的端口;空的或不成数 = -1(校验会拦下)。 */
    private int portValue() {
        if (portText == null) {
            return McpMode.instance().config().port();
        }
        try {
            return Integer.parseInt(portText.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 每次改动都重算一遍能不能保存。
     *
     * <p>拦得最死的一条:<b>开了局域网、令牌却是空的</b>。配置注释里那句"空令牌在回环上无害"
     * 的前提是回环,地址一放开就是谁都能操控主人的同伴。红字挂在局域网那一行,不弹全局警告。
     */
    private void refreshSaveState() {
        if (saveButton == null || portField == null) {
            return;
        }
        int port = portValue();
        boolean portOk = port >= 1 && port <= 65535;
        portField.setError(portOk ? null : t("numen.brain.port_range"));
        boolean tokenOk = !lanNow() || !McpMode.instance().token().isBlank();
        saveButton.setEnabled(portOk && tokenOk);
        saveButton.setLabel(saveLabel());
    }

    private void save() {
        McpConfig cfg = McpMode.instance().config();
        List<String> hidden = new ArrayList<>();
        for (String piece : (hiddenText == null ? "" : hiddenText).split(",")) {
            String name = piece.strip();
            if (!name.isEmpty()) hidden.add(name);
        }
        int timeout = cfg.callTimeoutSeconds();
        if (timeoutText != null && !timeoutText.isBlank()) {
            try {
                timeout = Integer.parseInt(timeoutText.strip());
            } catch (NumberFormatException e) {
                timeout = cfg.callTimeoutSeconds();
            }
        }
        // 租约照配置自己的规矩夹进 30~600(与读配置时的行为一致);填不成数 = 保持配置原值。
        int lease = cfg.leaseSeconds();
        if (leaseText != null && !leaseText.isBlank()) {
            try {
                lease = McpConfig.clampLeaseSeconds(Integer.parseInt(leaseText.strip()));
            } catch (NumberFormatException e) {
                lease = cfg.leaseSeconds();
            }
        }
        boolean ok = McpMode.instance().applySettings(
                lanNow() ? McpConfig.ANY_HOST : McpConfig.LOOPBACK,
                portValue(),
                Math.max(1, timeout),
                hidden,
                McpMode.instance().token(),
                lease);
        if (ok) {
            // 落地了,配置重新成为真源——草稿清空,下次 build 照配置填。
            portText = timeoutText = hiddenText = leaseText = null;
            lanOn = null;
            notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.saved"), 2_000);
        } else {
            // 起服失败最常见的就是端口被占用——把话挂回出错的那个框,别飘在别处
            portField.setError(I18n.get("numen.brain.port_taken", portValue()));
        }
    }

    // ---- 立即停止 ----

    /**
     * 主人亲手停:名下每具身体各走一道 {@link NumenActuator#stop}——MCP 的 stop 工具走的同一条路,
     * 三路互斥(外脑在跑段按编号切断、回执照原路交回;她自己的回合走 halt(OWNER_STOP);
     * 服务端自己起的那段发不认编号的叫停),不弹审批、幂等。回执都落在主人客户端的主线程,
     * 所以在回调里直接写 UI;最后一笔回来时在话筒行报一句账:停了什么,或确实没什么在跑。
     */
    private void stopEverything() {
        List<NumenRoster.Entry> roster = NumenRoster.instance().entries();
        if (roster.isEmpty()) {
            notice.show(InlineAlert.Severity.INFO, "名下没有同伴,没有可停的东西", 2_000);
            return;
        }
        int[] pending = {roster.size()};
        StringBuilder stopped = new StringBuilder();
        for (NumenRoster.Entry entry : roster) {
            NumenActuator.stop(new NumenActuator.StopRequest(entry.uuid())).whenComplete((report, err) -> {
                if (stopped.length() > 0) stopped.append(";");
                if (err != null) {
                    stopped.append(entry.name()).append(":叫停没能发出去");
                } else if (report.wasRunning()) {
                    String what = report.program() != null ? report.program() : report.task();
                    stopped.append(entry.name()).append(":").append(what != null ? what : "在跑的东西");
                }
                if (--pending[0] == 0) {
                    if (stopped.length() > 0) {
                        notice.show(InlineAlert.Severity.SUCCESS, "已停下:" + stopped, 4_000);
                    } else {
                        notice.show(InlineAlert.Severity.INFO, "没有在跑的东西", 2_000);
                    }
                }
            });
        }
    }

    // ---- 令牌 ----

    /**
     * 换令牌要过确认卡。
     *
     * <p>我们自己把明文令牌嵌进「接入提示词」、并教主人复制给外部 AI——那就必须给他一条
     * 作废的路。而作废是有代价的:在线的客户端会当场断开,得说清楚再让他点。
     */
    private void askRegenerate() {
        confirm.open(fixedUi, dimX, dimY, dimW, dimH,
                t("numen.brain.regen_confirm_title") + "\n" + t("numen.brain.regen_confirm_body"),
                t("numen.gui.settings.cancel"), t("numen.brain.regenerate"),
                () -> {
                    McpConfig cfg = McpMode.instance().config();
                    McpMode.instance().applySettings(cfg.host(), cfg.port(),
                            cfg.callTimeoutSeconds(), cfg.hiddenTools(), McpConfig.mintToken(),
                            cfg.leaseSeconds());
                    notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.saved"), 2_000);
                    build(x, y, w, h);   // 令牌从无到有时,复制钮得跟着亮起来
                });
    }

    private String tokenText() {
        McpMode mcp = McpMode.instance();
        return mcp.token().isBlank() ? t("numen.brain.token_none") : mcp.maskedToken();
    }

    // ---- 渲染 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        McpMode mcp = McpMode.instance();

        scroll.beginClip(s);
        int dy = -scroll.offset();
        // 状态区四行的值:数据(开着没、谁接上、多久前、租约剩几秒)每帧照 McpMode 现读,
        // 右对齐画在各自的标签行里;「已失联」用醒目的红——那是身体已经停下待机的信号。
        // 口径先看有没有人接过、再看租约:从没接入不是"过期",别把两件事说混。
        int statusX = x + STATUS_LABEL_W + 8;
        int statusRight = x + w - 4;
        boolean on = mcp.enabled();
        String who = mcp.clientName();
        rightValue(s, on ? "已开启" : "已关闭", statusX, statusRight, statusRunRow + dy,
                on ? c.success() : c.textMuted());
        rightValue(s, !on ? "—" : (who == null ? "尚无客户端接入" : who), statusX, statusRight,
                statusClientRow + dy, on && who != null ? c.accent() : c.textMuted());
        rightValue(s, !on ? "—" : (mcp.lastActivityMs() == 0 ? "从未" : sinceLabel(mcp.lastActivityMs())),
                statusX, statusRight, statusActivityRow + dy,
                on && mcp.lastActivityMs() > 0 ? c.accent() : c.textMuted());
        long leaseLeft = mcp.leaseRemainingSeconds();
        String leaseValue;
        int leaseColor;
        if (!on) {
            leaseValue = "—";
            leaseColor = c.textMuted();
        } else if (leaseLeft > 0) {
            leaseValue = leaseLeft + " 秒";
            leaseColor = c.accent();
        } else if (mcp.lastRenewedMs() == 0) {
            leaseValue = "还没有请求";
            leaseColor = c.textMuted();
        } else {
            leaseValue = "已失联,身体在安全待机";
            leaseColor = c.danger();
        }
        rightValue(s, leaseValue, statusX, statusRight, statusLeaseRow + dy, leaseColor);
        // 地址与令牌:行右侧的值(Telegram 设置行右侧是强调色的字),右对齐贴着各自的图标钮
        int valueX = x + labelW() + 8;
        rightValue(s, mcp.endpoint(), valueX, x + w - ICON_BTN - 4, endpointRow + dy, c.accent());
        rightValue(s, tokenText(), valueX, x + w - ICON_BTN - ICON_PITCH - 4, tokenRow + dy,
                mcp.token().isBlank() ? c.textMuted() : c.accent());
        if (advanced) {
            // 绑到所有网卡这件事本身会成功,只是降级——按自家判据是 warning 不是 danger。
            // 但令牌为空时它就变成"这次保存不该发生",那才是 danger。
            if (lanNow()) {
                boolean noToken = mcp.token().isBlank();
                s.drawText(t(noToken ? "numen.brain.lan_needs_token" : "numen.brain.lan_warn"),
                        x, noteBelow(lanRow) + dy, noToken ? c.danger() : c.warning(), false);
            }
            // 租约那一行的说明:一句说清"多久算不在"与"不在了会怎样"——待机,不是交还内脑。
            s.drawText("30 到 600 秒。大脑每隔这么久没请求,就认为它不在了;身体会停下并待机,不会让内置脑接管。",
                    x, noteBelow(leaseRow) + dy, c.textMuted(), false);
        }
        ui.renderContent(s, c, mouseX, mouseY, nowMs);
        scroll.endClip(s);
        scroll.renderThumb(s, c);

        // 开着时开关左边报一句在场状态(等待接入 / 谁在用 · 多久前 · 租约剩多久;过期报待机),
        // 底色跟着租约三态走:在场=绿;还没续约=灰(刚开服没人连是常态,不算警报);
        // 续过约又断了=红,身体已经停下待机。关着时开关自己就说明了。
        if (mcp.enabled()) {
            String badge = statusLine(mcp);
            int bw = Minecraft.getInstance().font.width(badge) + 8;
            int badgeBg = mcp.leaseOk() ? c.success()
                    : (mcp.lastRenewedMs() <= 0 ? c.textMuted() : c.danger());
            Badge.draw(s, badge, x + w - 30 - bw,
                    SettingsRows.controlY(y, s.lineHeight()),
                    badgeBg, 0xFFFFFFFF);
        }
        // 令牌就在提示词里,这句提醒紧挨着那个按钮——它是按钮的注脚,不是另起一行的公告。
        s.drawText(TextClip.fit(s, t("numen.brain.prompt_warn"), w - promptW - 8),
                x + promptW + 8,
                NumenStyle.centerIn(footerRow, NumenStyle.CONTROL_H, s.lineHeight()),
                c.textMuted(), false);
        // 话筒那一行平时空着,只有起服失败时说话(回执胶囊也落在这儿)。
        String err = mcp.lastError();
        if (err != null) {
            s.drawText(TextClip.fit(s, I18n.get("numen.brain.start_failed", err), w),
                    x, msgRow, c.danger(), false);
        }

        ui.renderOverlayLayer(s, c, mouseX, mouseY, nowMs);
        fixedUi.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 行右侧的值:在 {@code [left, right)} 里右对齐,放不下从尾部收口。 */
    private static void rightValue(IDrawSurface s, String text, int left, int right, int rowY, int color) {
        String shown = TextClip.fit(s, text, Math.max(0, right - left));
        s.drawText(shown, right - s.textWidth(shown), SettingsRows.controlY(rowY, s.lineHeight()), color, false);
    }

    /** 悬停在哪枚图标上要说的一句;宿主画完这一分区再把它画在最上面。 */
    public String tooltipAt(double mx, double my) {
        if (!scroll.inside(my)) {
            return null;   // 滚出视口的行被裁掉了,坐标上却还在
        }
        for (Button b : iconButtons) {
            if (b.enabled() && b.contains(mx, my)) return b.tooltip();
        }
        return null;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (fixedUi.hasOverlay()) return fixedUi.mouseClicked(mx, my, button);
        if (ui.hasOverlay()) return ui.mouseClicked(mx, my, button);
        if (fixedUi.mouseClicked(mx, my, button)) return true;
        // 视口外的行虽被裁掉,坐标上仍在——点击按可视区域裁决。
        return scroll.inside(my) && ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta) || scroll.scrolled(my, delta);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return fixedUi.keyPressed(keyCode, modifiers) || ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return fixedUi.charTyped(ch) || ui.charTyped(ch);
    }

    // ---- 内部 ----

    /**
     * 一枚图标钮:GHOST 按钮(没有底也没有边)+ 一枚贴图 + 悬停说一句,在行内垂直居中。
     *
     * <p>动作写成 lambda 而不是 build 时就把值取出来:配置随时可变,捕获到的会是过期值。
     */
    private Button iconButton(ResourceLocation sprite, String tip, int bx, int by, Runnable action) {
        Button b = ui.add(new Button(tip, Button.Style.GHOST, action)
                .icon(Sprites.SIZE, Sprites.painter(sprite))
                .tooltip(tip));
        b.setBounds(bx, SettingsRows.controlY(by, ICON_BTN), ICON_BTN, ICON_BTN);
        iconButtons.add(b);
        return b;
    }

    private void copy(String text) {
        ui.copyToClipboard(text);
        notice.show(InlineAlert.Severity.SUCCESS, t("numen.brain.copied"), 1_500);
    }

    /** 开着时的一句:先看有没有续过约,再看租约——从没续约(包括握了手还没成功调用过)不是
     *  "过期",别把两件事说混;在场才报谁在用、多久前活跃过、租约还剩多久。租约那两句直接
     *  写中文不走语言表:这是本页新造的口径,新增 key 会掉进 en_us 有、zh_cn 缺的坑。 */
    private static String statusLine(McpMode mcp) {
        if (mcp.lastRenewedMs() <= 0) return t("numen.brain.status_waiting");
        if (!mcp.leaseOk()) {
            return "已失联,身体在安全待机";
        }
        return I18n.get("numen.brain.status_connected", mcp.clientName(), sinceLabel(mcp.lastActivityMs()))
                + " · 租约剩 " + mcp.leaseRemainingSeconds() + " 秒";
    }

    private static String sinceLabel(long stampMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - stampMs) / 1000);
        if (sec < 60) return I18n.get("numen.brain.since_sec", sec);
        return I18n.get("numen.brain.since_min", sec / 60);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
