package com.dwinovo.numen.client.screen.panel;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.screen.settings.BrainPanel;
import com.dwinovo.numen.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.client.screen.settings.LibraryListPanel;
import com.dwinovo.numen.client.screen.settings.McpFormPanel;
import com.dwinovo.numen.client.screen.settings.PersonaFormPanel;
import com.dwinovo.numen.client.screen.settings.ProfileFormPanel;
import com.dwinovo.numen.client.screen.settings.SkinFormPanel;
import com.dwinovo.numen.client.screen.settings.SttPanel;
import com.dwinovo.numen.client.screen.settings.ThemePanel;
import com.dwinovo.numen.client.screen.settings.VoiceFormPanel;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Disclosure;
import com.dwinovo.numen.client.ui.widget.InlineAlert;
import com.dwinovo.numen.client.ui.widget.ScrollBox;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.persona.PersonaLibrary;
import com.dwinovo.numen.sdk.ApiRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 设置页(面板 F9 左栏「设置」格)——把 {@code NumenScreen} 设置里的八个分区搬进面板:
 * 左侧一支二级列表挑分区,右侧矩形里画选中的分区。分区内容<b>原样复用</b>
 * {@code client.screen.settings} 下既有的面板类(ProfileFormPanel / PersonaFormPanel /
 * VoiceFormPanel / SttPanel / SkinFormPanel / McpFormPanel / BrainPanel / ThemePanel),
 * 本页只做托管(建几何、转发输入、画到自己的坐标系),不重写它们、不新增设置项。
 * Skills 分区不在面板里(技能独立成页,见 PANEL_NAV_SKILLS)。
 *
 * <p>除八个分区外,本页末尾多一支「外接脑」(bridge,DSH 心跳自报的 {@code brain.json}):
 * 只读状态行、8766 只读小门的开关(本区块唯一可写项)、四行文件路径与三组工具清单。
 *
 * <h2>托管的形状</h2>
 * 各面板照 {@code SettingsView} 已暴露的形状接:列表是 {@link LibraryListPanel}
 * (build/render/mouseClicked/mouseScrolled/keyPressed),表单面板 open(draft) + build + render,
 * 整页面板(Stt/Brain/Theme)直接 build + render。表单模态卡(暗幕 + 近全幅卡 + 卡顶标题)
 * 照 {@code SettingsView} 的做法在本页重画一份卡壳——面板类只认自己的坐标系,卡壳归宿主。
 *
 * <h2>外接脑区块的口径</h2>
 * <ul>
 *   <li>{@code brain.json} 是 DSH 心跳插件写的,本页<b>只读、永不写</b>;路径由 mod 配置的
 *       {@code brain.bridgeFile} 给({@link PanelSnapshot#brainFile()}),字段按
 *       {@code phase-hud/bridge-format.md} 的 v1 契约读,缺了显示「—」。</li>
 *   <li>温度、状态表频率:M1 里<b>只读</b>——它们活在 DSH host 的插件配置里,mod 没有写入口;
 *       行尾注明「改 host 配置」,不画可点的控件。</li>
 *   <li>8766(live.enabled)可写:以 JSON 树为单位改 mod 自己的 mcp_server.json
 *       (读整份对象、只替换 {@code live.enabled}、其余键原样写回),行尾注明「重开游戏客户端生效」,
 *       写失败把原因显示在行上。</li>
 *   <li>文件四行的路径一律运行时求值:mod 配置由游戏目录拼出,DSH 侧三行由 brain.json 自报
 *       ({@code personaFile} / {@code profileDir}+cordis.patch.yml / {@code stateFile}),
 *       字段缺失整行隐藏;文件不存在整行置灰写「未找到」、按钮禁用。</li>
 *   <li>工具清单三组:身体工具枚举 {@link ToolRegistry#all()}(中文走约定键
 *       {@code numen.tool.<name>},缺键回落英文原名,照 ChatView 的口径);脚本 API 取
 *       {@link ApiRegistry#groups()} 按命名空间折叠;大脑工具取 brain.json 的 {@code tools[]}。</li>
 * </ul>
 *
 * <h2>已知边界</h2>
 * <ul>
 *   <li>面板骨架的 {@code Page} 没有拖动/松开回调:声线表单的音量滑条拖动、人设正文拖选
 *       在面板里不可用(点击仍可用);要改得改骨架,不是本页能做的。</li>
 *   <li>Esc 由骨架先吃(先关面板),表单开着时不能用它收表单——用卡上的「取消」。</li>
 *   <li>皮肤文件的拖放进面板没有入口(Page 无文件拖放回调),仍可用表单里的原生文件选择。</li>
 * </ul>
 */
public final class SettingsPage implements NumenPanelScreen.Page {

    /** 把本页挂进面板的设置格;幂等(init 随窗口缩放重跑,不换实例、不丢状态)。 */
    public static void register() {
        if (NumenPanelScreen.page(NumenPanelScreen.PAGE_SETTINGS) == null) {
            NumenPanelScreen.setPage(NumenPanelScreen.PAGE_SETTINGS, new SettingsPage());
        }
    }

    // ---- 分区 ----

    /**
     * 面板里的八个设置分区 + 外接脑。顺序即二级列表顺序;八个分区与 SettingsView 的
     * nav 一致(SKILLS 不在其中),外接脑排在它的近亲「外接大脑」后面。
     */
    private enum Sec { PROVIDER, PERSONA, VOICE, STT, SKIN, MCP, BRAIN, THEME, EXTERNAL }

    private static final Sec[] NAV = {
            Sec.PROVIDER, Sec.PERSONA, Sec.VOICE, Sec.STT, Sec.SKIN,
            Sec.MCP, Sec.BRAIN, Sec.THEME, Sec.EXTERNAL};

    /** 分区名:设置分区沿用 SettingsView 的 nav 键;外接脑用自己的 panel.settings 键。 */
    private static String label(Sec s) {
        return switch (s) {
            case PROVIDER -> I18n.get(ModLanguageData.Keys.PROVIDER_TITLE);
            case PERSONA -> I18n.get("numen.settings.nav.persona");
            case VOICE -> I18n.get(ModLanguageData.Keys.VOICE_TITLE);
            case STT -> I18n.get(ModLanguageData.Keys.STT_NAV);
            case SKIN -> I18n.get(ModLanguageData.Keys.SKIN_TITLE);
            case MCP -> I18n.get("numen.settings.nav.mcp");
            case BRAIN -> I18n.get("numen.settings.nav.brain");
            case THEME -> I18n.get("numen.settings.nav.theme");
            case EXTERNAL -> I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_EXTERNAL);
        };
    }

    // ---- 版式 ----

    /** 左侧二级列表的宽与行高。 */
    private static final int LIST_W = 104;
    private static final int ITEM_H = 20;
    /** 列表与右侧内容之间的一道发丝线。 */
    private static final int LIST_GAP = 7;
    /** 表单模态卡的内缩(照 SettingsView 的卡壳)。 */
    private static final int CARD_INSET = 10;
    private static final int CARD_TITLE_H = 16;
    /** 外接脑页:状态行的行高、文件行的行高。 */
    private static final int EXT_ROW_H = 18;
    private static final int EXT_FILE_H = 22;
    /** 外接脑页:标签列的宽(状态行与文件行的值都从这条线往右画)。 */
    private static final int EXT_LABEL_W = 96;

    // ---- 页面几何(每帧覆写)与重建判定 ----

    private int px, py, pw, ph;
    private Sec section = Sec.PROVIDER;
    /** 上次建控件的 (分区, 几何);变了才重建——面板类 build 会清自己的 UiRoot,不能每帧跑。 */
    private Sec builtFor;
    private int builtX, builtY, builtW, builtH = -1;
    /** 事件回调里置上,下一帧重建(等价 SettingsView 的 host.rebuild())。 */
    private boolean needBuild;

    private int rx() { return px + LIST_W + LIST_GAP; }
    private int rw() { return pw - LIST_W - LIST_GAP; }

    /** 整屏的 GUI 坐标:表单的下拉弹层与确认卡要越出本页矩形往下画。 */
    private static int screenH() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    private static int screenW() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private SettingsPage() {
    }

    // ======================================================================
    // 八个分区:照 SettingsView 的形状托管。状态机(草稿/编辑 id/开关)一并搬来。
    // ======================================================================

    // ---- 供应商:档案列表 + ProfileFormPanel ----
    private boolean addingProvider;
    private String providerEditId;
    private ProfileFormPanel providerForm;
    private ProfileFormPanel.Draft providerDraft = new ProfileFormPanel.Draft();
    private LibraryListPanel<com.dwinovo.numen.agent.llm.ProviderLibrary.Entry> profileList;

    private ProfileFormPanel providerForm() {
        if (providerForm == null) {
            providerForm = new ProfileFormPanel(
                    this::onProfileSave,
                    () -> {
                        addingProvider = false;
                        providerEditId = null;
                        needBuild = true;
                    });
        }
        return providerForm;
    }

    private LibraryListPanel<com.dwinovo.numen.agent.llm.ProviderLibrary.Entry> profileList() {
        if (profileList == null) {
            profileList = new LibraryListPanel<>(
                    ModLanguageData.Keys.PROVIDER_ADD,
                    ModLanguageData.Keys.PROVIDER_EMPTY,
                    () -> com.dwinovo.numen.agent.llm.ProviderLibrary.instance().list(),
                    e -> {
                        boolean hasKey = nb(e.apiKey());
                        String meta = (nb(e.provider()) ? e.provider() : "?") + " · "
                                + (nb(e.model()) ? e.model() : "?")
                                + (hasKey ? "" : " · " + I18n.get(ModLanguageData.Keys.PROVIDER_NO_KEY));
                        UUID uuid = companionUuid();
                        Boolean marked = uuid == null ? null : e.id().equals(
                                com.dwinovo.numen.client.agent.CompanionHome.binding(uuid).providerId());
                        return new LibraryListPanel.Row(e.name() == null ? "" : e.name(), meta, !hasKey, marked);
                    },
                    e -> Component.translatable(ModLanguageData.Keys.PROVIDER_DELETE_CONFIRM,
                            e.name() == null ? "" : e.name()).getString(),
                    e -> com.dwinovo.numen.agent.llm.ProviderLibrary.instance().remove(e.id()),
                    () -> {
                        addingProvider = true;
                        providerEditId = null;
                        providerDraft = new ProfileFormPanel.Draft();
                        needBuild = true;
                    },
                    this::beginEditProvider)
                    // 换绑走 setProviderEntry(下一次请求即生效);不许解绑——同伴必须有端点
                    .withBind(e -> {
                        UUID uuid = companionUuid();
                        if (uuid != null) {
                            AgentLoopRegistry.getOrCreate(uuid).setProviderEntry(e.id());
                        }
                    }, null);
        }
        return profileList;
    }

    private void onProfileSave(ProfileFormPanel.Draft d) {
        var lib = com.dwinovo.numen.agent.llm.ProviderLibrary.instance();
        int ctx = parseCtx(d.ctx);
        if (providerEditId != null) {
            lib.update(new com.dwinovo.numen.agent.llm.ProviderLibrary.Entry(
                    providerEditId, d.name.trim(), d.provider, d.model.trim(),
                    d.apiKey.trim(), d.baseUrl.trim(), d.reasoningEffort, d.proxy.trim(), ctx));
        } else {
            lib.create(d.name.trim(), d.provider, d.model.trim(),
                    d.apiKey.trim(), d.baseUrl.trim(), d.reasoningEffort, d.proxy.trim(), ctx);
        }
        addingProvider = false;
        providerEditId = null;
        needBuild = true;
    }

    private void beginEditProvider(com.dwinovo.numen.agent.llm.ProviderLibrary.Entry e) {
        addingProvider = true;
        providerEditId = e.id();
        providerDraft = new ProfileFormPanel.Draft();
        providerDraft.name = nv(e.name());
        providerDraft.provider = nv(e.provider());
        providerDraft.model = nv(e.model());
        providerDraft.apiKey = nv(e.apiKey());
        providerDraft.baseUrl = nv(e.baseUrl());
        providerDraft.reasoningEffort = nv(e.reasoningEffort());
        providerDraft.proxy = nv(e.proxy());
        providerDraft.ctx = e.ctx() > 0 ? String.valueOf(e.ctx()) : "";
        needBuild = true;
    }

    /** 档案的上下文长度输入:空/非数字/非正数一律 0(= 按模型表自动)。 */
    private static int parseCtx(String raw) {
        try {
            int v = Integer.parseInt(raw == null ? "" : raw.trim());
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- 人设:目录重扫 + 列表 + PersonaFormPanel ----
    private boolean addingPersona;
    private String personaEditId;
    private PersonaFormPanel personaForm;
    private PersonaFormPanel.Draft personaDraft = new PersonaFormPanel.Draft();
    private LibraryListPanel<PersonaLibrary.Persona> personaList;

    private PersonaFormPanel personaForm() {
        if (personaForm == null) {
            personaForm = new PersonaFormPanel(this::onPersonaSave,
                    () -> {
                        addingPersona = false;
                        personaEditId = null;
                        needBuild = true;
                    });
        }
        return personaForm;
    }

    private LibraryListPanel<PersonaLibrary.Persona> personaList() {
        if (personaList == null) {
            personaList = new LibraryListPanel<>(
                    "numen.persona.add", "numen.persona.empty",
                    () -> PersonaLibrary.instance().list(),
                    p -> {
                        String badge = p.preset() ? I18n.get("numen.persona.preset_badge") + " · " : "";
                        // 正文预览压成单行(MD 里的换行在行里没有意义)。
                        String meta = (badge + p.text()).replace('\n', ' ');
                        UUID uuid = companionUuid();
                        Boolean marked = uuid == null ? null : p.id().equals(
                                com.dwinovo.numen.client.agent.CompanionHome.binding(uuid).personaId());
                        return new LibraryListPanel.Row(p.name(), meta, false, marked, p.preset());
                    },
                    p -> Component.translatable("numen.persona.delete_confirm", p.name()).getString(),
                    p -> PersonaLibrary.instance().remove(p.id()),
                    () -> {
                        addingPersona = true;
                        personaEditId = null;
                        personaDraft = new PersonaFormPanel.Draft();
                        needBuild = true;
                    },
                    this::beginEditPersona)
                    // 换绑 = setPersona(活切,聊天流插分隔记号);解绑回默认人设
                    .withBind(
                            p -> {
                                UUID uuid = companionUuid();
                                if (uuid != null) {
                                    AgentLoopRegistry.getOrCreate(uuid).setPersona(p.id());
                                }
                            },
                            p -> {
                                UUID uuid = companionUuid();
                                if (uuid != null) {
                                    AgentLoopRegistry.getOrCreate(uuid).setPersona(null);
                                }
                            })
                    .withClone(p -> PersonaLibrary.instance().clonePersona(p.id()))
                    // ↻ 重扫 persona/ 目录——外部编辑器改完 md 不用重开面板。
                    .withTitleAction("↻", () -> PersonaLibrary.instance().reload());
        }
        return personaList;
    }

    private void onPersonaSave(PersonaFormPanel.Draft d) {
        String name = d.name.trim();
        String text = d.text.trim();
        var lib = PersonaLibrary.instance();
        if (personaEditId != null) {
            PersonaLibrary.Persona saved = lib.update(personaEditId, name, text);
            // 改名 = 换了身份(人设的 id 就是文件名),得把在用的同伴重新指过去。
            if (saved != null && !saved.id().equals(personaEditId)) {
                for (UUID cu : AgentLoopRegistry.loadedEntityUuids()) {
                    EntityAgentLoop l = AgentLoopRegistry.get(cu).orElse(null);
                    if (l != null && personaEditId.equals(l.personaId())) {
                        l.setPersona(saved.id());
                    }
                }
            }
        } else {
            lib.create(name, text);
        }
        addingPersona = false;
        personaEditId = null;
        personaDraft = new PersonaFormPanel.Draft();
        needBuild = true;
    }

    private void beginEditPersona(PersonaLibrary.Persona p) {
        addingPersona = true;
        personaEditId = p.id();
        var d = new PersonaFormPanel.Draft();
        d.name = p.name();
        d.text = p.text();
        personaDraft = d;
        needBuild = true;
    }

    // ---- 声线:列表 + VoiceFormPanel(试听/音量滑条) ----
    private boolean addingVoice;
    private String voiceEditId;
    private VoiceFormPanel voiceForm;
    private VoiceFormPanel.Draft voiceDraft = VoiceFormPanel.freshDraft();
    private LibraryListPanel<com.dwinovo.numen.client.voice.VoiceLibrary.Entry> voiceList;

    private VoiceFormPanel voiceForm() {
        if (voiceForm == null) {
            voiceForm = new VoiceFormPanel(this::onVoiceSave,
                    () -> {
                        addingVoice = false;
                        voiceEditId = null;
                        voiceForm.cancelPendingTest();
                        needBuild = true;
                    });
        }
        return voiceForm;
    }

    private LibraryListPanel<com.dwinovo.numen.client.voice.VoiceLibrary.Entry> voiceList() {
        if (voiceList == null) {
            voiceList = new LibraryListPanel<>(
                    ModLanguageData.Keys.VOICE_ADD,
                    ModLanguageData.Keys.VOICE_EMPTY,
                    () -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().list(),
                    e -> {
                        String detail;
                        if (e.isSovits()) detail = nb(e.refAudio()) ? e.refAudio() : "?";
                        else if (e.isMiniMax() || e.isFishAudio()) detail = nb(e.voice()) ? e.voice() : "?";
                        else detail = nb(e.model()) ? e.model() : "?";
                        String meta = (nb(e.backend()) ? e.backend() : "openai") + " · " + detail
                                + " · vol " + Math.round(e.volume() * 5.0f);
                        UUID uuid = companionUuid();
                        Boolean marked = uuid == null ? null : e.id().equals(
                                com.dwinovo.numen.client.agent.CompanionHome.binding(uuid).voiceId());
                        return new LibraryListPanel.Row(e.name(), meta, false, marked);
                    },
                    e -> Component.translatable(ModLanguageData.Keys.VOICE_DELETE_CONFIRM,
                            e.name() == null ? "" : e.name()).getString(),
                    e -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().remove(e.id()),
                    () -> {
                        addingVoice = true;
                        voiceEditId = null;
                        voiceDraft = VoiceFormPanel.freshDraft();
                        needBuild = true;
                    },
                    this::beginEditVoice)
                    // 换绑/解绑写进 CompanionHome 绑定,下一句话就用新声线(VoiceLibrary 现查)
                    .withBind(
                            e -> {
                                UUID uuid = companionUuid();
                                if (uuid != null) {
                                    com.dwinovo.numen.client.agent.CompanionHome.bind(uuid,
                                            com.dwinovo.numen.client.agent.CompanionHome
                                                    .binding(uuid).withVoice(e.id()));
                                }
                            },
                            e -> {
                                UUID uuid = companionUuid();
                                if (uuid != null) {
                                    com.dwinovo.numen.client.agent.CompanionHome.bind(uuid,
                                            com.dwinovo.numen.client.agent.CompanionHome
                                                    .binding(uuid).withVoice(null));
                                }
                            })
                    .withToggle(ModLanguageData.Keys.VOICE_ENABLED,
                            () -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().enabled(),
                            v -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().setEnabled(v));
        }
        return voiceList;
    }

    private void onVoiceSave(VoiceFormPanel.Draft d) {
        String name = d.name.trim();
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
        if (voiceEditId != null) {
            lib.update(VoiceFormPanel.entryOf(d, voiceEditId, name));
        } else {
            var e = VoiceFormPanel.entryOf(d, "", name);
            var created = lib.create(name, e.backend(), e.url(), e.apiKey(), e.groupId(), e.model(),
                    e.voice(), e.refAudio(), e.promptText(), e.textLang(), e.volume());
            // 从面板新建 → 直接绑给当前同伴(建声线就是给这只配音)。
            UUID uuid = companionUuid();
            if (uuid != null) {
                com.dwinovo.numen.client.agent.CompanionHome.bind(uuid,
                        com.dwinovo.numen.client.agent.CompanionHome.binding(uuid)
                                .withVoice(created.id()));
            }
        }
        addingVoice = false;
        voiceEditId = null;
        voiceDraft = VoiceFormPanel.freshDraft();
        needBuild = true;
    }

    private void beginEditVoice(com.dwinovo.numen.client.voice.VoiceLibrary.Entry e) {
        addingVoice = true;
        voiceEditId = e.id();
        var d = new VoiceFormPanel.Draft();
        d.backend = normalizeVoiceBackend(e.backend());
        d.name = nv(e.name());
        d.url = nv(e.url());
        d.apiKey = nv(e.apiKey());
        d.groupId = nv(e.groupId());
        d.model = nv(e.model());
        d.voice = nv(e.voice());
        d.refAudio = nv(e.refAudio());
        d.promptText = nv(e.promptText());
        d.textLang = nv(e.textLang());
        // 存储的是增益(0.2~2.0),表单显示 1~10 档。
        d.volume = Math.round(Mth.clamp(e.volume(), 0.2f, 2.0f) * 5.0f);
        voiceDraft = d;
        needBuild = true;
    }

    /** 存储里的 backend 串归一到下拉的已知 id(未知/留空按 openai)。 */
    private static String normalizeVoiceBackend(String backend) {
        String b = backend == null ? "" : backend.toLowerCase(Locale.ROOT).strip();
        return switch (b) {
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_SOVITS,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_FISH,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_DASHSCOPE,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MIMO,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_DOUBAO -> b;
            default -> com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_OPENAI;
        };
    }

    // ---- 皮肤:列表 + SkinFormPanel(文件选择导入;拖放进面板没有入口,见类注释) ----
    private boolean addingSkin;
    private SkinFormPanel skinForm;
    private SkinFormPanel.Draft skinDraft = new SkinFormPanel.Draft();
    private LibraryListPanel<com.dwinovo.numen.client.skin.SkinLibrary.Entry> skinList;

    private SkinFormPanel skinForm() {
        if (skinForm == null) {
            skinForm = new SkinFormPanel(
                    signedName -> {   // 保存完成;真签过名才在列表页报回执
                        addingSkin = false;
                        skinDraft = new SkinFormPanel.Draft();
                        needBuild = true;
                        if (signedName != null) {
                            skinList().noticeSuccess(Component.translatable(
                                    ModLanguageData.Keys.SKIN_SIGN_OK, signedName).getString());
                        }
                    },
                    () -> {   // 取消
                        addingSkin = false;
                        skinForm.cancelPending();
                        needBuild = true;
                    },
                    this::openNativeSkinPicker);
        }
        return skinForm;
    }

    private LibraryListPanel<com.dwinovo.numen.client.skin.SkinLibrary.Entry> skinList() {
        if (skinList == null) {
            skinList = new LibraryListPanel<>(
                    ModLanguageData.Keys.SKIN_ADD,
                    ModLanguageData.Keys.SKIN_EMPTY,
                    () -> com.dwinovo.numen.client.skin.SkinLibrary.instance().list(),
                    e -> {
                        boolean signed = e.signed();
                        String meta = I18n.get(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_SLIM
                                .equals(e.variant())
                                ? ModLanguageData.Keys.SKIN_VARIANT_SLIM
                                : ModLanguageData.Keys.SKIN_VARIANT_CLASSIC)
                                + " · " + I18n.get(signed ? ModLanguageData.Keys.SKIN_SIGNED
                                : ModLanguageData.Keys.SKIN_UNSIGNED);
                        return new LibraryListPanel.Row(e.name(), meta, !signed, null);
                    },
                    e -> Component.translatable(ModLanguageData.Keys.SKIN_DELETE_CONFIRM,
                            e.name() == null ? "" : e.name()).getString(),
                    e -> com.dwinovo.numen.client.skin.SkinLibrary.instance().remove(e.id()),
                    () -> {
                        addingSkin = true;
                        skinDraft = new SkinFormPanel.Draft();
                        needBuild = true;
                    },
                    this::beginEditSkin)
                    .withRowIcon(16, (s, e, ix, iy, size) -> {
                        if (!(s instanceof McDrawSurface mc)) return;
                        var lib = com.dwinovo.numen.client.skin.SkinLibrary.instance();
                        var face = com.dwinovo.numen.client.skin.SkinTextures.faceOf(e.id(), lib.pngPath(e.id()));
                        if (face != null) {
                            PlayerFaceRenderer.draw(mc.graphics(), face, ix, iy, size);
                        }
                    });
        }
        return skinList;
    }

    private void beginEditSkin(com.dwinovo.numen.client.skin.SkinLibrary.Entry e) {
        addingSkin = true;
        var d = new SkinFormPanel.Draft();
        d.editId = e.id();
        d.name = e.name();
        d.variant = e.variant();
        // 不换图时沿用落盘原图(改手臂模型重签也从盘上读),dropped 留空。
        skinDraft = d;
        needBuild = true;
    }

    /** 原生文件对话框(LWJGL tinyfd,MC 自带;FCL 端会翻译成安卓文件选择器):
     *  独立线程弹窗防冻主循环,选中后回主线程导入。 */
    private void openNativeSkinPicker() {
        new Thread(() -> {
            String chosen = null;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png")).flip();
                chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                        "Numen skin (64x64 png)", null, filters, "PNG", false);
            } catch (Throwable t) {
                Constants.LOG.warn("[numen-skin] native file dialog unavailable", t);
            }
            String path = chosen;
            Minecraft.getInstance().execute(() -> {
                if (path != null && section == Sec.SKIN && addingSkin) {
                    skinForm().importFile(Path.of(path));
                }
            });
        }, "numen-skin-picker").start();
    }

    // ---- MCP(工具扩展):服务器列表 + McpFormPanel ----
    private boolean addingMcp;
    private McpFormPanel mcpForm;
    private McpFormPanel.Draft mcpDraft = new McpFormPanel.Draft();
    private LibraryListPanel<com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle> mcpList;

    private McpFormPanel mcpForm() {
        if (mcpForm == null) {
            mcpForm = new McpFormPanel(this::onMcpSave,
                    () -> {
                        addingMcp = false;
                        needBuild = true;
                    });
        }
        return mcpForm;
    }

    private LibraryListPanel<com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle> mcpList() {
        if (mcpList == null) {
            mcpList = new LibraryListPanel<>(
                    "numen.mcp.add", "numen.mcp.empty",
                    com.dwinovo.numen.mcp.client.McpClientManager::servers,
                    h -> new LibraryListPanel.Row(h.name(), mcpMeta(h),
                            h.status() == com.dwinovo.numen.mcp.client.McpClientManager.Status.FAILED, null),
                    h -> Component.translatable("numen.mcp.delete_confirm", h.name()).getString(),
                    h -> com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(h.name()),
                    () -> {
                        addingMcp = true;
                        mcpDraft = new McpFormPanel.Draft();
                        needBuild = true;
                    },
                    h -> beginEditMcp(h.name()))
                    .withRowIcon(8, (s, h, ix, iy, size) ->
                            s.fillRect(ix + 1, iy + 1, 6, 6, mcpDotColor(h.status())))
                    .withRowToggle(
                            com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle::toggledOn,
                            h -> {
                                var st = h.status();
                                // 连接中/已连接 → 关;禁用/失败 → (重)连(失败的点一下即重试)
                                if (st == com.dwinovo.numen.mcp.client.McpClientManager.Status.CONNECTED
                                        || st == com.dwinovo.numen.mcp.client.McpClientManager.Status.CONNECTING) {
                                    com.dwinovo.numen.mcp.client.McpClientManager.disableServer(h.name());
                                } else {
                                    com.dwinovo.numen.mcp.client.McpClientManager.enableServer(h.name());
                                }
                            });
        }
        return mcpList;
    }

    private void onMcpSave(McpFormPanel.Draft d) {
        String name = d.name.trim();
        String target = d.target.trim();
        // 编辑时保留服务器原有的启停(普通编辑不该翻开关)。
        boolean enabled = true;
        if (d.editOriginal != null) {
            var orig = com.dwinovo.numen.mcp.client.McpClientManager.spec(d.editOriginal);
            if (orig != null) enabled = orig.enabled();
        }
        com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec spec;
        if (d.stdio) {
            String[] parts = target.split("\\s+");
            String command = parts[0];
            List<String> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) args.add(parts[i]);
            spec = new com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec(name, "stdio", "", Map.of(),
                    command, List.copyOf(args), parseEnv(d.extra), enabled, 20, 120);
        } else {
            spec = new com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec(name, "http", target, parseHeader(d.extra),
                    "", List.of(), Map.of(), enabled, 20, 120);
        }
        com.dwinovo.numen.mcp.client.McpClientManager.upsertServer(spec);
        // 编辑中改名 → upsert 写的是新名条目;旧的删掉。
        if (d.editOriginal != null && !d.editOriginal.equals(name)) {
            com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(d.editOriginal);
        }
        addingMcp = false;
        mcpDraft = new McpFormPanel.Draft();
        needBuild = true;
    }

    private void beginEditMcp(String name) {
        var spec = com.dwinovo.numen.mcp.client.McpClientManager.spec(name);
        if (spec == null) return;
        addingMcp = true;
        var d = new McpFormPanel.Draft();
        d.editOriginal = name;
        d.stdio = spec.isStdio();
        d.name = spec.name();
        if (d.stdio) {
            StringBuilder cmd = new StringBuilder(spec.command() == null ? "" : spec.command());
            for (String a : spec.args()) cmd.append(' ').append(a);
            d.target = cmd.toString().trim();
            d.extra = joinPairs(spec.env(), '=');       // stdio → env "KEY=value"
        } else {
            d.target = nv(spec.url());
            d.extra = joinPairs(spec.headers(), ':');    // http → header "Name: Value"
        }
        mcpDraft = d;
        needBuild = true;
    }

    private static Map<String, String> parseHeader(String line) {
        return parsePairs(line, ':');
    }

    private static Map<String, String> parseEnv(String line) {
        return parsePairs(line, '=');
    }

    private static Map<String, String> parsePairs(String line, char sep) {
        String s = line == null ? "" : line.trim();
        if (s.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : s.split("[;\\n]")) {
            String p = part.trim();
            int i = p.indexOf(sep);
            if (i <= 0) continue;
            String k = p.substring(0, i).trim();
            String v = p.substring(i + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return Map.copyOf(out);
    }

    private static String joinPairs(Map<String, String> m, char sep) {
        if (m == null || m.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (var e : m.entrySet()) {
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append(sep == ':' ? ": " : "=").append(e.getValue());
        }
        return b.toString();
    }

    private int mcpDotColor(com.dwinovo.numen.mcp.client.McpClientManager.Status s) {
        UiTheme t = UiTheme.current();
        return switch (s) {
            case CONNECTED -> t.ok();
            case CONNECTING -> t.run();
            case FAILED -> t.fail();
            case DISABLED -> t.faint();
        };
    }

    private String mcpMeta(com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle h) {
        return switch (h.status()) {
            case CONNECTED -> I18n.get("numen.mcp.connected", h.type(), h.toolCount());
            case CONNECTING -> I18n.get("numen.mcp.connecting", h.type());
            case FAILED -> I18n.get("numen.mcp.failed", h.type());
            case DISABLED -> I18n.get("numen.mcp.disabled", h.type());
        };
    }

    private List<Component> mcpTooltip(com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle h) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(h.name()));
        var spec = com.dwinovo.numen.mcp.client.McpClientManager.spec(h.name());
        if (spec != null) {
            lines.add(Nb.colored(spec.isStdio() ? spec.command() : spec.url(), UiTheme.current().faint()));
        }
        if (h.status() == com.dwinovo.numen.mcp.client.McpClientManager.Status.FAILED && !h.error().isBlank()) {
            lines.add(Nb.colored(h.error(), UiTheme.current().fail()));
        } else if (!h.toolNames().isEmpty()) {
            lines.add(Nb.colored(String.join(", ", h.toolNames()), UiTheme.current().textDim()));
        }
        return lines;
    }

    // ---- 整页面板:STT / 外接大脑 / 主题 ----
    private SttPanel sttPanel;
    private BrainPanel brainPanel;
    private ThemePanel themePanel;

    private SttPanel sttPanel() {
        if (sttPanel == null) sttPanel = new SttPanel();
        return sttPanel;
    }

    private BrainPanel brainPanel() {
        if (brainPanel == null) brainPanel = new BrainPanel();
        return brainPanel;
    }

    private ThemePanel themePanel() {
        if (themePanel == null) themePanel = new ThemePanel(() -> { });
        return themePanel;
    }

    // ---- 表单模态与分区状态 ----

    private boolean formActive() {
        return addingProvider || addingVoice || addingSkin || addingPersona || addingMcp;
    }

    /** 换分区:表单与在途回调清掉(照 SettingsView.resetSectionState)。 */
    private void resetSectionState() {
        addingMcp = false;
        addingPersona = false;
        personaEditId = null;
        addingProvider = false;
        providerEditId = null;
        addingVoice = false;
        voiceEditId = null;
        if (voiceForm != null) voiceForm.cancelPendingTest();   // 在途试听回调作废
        addingSkin = false;
        if (skinForm != null) skinForm.cancelPending();          // 在途 MineSkin 签名回调作废
    }

    /** 挑一个分区(二级列表点行);副作用照 SettingsView.selectSection。 */
    private void selectSection(Sec s) {
        if (s == section) return;
        section = s;
        resetSectionState();
        if (sttPanel != null) sttPanel.reseed();   // 进分区从已存配置重播种
        if (s == Sec.PERSONA) {
            // 人设是目录里的 .md 文件:进页先重扫,外部编辑器的修改即时可见。
            PersonaLibrary.instance().reload();
        }
        needBuild = true;
    }

    // ======================================================================
    // 建控件(照 SettingsView.buildWidgets 的分发,几何换成右侧矩形)
    // ======================================================================

    private void buildSection() {
        builtFor = section;
        builtX = px;
        builtY = py;
        builtW = pw;
        builtH = ph;
        needBuild = false;
        if (section == Sec.EXTERNAL) {
            buildExternal();
            return;
        }
        int x = rx(), y = py, w = rw(), h = ph;
        int dimX = 0, dimY = 0, dimW = screenW(), dimH = screenH();
        switch (section) {
            case PROVIDER -> {
                profileList().build(x, y, w, h, dimX, dimY, dimW, dimH);
                if (addingProvider) buildProviderForm();
            }
            case PERSONA -> {
                personaList().build(x, y, w, h, dimX, dimY, dimW, dimH);
                if (addingPersona) buildPersonaForm();
            }
            case VOICE -> {
                voiceList().build(x, y, w, h, dimX, dimY, dimW, dimH);
                if (addingVoice) buildVoiceForm();
            }
            case SKIN -> {
                skinList().build(x, y, w, h, dimX, dimY, dimW, dimH);
                if (addingSkin) buildSkinForm();
            }
            case MCP -> {
                mcpList().build(x, y, w, h, dimX, dimY, dimW, dimH);
                if (addingMcp) buildMcpForm();
            }
            case STT -> sttPanel().build(x, y, w, h);
            case BRAIN -> {
                // 换令牌的确认卡盖住整屏,不只这一格
                brainPanel().setDimBounds(dimX, dimY, dimW, dimH);
                brainPanel().build(x, y, w, h);
            }
            case THEME -> themePanel().build(x, y, w, h);
            default -> { }
        }
    }

    /** 表单卡里的 NumenUI 表单;卡壳由 renderFormCard 画。 */
    private void buildProviderForm() {
        providerForm().open(providerDraft);
        providerForm().build(formX(), formY(), formW(), formBottom() - formY(), screenH() - 2);
    }

    private void buildVoiceForm() {
        voiceForm().open(voiceDraft);
        voiceForm().build(formX(), formY(), formW(), formBottom() - formY(), screenH() - 2);
    }

    private void buildPersonaForm() {
        personaForm().open(personaDraft);
        personaForm().build(formX(), formY(), formW(), formBottom() - formY());
    }

    private void buildSkinForm() {
        skinForm().open(skinDraft);
        skinForm().build(formX(), formY(), formW(), formBottom() - formY());
    }

    private void buildMcpForm() {
        mcpForm().open(mcpDraft);
        mcpForm().build(formX(), formY(), formW(), formBottom() - formY());
    }

    /** 表单卡壳的几何:右侧矩形内缩 10px 的近全幅卡。 */
    private int cardX() { return rx() + CARD_INSET; }
    private int cardY() { return py + CARD_INSET; }
    private int cardW() { return rw() - CARD_INSET * 2; }
    private int cardH() { return ph - CARD_INSET * 2; }
    private int formX() { return cardX() + 10; }
    private int formY() { return cardY() + CARD_INSET + CARD_TITLE_H; }
    private int formW() { return cardW() - 20; }
    private int formBottom() { return cardY() + cardH() - 6; }

    // ======================================================================
    // 渲染
    // ======================================================================

    @Override
    public void render(NumenPanelScreen.Rect rect) {
        GuiGraphics g = NumenPanelScreen.frame();
        if (g == null) return;
        px = rect.x;
        py = rect.y;
        pw = rect.w;
        ph = rect.h;
        UiTheme t = UiTheme.current();
        boolean geoChanged = builtFor != section || builtX != px || builtY != py
                || builtW != pw || builtH != ph;
        if (geoChanged || needBuild) buildSection();
        // 外接脑的数据每秒重采一次(brain.json / 租约 / 快照年龄都在动)
        if (section == Sec.EXTERNAL && System.currentTimeMillis() - extBuiltAt > 1000) {
            buildSection();
        }

        int mx = NumenPanelScreen.mouseX();
        int my = NumenPanelScreen.mouseY();
        long now = net.minecraft.Util.getMillis();

        drawSectionList(g, t, mx, my);
        // 列表与内容之间的发丝线
        g.fill(px + LIST_W, py, px + LIST_W + 1, py + ph, t.surfaceBorder());

        McDrawSurface s = new McDrawSurface(g, NumenPanelScreen.font());
        List<Component> tooltip = null;
        switch (section) {
            case PROVIDER -> {
                profileList().render(s, HostThemeColors.current(), mx, my, now);
                if (addingProvider) {
                    renderFormCard(g, s, t, Component.translatable(ModLanguageData.Keys.PROVIDER_TITLE));
                    providerForm().render(s, HostThemeColors.current(), mx, my, now);
                }
            }
            case PERSONA -> {
                personaList().render(s, HostThemeColors.current(), mx, my, now);
                if (addingPersona) {
                    renderFormCard(g, s, t, Component.translatable("numen.persona.title"));
                    personaForm().render(s, HostThemeColors.current(), mx, my, now);
                }
            }
            case VOICE -> {
                voiceList().render(s, HostThemeColors.current(), mx, my, now);
                if (addingVoice) {
                    renderFormCard(g, s, t, Component.translatable(ModLanguageData.Keys.VOICE_TITLE));
                    voiceForm().render(s, HostThemeColors.current(), mx, my, now);
                }
            }
            case SKIN -> {
                skinList().render(s, HostThemeColors.current(), mx, my, now);
                if (addingSkin) {
                    renderFormCard(g, s, t, Component.translatable(ModLanguageData.Keys.SKIN_TITLE));
                    skinForm().render(s, HostThemeColors.current(), mx, my, now);
                }
            }
            case MCP -> {
                mcpList().render(s, HostThemeColors.current(), mx, my, now);
                if (addingMcp) {
                    renderFormCard(g, s, t, Component.translatable("numen.mcp.title"));
                    mcpForm().render(s, HostThemeColors.current(), mx, my, now);
                } else {
                    // 悬停行体 → tooltip:工具名 + url/命令 + 错误(骨架没有 tip 通道,本页自画)
                    var hovered = mcpList().entryAtBody(mx, my);
                    if (hovered != null) tooltip = mcpTooltip(hovered);
                }
            }
            case STT -> sttPanel().render(s, HostThemeColors.current(), mx, my, now);
            case BRAIN -> {
                brainPanel().render(s, HostThemeColors.current(), mx, my, now);
                String tip = brainPanel().tooltipAt(mx, my);
                if (tip != null) tooltip = List.of(Component.literal(tip));
            }
            case THEME -> themePanel().render(s, HostThemeColors.current(), mx, my, now);
            case EXTERNAL -> renderExternal(s, t, mx, my, now);
        }
        if (tooltip != null) drawTooltip(g, t, tooltip, mx, my);
    }

    /** 二级列表:一行一个分区,悬停亮底,选中加左侧 2px 强调条。 */
    private void drawSectionList(GuiGraphics g, UiTheme t, int mx, int my) {
        Font font = NumenPanelScreen.font();
        int x = px;
        int y = py;
        for (Sec s : NAV) {
            boolean sel = s == section;
            boolean hot = !sel && mx >= x && mx < x + LIST_W && my >= y && my < y + ITEM_H;
            if (sel) {
                g.fill(x, y, x + LIST_W, y + ITEM_H, t.over());
                g.fill(x, y, x + 2, y + ITEM_H, t.cta());
            } else if (hot) {
                g.fill(x, y, x + LIST_W, y + ITEM_H, t.over());
            }
            String name = Nb.clip(font, label(s), LIST_W - 10);
            Nb.text(g, font, name, x + 5, y + (ITEM_H - font.lineHeight) / 2 + 1,
                    sel ? t.text() : t.textDim());
            y += ITEM_H;
        }
    }

    /** 指针落在二级列表哪一行上;不在任何行上是 null(与 drawSectionList 同一套几何)。 */
    private Sec listAt(double mx, double my) {
        if (mx < px || mx >= px + LIST_W) return null;
        int y = py;
        for (Sec s : NAV) {
            if (my >= y && my < y + ITEM_H) return s;
            y += ITEM_H;
        }
        return null;
    }

    /** 表单模态卡壳:暗幕 + 近全幅卡 + 卡顶标题;卡里的表单由调用方随后自己 render。 */
    private void renderFormCard(GuiGraphics g, McDrawSurface s, UiTheme t, Component title) {
        g.fill(cardX(), cardY(), cardX() + cardW(), cardY() + cardH(),
                (t.border() & 0xFFFFFF) | 0x99000000);
        NumenStyle.box(s, cardX(), cardY(), cardW(), cardH(), t.band(), t.aiBorder());
        Nb.text(g, NumenPanelScreen.font(), title.copy().withStyle(ChatFormatting.BOLD),
                formX(), cardY() + 8, t.text());
    }

    /** 一只小 tooltip:暗底描边 + 逐行文字(骨架的 Page 没有 tip 通道,这里自画)。 */
    private void drawTooltip(GuiGraphics g, UiTheme t, List<Component> lines, int mx, int my) {
        Font font = NumenPanelScreen.font();
        int w = 0;
        for (Component c : lines) w = Math.max(w, font.width(c));
        w += 8;
        int h = lines.size() * (font.lineHeight + 1) + 6;
        int x = Math.min(mx + 12, screenW() - w - 2);
        int y = Math.min(my + 12, screenH() - h - 2);
        g.fill(x, y, x + w, y + h, 0xF0101010);
        Nb.border(g, x, y, w, h, 1, t.border());
        int ty = y + 3;
        for (Component c : lines) {
            Nb.text(g, font, c, x + 4, ty, 0xFFFFFFFF);
            ty += font.lineHeight + 1;
        }
    }

    /** 面板语境下的当前同伴:准星指着的优先,否则名册第一只(照骨架「叫停」的口径)。 */
    private static UUID companionUuid() {
        var body = com.dwinovo.numen.client.chat.CompanionChatScreen.crosshairCompanion();
        var entries = com.dwinovo.numen.client.agent.NumenRoster.instance().entries();
        if (body != null) {
            for (com.dwinovo.numen.client.agent.NumenRoster.Entry e : entries) {
                if (e.uuid().equals(body.getUUID())) return e.uuid();
            }
        }
        return entries.isEmpty() ? null : entries.get(0).uuid();
    }

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }

    // ======================================================================
    // 输入(照 SettingsView 的分发:表单优先,其次列表/整页面板)
    // ======================================================================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            Sec hit = listAt(mx, my);
            if (hit != null) {
                selectSection(hit);
                return true;
            }
        }
        if (section == Sec.EXTERNAL) return externalClick(mx, my, button);
        if (mx < rx()) return false;
        if (button != 0) {
            // 右键只有条目库的行认(弹出编辑/克隆/删除菜单);表单开着时不接
            LibraryListPanel<?> lib = formActive() ? null : libraryOf(section);
            return lib != null && lib.mouseClicked(mx, my, button);
        }
        // 表单卡开着:事件整体交给表单面板(浮层优先吃掉一切)
        if (formActive()) {
            return formMouseClicked(mx, my, button);
        }
        LibraryListPanel<?> lib = libraryOf(section);
        if (lib != null) {
            // 删除确认卡开着时面板吃掉一切(模态);平时接行/开关/新建,没命中就往下放行
            if (lib.mouseClicked(mx, my, 0)) return true;
        }
        return switch (section) {
            case STT -> sttPanel().mouseClicked(mx, my, 0);
            case BRAIN -> brainPanel().mouseClicked(mx, my, 0);
            case THEME -> themePanel().mouseClicked(mx, my, 0);
            default -> false;
        };
    }

    private boolean formMouseClicked(double mx, double my, int button) {
        return switch (section) {
            case PROVIDER -> providerForm().mouseClicked(mx, my, 0);
            case PERSONA -> personaForm().mouseClicked(mx, my, 0);
            case VOICE -> voiceForm().mouseClicked(mx, my, 0);
            case SKIN -> skinForm().mouseClicked(mx, my, 0);
            case MCP -> mcpForm().mouseClicked(mx, my, 0);
            default -> false;
        };
    }

    private LibraryListPanel<?> libraryOf(Sec s) {
        return switch (s) {
            case PROVIDER -> profileList();
            case VOICE -> voiceList();
            case PERSONA -> personaList();
            case SKIN -> skinList();
            case MCP -> mcpList();
            default -> null;
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (section == Sec.EXTERNAL) return externalScroll(mx, my, delta);
        if (formActive()) return formMouseScrolled(mx, my, delta);
        LibraryListPanel<?> lib = libraryOf(section);
        if (lib != null && lib.mouseScrolled(mx, my, delta)) return true;
        return switch (section) {
            case STT -> sttPanel().mouseScrolled(mx, my, delta);
            case BRAIN -> brainPanel().mouseScrolled(mx, my, delta);
            case THEME -> themePanel().mouseScrolled(mx, my, delta);
            default -> false;
        };
    }

    private boolean formMouseScrolled(double mx, double my, double delta) {
        return switch (section) {
            case PROVIDER -> providerForm().mouseScrolled(mx, my, delta);
            case PERSONA -> personaForm().mouseScrolled(mx, my, delta);
            case VOICE -> voiceForm().mouseScrolled(mx, my, delta);
            case SKIN -> skinForm().mouseScrolled(mx, my, delta);
            default -> false;
        };
    }

    @Override
    public boolean keyPressed(int keyCode) {
        // 面板骨架的 Page 没带修饰键;文本框的真实字符走 charTyped,这里只管焦点导航与面板自己的键。
        int modifiers = 0;
        if (section == Sec.EXTERNAL) return exUi.keyPressed(keyCode, modifiers);
        if (formActive()) return formKeyPressed(keyCode, modifiers);
        LibraryListPanel<?> lib = libraryOf(section);
        if (lib != null && lib.keyPressed(keyCode, modifiers)) return true;   // ESC 关删除确认(= 取消)
        return switch (section) {
            case STT -> sttPanel().keyPressed(keyCode, modifiers);
            case BRAIN -> brainPanel().keyPressed(keyCode, modifiers);
            default -> false;
        };
    }

    private boolean formKeyPressed(int keyCode, int modifiers) {
        return switch (section) {
            case PROVIDER -> providerForm().keyPressed(keyCode, modifiers);
            case PERSONA -> personaForm().keyPressed(keyCode, modifiers);
            case VOICE -> voiceForm().keyPressed(keyCode, modifiers);
            case SKIN -> skinForm().keyPressed(keyCode, modifiers);
            case MCP -> mcpForm().keyPressed(keyCode, modifiers);
            default -> false;
        };
    }

    @Override
    public boolean charTyped(char ch) {
        if (section == Sec.EXTERNAL) return exUi.charTyped(ch);
        if (formActive()) return formCharTyped(ch);
        return switch (section) {
            case STT -> sttPanel().charTyped(ch);
            case BRAIN -> brainPanel().charTyped(ch);
            default -> false;
        };
    }

    private boolean formCharTyped(char ch) {
        return switch (section) {
            case PROVIDER -> providerForm().charTyped(ch);
            case PERSONA -> personaForm().charTyped(ch);
            case VOICE -> voiceForm().charTyped(ch);
            case SKIN -> skinForm().charTyped(ch);
            case MCP -> mcpForm().charTyped(ch);
            default -> false;
        };
    }

    // ======================================================================
    // 外接脑区块(bridge)
    // ======================================================================

    private final UiRoot exUi = new UiRoot();
    private final ScrollBox exScroll = new ScrollBox();
    private InlineAlert exAlert;
    private Toggle exLiveToggle;
    /** 8766 开关在界面上的状态:成功写进配置才翻;写失败下次重建弹回。 */
    private boolean liveEnabledUi;
    private boolean liveEnabledSeeded;
    /** 写 8766 配置失败的原因,显示在开关行下面(不静默)。 */
    private String liveWriteError;
    /** 脚本 API 命名空间的折叠状态(重建不丢)。 */
    private final Map<String, Boolean> nsOpen = new HashMap<>();
    private long extBuiltAt;
    /** 动态行的纵向锚点(内容坐标):快照年龄 / 租约剩余每帧现画。 */
    private int extAgeRow = -1, extLeaseRow = -1;
    /** 接入状态行的纵向锚点。 */
    private int extConnRow = -1;
    private String extBrainBadge = "";
    /** 接入状态(徽标文案已收进 i18n,颜色判定不能再靠 startsWith 字面量)。 */
    private boolean extBrainBridged;

    /** 静态值行(建控件时算好,渲染只读)。 */
    private record ValueRow(String label, String value, String note, int y) {}
    /** 文件区一行:标签 + 路径全文 + 复制/打开文件夹;不存在整行置灰写「未找到」。 */
    private static final class FileRow {
        final String label;
        final String pathText;
        final boolean exists;
        final int y;

        FileRow(String label, String pathText, boolean exists, int y) {
            this.label = label;
            this.pathText = pathText;
            this.exists = exists;
            this.y = y;
        }
    }
    /** 工具清单的一行:名字 + 一句说明 + 右侧来源标。 */
    private record ToolRow(String name, String zh, String source, int y) {}

    private final List<ValueRow> extValueRows = new ArrayList<>();
    private final List<FileRow> extFileRows = new ArrayList<>();
    private final List<ToolRow> extToolRows = new ArrayList<>();
    /** 手画的一行字(8766 的标签、生效说明、脚本 API 的组行),x/y 建控件时定死。 */
    private record TextLine(String text, int x, int y, boolean dim) {}
    private final List<TextLine> extTextLines = new ArrayList<>();

    /** mod 自己的配置文件(8766 开关写在它的 live 段)。路径由游戏目录拼出,不写死。 */
    private static Path mcpServerFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(Constants.CONFIG_ROOT)
                .resolve("mcp_server.json");
    }

    /** brain.json 在磁盘上的位置(由 mod 配置的 brain.bridgeFile 给);null = 没接外接脑。 */
    private static Path brainFile() {
        return PanelSnapshot.brainFile();
    }

    /** 读 brain.json;缺文件/损坏当「没数据」,不抛。 */
    private static JsonObject readBrain() {
        Path f = brainFile();
        if (f == null || !Files.isRegularFile(f)) return null;
        try {
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String brainStr(JsonObject o, String key) {
        if (o == null) return null;
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static Double brainNum(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private void buildExternal() {
        extBuiltAt = System.currentTimeMillis();
        exUi.clear();
        exAlert = null;
        exLiveToggle = null;
        if (!liveEnabledSeeded) {
            liveEnabledSeeded = true;
            McpConfig cfg = McpMode.instance().config();
            liveEnabledUi = cfg != null && cfg.liveEnabled();
        }
        extValueRows.clear();
        extFileRows.clear();
        extToolRows.clear();
        extTextLines.clear();
        extAgeRow = extLeaseRow = extConnRow = -1;

        int x = rx() + NumenStyle.PAD;
        int w = rw() - NumenStyle.PAD * 2;
        int right = x + w;
        int contentTop = py;

        JsonObject brain = readBrain();
        boolean bridged = brain != null;
        extBrainBridged = bridged;
        extBrainBadge = I18n.get(bridged ? ModLanguageData.Keys.PANEL_SETTINGS_BRIDGED
                : ModLanguageData.Keys.PANEL_SETTINGS_UNBRIDGED);

        int y = py;

        // ---- 接入状态 ----
        extConnRow = y;
        y += 16;

        // ---- 状态组:preset / 模型·供应商 / 会话 id / 心跳 tick / 快照年龄 / 租约剩余 / 温度 / 状态表频率 ----
        y = extHeader(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_STATUS));
        y = extValue(y, "preset", brainStr(brain, "presetInUse"), null);
        y = extValue(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_MODEL_PROVIDER), modelProvider(brain), null);
        y = extValue(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_SESSION), sessionHead(brain), null);
        y = extValue(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_TICK), tickLabel(brain), null);
        extAgeRow = y;   // 快照年龄:每帧照 PanelSnapshot 现画
        y += EXT_ROW_H;
        extLeaseRow = y; // 租约剩余:每帧照 McpMode 现画
        y += EXT_ROW_H;
        // 温度 / 状态表频率:brain.json 只读(值是 DSH 自报的);要改去 host 配置,不画可点控件。
        String hostNote = I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_HOST_NOTE);
        y = extValue(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_TEMPERATURE),
                numLabel(brainNum(brain, "temperature")), hostNote);
        y = extValue(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_INTERVAL),
                freqLabel(brainNum(brain, "intervalMs")), hostNote);
        y += 4;

        // ---- 8766 组:唯一可写的开关 ----
        y = extHeader(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_LIVE_GATE));
        String liveLabel = "live.enabled (" + McpConfig.DEFAULT_LIVE_PORT + ")";
        extTextLines.add(new TextLine(liveLabel, x, y + 4, false));
        exLiveToggle = exUi.add(new Toggle(liveEnabledUi, this::onLiveToggle));
        exLiveToggle.setBounds(right - 24, y + 3, 22, 11);
        y += EXT_ROW_H;
        extTextLines.add(new TextLine(I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_RESTART_NOTE), x + 10, y, true));
        y += 12;
        exAlert = exUi.add(new InlineAlert());
        exAlert.setBounds(x, y, Math.min(w, 260), 14);
        if (liveWriteError != null) {
            exAlert.show(InlineAlert.Severity.ERROR,
                    I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_WRITE_FAIL, liveWriteError));
        } else {
            exAlert.clear();
        }
        y += 20;

        // ---- 文件组:路径全文 + 复制 + 打开文件夹 ----
        y = extHeader(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_FILES));
        // 行 1:mod 配置(路径由游戏目录拼出;8766 开关就在它的 live 段里)。
        y = addFileRow(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_MOD_CFG), mcpServerFile(), right);
        // 行 2-4:DSH 侧路径全部由 brain.json 自报,mod 不猜;字段缺失整行隐藏。
        if (bridged) {
            String persona = brainStr(brain, "personaFile");
            if (persona != null && !persona.isBlank()) {
                y = addFileRow(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_PERSONA_FILE), Path.of(persona), right);
            }
            String profileDir = brainStr(brain, "profileDir");
            if (profileDir != null && !profileDir.isBlank()) {
                y = addFileRow(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_TICK_CFG),
                        Path.of(profileDir).resolve("cordis.patch.yml"), right);
            }
            String stateFile = brainStr(brain, "stateFile");
            if (stateFile != null && !stateFile.isBlank()) {
                y = addFileRow(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_STATE_FILE), Path.of(stateFile), right);
            }
        }
        y += 6;

        // ---- 工具组 ----
        y = extHeader(y, I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_TOOLS));
        for (NumenTool tool : ToolRegistry.all()) {
            extToolRows.add(new ToolRow(toolLabel(tool.name()), nv(tool.description()),
                    I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_SRC_BODY), y));
            y += 13;
        }
        if (extToolRows.isEmpty()) {
            extTextLines.add(new TextLine(
                    I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_BODY_TOOLS_NONE), x, y, true));
            y += 12;
        }
        y += 4;

        // 脚本 API:按命名空间分组折叠(ApiRegistry 的组全带 namespace)。
        Map<String, List<String>> byNs = new LinkedHashMap<>();
        for (ApiRegistry.Group group : ApiRegistry.groups()) {
            byNs.computeIfAbsent(group.namespace(), k -> new ArrayList<>())
                    .add(group.fullName() + " — " + nv(group.summary()) + " "
                            + I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_API_FUNCS, group.functions().size()));
        }
        if (!byNs.isEmpty()) {
            for (var e : byNs.entrySet()) {
                String ns = e.getKey();
                Disclosure d = exUi.add(new Disclosure(
                        ns + " · " + I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_API_GROUPS, e.getValue().size()),
                        nsOpen.getOrDefault(ns, false),
                        () -> nsOpen.put(ns, !nsOpen.getOrDefault(ns, false))));
                d.setBounds(x, y, w, 14);
                y += 16;
                if (nsOpen.getOrDefault(ns, false)) {
                    for (String line : e.getValue()) {
                        extTextLines.add(new TextLine(line, x + 10, y, true));
                        y += 11;
                    }
                }
            }
        } else {
            extTextLines.add(new TextLine(I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_API_NONE), x, y, true));
            y += 12;
        }
        y += 4;

        // 大脑工具:brain.json 的 tools[](那边给什么显示什么,它自己带中文)。
        int brainToolCount = 0;
        if (bridged) {
            JsonArray tools = brain.has("tools") && brain.get("tools").isJsonArray()
                    ? brain.getAsJsonArray("tools") : null;
            if (tools != null) {
                for (var el : tools) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String name = brainStr(o, "name");
                    if (name == null) continue;
                    String zh = brainStr(o, "zh");
                    extToolRows.add(new ToolRow(name, zh == null ? "" : zh,
                            I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_SRC_BRAIN), y));
                    brainToolCount++;
                    y += 13;
                }
            }
        }
        if (brainToolCount == 0) {
            extTextLines.add(new TextLine(I18n.get(bridged ? ModLanguageData.Keys.PANEL_SETTINGS_BRAIN_TOOLS_NONE
                    : ModLanguageData.Keys.PANEL_SETTINGS_BRAIN_TOOLS_OFF), x, y, true));
            y += 12;
        }
        y += NumenStyle.PAD;

        int contentH = y - contentTop;
        exScroll.measure(exUi, rx(), py, rw() - NumenStyle.SCROLLBAR_W, ph, contentH);
        exUi.setViewportHeight(screenH());
    }

    private static String modelProvider(JsonObject brain) {
        String m = brainStr(brain, "model");
        String p = brainStr(brain, "provider");
        if (m == null && p == null) return null;
        return (p == null ? "" : p + " · ") + (m == null ? "—" : m);
    }

    private static String sessionHead(JsonObject brain) {
        String sid = brainStr(brain, "mainSession");
        return sid == null || sid.length() < 8 ? null : sid.substring(0, 8);
    }

    private static String tickLabel(JsonObject brain) {
        Double ms = brainNum(brain, "tickMs");
        return ms == null ? null : Math.round(ms) + " ms";
    }

    private static String numLabel(Double d) {
        return d == null ? null : String.valueOf(d);
    }

    private static String freqLabel(Double ms) {
        return ms == null ? null : Math.round(ms) + " ms";
    }

    /** 状态区一行:标签左、右对齐的值;值缺了不画值,标签行保留(renderExternal 对 null 直接跳过)。返回下一行的顶边。 */
    private int extValue(int y, String label, String value, String note) {
        extValueRows.add(new ValueRow(label, value == null || value.isBlank() ? null : value, note, y));
        return y + EXT_ROW_H;
    }

    private int extHeader(int y, String title) {
        extValueRows.add(new ValueRow(title, null, null, y));
        return y + 16;
    }

    /** 文件区一行:标签 + 路径全文(渲染时画)+ 复制/文件夹两枚钮(存在才可用)。 */
    private int addFileRow(int y, String labelText, Path file, int right) {
        boolean exists = file != null && Files.isRegularFile(file);
        String pathText = file == null ? "" : file.toString();
        Button copy = exUi.add(new Button(I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_COPY),
                Button.Style.GHOST, () ->
                Minecraft.getInstance().keyboardHandler.setClipboard(pathText)));
        copy.setBounds(right - 30, y, 30, 14);
        copy.setEnabled(exists);
        Button open = exUi.add(new Button(I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_FOLDER),
                Button.Style.GHOST, () -> openFolder(file)));
        open.setBounds(right - 62, y, 30, 14);
        open.setEnabled(exists);
        extFileRows.add(new FileRow(labelText, pathText, exists, y));
        return y + EXT_FILE_H;
    }

    /** 打开所在文件夹(资源管理器/访达自己会选中);openFile 失败退回 openUri 父目录。 */
    private static void openFolder(Path file) {
        if (file == null) return;
        try {
            net.minecraft.Util.getPlatform().openFile(file.toFile());
        } catch (Exception primary) {
            try {
                net.minecraft.Util.getPlatform().openUri(file.getParent().toUri());
            } catch (Exception fallback) {
                Constants.LOG.warn("[numen-panel] open folder failed: {} / {}",
                        primary.toString(), fallback.toString());
            }
        }
    }

    /** 8766 开关回调:以 JSON 树为单位改 mcp_server.json 的 live.enabled,其余字段原样保留。 */
    private void onLiveToggle(boolean on) {
        Path f = mcpServerFile();
        try {
            if (!Files.isRegularFile(f)) {
                liveWriteError = I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_CFG_MISSING, f.getFileName());
                needBuild = true;
                return;
            }
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject live = o.has("live") && o.get("live").isJsonObject()
                    ? o.getAsJsonObject("live") : new JsonObject();
            live.addProperty("enabled", on);   // 只动这一个键,token/bridgeFile 等原样保留
            o.add("live", live);
            Files.createDirectories(f.getParent());
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
            liveEnabledUi = on;
            liveWriteError = null;
        } catch (Exception e) {
            liveWriteError = e.toString();
        }
        needBuild = true;
    }

    /** 工具名 → 人话:约定键 {@code numen.tool.<name>};缺键回落英文原名(照 ChatView 的口径)。 */
    private static String toolLabel(String name) {
        String key = "numen.tool." + name;
        return I18n.exists(key) ? I18n.get(key) : name;
    }

    private void renderExternal(McDrawSurface s, UiTheme t, int mx, int my, long now) {
        int dy = -exScroll.offset();
        int x = rx() + NumenStyle.PAD;
        int right = x + rw() - NumenStyle.PAD * 2;
        long nowMs = System.currentTimeMillis();

        exScroll.beginClip(s);
        // 接入状态
        if (extConnRow >= 0) {
            s.drawText(extBrainBadge, x, extConnRow + dy,
                    extBrainBridged ? t.ok() : t.fail(), false);
        }
        // 状态行:标签左、值右;组抬头(没有值)用正文色,动态两行(快照年龄/租约剩余)按锚点每帧现算
        for (ValueRow row : extValueRows) {
            boolean header = row.value() == null && row.note() == null;
            s.drawText(TextClip.fit(s, row.label(), EXT_LABEL_W - 6),
                    x, row.y() + dy, header ? t.text() : t.textDim(), false);
            if (row.value() == null) continue;
            int avail = right - x - EXT_LABEL_W;
            if (row.note() != null) avail -= s.textWidth(row.note()) + 12;
            s.drawText(TextClip.fit(s, row.value(), Math.max(20, avail)),
                    x + EXT_LABEL_W, row.y() + dy, t.text(), false);
            if (row.note() != null) {
                s.drawText(row.note(), right - s.textWidth(row.note()), row.y() + dy + 1, t.faint(), false);
            }
        }
        if (extAgeRow >= 0) {
            long age = PanelSnapshot.bodyStalenessMs(nowMs);
            String val = age == Long.MAX_VALUE
                    ? I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_NO_SNAPSHOT)
                    : I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_AGO, age / 1000.0);
            int color = PanelSnapshot.bodyFresh(nowMs) ? t.ok() : t.fail();
            drawRightValue(s, val, x + EXT_LABEL_W, right, extAgeRow + dy, color);
        }
        if (extLeaseRow >= 0) {
            McpMode mcp = McpMode.instance();
            String val;
            int color;
            if (!mcp.enabled()) {
                val = "—";
                color = t.textDim();
            } else {
                long left = mcp.leaseRemainingSeconds();
                if (left > 0) {
                    val = I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_LEASE_SEC, left);
                    color = t.ok();
                } else if (mcp.lastRenewedMs() == 0) {
                    val = I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_NO_REQUEST);
                    color = t.textDim();
                } else {
                    val = I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_LEASE_LOST);
                    color = t.fail();
                }
            }
            drawRightValue(s, val, x + EXT_LABEL_W, right, extLeaseRow + dy, color);
        }
        // 小字注(8766 生效说明、脚本 API 的组行)
        for (TextLine line : extTextLines) {
            s.drawText(line.text(), line.x(), line.y() + dy, line.dim() ? t.textDim() : t.text(), false);
        }
        // 文件行:标签 + 路径全文;不存在的行置灰并在尾部写「未找到」
        for (FileRow row : extFileRows) {
            s.drawText(TextClip.fit(s, row.label, 66), x, row.y + dy, t.textDim(), false);
            int color = row.exists ? t.text() : t.faint();
            String path = row.exists ? row.pathText
                    : row.pathText + "   " + I18n.get(ModLanguageData.Keys.PANEL_SETTINGS_NOT_FOUND);
            s.drawText(TextClip.fit(s, path, rw() - 88), x + 72, row.y + dy, color, false);
        }
        // 工具行:名字 + 说明 + 右侧来源标(身体 / 大脑)
        for (ToolRow row : extToolRows) {
            s.drawText(TextClip.fit(s, row.name(), 130), x, row.y() + dy, t.text(), false);
            if (!row.zh().isBlank()) {
                int zx = x + Math.min(134, s.textWidth(row.name()) + 8);
                s.drawText(TextClip.fit(s, row.zh(), Math.max(40, right - zx - 40)),
                        zx, row.y() + dy, t.textDim(), false);
            }
            s.drawText(row.source(), right - s.textWidth(row.source()) - 2, row.y() + dy, t.faint(), false);
        }
        exUi.renderContent(s, HostThemeColors.current(), mx, my, now);
        exScroll.endClip(s);
        exScroll.renderThumb(s, HostThemeColors.current());
        exUi.renderOverlayLayer(s, HostThemeColors.current(), mx, my, now);
    }

    /** 行右侧的值:在 {@code [left, right)} 里右对齐,放不下从尾部收口。 */
    private static void drawRightValue(McDrawSurface s, String text, int left, int right, int rowY, int color) {
        String shown = TextClip.fit(s, text, Math.max(0, right - left));
        s.drawText(shown, right - s.textWidth(shown), rowY, color, false);
    }

    private boolean externalClick(double mx, double my, int button) {
        if (exUi.hasOverlay()) return exUi.mouseClicked(mx, my, button);
        return exScroll.inside(my) && exUi.mouseClicked(mx, my, button);
    }

    private boolean externalScroll(double mx, double my, double delta) {
        return exUi.mouseScrolled(mx, my, delta) || exScroll.scrolled(my, delta);
    }
}
