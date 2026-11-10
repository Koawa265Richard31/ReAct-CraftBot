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
import com.dwinovo.numen.gear.ContainerBoxes;
import com.dwinovo.numen.gear.SlotRefs;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.BoxReadRequest;
import com.dwinovo.numen.network.payload.BoxReadResult;
import com.dwinovo.numen.network.payload.GearSwapRequest;
import com.dwinovo.numen.network.payload.GearSwapResult;
import com.dwinovo.numen.network.payload.RequestStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
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
 * 角色页(M1 只读 + M2 穿脱交互 + M3 双向转移 + 物品自带容器)——面板左栏第一格的内容,版式照 {@code docs/hud-replace-draft.md} §三:
 * <ol>
 *   <li><b>顶部状态条,常驻不滚</b>:血量(+细血条) / 饥饿 / 效果 / 活动 / 外接脑三态。
 *       血量缺省(她没被客户端追踪到)这一格显示「未知」,<b>绝不填 0</b>;饥饿同理。</li>
 *   <li><b>穿戴</b>:遍历 {@link #GEAR} 列表照序渲染、不自己排序(口径照 §四的 GearView:
 *       {@code name / item / writable});{@code writable=false} 画虚线格并在槽名下标「只读」。
 *       GearView 类本身由服务端补丁往 {@code NumenStatePayload} 里加(本树还没有它,见
 *       {@link #setGear}),载荷处理器落地后调 {@link #setGear} 把列表喂进来。</li>
 *   <li><b>背包</b>:主背包 27 格 + 快捷栏 9 格,来自 {@link ClientNumenState} 的
 *       {@link BodySnapshot};画法照 {@code ProfilePage} 的物品格——{@code g.renderItem} +
 *       {@code g.renderItemDecorations},悬停给原版 tooltip。装着可展开容器的格画一圈淡强调色
 *       虚线框({@link #boxMark} 的说明),只是记号,不改这格的任何手势。</li>
 *   <li><b>容器</b>:两边背包与她的穿戴位上「能展开的物品自带容器」逐格列出(潜影盒与挂着
 *       物品级物品栏能力的背包,判据 {@link ContainerBoxes#isContainer}——与服务端同一条),
 *       点一下展开看内格。详见下面「容器」一节。</li>
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
 *       就挑现有槽里第一个空位;都定不下来不发、页头给一句提示。<b>M3 选格在手时这格改作完成
 *       一笔转移</b>(见下节),M2 的穿上只在没有选格时触发。</li>
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
 * <h2>双向转移(M3):我背包一格 + 她背包一格</h2>
 * 面板新增「我的背包」区:主人自己的 36 格,直读客户端活背包(见 {@link #myItems}),只多一块
 * 显示与点击,不建快照层。M3 的手势<b>从我的背包起手</b>:点我的一格立起选格(亮虚线框 + 页头
 * 提示),再点她背包一格完成一笔;方向与 {@code dir} 字节由两格<b>此刻的内容</b>定死
 * (口径照 {@code docs/hud-replace-draft.md} 12.3 的表):
 * <ul>
 *   <li>她那格有货、我这格空 → <b>取回</b>(dir 2,她的 {@code inv:<n>} → 我的 {@code inv:<m>});</li>
 *   <li>我这格有货、她那格空 → <b>交给</b>(dir 3,我的 {@code inv:<m>} → 她的 {@code inv:<n>});</li>
 *   <li>两格都有货:同物品同组件且并得下 → <b>取回并堆</b>(dir 2,合起来的那叠落进先点的
 *       我这格);其余(不同物品、或同物品并不下)→ <b>交换</b>(dir 4,格对格原样互换)。</li>
 *   <li>两格都空 → 不发,页头提示;两格都在同一侧 → 不构成转移(她内部的移动不在 M3 范围)——
 *       我这边再点任何一格都撤掉选格并提示;她那侧单点永远是 M2 的穿上或选格的第二格,
 *       「两格都在她那边」的配对从起手侧就立不起来。</li>
 * </ul>
 *
 * <p><b>与 M2 手势的冲突及裁定</b>:她的背包格原本(M2)单点 = 给她穿;M3 又要它当转移的
 * 第二格。裁定:转移的起手侧只在我的背包(我背包格没有 M2 角色),她背包格<b>有选格在手</b>时
 * 改作完成转移、<b>没选格</b>时保持 M2 穿上——M2 的两条单点手势(点穿戴槽卸、点背包格穿)
 * 在没有选格时原样生效。选格在手时她的穿戴槽不再触发 M2 卸下(那会从一次没瞄准的第二击里
 * 误摘装备),提示「转移只在两边背包格之间」并撤掉选格。
 *
 * <p>M3 与 M2 共用同一笔在飞(一次一单,同一套 pending 字段、同一个 5s 超时、同一个回执入口
 * {@link #acceptGearSwapResult});转移的 expectFrom/expectTo 按<b>完成那一刻</b>两格的现状给,
 * 服务端对不上现状就一格不动。回执的 wornAfter/ownerAfter 对转移携带 (fromAfter, toAfter):
 * 她那格按方向解出落进 {@link #PACK_TRUTH} 重画;我这格不本地写——直读的活背包由原版容器
 * 同步把服务端事实带回来,面板不替它动背包。不做乐观更新、不排队、闸关着不起选格不发请求,
 * 全部与 M2 同一条。
 *
 * <h2>容器:背包 ⇄ 物品自带容器</h2>
 * 「容器」区枚举<b>两方</b>的容器——她的背包 36 格、我的背包 36 格、她的穿戴位( GearSource
 * 登记的槽,容器型饰品也在内),判据 {@link ContainerBoxes#isContainer} 与服务端 resolve 同一条
 * (潜影盒,或物品级物品栏能力);<b>别人的物品不在枚举里</b>——「容器在别人手里」的读权限口径
 * 与服务端一致(SlotRefs 的文法只有 my|her 两个 holder):只有她的和主人的物品可以展开,
 * 其他人的连格数都不给,面板上根本不产生那一格。
 *
 * <ul>
 *   <li><b>点容器格 = 展开/收起/重读</b>:没展开就发 {@link BoxReadRequest}(读,不受写权限闸
 *       限制——闸管的是写);展开中且锚点堆还是读回执里那件({@link #boxStale})就收起(本地
 *       动作,不发请求);动过了(她开过原版界面、物品被挪走)就重读对齐。读也占「一次一单」
 *       的在飞位,与写互斥:页面上一次只等一件事。</li>
 *   <li><b>内格只来自服务端事实</b>:整盒真值存在 {@link #BOX_CONTENTS}(读回执原样,EMPTY 格
 *       也是事实),面板不猜、不做乐观更新;单包装不下的截断回执置 {@code truncated},没送到的
 *       格画「…」,绝不画成空格。空容器画整版空格 + 「盒是空的」;满容器看节头计数 n/m。</li>
 *   <li><b>容器转移 = 盒内选格 + 背包格</b>:点一格内格立起选格,再点一格背包完成一笔——
 *       <b>方向由背包格此刻的内容定死</b>:空格 = 取出(dir 5,盒内 → 那格背包),有物 = 放入
 *       (dir 6,那格背包 → 盒内;收得下问容器自己的规则,服务端如实回一句)。expectBox 钉住
 *       最近一次读回执的整盒锚点堆,expectFrom/expectTo 按 from→to 各钉一格;盒动过了先提示
 *       重读、不发写。一笔容器转移与穿脱/转移共用同一套 pending 字段、同一个 5s 超时、同一个
 *       busy(),回执经 {@link #acceptGearSwapResult} 落地:她那格背包落 {@link #PACK_TRUTH},
 *       随后要一份最新状态(对齐 gear 锚点那件堆的 NBT)再拉一次读,重画整盒。</li>
 *   <li><b>写权限闸关着</b>:展开、收起、重读都可以(读不是写);内格点不了(悬停提示「写权限
 *       关着」),任何 dir 5/6 的请求都不会发出。</li>
 *   <li><b>嵌套深度</b>:容器里再放容器不开。上限常量在服务端一处
 *       ({@code ContainerBoxes.MAX_BOX_DEPTH}),客户端的结构性对应:容器列表只枚举锚点层
 *       (背包格/穿戴格上的物品),内格<b>永不</b>展开;往盒里放一件它自己也是容器的物品,客户端
 *       用同一条判据({@link ContainerBoxes#isContainer})与同一句原话
 *       ({@code ContainerBoxes.PAYLOAD_DEPTH})就地拦下,服务端 resolve 会再判一遍。</li>
 * </ul>
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

    // ---- 在飞的一笔(M2 穿脱与 M3 转移共用):一次只飞一笔,不做乐观更新 ----

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
    /** 在飞的背包格下标(穿上时服务端按「首个全等」钉的那格;M3 转移是她的那一格);-1 = 无。 */
    private static int pendingPackIdx = -1;
    /** 发出时那格背包里的东西(回执到达后按服务端同一口径重新钉格)。 */
    private static ItemStack pendingExpectOwner = ItemStack.EMPTY;
    /** 在飞的这笔是 M3 转移(dir 2/3/4)而非 M2 穿脱;回执两端按方向解(见 acceptGearSwapResult)。 */
    private static boolean pendingTransfer;
    /** 在飞转移的方向({@link GearSwapRequest#TAKE_BACK}/{@link GearSwapRequest#GIVE}/{@link GearSwapRequest#SWAP})。 */
    private static byte pendingDir;
    /** 在飞转移时我这格的下标(「处理中」遮罩用);-1 = 无。 */
    private static int pendingMyIdx = -1;
    /** 在飞的这笔是容器读(展开/重读):回执从 {@link #acceptBoxReadResult} 进。 */
    private static boolean pendingBoxRead;
    /** 在飞的这笔是容器写(dir 5/6):回执仍走 GearSwapResult,两端按 from→to 解。 */
    private static boolean pendingBoxWrite;
    /** 在飞的容器操作的 box: 地址(读 = 被读那盒的容器格遮罩;写 = 动的那盒);null = 无。 */
    private static String pendingBoxRef;
    /** 在飞的容器写的内格序号(「处理中」遮罩用);读与无 = -1。 */
    private static int pendingBoxInner = -1;
    /** M3 选格:在我背包里点下的那格(转移起手侧);-1 = 没有选格。 */
    private static int selMyIdx = -1;

    // ---- 容器:展开状态与读回执落的真值 ----

    /** 展开中的容器地址({@link SlotRefs} 文法的 box: 全形,展开请求固定钉内格 0,读回的是整盒);
     * null = 没有展开。 */
    private static String openBoxRef;
    /** 最近一次读回执的整盒锚点堆(潜影盒的内容就编在这堆自己的 NBT 里):{@link #boxStale}
     * 拿它和锚点格现状比,判断「画出来的还是不是现在这个」。 */
    private static ItemStack boxStackAfter = ItemStack.EMPTY;
    /** 最近一次读回执的内格清单(服务端事实原样,EMPTY 格也是事实);空 = 没画过内容。 */
    private static final List<ItemStack> BOX_CONTENTS = new ArrayList<>();
    /** 读回执被单包上限截断过:没送到的格画「…」,绝不画成空格(BoxReadResult 的约定)。 */
    private static boolean boxTruncated;
    /** 盒内选格(容器转移的起手侧);-1 = 没有选格。 */
    private static int selBoxInner = -1;

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
    private static final int[] myHitX = new int[36], myHitY = new int[36];
    /** 我的背包命中矩形 → 物品下标(与她那边同构同编号),与矩形一一对应。 */
    private static final int[] myHitIdx = new int[36];
    private static int myHitCount;

    // ---- 容器的命中矩形与本帧枚举(渲染时记,点击时查——与上面同一条时序约定) ----

    /** 本帧枚举出的一件可展开容器:box 地址(展开/读/写都用它)+ 展示堆 + 锚点标签。 */
    private record BoxCell(String ref, ItemStack shown, String label) {}

    /** 容器列表的命中矩形(数量不定,增长式);矩形 → {@link BoxCell} 下标。 */
    private static int[] boxHitX = new int[8], boxHitY = new int[8];
    private static int boxHitCount;
    /** 盒内格的命中矩形;下标 = 内格序号(0..内格数-1),与矩形一一对应。 */
    private static int[] innerHitX = new int[32], innerHitY = new int[32];
    private static int innerHitCount;
    /** 本帧枚举的容器清单;渲染路径每帧重建(锚点堆是活的,缓存会陈旧),点击路径只读。 */
    private static final List<BoxCell> BOX_CELLS = new ArrayList<>();
    /** 本帧「她的背包哪些格装着可展开容器」:给背包格画记号,不改手势。 */
    private static final boolean[] HER_PACK_BOX = new boolean[36];
    /** 本帧「我的背包哪些格装着可展开容器」:同上。 */
    private static final boolean[] MY_PACK_BOX = new boolean[36];
    /** 本帧「她的穿戴位哪些槽(按 {@link #GEAR} 下标)装着可展开容器」:同上。 */
    private static final java.util.BitSet GEAR_BOX = new java.util.BitSet();

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

    // ---- 点击(M2 穿脱 + M3 双向转移 + 容器):点穿戴槽 = 卸下,点背包格 = 给她穿;
    // M3 从我的背包起手点选,两格对上一侧一笔;容器转移从盒内格起手点选,背包格收笔(dir 5/6)。
    // 开关关着:穿脱与转移都不发,容器读(展开/收起/重读)照常——闸管的是写。一笔在飞时都不发。 ----
    // 已知取舍:点击时按同一口径(准星优先)重新挑同伴——渲染与点击之间准星换人,点的是
    // 换人后那位同位置的格子。请求内部自洽(谁的槽配谁的物品),口径与叫停钮同源。

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        // 常驻区(状态条+写权限行+提示行)滚不下去,滚上去的格会被它盖住——盖住的部分点不着
        double hitFloor = viewY + STATUS_H + WRITE_H + (hasMessage() ? MSG_H : 0);
        if (my < hitFloor) return false;
        boolean m3Armed = selMyIdx >= 0;
        boolean boxArmed = selBoxInner >= 0;
        for (int r = 0; r < gearHitCount && r < GEAR.size(); r++) {
            if (overCell(mx, my, gearHitX[r], gearHitY[r])) {
                // 转移或容器选格在手:穿戴槽不是端点——别让没瞄准的第二击误触发 M2 的卸下
                return (m3Armed || boxArmed) ? transferNotGear() : gearClicked(r);
            }
        }
        for (int r = 0; r < boxHitCount && r < BOX_CELLS.size(); r++) {
            if (overCell(mx, my, boxHitX[r], boxHitY[r])) return boxCellClicked(r);
        }
        for (int r = 0; r < innerHitCount && r < BOX_CONTENTS.size(); r++) {
            if (overCell(mx, my, innerHitX[r], innerHitY[r])) return innerCellClicked(r);
        }
        for (int r = 0; r < packHitCount; r++) {
            if (overCell(mx, my, packHitX[r], packHitY[r])) {
                if (boxArmed) return completeBoxTransfer(packHitIdx[r], true);
                return m3Armed ? completeTransfer(packHitIdx[r]) : packClicked(packHitIdx[r]);
            }
        }
        for (int r = 0; r < myHitCount; r++) {
            if (overCell(mx, my, myHitX[r], myHitY[r])) {
                if (boxArmed) return completeBoxTransfer(myHitIdx[r], false);
                return mySlotClicked(myHitIdx[r]);
            }
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

    // ---- M3 双向转移:从我的背包起手点选,她的背包一格收笔 ----

    /**
     * 点我的背包一格(M3 起手)。闸关着不立选格(与 M2 同一句「写权限关着」);在飞时忽略(busy
     * 提示,一次一单);一个同伴都没有就静默,与 M2 的 packClicked 同一条。已有选格(含再点
     * 同一格)= 两格都在我这边,不构成转移——撤掉选格,页头说一句。
     */
    private boolean mySlotClicked(int itemsIdx) {
        if (!ClientPrefs.gearWriteAllowed()) {
            hintWriteLocked();
            return true;
        }
        if (busy()) return true;
        UUID who = pickCompanion();
        if (who == null) return true;
        if (selMyIdx >= 0) {
            selMyIdx = -1;
            messageKey = ModLanguageData.Keys.PANEL_CHAR_TRANSFER_SAMESIDE;
            messageRaw = null;
            return true;
        }
        selMyIdx = itemsIdx;
        messageKey = ModLanguageData.Keys.PANEL_CHAR_TRANSFER_SELECTED;
        messageRaw = null;
        return true;
    }

    /**
     * 选格在手,点她背包一格 = 完成一笔转移。方向与 {@code dir} 字节由两格<b>此刻的内容</b>定死
     * (类注释「M3」一节的表);expectFrom/expectTo 按完成那一刻的两格现状给,服务端拿它对现状,
     * 任何一头变了都会被指纹拒下、一格不动。发完选格即撤(一笔已飞,遮罩交给
     * {@link #pendingPackIdx}/{@link #pendingMyIdx})。这里不写任何快照——物品动没动,以
     * {@link GearSwapResult} 与原版容器同步带来的事实为准。
     */
    private boolean completeTransfer(int herIdx) {
        if (!ClientPrefs.gearWriteAllowed()) {
            selMyIdx = -1;
            hintWriteLocked();
            return true;
        }
        UUID who = pickCompanion();
        if (who == null) {
            selMyIdx = -1;
            return true;
        }
        if (busy()) return true;
        BodySnapshot snap = ClientNumenState.get(who).orElse(null);
        if (snap == null || !snap.loaded() || snap.items() == null) {
            selMyIdx = -1;
            return true;
        }
        int myIdx = selMyIdx;
        selMyIdx = -1;
        ItemStack mine = myShown(myItems(), myIdx).copy();
        ItemStack hers = packShown(snap.items(), herIdx).copy();
        byte dir;
        int fromSlot, toSlot;
        ItemStack expectFrom, expectTo;
        if (mine.isEmpty() && hers.isEmpty()) {
            messageKey = ModLanguageData.Keys.PANEL_CHAR_TRANSFER_EMPTY;
            messageRaw = null;
            return true;
        } else if (hers.isEmpty()) {
            dir = GearSwapRequest.GIVE;              // 我的 inv:m → 她的 inv:n
            fromSlot = myIdx;
            toSlot = herIdx;
            expectFrom = mine;
            expectTo = ItemStack.EMPTY;
        } else if (mine.isEmpty()) {
            dir = GearSwapRequest.TAKE_BACK;         // 她的 inv:n → 我的 inv:m
            fromSlot = herIdx;
            toSlot = myIdx;
            expectFrom = hers;
            expectTo = ItemStack.EMPTY;
        } else if (ItemStack.isSameItemSameTags(mine, hers)
                && mine.getCount() + hers.getCount() <= mine.getMaxStackSize()) {
            dir = GearSwapRequest.TAKE_BACK;         // 同物品并得下:合起来的那叠落进先点的我这格
            fromSlot = herIdx;
            toSlot = myIdx;
            expectFrom = hers;
            expectTo = mine;
        } else {
            dir = GearSwapRequest.SWAP;              // 其余:格对格原样互换
            fromSlot = herIdx;
            toSlot = myIdx;
            expectFrom = hers;
            expectTo = mine;
        }
        sendTransfer(who, dir, herIdx, myIdx, expectFrom, expectTo);
        return true;
    }

    /** 选格(M3 转移或容器写)在手时点了穿戴槽:两类选格都不碰穿戴槽——提示一句、
     * 撤掉选格,不触发 M2 的卸下。 */
    private static boolean transferNotGear() {
        selMyIdx = -1;
        selBoxInner = -1;
        messageKey = ModLanguageData.Keys.PANEL_CHAR_TRANSFER_GEAR;
        messageRaw = null;
        return true;
    }

    // ---- 容器:展开/收起/重读(读)+ 盒内格起手、背包格收笔的容器转移(dir 5/6,写) ----

    /**
     * 点容器格:有选格在手(M3 转移或容器盒内)先撤——容器格不是任何转移的端点;没展开就发读
     * 展开,展开中且锚点堆还是读回执里那件就收起(本地动作,不发请求),动过了就重读对齐。
     * 读不受写权限闸限制(闸管的是写),但与写共用一次一单的在飞位。
     */
    private boolean boxCellClicked(int idx) {
        if (selMyIdx >= 0 || selBoxInner >= 0) {
            selMyIdx = -1;
            selBoxInner = -1;
            messageKey = ModLanguageData.Keys.PANEL_CHAR_TRANSFER_GEAR;
            messageRaw = null;
            return true;
        }
        if (busy()) return true;
        UUID who = pickCompanion();
        if (who == null) return true;
        String ref = BOX_CELLS.get(idx).ref();
        if (ref.equals(openBoxRef)) {
            if (boxStale()) {
                sendBoxRead(who, ref);   // 盒子在服务端动过了:重读,以服务端事实重画
            } else {
                collapseBox();           // 还是看见的样子:收起
            }
        } else {
            sendBoxRead(who, ref);       // 展开(或从另一只切过来)
        }
        return true;
    }

    /**
     * 点盒内格(写权限闸开着才有这一步;闸关着展开是纯只读,这里吃掉点击、页头说明原因):
     * 立起盒内选格,再点一格背包完成 dir 5/6 的一笔。M3 选格在手时容器内格不是转移端点
     * (与穿戴槽同一条),撤掉 M3 选格并提示;已在飞与已有盒内选格的处理照 M3 选格同一条。
     */
    private boolean innerCellClicked(int inner) {
        if (!ClientPrefs.gearWriteAllowed()) {
            hintWriteLocked();
            return true;
        }
        if (selMyIdx >= 0) {
            transferNotGear();
            return true;
        }
        if (busy()) return true;
        if (selBoxInner >= 0) {
            selBoxInner = -1;
            messageKey = ModLanguageData.Keys.PANEL_CHAR_BOX_SAMESIDE;
            messageRaw = null;
            return true;
        }
        selBoxInner = inner;
        messageKey = ModLanguageData.Keys.PANEL_CHAR_BOX_ARMED;
        messageRaw = null;
        return true;
    }

    /**
     * 盒内选格在手,点一格背包 = 完成一笔容器转移。<b>方向由背包格此刻的内容定死</b>:
     * 空格 = 取出(dir 5,盒内 → 那格背包,整叠落进);有物 = 放入(dir 6,那格背包 → 盒内,
     * 收得下问容器自己的规则,服务端如实回一句,面板不预判「并得下/并不下」)。发完选格即撤,
     * 不做乐观更新——格上「处理中」遮罩交给 pending 字段,内容以回执与重读为准。盒动过了
     * ({@link #boxStale})不发写:expectBox 必然对不上,先提示重读。她那格与我那格都是合法的
     * 背包端(服务端的 dir 5/6 两端都带侧别),互斥与守恒口径服务端罩得住。
     */
    private boolean completeBoxTransfer(int bagIdx, boolean bagHers) {
        if (!ClientPrefs.gearWriteAllowed()) {
            selBoxInner = -1;
            hintWriteLocked();
            return true;
        }
        UUID who = pickCompanion();
        if (who == null) {
            selBoxInner = -1;
            return true;
        }
        if (busy()) return true;
        if (boxStale()) {
            selBoxInner = -1;
            messageKey = ModLanguageData.Keys.PANEL_CHAR_BOX_STALE;
            messageRaw = null;
            return true;
        }
        SlotRefs.Parsed box = SlotRefs.parse(openBoxRef);
        if (box.error() != null) {
            selBoxInner = -1;
            return true;   // 展开用的地址本就合法;防御一拍,不发
        }
        int inner = selBoxInner;
        ItemStack innerCur = inner >= 0 && inner < BOX_CONTENTS.size() && BOX_CONTENTS.get(inner) != null
                ? BOX_CONTENTS.get(inner) : ItemStack.EMPTY;
        List<ItemStack> bagItems = bagHers ? herPackItems() : myItems();
        ItemStack bagCur = bagHers ? packShown(bagItems, bagIdx) : myShown(bagItems, bagIdx);
        selBoxInner = -1;
        if (bagCur.isEmpty() && innerCur.isEmpty()) {
            messageKey = ModLanguageData.Keys.PANEL_CHAR_BOX_INNER_EMPTY;
            messageRaw = null;
            return true;   // 两头都空,没有可动的
        }
        String boxRef = SlotRefs.formatBox(box.hers(), SlotRefs.anchorText(box), inner);
        String bagRef = (bagHers ? "her:inv:" : "my:inv:") + bagIdx;
        byte dir;
        String fromRef, toRef;
        ItemStack expectFrom, expectTo;
        if (bagCur.isEmpty()) {
            dir = GearSwapRequest.BOX_TAKE;          // 盒内 inner → 背包 bagIdx
            fromRef = boxRef;
            toRef = bagRef;
            expectFrom = innerCur.copy();
            expectTo = ItemStack.EMPTY;
        } else {
            if (ContainerBoxes.isContainer(bagCur)) {
                // 盒中盒:同一条判据、同一句原话就地拦下(服务端 resolve 会再判一遍,这里省一趟
                // 注定失败的往返);MAX_BOX_DEPTH 的常量只在服务端一处。
                messageKey = null;
                messageRaw = ContainerBoxes.PAYLOAD_DEPTH;
                return true;
            }
            dir = GearSwapRequest.BOX_PUT;           // 背包 bagIdx → 盒内 inner
            fromRef = bagRef;
            toRef = boxRef;
            expectFrom = bagCur.copy();
            expectTo = innerCur.copy();
        }
        sendBox(who, dir, fromRef, toRef, expectFrom, expectTo, boxRef, inner, bagIdx, bagHers);
        return true;
    }

    /** 收起展开的盒:纯本地动作,不发请求、不动任何真值以外的东西。 */
    private static void collapseBox() {
        openBoxRef = null;
        boxStackAfter = ItemStack.EMPTY;
        BOX_CONTENTS.clear();
        boxTruncated = false;
        selBoxInner = -1;
    }

    /**
     * 展开的那盒还是不是读回执里看见的样子:锚点堆全等比较({@code ItemStack.matches}——潜影盒
     * 的内容编在这堆自己的 NBT 里,内容或位置动了它都变)。没展开/没有真值 = 不算过期。
     * 锚点堆照枚举同一条取法(她的背包/我的背包/她的穿戴位)。
     */
    private static boolean boxStale() {
        if (openBoxRef == null || boxStackAfter.isEmpty()) return false;
        SlotRefs.Parsed parsed = SlotRefs.parse(openBoxRef);
        if (parsed.error() != null) return false;
        return !ItemStack.matches(anchorShown(parsed), boxStackAfter);
    }

    /** 一条 box 地址的锚点堆在面板上的现状;找不到(物品不在了)照实给空堆。 */
    private static ItemStack anchorShown(SlotRefs.Parsed parsed) {
        if (parsed.gearName() != null) {
            int seen = 0;
            for (GearCell c : GEAR) {
                if (parsed.gearName().equals(c.name())) {
                    if (seen == parsed.gearOrdinal()) return gearShown(c);
                    seen++;
                }
            }
            return ItemStack.EMPTY;
        }
        List<ItemStack> items = parsed.hers() ? herPackItems() : myItems();
        return parsed.hers() ? packShown(items, parsed.invIndex()) : myShown(items, parsed.invIndex());
    }

    /** 她的背包此刻面板显示的那份(快照;没同步给空表)。 */
    private static List<ItemStack> herPackItems() {
        UUID who = pickCompanion();
        BodySnapshot snap = who == null ? null : ClientNumenState.get(who).orElse(null);
        return snap != null && snap.loaded() && snap.items() != null ? snap.items() : List.of();
    }

    /**
     * 发一笔容器转移(dir 5 取出 / 6 放入)。M2 的 slot/expectWorn/expectOwner 与 M3 的
     * fromSlot/toSlot 留空(载荷约定的容器形状);expectBox 钉住最近一次读回执的整盒锚点堆。
     */
    private static void sendBox(UUID who, byte dir, String fromRef, String toRef,
                                ItemStack expectFrom, ItemStack expectTo,
                                String boxRef, int inner, int bagIdx, boolean bagHers) {
        long tx = nextTxId++;
        pendingTx = tx;
        pendingSentMs = System.currentTimeMillis();
        pendingTransfer = false;
        pendingBoxRead = false;
        pendingBoxWrite = true;
        pendingDir = dir;
        pendingBoxRef = boxRef;
        pendingBoxInner = inner;
        pendingGearIdx = -1;
        pendingGearName = null;
        pendingPackIdx = bagHers ? bagIdx : -1;
        pendingMyIdx = bagHers ? -1 : bagIdx;
        pendingCompanion = who;
        pendingExpectOwner = ItemStack.EMPTY;
        NumenNetwork.sendToServer(new GearSwapRequest(tx, who, "", ItemStack.EMPTY, ItemStack.EMPTY,
                dir, -1, -1, expectFrom, expectTo, fromRef, toRef, boxStackAfter.copy()));
    }

    /**
     * 发一次容器读(展开/重读)。读不动世界,但占「一次一单」的在飞位:读与写互斥,页面上
     * 一次只等一件事。expectBox 带上面板自己看见的那一盒——服务端不据它拒读(读永远回答
     * 此刻真值),带的只是「我上次看到的是这个」。
     */
    private static void sendBoxRead(UUID who, String ref) {
        long tx = nextTxId++;
        pendingTx = tx;
        pendingSentMs = System.currentTimeMillis();
        pendingTransfer = false;
        pendingBoxRead = true;
        pendingBoxWrite = false;
        pendingDir = 0;
        pendingBoxRef = ref;
        pendingBoxInner = -1;
        pendingGearIdx = -1;
        pendingGearName = null;
        pendingPackIdx = -1;
        pendingMyIdx = -1;
        pendingCompanion = who;
        pendingExpectOwner = ItemStack.EMPTY;
        ItemStack seen = ref.equals(openBoxRef) ? boxStackAfter.copy() : ItemStack.EMPTY;
        NumenNetwork.sendToServer(new BoxReadRequest(tx, who, ref, seen));
    }

    /**
     * 容器读的回执到了({@code ClientPayloadSink.boxRead} 挂的这里;客户端主线程)。认领在飞的
     * 读,以回执的内格清单为整盒真值重画——内容从来只来自服务端事实,面板不猜。失败的 reason
     * 原样放到页头,展开状态不动(没开成的容器一个格都不画);截断的回执把话亮出来,没送到的
     * 格画「…」不画成空格。旁牌/迟到的回执不认,也不动任何状态。
     */
    public static void acceptBoxReadResult(BoxReadResult p) {
        if (p == null || pendingTx == 0 || !pendingBoxRead || p.txId() != pendingTx) return;
        pendingTx = 0;
        pendingBoxRead = false;
        pendingBoxRef = null;
        pendingCompanion = null;
        selBoxInner = -1;   // 内容要换形了,盒内选格不再指向同一格
        if (!p.ok()) {
            messageKey = null;
            messageRaw = p.reason();
            return;
        }
        openBoxRef = p.boxRef();
        boxStackAfter = p.boxAfter() == null ? ItemStack.EMPTY : p.boxAfter();
        BOX_CONTENTS.clear();
        if (p.contents() != null) {
            for (ItemStack st : p.contents()) {
                BOX_CONTENTS.add(st == null ? ItemStack.EMPTY : st);
            }
        }
        boxTruncated = p.truncated();
        messageKey = boxTruncated ? ModLanguageData.Keys.PANEL_CHAR_BOX_TRUNC : null;
        messageRaw = null;
    }

    /**
     * 发一笔转移(dir 2/3/4)。fromSlot/toSlot 照载荷的口径:取回与交换 fromSlot=她那格、
     * toSlot=我这格,交给相反;M2 的 slot/expectWorn/expectOwner 留空(载荷约定的转移形状)。
     */
    private static void sendTransfer(UUID who, byte dir, int herIdx, int myIdx,
                                     ItemStack expectFrom, ItemStack expectTo) {
        long tx = nextTxId++;
        pendingTx = tx;
        pendingSentMs = System.currentTimeMillis();
        pendingTransfer = true;
        pendingDir = dir;
        pendingGearIdx = -1;
        pendingGearName = null;
        pendingPackIdx = herIdx;
        pendingMyIdx = myIdx;
        pendingCompanion = who;
        pendingExpectOwner = ItemStack.EMPTY;
        NumenNetwork.sendToServer(new GearSwapRequest(tx, who, "", ItemStack.EMPTY, ItemStack.EMPTY,
                dir,
                dir == GearSwapRequest.GIVE ? myIdx : herIdx,
                dir == GearSwapRequest.GIVE ? herIdx : myIdx,
                expectFrom, expectTo));
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
        scanContainers(who, snap);

        // ---- 可滚内容:裁剪在常驻区(状态条 + 写权限行 + 提示行)下面,滚上去不会盖住它们 ----
        int fixedH = STATUS_H + WRITE_H + (hasMessage() ? MSG_H : 0);
        gearHitCount = 0;
        packHitCount = 0;
        myHitCount = 0;
        boxHitCount = 0;
        innerHitCount = 0;
        g.enableScissor(x, y + fixedH + 1, x + w, y + h);
        int cy = y + fixedH + 8 - Math.round(scroll);

        cy = drawGear(g, f, t, x, y + h, iw, cy, mx, my, hover, tip);
        cy = drawInventory(g, f, t, x, y + h, iw, cy, mx, my, hover, tip, snap);
        cy = drawMyInventory(g, f, t, x, y + h, iw, cy, mx, my, hover, tip);
        cy = drawExtra(g, f, t, x, y + h, iw, cy, mx, my, hover, tip);

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
        if (GEAR_BOX.get(idx)) {
            boxMark(g, t, x, y);   // 这件是可展开的容器:只是记号,M2 的卸下照旧
        }
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
        if (i < HER_PACK_BOX.length && HER_PACK_BOX[i]) {
            boxMark(g, t, x, y);   // 这件是可展开的容器:只是记号,M2 穿上/M3 转移照旧
        }
        if (locked && overCell(mx, my, x, y)) {
            tip[0] = I18n.get(ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED);
        }
        if (pendingTx != 0 && pendingPackIdx == i) {
            pendingVeil(g, t, x, y);
        }
        notePackHit(i, x, y);
    }

    // ---- 我的背包(M3):主人自己的 36 格,直读客户端活背包,只读渲染 + 点选 ----

    /**
     * 主人的背包:客户端活背包原样(0-8 快捷栏、9-35 主背包,与她那边同构同编号——服务端的
     * 转移事务就是按这套编号在两边 {@code Inventory.items} 上动的)。这里不建快照层,也不设
     * 回执真值层:服务端改了这一格,原版的容器同步自会把新内容推进活背包,面板下一帧就是事实;
     * 面板自己再写一笔就是第二支笔,与「不做乐观更新」同一条要守。
     */
    private static List<ItemStack> myItems() {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null) return List.of();
        return me.getInventory().items;
    }

    /** 我的背包区:版式照她那边(3×9 主背包 + 快捷栏行),格子可点——M3 转移的起手侧。 */
    private int drawMyInventory(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy,
                                int mx, int my, ItemStack[] hover, String[] tip) {
        List<ItemStack> items = myItems();
        boolean ready = !items.isEmpty();
        cy = head(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_MYBACKPACK),
                ready ? "27 + 9" : "", x, cy, iw);
        if (!ready) {
            return hint(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_MYBACKPACK_EMPTY), x, cy) + 4;
        }
        boolean locked = !ClientPrefs.gearWriteAllowed();
        int gx = x;
        for (int i = 9; i < 36 && i < items.size(); i++) {
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int sx = gx + col * SLOT, sy = cy + row * SLOT;
            slotBg(g, t, sx, sy);
            if (sy <= clipBottom) drawMyCell(g, f, t, items, i, sx, sy, mx, my, hover, tip, locked);
        }
        cy += 3 * SLOT + 5;
        if (cy <= clipBottom) {
            Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_HOTBAR), gx, cy, t.faint());
        }
        cy += 10;
        for (int i = 0; i < 9 && i < items.size(); i++) {
            int sx = gx + i * SLOT, sy = cy;
            slotBg(g, t, sx, sy);
            if (sy <= clipBottom) drawMyCell(g, f, t, items, i, sx, sy, mx, my, hover, tip, locked);
        }
        return cy + SLOT + 8;
    }

    /** 我的一格:物品 + 悬停提示 + 写权限提示 + 选格亮框 + 在飞「处理中」遮罩 + 命中矩形。 */
    private void drawMyCell(GuiGraphics g, Font f, UiTheme t, List<ItemStack> items, int i,
                            int x, int y, int mx, int my, ItemStack[] hover, String[] tip, boolean locked) {
        ItemStack st = myShown(items, i);
        collect(g, st, x + 1, y + 1, mx, my, hover);
        if (i < MY_PACK_BOX.length && MY_PACK_BOX[i]) {
            boxMark(g, t, x, y);   // 这件是可展开的容器:只是记号,M3 起手选格照旧
        }
        if (locked && overCell(mx, my, x, y)) {
            tip[0] = I18n.get(ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED);
        }
        if (selMyIdx == i) {
            dashedFrame(g, x, y, SLOT, SLOT, t.cta());   // 选格:亮虚线框,物品全可见
        }
        if (pendingTx != 0 && pendingMyIdx == i) {
            pendingVeil(g, t, x, y);
        }
        noteMyHit(i, x, y);
    }

    /** 我这格现在的东西:直读活背包,没有真值层(见 {@link #myItems})。 */
    private static ItemStack myShown(List<ItemStack> items, int i) {
        return i >= 0 && i < items.size() && items.get(i) != null ? items.get(i) : ItemStack.EMPTY;
    }

    private static void noteMyHit(int itemsIdx, int x, int y) {
        if (itemsIdx < 0 || itemsIdx >= myHitIdx.length) return;
        myHitX[itemsIdx] = x;
        myHitY[itemsIdx] = y;
        myHitIdx[itemsIdx] = itemsIdx;
        myHitCount = Math.max(myHitCount, itemsIdx + 1);
    }

    // ---- 容器:物品自带容器逐格列出,展开看内格,盒内 ⇄ 背包的收与放 ----

    /**
     * 枚举两方的可展开容器,渲染路径每帧重建(锚点堆是活的,缓存会陈旧):她的背包 36 格
     * (照背包区的画序:主背包 9–35,再快捷栏 0–8)、我的背包 36 格、她的穿戴位(按
     * {@link #GEAR} 照序,同名多格的序号 = 第几次出现,与服务端 gearSlot 的取法一致)。
     * 判据 {@link ContainerBoxes#isContainer} 与服务端 resolve 同一条;<b>别人的物品不在
     * 枚举里</b>——「容器在别人手里」的读权限口径:只有她的和主人的物品可以展开,其他人的
     * 连格数都不给,面板上根本不产生那一格(服务端 SlotRefs 文法同样没有第三方的写法)。
     * 深度上限的结构性对应:只有锚点层(背包格/穿戴格上的物品)进列表,内格永不展开;
     * 常量本身只在服务端一处({@code ContainerBoxes.MAX_BOX_DEPTH})。
     */
    private void scanContainers(UUID who, BodySnapshot snap) {
        BOX_CELLS.clear();
        java.util.Arrays.fill(HER_PACK_BOX, false);
        java.util.Arrays.fill(MY_PACK_BOX, false);
        GEAR_BOX.clear();
        List<ItemStack> hers = snap != null && snap.loaded() && snap.items() != null ? snap.items() : List.of();
        String herLabel = I18n.get(ModLanguageData.Keys.PANEL_CHAR_BOX_HER);
        String myLabel = I18n.get(ModLanguageData.Keys.PANEL_CHAR_BOX_MY);
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? 9 : 0, to = pass == 0 ? 36 : 9;
            for (int i = from; i < to && i < hers.size(); i++) {
                ItemStack st = packShown(hers, i);
                if (st.isEmpty()) continue;
                if (ContainerBoxes.isContainer(st)) {
                    HER_PACK_BOX[i] = true;
                    BOX_CELLS.add(new BoxCell(SlotRefs.formatBox(true, "inv:" + i, 0),
                            st, herLabel + " " + i));
                }
            }
        }
        List<ItemStack> mine = myItems();
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? 9 : 0, to = pass == 0 ? 36 : 9;
            for (int i = from; i < to && i < mine.size(); i++) {
                ItemStack st = myShown(mine, i);
                if (st.isEmpty()) continue;
                if (ContainerBoxes.isContainer(st)) {
                    MY_PACK_BOX[i] = true;
                    BOX_CELLS.add(new BoxCell(SlotRefs.formatBox(false, "inv:" + i, 0),
                            st, myLabel + " " + i));
                }
            }
        }
        HashMap<String, Integer> seen = new HashMap<>();
        for (int g = 0; g < GEAR.size(); g++) {
            GearCell c = GEAR.get(g);
            int ordinal = seen.merge(c.name() == null ? "" : c.name(), 1, Integer::sum) - 1;
            ItemStack st = gearShown(c);
            if (st.isEmpty() || !ContainerBoxes.isContainer(st)) continue;
            GEAR_BOX.set(g);
            BOX_CELLS.add(new BoxCell(
                    SlotRefs.formatBox(true, "gear:" + c.name() + (ordinal > 0 ? "#" + ordinal : ""), 0),
                    st, c.name() + (ordinal > 0 ? "#" + ordinal : "")));
        }
    }

    /** 容器区:节头计数(展开中 = n/m) → 容器格一行行 → 过期提示 → 展开那盒的内格网。 */
    private int drawExtra(GuiGraphics g, Font f, UiTheme t, int x, int clipBottom, int iw, int cy,
                          int mx, int my, ItemStack[] hover, String[] tip) {
        cy = head(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_EXTRA),
                boxHeadCount(), x, cy, iw);
        if (BOX_CELLS.isEmpty()) {
            return hint(g, f, t, I18n.get(ModLanguageData.Keys.PANEL_CHAR_BOX_NONE), x, cy) + 4;
        }
        boolean stale = boxStale();
        int perRow = Math.max(1, iw / GEAR_PITCH);
        int rowTop = cy;
        for (int i = 0; i < BOX_CELLS.size(); i++) {
            int col = i % perRow;
            if (col == 0 && i > 0) rowTop += GEAR_ROW_H;
            int sx = x + col * GEAR_PITCH;
            if (rowTop > clipBottom) break;              // 滚出裁剪区就别画了
            drawBoxCell(g, f, t, i, sx, rowTop, mx, my, hover, tip, stale);
        }
        cy = rowTop + GEAR_ROW_H;
        if (openBoxRef != null && stale && cy <= clipBottom) {
            Nb.text(g, f, I18n.get(ModLanguageData.Keys.PANEL_CHAR_BOX_STALE), x, cy, t.fail());
            cy += 12;
        }
        // 展开的那盒:内格 9 列铺开;截断时末尾画「…」,绝不把没送到的格画成空格
        if (openBoxRef != null && !BOX_CONTENTS.isEmpty()) {
            int filled = 0;
            for (ItemStack st : BOX_CONTENTS) {
                if (st != null && !st.isEmpty()) filled++;
            }
            int rows = (BOX_CONTENTS.size() + 8) / 9;
            for (int i = 0; i < BOX_CONTENTS.size(); i++) {
                int col = i % 9, row = i / 9;
                int sx = x + col * SLOT, sy = cy + row * SLOT;
                slotBg(g, t, sx, sy);
                if (sy <= clipBottom) drawInnerCell(g, f, t, i, sx, sy, mx, my, hover, tip);
            }
            if (cy <= clipBottom && (boxTruncated || filled == 0)) {
                int tx = x + Math.min(9, BOX_CONTENTS.size()) * SLOT + 4;
                String note = boxTruncated ? "…"
                        : I18n.get(ModLanguageData.Keys.PANEL_CHAR_BOX_EMPTY);
                Nb.text(g, f, note, tx, cy + (Math.min(rows, 1) * SLOT - f.lineHeight) / 2 + 1, t.faint());
            }
            cy += rows * SLOT;
        }
        return cy + 8;
    }

    /** 节头右端的计数:展开中 = 「 filled/格数 」(截断加「+」),没展开 = 容器件数。 */
    private static String boxHeadCount() {
        if (openBoxRef == null) {
            return BOX_CELLS.isEmpty() ? "" : String.valueOf(BOX_CELLS.size());
        }
        int filled = 0;
        for (ItemStack st : BOX_CONTENTS) {
            if (st != null && !st.isEmpty()) filled++;
        }
        return filled + "/" + BOX_CONTENTS.size() + (boxTruncated ? "+" : "");
    }

    /** 一格容器(列表):格底 + 物品 + 锚点标签;展开中的画强调色实线框,读在飞的画「处理中」。 */
    private void drawBoxCell(GuiGraphics g, Font f, UiTheme t, int idx, int x, int y,
                             int mx, int my, ItemStack[] hover, String[] tip, boolean stale) {
        BoxCell cell = BOX_CELLS.get(idx);
        boolean expanded = cell.ref().equals(openBoxRef);
        slotBg(g, t, x, y);
        collect(g, cell.shown(), x + 1, y + 1, mx, my, hover);
        if (expanded) {
            solidFrame(g, x, y, SLOT, SLOT, t.accent());
            if (stale) {
                dashedFrame(g, x, y, SLOT, SLOT, t.fail());
            }
        }
        if (pendingTx != 0 && pendingBoxRead && cell.ref().equals(pendingBoxRef)) {
            pendingVeil(g, t, x, y);
        }
        noteBoxHit(idx, x, y);
        Nb.text(g, f, Nb.clip(f, cell.label() == null ? "?" : cell.label(), GEAR_PITCH - 6),
                x, y + SLOT + 2, t.faint());
    }

    /** 一格盒内:格底 + 内容 + 盒内选格亮框 + 写在飞遮罩 + 闸关提示 + 命中矩形。 */
    private void drawInnerCell(GuiGraphics g, Font f, UiTheme t, int i, int x, int y,
                               int mx, int my, ItemStack[] hover, String[] tip) {
        ItemStack st = i < BOX_CONTENTS.size() && BOX_CONTENTS.get(i) != null
                ? BOX_CONTENTS.get(i) : ItemStack.EMPTY;
        collect(g, st, x + 1, y + 1, mx, my, hover);
        if (!ClientPrefs.gearWriteAllowed() && overCell(mx, my, x, y)) {
            tip[0] = I18n.get(ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED);
        }
        if (selBoxInner == i) {
            dashedFrame(g, x, y, SLOT, SLOT, t.cta());   // 盒内选格:亮虚线框,与 M3 同款
        }
        if (pendingTx != 0 && pendingBoxWrite && pendingBoxInner == i) {
            pendingVeil(g, t, x, y);
        }
        noteInnerHit(i, x, y);
    }

    /** 容器记号(背包格/穿戴格上):一圈淡强调色虚线框——「这件能展开」,不改这格的任何手势。 */
    private static void boxMark(GuiGraphics g, UiTheme t, int x, int y) {
        dashedFrame(g, x, y, SLOT, SLOT, UiTheme.mix(t.accent(), t.band(), 0.45f));
    }

    /** 实线细框:四边各 1 像素——展开中的容器的记号(与虚线的只读/处理中都分得开)。 */
    private static void solidFrame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void noteBoxHit(int idx, int x, int y) {
        if (idx >= boxHitX.length) {
            int cap = Math.max(idx + 1, BOX_CELLS.size()) + 8;
            boxHitX = java.util.Arrays.copyOf(boxHitX, cap);
            boxHitY = java.util.Arrays.copyOf(boxHitY, cap);
        }
        boxHitX[idx] = x;
        boxHitY[idx] = y;
        boxHitCount = Math.max(boxHitCount, idx + 1);
    }

    private static void noteInnerHit(int inner, int x, int y) {
        if (inner >= innerHitX.length) {
            int cap = Math.max(inner + 1, BOX_CONTENTS.size()) + 8;
            innerHitX = java.util.Arrays.copyOf(innerHitX, cap);
            innerHitY = java.util.Arrays.copyOf(innerHitY, cap);
        }
        innerHitX[inner] = x;
        innerHitY[inner] = y;
        innerHitCount = Math.max(innerHitCount, inner + 1);
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
            pendingTransfer = false;
            pendingBoxRead = false;
            pendingBoxWrite = false;
            pendingBoxRef = null;
            pendingBoxInner = -1;
            pendingDir = 0;
            pendingMyIdx = -1;
            selMyIdx = -1;
            selBoxInner = -1;
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
     * <p>M3 转移(dir 2/3/4)的回执两端 = (fromAfter, toAfter):她那格按方向解出(交给 =
     * to 端在 ownerAfter,取回/交换 = from 端在 wornAfter)落进 {@link #PACK_TRUTH} 重画;
     * 我这格不本地写——直读的活背包等原版容器同步把服务端事实带回来,面板不替它动背包。
     *
     * <p>M2 穿脱的槽侧真值按<b>发送时记下的槽名</b>落键——在飞中 {@link #setGear} 换形时,同下标
     * 可能已是另一个槽,下标键会把真值盖到错的槽名下。卸下这笔服务端不背背包端(expectOwner=EMPTY
     * 钉不到格,ownerAfter 恒空),所以成功落地后顺势发一份 {@link RequestStatePayload},把
     * 「摘下的东西进了哪格」也一并对齐——服务端的背包变化推送(CompanionStateWatch,0.5s
     * 节流)通常更早到,这份请求只是把顺序颠倒、推送丢失等缝隙也补上。M3 转移的两端都按
     * fromSlot/toSlot 钉死了下标,不再额外要状态。
     *
     * <p>容器转移(dir 5/6)同口径:回执两端照 from→to 序,背包端按方向解出落
     * {@link #PACK_TRUTH}(她那边;我那边照旧不本地写);随后要一份最新状态(gear 锚点的盒把
     * 内容写进穿戴位那件堆自己的 NBT,穿戴列表要跟着换)并拉一次 {@link BoxReadRequest} 重画
     * 整盒——回执只带动的那一格,其余内格以重读为准。
     */
    public static void acceptGearSwapResult(GearSwapResult p) {
        if (p == null || pendingTx == 0 || p.txId() != pendingTx) return;
        pendingTx = 0;
        boolean transfer = pendingTransfer;
        boolean boxWrite = pendingBoxWrite;
        byte dir = pendingDir;
        int herIdx = pendingPackIdx;
        String gearName = pendingGearName;
        UUID who = pendingCompanion;
        ItemStack expectOwner = pendingExpectOwner;
        String boxRef = pendingBoxRef;
        pendingGearIdx = -1;
        pendingPackIdx = -1;
        pendingGearName = null;
        pendingCompanion = null;
        pendingExpectOwner = ItemStack.EMPTY;
        pendingTransfer = false;
        pendingBoxRead = false;
        pendingBoxWrite = false;
        pendingBoxRef = null;
        pendingBoxInner = -1;
        pendingDir = 0;
        pendingMyIdx = -1;
        if (!p.ok()) {
            messageKey = null;
            messageRaw = p.reason();
            return;
        }
        messageKey = null;
        messageRaw = null;
        if (boxWrite) {
            // 容器转移(dir 5/6)的回执,两端照 from→to 序:背包端在 dir 5 是 ownerAfter、
            // dir 6 是 wornAfter。她那格落 PACK_TRUTH;我那格不本地写——原版容器同步带回(与
            // M3 同一条)。gear 锚点的盒把内容写进穿戴位那件堆自己的 NBT,GEAR 列表里的旧堆过期
            // 了:顺势要一份最新状态(M2 穿脱同一条)把穿戴与背包一并对齐,再拉一次读重画整盒
            // (spec §4.1 的约定:回执只带动的那一格,其余内格不在回执里)。
            ItemStack bagAfter = dir == GearSwapRequest.BOX_TAKE ? p.ownerAfter() : p.wornAfter();
            if (herIdx >= 0 && herIdx < PACK_TRUTH.length) {
                PACK_TRUTH[herIdx] = bagAfter == null ? ItemStack.EMPTY : bagAfter;
            }
            if (who != null) {
                NumenNetwork.sendToServer(new RequestStatePayload(who));
            }
            if (boxRef != null && who != null) {
                sendBoxRead(who, boxRef);
            }
            return;
        }
        if (transfer) {
            ItemStack hersAfter = dir == GearSwapRequest.GIVE ? p.ownerAfter() : p.wornAfter();
            if (herIdx >= 0 && herIdx < PACK_TRUTH.length) {
                PACK_TRUTH[herIdx] = hersAfter == null ? ItemStack.EMPTY : hersAfter;
            }
            return;
        }
        if (gearName != null) {
            GEAR_TRUTH.put(gearName, p.wornAfter());
        }
        if (who != null) {
            NumenNetwork.sendToServer(new RequestStatePayload(who));
        }
        int packIdx = firstMatch(packTruthSource == null ? null : packTruthSource.items(), expectOwner);
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
