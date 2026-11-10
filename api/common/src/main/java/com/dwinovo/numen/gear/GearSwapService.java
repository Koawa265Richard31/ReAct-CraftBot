package com.dwinovo.numen.gear;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.container.ContainerBox;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.GearSwapRequest;
import com.dwinovo.numen.network.payload.GearSwapResult;
import com.dwinovo.numen.program.ServerPrograms;
import com.dwinovo.numen.sdk.Dispatcher;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 服务端主线程上的穿脱事务:面板的一条 {@link GearSwapRequest} 从这里走完七道校验、经任务槽
 * 真正动手、守恒断言收尾,再用 {@link GearSwapResult} 按 (主人, txId) 回一张回执。
 *
 * <h2>路只有一条</h2>真正动手的那一步不自己搬东西、也不碰 {@code Wardrobe}:它把参数写成一条
 * {@code numen.gear.wear} / {@code numen.gear.remove} 调用,经 {@link Dispatcher#serve} 交进去——和模型
 * 的工具调用同一个门。门里 {@code ServerCall.sync} 把 {@code EquipTaskRecord} 放进她身体的同步动作槽
 * ({@code TaskDispatch.runSync}),穿脱由任务槽里跑着的那个任务执行。
 *
 * <h2>时序与守恒断言</h2>活是一次性的:派下的当刻、就在 {@code serve()} 这一次调用栈里,任务槽
 * {@code TaskSlot.put} 就把它 start 掉、穿戴当场动了;回执要等下一次大脑 tick 的结算
 * ({@code CompanionBrain.shipResults})才回来。于是守恒断言夹的是<b>交易本身</b>:提交前拍一份快照,
 * {@code serve()} 返回后立刻再拍一份——两次快照之间没有任何世界活动可以插进来(同一条栈,同一个
 * tick),多重集不等就只能是这笔交易自己破了账:当场原样放回(此刻还没到下一个 tick,不存在会被
 * 误伤的战利品或新添的损伤)、记 ERROR、当场回 {@code ok=false, reason="consistency check failed"}。
 * 等回执的那一步只把工具口径的结论与此刻的真实两端送回主人,不再查账——tick 之间发生的战斗、
 * 捡拾都是真实世界,不是这笔交易的账。
 *
 * <h2>乐观并发</h2>客户端按它上次看到的样子发 {@code expectWorn}(槽上)与 {@code expectOwner}
 * (她背包里要动的那格;取下时可以不给)。合不上就不动手,回一句人话。真正的守恒断言是最后一道:
 * 「点名的那格穿戴位 + 她背包 36 格」的物品件数与组件多重集必须相等——穿戴只该在两边之间搬东西,
 * 不多不少。口径说明:穿戴实际动的只有<b>她的</b>背包({@code body.getInventory()})与她的穿戴位置,
 * 主人的东西从头到尾不在这笔账里。同名多格(如饰品栏的两个 ring)按来源给出的固定顺序取第一格为准,
 * 与 {@code <worn>} 的排列一致;真正派单只带物品 id,同名不同组件的多格穿上的可能是另一份——这是
 * 那扇门({@code numen.gear.wear} 以物品为参数)的口径,面板按回执的两端现状重绘,看到的仍是真值。
 *
 * <h2>txId 与走失的回执</h2>两表都按 (主人, txId) 复合键控:txId 只在一位主人的名下唯一,谁的号
 * 毒不到别人。正常回执在下一两个 tick 就到;她死亡走的是「丢掉任务、不发工具结果」
 * ({@code TaskSlot.dropNoResult}),回执永远不来——{@link #sweep} 在每笔提交入口把这类走失的笔清账、
 * 补一张失败回执,那个 txId 从此重放得到这句失败,而不是永远「正在办理」。只在服务端主线程调用与回调。
 *
 * <h2>M3:两边的背包之间搬东西(dir 2 取回 / 3 交给 / 4 交换)</h2>五个方向共用同一张 tx 表、同一道
 * 「身体在、世界在」的门、同一套「现状对不上就不动手」的指纹,守恒断言的口径从「她这边」扩成
 * 「两边合计」:前后「她的 36 格 + 主人的 36 格」的件数与组件多重集相等。寻址照
 * {@code docs/hud-replace-draft.md} 的 {@code inv:<0-35>}(0–8 快捷栏、9–35 主背包;两边物品栏同构,
 * 同一套编号),三种方向的两侧由方向本身定死(取回:她的 n → 我的 m;交给:我的 m → 她的 n;交换:
 * 她的 n ⇄ 我的 m)。指纹扩成「期望源格内容 + 期望目的格内容」:任何一头与面板看见的样子合不上,
 * 一格都不动。转移<b>不经任务槽</b>:既有的 {@code TransferCompanionTask} 的参数
 * ({@code ContainerOps.Move})是她打开的那张界面里的格下标,哪张菜单都同时装不下两边的物品栏,
 * 三个方向都表达不了(缺的是一张两侧物品栏同席的界面);而转移的成败在本条调用栈里就定了——
 * 动手前拍两边的快照、动手后立刻再拍一份,两次快照夹住的就是这笔交易,不等就两边原样放回。
 * 没有等回执的在飞窗口,所以不走 {@code Flight},回执当场发、txId 当场记档;死在提交当刻的身体
 * 在最前面那道门就拒了。<b>正因为它不排队,动手前另加一道互斥</b>:{@code ServerPrograms.running}
 * 为真(她正在服务端跑程序)就拒,不让两边的背包改动与程序的动作交错;穿脱不走这道门,它经
 * {@code Dispatcher.serve} 交给任务槽,和程序本来就在同一条队里。
 *
 * <h2>M4:背包 ⇄ 物品自带容器(dir 5 取出 / 6 放入,协议 3)</h2>寻址换成 SlotRefs 的字符串家族
 * (一端 {@code box:} 地址,另一端 {@code my:inv:<n>}/{@code her:inv:<n>};她的背包格也收,容器操作
 * 本就经主人发起),其余骨架与 M3 转移<b>同一条</b>:sweep → txId 重放查(同一张 {@code OwnerTx} 表)
 * → 身体门 → 程序互斥(口径比 M3 精确:操作的任一端落在她那边才查,纯我这边不查——她的程序动不到
 * 我的背包和我的盒)→ resolve({@code ContainerBoxes}:锚点、能力、深度)→ 三道指纹(expectBox 钉
 * 容器整堆、盒内那格与背包那格各钉一格)→ 收放判定 → 动手前拍「她 36 + 我 36 + 盒内」三方快照、
 * 动手后立刻再拍({@code ContainerBoxes.boxAccount},锚点堆那一项换守恒掩蔽键)、不等就三方原样放回。
 * 容器操作也不排队:没有打开的菜单可挂(面板是自绘屏幕,不是 AbstractContainerMenu,M3 走同步路径
 * 撞的就是这堵墙),成败在提交当刻就定——回执当场发、txId 当场记档,不走 {@code Flight}。
 * 互斥拦不住的并发与 M3 对主人自己 GUI 的让步同一级:现状指纹(expectBox/expectFrom/expectTo)挡住
 * 绝大多数。读(打开容器看内容)是另一条无事务的路,在 {@code ContainerBoxes.read},载荷
 * {@code BoxReadRequest}/{@code BoxReadResult}。深度上限、守恒口径、读权限的落点见
 * {@code ContainerBoxes} 的类注释;失败清单(spec §五)的语句原样在 {@code ContainerBoxes} 的常量与
 * 构句器里。
 */
public final class GearSwapService {

    private GearSwapService() {}

    /** 记住的最近结果数:重放只在这窗口里原样回。 */
    private static final int REMEMBERED = 64;

    /** 一笔在飞多久还没等到回执就视为走失(正常回执在下一两个 tick 就到)。 */
    private static final long STALE_MS = 10_000;

    /** 一条事务的结论。 */
    private record Verdict(boolean ok, String reason) {}

    /** 两表的键:(主人, txId)——txId 只在一位主人的名下唯一。 */
    private record OwnerTx(UUID owner, long txId) {}

    /**
     * 回执上的两端现状:穿戴(dir 0/1)是点名的穿戴位 + 钉住的那格背包格;转移(dir 2/3/4)是
     * (fromAfter, toAfter)——源格与目的格之后的现状(与请求的 from/to 同序)。
     */
    private record Ends(ItemStack worn, ItemStack owner) {
        static final Ends NONE = new Ends(ItemStack.EMPTY, ItemStack.EMPTY);
    }

    /** 动手前后的现状:点名的那一个穿戴位 + 她背包 36 格,都是拷贝。 */
    private record Snapshot(String slotName, ItemStack worn, List<ItemStack> backpack) {}

    /**
     * M3 转移寻址的一格:哪一边({@code hers} = 她的背包,否则主人的背包)+ {@code inv:<0-35>} 编号
     * (docs/hud-replace-draft.md 的 SlotRef;两边的物品栏同构,同一套编号)。
     */
    private record Ref(boolean hers, int index) {}

    /** 已受理、还没等到回执的一笔。 */
    private record Flight(MinecraftServer server, UUID body, String slotName, int ownerSlot,
                          ItemStack moved, long submittedMs) {}

    /** (主人,txId) → 结论,最近 {@link #REMEMBERED} 个;重放原样回,不重新动手。 */
    private static final Map<OwnerTx, Verdict> LAST_RESULT = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<OwnerTx, Verdict> eldest) {
            return size() > REMEMBERED;
        }
    };

    /**
     * (主人,txId) → 在飞的一笔。正常回执下一两个 tick 就到;死亡那一路的回执永远不来,靠两道
     * 保险收口:每笔提交入口先 {@link #sweep} 把走失的清账补回执,超龄的最老一笔在此兜底淘汰
     * (年轻的在飞笔绝不挤掉)。所以本表有界,一笔走失也不会把那个 txId 永久毒化。
     */
    private static final Map<OwnerTx, Flight> FLIGHT = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<OwnerTx, Flight> eldest) {
            return size() > REMEMBERED
                    && System.currentTimeMillis() - eldest.getValue().submittedMs() > STALE_MS;
        }
    };

    // ---------------------------------------------------------------------
    // 进
    // ---------------------------------------------------------------------

    /** 收件人不是它的主人:过不了信任门。按(主人,txId)记,毒不到别人的号;她的现状无从读起,两端照实留空。 */
    public static void rejectNotOwned(long txId, ServerPlayer player) {
        refuse(player, null, txId, "", ItemStack.EMPTY,
                new Verdict(false, "that companion is not yours"));
    }

    /**
     * 面板的一条穿脱/转移请求:校验按序判,任一不过就回一句人话、什么也不动。穿戴(dir 0/1)全过了才
     * 拍快照、经 {@link Dispatcher#serve} 把活交给任务槽;转移(dir 2/3/4)在 {@link #transfer} 里同一
     * 条调用栈内动手收尾。主线程。
     */
    public static void submit(ServerPlayer owner, NumenPlayer body, GearSwapRequest req) {
        sweep();
        OwnerTx key = new OwnerTx(owner.getUUID(), req.txId());
        boolean transfer = req.dir() == GearSwapRequest.TAKE_BACK || req.dir() == GearSwapRequest.GIVE
                || req.dir() == GearSwapRequest.SWAP;
        boolean box = req.dir() == GearSwapRequest.BOX_TAKE || req.dir() == GearSwapRequest.BOX_PUT;
        String slotName = req.slot() == null ? "" : req.slot().trim().toLowerCase(Locale.ROOT);

        // ① txId 见过:原样回上次的结论,两端按此刻现状如实附上(不是上次那一刻的)。
        Verdict remembered = LAST_RESULT.get(key);
        if (remembered != null) {
            reply(owner, req.txId(), remembered, endsNow(owner, body, req, slotName, transfer, box));
            return;
        }
        if (FLIGHT.containsKey(key)) {
            reply(owner, req.txId(), new Verdict(false, "a request with this id is still being carried out"),
                    endsNow(owner, body, req, slotName, transfer, box));
            return;
        }

        boolean wear = req.dir() == GearSwapRequest.WEAR;
        if (req.dir() != GearSwapRequest.WEAR && req.dir() != GearSwapRequest.REMOVE && !transfer && !box) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "unknown direction " + req.dir() + " (0 = wear, 1 = take off, "
                            + "2 = take back, 3 = give, 4 = swap, "
                            + "5 = take out of a container, 6 = put into a container)"));
            return;
        }

        // ② 身体在加载区、未死亡。六个方向同一道门。
        if (body.isDeadOrDying() || !body.isAlive()) {
            refuse(owner, body, req.txId(),
                    new Verdict(false, "she is dead — nothing to wear or take off right now"),
                    endsNow(owner, body, req, slotName, transfer, box));
            return;
        }
        if (!body.level().isLoaded(body.blockPosition())) {
            refuse(owner, body, req.txId(),
                    new Verdict(false, "she is in unloaded chunks — bring her back into view first"),
                    endsNow(owner, body, req, slotName, transfer, box));
            return;
        }

        if (transfer) {
            // 转移在同一条调用栈里动手(不经任务槽),所以没有别的东西替它排队:她正在跑程序时
            // 直接拒,别让两边的背包改动与程序的动作交错。穿脱(dir 0/1)不用这道门——它经
            // Dispatcher.serve 交给任务槽,和程序本来就在同一条队里。
            if (ServerPrograms.running(body.getUUID())) {
                refuse(owner, body, req.txId(),
                        new Verdict(false, "she is running a program on the server — let it finish"
                                + " (or stop it) before moving items between the two backpacks"),
                        endsNow(owner, body, req, slotName, transfer, box));
                return;
            }
            transfer(owner, body, key, req);
            return;
        }
        if (box) {
            // M4 容器操作:同一条调用栈动手收尾,互斥在 boxTransfer 里按解析出的侧别判(任一端落在
            // 她那边才查)。五个方向同一道身体门已经过完。
            boxTransfer(owner, body, key, req);
            return;
        }

        // ③ 该槽 writable:得是某个 GearSource 登记过的位置。
        GearSlot target = null;
        for (GearSlot s : NumenPlugins.gearSlots(body)) {
            if (s.name().equals(slotName)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "she has no slot named '" + slotName
                            + "' — only slots a gear source registered can be worn or taken off"));
            return;
        }

        // ④ 槽上还是客户端看见的那件。
        if (!ItemStack.matches(target.worn(), req.expectWorn())) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "her '" + slotName
                            + "' slot is not what you saw — something changed; refresh and try again"));
            return;
        }

        // ⑤ 背包里还是客户端看见的那件(穿上时必给;取下时可不给,给了也要对得上)。
        Inventory inv = body.getInventory();
        int ownerSlot = pinSlot(inv, req.expectOwner());
        if (wear && req.expectOwner().isEmpty()) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "no item was named to wear"));
            return;
        }
        if (!req.expectOwner().isEmpty() && ownerSlot < 0) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "that item is no longer in her backpack where you saw it — something "
                            + "changed; refresh and try again"));
            return;
        }

        // ⑥ 这一格收得进 / 摘得下:理由原样给人。
        if (wear) {
            var refusal = target.refuseWear(req.expectOwner().copyWithCount(1));
            if (refusal.isPresent()) {
                refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                        new Verdict(false, refusal.get()));
                return;
            }
        } else {
            var refusal = target.refuseRemove();
            if (refusal.isPresent()) {
                refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                        new Verdict(false, refusal.get()));
                return;
            }
        }

        // ⑦ 取下要有地方放;穿上要有那件东西。
        if (wear) {
            boolean have = false;
            for (int i = 0; i < inv.items.size(); i++) {
                if (!inv.items.get(i).isEmpty() && inv.items.get(i).is(req.expectOwner().getItem())) {
                    have = true;
                    break;
                }
            }
            if (!have) {
                refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                        new Verdict(false, "no " + idOf(req.expectOwner()) + " in her backpack to equip"));
                return;
            }
        } else if (inv.getFreeSlot() < 0) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "her backpack is full — no room for what comes off"));
            return;
        }

        // 全过:拍下动手前的快照,经 numen.gear.* 那扇门交给任务槽;serve 返回后立刻再拍一份——
        // 两次快照夹住的就是交易本身(见类注释「时序与守恒断言」)。回执在下一次大脑 tick 的结算里回来。
        Snapshot before = snapshot(body, slotName);
        Dispatcher.serve(wear ? "numen.gear.wear" : "numen.gear.remove",
                argsOf(wear, slotName, req), body, "gearswap-" + req.txId(),
                replyJson -> conclude(key, replyJson));
        Snapshot mid = snapshot(body, slotName);
        if (!conservationMultiset(mid).equals(conservationMultiset(before))) {
            // 交易破了账:此刻还在同一个 tick 里,原样放回不会误伤任何战利品或损伤。当场记 ERROR、当场回执。
            Constants.LOG.error("[numen-gear] tx {} broke conservation ({} entries before, {} after the swap)"
                    + " — rolled back", req.txId(), countOf(before), countOf(mid));
            rollback(body, before);
            Verdict broken = new Verdict(false, "consistency check failed");
            LAST_RESULT.put(key, broken);
            reply(owner, req.txId(), broken, endsNow(body, slotName, req.expectOwner()));
            return;
        }
        FLIGHT.put(key, new Flight(body.server, body.getUUID(), slotName, ownerSlot,
                (wear ? req.expectOwner() : req.expectWorn()).copy(), System.currentTimeMillis()));
    }

    /** 一条经门而入的调用参数:穿上点名物品与槽;取下点名槽与要摘的那件。 */
    private static JsonObject argsOf(boolean wear, String slotName, GearSwapRequest req) {
        JsonObject args = new JsonObject();
        if (wear) {
            args.addProperty("item", idOf(req.expectOwner()));
            if (!slotName.isEmpty()) {
                args.addProperty("slot", slotName);
            }
        } else {
            args.addProperty("slot", slotName);
            if (!req.expectWorn().isEmpty()) {
                args.addProperty("item", idOf(req.expectWorn()));
            }
        }
        return args;
    }

    // ---------------------------------------------------------------------
    // M3:两边的背包之间搬东西(dir 2 取回 / 3 交给 / 4 交换)
    // ---------------------------------------------------------------------

    /**
     * 三个转移方向:范围、两侧指纹、收得下,按序判,任一不过就回一句人话、两边的物品一格都不动;
     * 全过了拍两边的快照、当场动手、立刻再拍一份——两次快照夹住的就是这笔交易,「两边合计」的件数
     * 与组件多重集不等就原样放回(此刻还在同一个 tick 里,不存在会被误伤的战利品或新添的损伤)。
     * 成败在本条调用栈里就定了:没有在飞窗口,回执当场发、txId 当场记档(见类注释「M3」一节)。
     */
    private static void transfer(ServerPlayer owner, NumenPlayer body, OwnerTx key, GearSwapRequest req) {
        Ref from = fromRef(req);
        Ref to = toRef(req);
        Inventory fromInv = invOf(owner, body, from);
        Inventory toInv = invOf(owner, body, to);
        String fromSide = from.hers() ? "her" : "your";
        String toSide = to.hers() ? "her" : "your";

        // ③ 两格都得存在:inv:<0-35>,两边的物品栏同构(36 格)。
        if (from.index < 0 || from.index >= fromInv.items.size()) {
            refuse(owner, body, req.txId(), new Verdict(false, fromSide + " side has no slot " + from.index
                    + " — both backpacks have 36 slots each, numbered inv:0-35"), transferEnds(owner, body, req));
            return;
        }
        if (to.index < 0 || to.index >= toInv.items.size()) {
            refuse(owner, body, req.txId(), new Verdict(false, toSide + " side has no slot " + to.index
                    + " — both backpacks have 36 slots each, numbered inv:0-35"), transferEnds(owner, body, req));
            return;
        }

        // ④ 两侧指纹:源格、目的格都得还是面板看见的那件,任一不合就什么都不动。
        if (!ItemStack.matches(fromInv.items.get(from.index), req.expectFrom())) {
            refuse(owner, body, req.txId(), new Verdict(false, fromSide + " slot " + from.index
                    + " is not what you saw — something changed; refresh and try again"), transferEnds(owner, body, req));
            return;
        }
        if (!ItemStack.matches(toInv.items.get(to.index), req.expectTo())) {
            refuse(owner, body, req.txId(), new Verdict(false, toSide + " slot " + to.index
                    + " is not what you saw — something changed; refresh and try again"), transferEnds(owner, body, req));
            return;
        }

        ItemStack src = fromInv.items.get(from.index);
        ItemStack dst = toInv.items.get(to.index);
        String verb = req.dir() == GearSwapRequest.GIVE ? "give" : "take back";

        // ⑤ 收得下:取回/交给要源格有东西、目的格空着或同一件还放得下;交换照原样换,只拒两边全空。
        if (req.dir() == GearSwapRequest.SWAP) {
            if (src.isEmpty() && dst.isEmpty()) {
                refuse(owner, body, req.txId(),
                        new Verdict(false, "both slots are empty — nothing to swap"), transferEnds(owner, body, req));
                return;
            }
        } else {
            if (src.isEmpty()) {
                refuse(owner, body, req.txId(), new Verdict(false, fromSide + " slot " + from.index
                        + " is empty — nothing to " + verb), transferEnds(owner, body, req));
                return;
            }
            if (!dst.isEmpty() && !ItemStack.isSameItemSameTags(dst, src)) {
                refuse(owner, body, req.txId(), new Verdict(false, toSide + " slot " + to.index + " holds "
                        + idOf(dst) + " — " + verb + " needs an empty or matching slot; a swap would trade "
                        + "the two instead"), transferEnds(owner, body, req));
                return;
            }
            if (!dst.isEmpty() && dst.getCount() + src.getCount() > dst.getMaxStackSize()) {
                refuse(owner, body, req.txId(), new Verdict(false, toSide + " slot " + to.index + " holds "
                        + dst.getCount() + " " + idOf(dst) + " and a stack tops out at "
                        + dst.getMaxStackSize() + " — " + src.getCount() + " more will not fit"), transferEnds(owner, body, req));
                return;
            }
        }

        // ⑥ 动手:前后两份快照夹住这笔账;不等就两边原样放回,当场记 ERROR、当场回执。
        List<ItemStack> fromBefore = copyItems(fromInv);
        List<ItemStack> toBefore = copyItems(toInv);
        ItemStack moved = src.copy();
        if (req.dir() == GearSwapRequest.SWAP) {
            fromInv.setItem(from.index, dst.copy());
            toInv.setItem(to.index, moved);
        } else {
            if (dst.isEmpty()) {
                toInv.setItem(to.index, moved);
            } else {
                dst.grow(moved.getCount());   // 同物品同组件,量已在 ⑤ 验过放得下
                toInv.setChanged();
            }
            fromInv.setItem(from.index, ItemStack.EMPTY);
        }
        List<ItemStack> fromAfter = copyItems(fromInv);
        List<ItemStack> toAfter = copyItems(toInv);
        if (!unionMultiset(fromAfter, toAfter).equals(unionMultiset(fromBefore, toBefore))) {
            Constants.LOG.error("[numen-gear] tx {} broke conservation across both backpacks "
                            + "({} entries before, {} after the transfer) — rolled back",
                    req.txId(), countOf(fromBefore, toBefore), countOf(fromAfter, toAfter));
            restore(fromInv, fromBefore);
            restore(toInv, toBefore);
            Verdict broken = new Verdict(false, "consistency check failed");
            LAST_RESULT.put(key, broken);
            reply(owner, req.txId(), broken, transferEnds(owner, body, req));
            return;
        }

        // ⑦ 成了:记档、回执。回执的两端按 (fromAfter, toAfter) 走 GearSwapResult 的两个堆字段。
        Verdict done = new Verdict(true, describeTransfer(req.dir(), from, to, moved,
                fromAfter.get(from.index), toAfter.get(to.index)));
        LAST_RESULT.put(key, done);
        reply(owner, req.txId(), done,
                new Ends(fromAfter.get(from.index).copy(), toAfter.get(to.index).copy()));
    }

    /** 源格在哪一边:取回与交换从她那格起,交给从我这格起(docs/hud-replace-draft.md 12.3 的表)。 */
    private static Ref fromRef(GearSwapRequest req) {
        return new Ref(req.dir() != GearSwapRequest.GIVE, req.fromSlot());
    }

    /** 目的格在哪一边:取回与交换落在我这格,交给落在她那格。 */
    private static Ref toRef(GearSwapRequest req) {
        return new Ref(req.dir() == GearSwapRequest.GIVE, req.toSlot());
    }

    private static Inventory invOf(ServerPlayer owner, NumenPlayer body, Ref ref) {
        return ref.hers() ? body.getInventory() : owner.getInventory();
    }

    /** 一张物品栏 36 格的拷贝,给守恒断言夹前后。 */
    private static List<ItemStack> copyItems(Inventory inv) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < inv.items.size(); i++) {
            out.add(inv.items.get(i).copy());
        }
        return out;
    }

    /** 两边合计的守恒多重集:键是「物品 id + 组件」,值是件数。跨两方的守恒 = 前后两个多重集相等。 */
    private static Map<String, Integer> unionMultiset(List<ItemStack> hers, List<ItemStack> mine) {
        Map<String, Integer> out = new TreeMap<>();
        for (ItemStack stack : hers) {
            merge(out, stack);
        }
        for (ItemStack stack : mine) {
            merge(out, stack);
        }
        return out;
    }

    private static int countOf(List<ItemStack> hers, List<ItemStack> mine) {
        return unionMultiset(hers, mine).size();
    }

    /** 断言不成立后的原样放回:只动两边背包 36 格——穿戴位一概不碰。 */
    private static void restore(Inventory inv, List<ItemStack> before) {
        for (int i = 0; i < before.size() && i < inv.items.size(); i++) {
            inv.setItem(i, before.get(i).copy());
        }
    }

    /** 转移的一句人话:动了哪边哪格、多少件什么、落到了哪边哪格(并堆就说明并进了那一叠)。 */
    private static String describeTransfer(byte dir, Ref from, Ref to, ItemStack moved,
                                           ItemStack fromAfter, ItemStack toAfter) {
        String what = moved.getCount() + " " + idOf(moved);
        String fromSide = from.hers() ? "her slot " + from.index : "your slot " + from.index;
        String toSide = to.hers() ? "her slot " + to.index : "your slot " + to.index;
        if (dir == GearSwapRequest.SWAP) {
            if (fromAfter.isEmpty()) {
                return "moved " + what + " from " + fromSide + " to " + toSide
                        + " (" + fromSide + " is now empty)";
            }
            if (toAfter.isEmpty()) {
                return "moved " + what + " from " + toSide + " to " + fromSide
                        + " (" + toSide + " is now empty)";
            }
            return "swapped " + fromSide + " (" + idOf(toAfter) + ") with " + toSide + " ("
                    + idOf(fromAfter) + ")";
        }
        String verb = dir == GearSwapRequest.GIVE ? "gave " : "took ";
        return verb + what + " from " + fromSide + " to " + toSide
                + (toAfter.getCount() == moved.getCount() ? ""
                : " (merged into the stack there, now " + toAfter.getCount() + ")");
    }

    // ---------------------------------------------------------------------
    // M4:背包 ⇄ 物品自带容器(dir 5 取出 / 6 放入,协议 3)
    // ---------------------------------------------------------------------

    /**
     * 两个容器方向:骨架与 {@link #transfer} 同一条——解析、互斥、resolve、指纹、收放按序判,任一
     * 不过就回一句人话、三方一格不动;全过了拍「她 36 + 我 36 + 盒内」的快照、当场动手、立刻再拍,
     * 三方合计(锚点堆那一项换守恒掩蔽键,见 {@code ContainerBoxes.boxAccount})不等就三方原样放回。
     * 判序与语句照 spec-m4-containers §五的表(1 地址 → 2 互斥 → 3-5 resolve 与深度 → 6-8 指纹 →
     * 9-11 收放 → 12 守恒)。成败在本条调用栈里就定了:回执当场发、txId 当场记档,没有在飞窗口。
     */
    private static void boxTransfer(ServerPlayer owner, NumenPlayer body, OwnerTx key, GearSwapRequest req) {
        boolean take = req.dir() == GearSwapRequest.BOX_TAKE;
        String boxRefText = take ? req.fromRef() : req.toRef();
        String bagRefText = take ? req.toRef() : req.fromRef();

        // ① 地址:一端必须是 box:,另一端必须是带侧别的背包格;盒内格 ⇄ 盒内格、裸 inv:<n> 都不合文法。
        SlotRefs.Parsed boxRef = SlotRefs.parse(boxRefText);
        SlotRefs.Parsed bagRef = SlotRefs.parse(bagRefText);
        Ends before = boxEndsNow(owner, body, req);
        boolean boxEnd = boxRef.error() == null && boxRef.boxInner() >= 0;
        boolean bagEnd = bagRef.error() == null && bagRef.boxInner() < 0 && bagRef.invIndex() >= 0;
        if (!boxEnd || !bagEnd) {
            refuse(owner, body, req.txId(), new Verdict(false, SlotRefs.PARSE_REFUSAL), before);
            return;
        }

        // ② 程序互斥:操作的任一端落在她那边(她的背包格,或锚点在她的背包/穿戴格)且她在跑程序才拒;
        // 纯我这边的容器操作(我的格 ⇄ 我的盒)不查——她的程序动不到我的背包和我的盒。
        // gear 锚点恒为她的(ContainerBoxes.resolve 的 gear 分支固定 BoxAnchor(true,…)):holder 词写
        // box:my::gear:… 也一样落在他身上,所以侧别判据必须把 gearName 算上,否则构造地址可绕过这道门
        // (面板永不拼出这种地址,纯改包可达)。
        boolean touchesHer = boxRef.hers() || boxRef.gearName() != null || bagRef.hers();
        if (touchesHer && ServerPrograms.running(body.getUUID())) {
            refuse(owner, body, req.txId(), new Verdict(false, ContainerBoxes.PROGRAM_BUSY), before);
            return;
        }

        // ③ resolve:锚点格有东西、开得成容器(潜影盒或物品级物品栏能力),§五 #3/#4 原句。
        ContainerBoxes.Resolve resolved = ContainerBoxes.resolve(owner, body, boxRefText);
        if (resolved.error() != null) {
            refuse(owner, body, req.txId(), new Verdict(false, resolved.error()), before);
            return;
        }

        // ④ 深度:放进容器的载荷堆自身也是容器就拒,空满不看(§3.1;读路径没有载荷堆,不判这条)。
        Inventory bagInv = bagRef.hers() ? body.getInventory() : owner.getInventory();
        int bagIndex = bagRef.invIndex();
        if (!take) {
            String depth = ContainerBoxes.refusePayload(bagInv.items.get(bagIndex));
            if (depth != null) {
                refuse(owner, body, req.txId(), new Verdict(false, depth), before);
                return;
            }
        }

        // ⑤ 三道指纹:容器整堆(expectBox,含内容 NBT)→ 内格序号 → 内格现状;再背包端的存在性与指纹,
        // 判据与 M3 转移的两侧指纹同一条。任一不合就一格不动。
        ContainerBox box = resolved.box();
        int inner = boxRef.boxInner();
        if (!ItemStack.matches(resolved.stack(), req.expectBox())) {
            refuse(owner, body, req.txId(), new Verdict(false, ContainerBoxes.STALE_BOX), before);
            return;
        }
        if (inner >= box.slots()) {
            refuse(owner, body, req.txId(),
                    new Verdict(false, ContainerBoxes.noInnerSlot(inner, box.slots())), before);
            return;
        }
        ItemStack expectInner = take ? req.expectFrom() : req.expectTo();
        ItemStack expectBag = take ? req.expectTo() : req.expectFrom();
        if (!ItemStack.matches(box.slot(inner), expectInner)) {
            refuse(owner, body, req.txId(), new Verdict(false, ContainerBoxes.staleInner(inner)), before);
            return;
        }
        String bagSide = bagRef.hers() ? "her" : "your";
        if (bagIndex >= bagInv.items.size()) {
            refuse(owner, body, req.txId(), new Verdict(false, bagSide + " side has no slot " + bagIndex
                    + " — both backpacks have 36 slots each, numbered inv:0-35"), before);
            return;
        }
        ItemStack bagCur = bagInv.items.get(bagIndex);
        if (!ItemStack.matches(bagCur, expectBag)) {
            refuse(owner, body, req.txId(), new Verdict(false, bagSide + " slot " + bagIndex
                    + " is not what you saw — something changed; refresh and try again"), before);
            return;
        }

        // ⑥ 收放判定:盒的一侧问 provider(§五 #9–#11 原句从 provider 回),背包的一侧照 M3 转移的
        // 「收得下」同一条。放进去的量与堆不换:盒子不换东西,只收放。
        if (take) {
            var refusal = box.refuseTake(inner);
            if (refusal.isPresent()) {
                refuse(owner, body, req.txId(), new Verdict(false, refusal.get()), before);
                return;
            }
            if (!bagCur.isEmpty() && !ItemStack.isSameItemSameTags(bagCur, box.slot(inner))) {
                refuse(owner, body, req.txId(), new Verdict(false, bagSide + " slot " + bagIndex + " holds "
                        + idOf(bagCur) + " — taking out of a box needs an empty or matching destination slot"), before);
                return;
            }
            if (!bagCur.isEmpty() && bagCur.getCount() + box.slot(inner).getCount() > bagCur.getMaxStackSize()) {
                refuse(owner, body, req.txId(), new Verdict(false, bagSide + " slot " + bagIndex + " holds "
                        + bagCur.getCount() + " " + idOf(bagCur) + " and a stack tops out at "
                        + bagCur.getMaxStackSize() + " — " + box.slot(inner).getCount() + " more will not fit"), before);
                return;
            }
        } else {
            if (bagCur.isEmpty()) {
                refuse(owner, body, req.txId(), new Verdict(false, bagSide + " slot " + bagIndex
                        + " is empty — nothing to put into the box"), before);
                return;
            }
            var refusal = box.refusePut(inner, bagCur);
            if (refusal.isPresent()) {
                refuse(owner, body, req.txId(), new Verdict(false, refusal.get()), before);
                return;
            }
        }

        // ⑦ 动手:三方口径(她 36 + 我 36 + 盒内)的前后两份快照夹住这笔账;gear 锚点的堆由
        // boxAccount 经 worn() 现读,两次现读都在本条调用栈里、同一个 tick,之间只夹着这笔交易自己。
        // 不等就三方原样放回(盒内格经 set 逐格放回),当场记 ERROR、当场回执。
        List<ItemStack> herBefore = copyItems(body.getInventory());
        List<ItemStack> myBefore = copyItems(owner.getInventory());
        List<ItemStack> innerBefore = copyBox(box);
        Map<String, Integer> account = ContainerBoxes.boxAccount(herBefore, myBefore, resolved.anchor(), box);
        if (take) {
            ItemStack out = box.take(inner);
            if (bagCur.isEmpty()) {
                bagInv.setItem(bagIndex, out);
            } else {
                bagCur.grow(out.getCount());   // 量已在 ⑥ 验过放得下
            }
        } else {
            ItemStack in = bagCur.copy();
            bagInv.setItem(bagIndex, ItemStack.EMPTY);
            box.put(inner, in);
        }
        box.touched();
        bagInv.setChanged();
        Map<String, Integer> after = ContainerBoxes.boxAccount(
                copyItems(body.getInventory()), copyItems(owner.getInventory()), resolved.anchor(), box);
        if (!after.equals(account)) {
            Constants.LOG.error("[numen-box] tx {} broke conservation across her backpack, your backpack "
                            + "and the box ({} entries before, {} after the box move) — rolled back",
                    req.txId(), account.size(), after.size());
            restore(body.getInventory(), herBefore);
            restore(owner.getInventory(), myBefore);
            restoreBox(box, innerBefore);
            box.touched();
            body.getInventory().setChanged();
            owner.getInventory().setChanged();
            Verdict broken = new Verdict(false, "consistency check failed");
            LAST_RESULT.put(key, broken);
            reply(owner, req.txId(), broken, boxEndsNow(owner, body, req));
            return;
        }

        // ⑧ 成了:记档、回执。回执两端按 from→to 序(盒内那格、背包那格);其余内格不在回执里,
        // 面板随后拉一次 BoxReadRequest 重画整盒(spec §4.1 的约定)。
        Verdict done = new Verdict(true, describeBox(req.dir(), inner, innerBefore.get(inner),
                boxRef, bagRef, box));
        LAST_RESULT.put(key, done);
        reply(owner, req.txId(), done, boxEndsNow(owner, body, req));
    }

    /** 容器操作的一句人话:动了哪一格、多少件什么、落到了哪一格(并堆就说明并进了那一叠)。 */
    private static String describeBox(byte dir, int inner, ItemStack innerBefore, SlotRefs.Parsed boxRef,
                                      SlotRefs.Parsed bagRef, ContainerBox box) {
        String where = "inner slot " + inner + " (" + anchorDesc(boxRef) + ")";
        if (dir == GearSwapRequest.BOX_TAKE) {
            String toSide = bagRef.hers() ? "her slot " + bagRef.invIndex() : "your slot " + bagRef.invIndex();
            return "took " + innerBefore.getCount() + " " + idOf(innerBefore) + " out of the box " + where
                    + " into " + toSide;
        }
        String fromSide = bagRef.hers() ? "her slot " + bagRef.invIndex() : "your slot " + bagRef.invIndex();
        ItemStack now = box.slot(inner);
        if (innerBefore.isEmpty()) {
            return "put " + now.getCount() + " " + idOf(now) + " from " + fromSide + " into the box " + where;
        }
        return "put " + (now.getCount() - innerBefore.getCount()) + " " + idOf(now) + " from " + fromSide
                + " into the box (merged into the stack in " + where + ", now " + now.getCount() + ")";
    }

    /** 锚点的一句话:盒躺在谁的哪一格上(日志与回执用语)。 */
    private static String anchorDesc(SlotRefs.Parsed boxRef) {
        if (boxRef.gearName() != null) {
            return "the box worn on her '" + boxRef.gearName() + "'";
        }
        return "the box in " + (boxRef.hers() ? "her" : "your") + " slot " + boxRef.invIndex();
    }

    /**
     * 容器操作两端此刻的现状(失败回执与重放共用):盒端 = resolve 后盒内那格(resolve 不成了照实留空),
     * 背包端 = 按侧别与下标现取。dir 5 的两端是 (盒内格, 背包格),dir 6 是 (背包格, 盒内格)。
     */
    private static Ends boxEndsNow(ServerPlayer owner, NumenPlayer body, GearSwapRequest req) {
        boolean take = req.dir() == GearSwapRequest.BOX_TAKE;
        String boxRefText = take ? req.fromRef() : req.toRef();
        String bagRefText = take ? req.toRef() : req.fromRef();
        SlotRefs.Parsed boxRef = SlotRefs.parse(boxRefText);
        SlotRefs.Parsed bagRef = SlotRefs.parse(bagRefText);
        ItemStack boxEnd = ItemStack.EMPTY;
        ItemStack bagEnd = ItemStack.EMPTY;
        if (boxRef.error() == null && boxRef.boxInner() >= 0) {
            ContainerBoxes.Resolve resolved = ContainerBoxes.resolve(owner, body, boxRefText);
            if (resolved.error() == null && boxRef.boxInner() < resolved.box().slots()) {
                boxEnd = resolved.box().slot(boxRef.boxInner()).copy();
            }
        }
        if (bagRef.error() == null && bagRef.boxInner() < 0 && bagRef.invIndex() >= 0) {
            Inventory inv = bagRef.hers() ? body.getInventory() : owner.getInventory();
            bagEnd = itemAt(inv, bagRef.invIndex());
        }
        return take ? new Ends(boxEnd, bagEnd) : new Ends(bagEnd, boxEnd);
    }

    /** 一个盒的内格拷贝,给守恒断言夹前后(与背包格的 copyItems 同款)。 */
    private static List<ItemStack> copyBox(ContainerBox box) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < box.slots(); i++) {
            out.add(box.slot(i).copy());
        }
        return out;
    }

    /** 断言不成立后的盒内原样放回:逐格覆写,调用方随后 touched 一次落盘。 */
    private static void restoreBox(ContainerBox box, List<ItemStack> before) {
        for (int i = 0; i < before.size() && i < box.slots(); i++) {
            box.set(i, before.get(i));
        }
    }

    // ---------------------------------------------------------------------
    // 收
    // ---------------------------------------------------------------------

    /** 工具调用的回执到了(主线程):把结论记下、连同此刻的真实两端发给主人。守恒断言在提交当刻已过。 */
    private static void conclude(OwnerTx key, String replyJson) {
        Flight flight = FLIGHT.remove(key);
        if (flight == null) {
            return;   // 被 sweep 清过账,或断言失败当场回执过:不再重复送。
        }
        Verdict verdict = verdictOf(replyJson);
        NumenPlayer body = NumenPlayer.findByUuid(flight.server(), flight.body());
        // 取下时提交当刻没有背包格可钉(物品还在身上),这里按内容找回它落到哪一格,
        // 否则回执的 ownerAfter 永远是空,面板要等下一份快照才显示——显示层陈旧一整拍。
        int slot = flight.ownerSlot();
        if (slot < 0 && !flight.moved().isEmpty()) {
            slot = slotOf(body, flight.moved());
        }
        conclude(key, flight.server(), verdict, endsAt(body, flight.slotName(), slot));
    }

    /** 校验不过的当场结论:记下、回执,不动任何东西。 */
    private static void refuse(ServerPlayer owner, NumenPlayer body, long txId, String slotName,
                               ItemStack expect, Verdict verdict) {
        refuse(owner, body, txId, verdict, endsNow(body, slotName, expect));
    }

    /** 校验不过的当场结论,两端现成(转移的失败路径自己算两端):记下、回执,不动任何东西。 */
    private static void refuse(ServerPlayer owner, NumenPlayer body, long txId, Verdict verdict, Ends ends) {
        conclude(new OwnerTx(owner.getUUID(), txId), owner.server, verdict, ends);
    }

    /** 记进最近结果表,把回执发给主人(不在线就算了,他重登看到的是真实状态)。 */
    private static void conclude(OwnerTx key, MinecraftServer server, Verdict verdict, Ends ends) {
        LAST_RESULT.put(key, verdict);
        ServerPlayer owner = server.getPlayerList().getPlayer(key.owner());
        if (owner != null) {
            reply(owner, key.txId(), verdict, ends);
        }
    }

    private static void reply(ServerPlayer owner, long txId, Verdict verdict, Ends ends) {
        NumenNetwork.sendToPlayer(owner, new GearSwapResult(txId, verdict.ok(), verdict.reason(),
                ends.worn(), ends.owner()));
    }

    /**
     * 把走失的在飞笔清账:她的身体没了或死了的(死亡那一路不回工具结果,回执永远不来),或超龄没
     * 等到回执的——补一张失败回执、记进 {@link #LAST_RESULT},那个 txId 从此重放得到这句失败,
     * 而不是永远「正在办理」。submit 入口每笔都先过这里。主线程。
     */
    private static void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<OwnerTx, Flight> entry : new ArrayList<>(FLIGHT.entrySet())) {
            Flight flight = entry.getValue();
            NumenPlayer body = NumenPlayer.findByUuid(flight.server(), flight.body());
            boolean dead = body != null && (body.isDeadOrDying() || !body.isAlive());
            if (!dead && now - flight.submittedMs() <= STALE_MS) {
                continue;
            }
            if (FLIGHT.remove(entry.getKey()) == null) {
                continue;   // 这一刻刚被收过尾
            }
            String why = body == null ? "she is gone — the swap never finished"
                    : dead ? "she died — the swap did not finish"
                    : "the swap's receipt never arrived — look her over, then retry";
            conclude(entry.getKey(), flight.server(), new Verdict(false, why),
                    endsAt(body, flight.slotName(), flight.ownerSlot()));
        }
    }

    /** 工具回执(线上 JSON)读成结论:失败拿它的那句人话;成功按它交回的账组一句。 */
    private static Verdict verdictOf(String replyJson) {
        ApiReply.Parsed parsed;
        try {
            parsed = ApiReply.parse(replyJson);
        } catch (RuntimeException broke) {
            Constants.LOG.error("[numen-gear] unreadable reply from the gear path: {}", replyJson, broke);
            return new Verdict(false, "the gear path answered something unreadable");
        }
        if (!parsed.ok()) {
            Object message = parsed.error().get("message");
            return new Verdict(false, message == null ? "the swap failed" : String.valueOf(message));
        }
        return new Verdict(true, describe(parsed.value()));
    }

    /** 成功的回执值({@code Wardrobe.Change} 的线上形状)翻成一句人话。 */
    private static String describe(Object value) {
        if (!(value instanceof Map<?, ?> change)) {
            return "done";
        }
        Object slot = change.get("slot");
        if (slot != null) {
            Object item = change.get("item");
            return item == null ? "equipped in " + slot : "equipped " + item + " in " + slot;
        }
        List<String> off = strings(change.get("removed"));
        List<String> kept = strings(change.get("stillWorn"));
        if (off.isEmpty() && kept.isEmpty()) {
            return "nothing to take off — already empty";
        }
        StringBuilder out = new StringBuilder();
        if (!off.isEmpty()) {
            out.append("took off ").append(String.join(", ", off));
        }
        if (!kept.isEmpty()) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append("still wearing ").append(String.join(", ", kept));
        }
        return out.toString();
    }

    private static List<String> strings(Object list) {
        List<String> out = new ArrayList<>();
        if (list instanceof List<?> items) {
            for (Object o : items) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // 快照、守恒多重集、回滚
    // ---------------------------------------------------------------------

    /** 两边现状:点名的那一个穿戴位 + 她背包 36 格,全是拷贝。 */
    private static Snapshot snapshot(NumenPlayer body, String slotName) {
        ItemStack worn = ItemStack.EMPTY;
        for (GearSlot s : NumenPlugins.gearSlots(body)) {
            if (s.name().equals(slotName)) {
                worn = s.worn().copy();
                break;
            }
        }
        List<ItemStack> backpack = new ArrayList<>();
        Inventory inv = body.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            backpack.add(inv.items.get(i).copy());
        }
        return new Snapshot(slotName, worn, backpack);
    }

    /** 一份快照的守恒多重集 (conservation multiset):键是「物品 id + 组件」,值是件数。穿戴守恒 = 前后两个多重集相等。 */
    private static Map<String, Integer> conservationMultiset(Snapshot snapshot) {
        Map<String, Integer> out = new TreeMap<>();
        merge(out, snapshot.worn());
        for (ItemStack stack : snapshot.backpack()) {
            merge(out, stack);
        }
        return out;
    }

    private static void merge(Map<String, Integer> multiset, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        multiset.merge(stack.getItem() + "#" + (stack.getTag() == null ? "" : stack.getTag()),
                stack.getCount(), Integer::sum);
    }

    private static int countOf(Snapshot snapshot) {
        return conservationMultiset(snapshot).size();
    }

    /** 断言不成立后的原样放回:只动点名的那一个穿戴位与背包 36 格——别的穿戴位(含护甲已有的损伤)一概不碰。 */
    private static void rollback(NumenPlayer body, Snapshot before) {
        for (GearSlot s : NumenPlugins.gearSlots(body)) {
            if (s.name().equals(before.slotName())) {
                s.swap(before.worn().copy());
                break;
            }
        }
        Inventory inv = body.getInventory();
        for (int i = 0; i < before.backpack().size() && i < inv.items.size(); i++) {
            inv.setItem(i, before.backpack().get(i).copy());
        }
        inv.setChanged();
    }

    /** 按内容找一格:只看「同物品同组件」,不看数量(卸下的那件可能与已有堆叠并成一格)。 */
    private static int slotOf(NumenPlayer body, ItemStack moved) {
        if (body == null) {
            return -1;
        }
        Inventory inv = body.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack one = inv.items.get(i);
            if (!one.isEmpty() && ItemStack.isSameItemSameTags(one, moved)) {
                return i;
            }
        }
        return -1;
    }

    /** 两端现状:点名的穿戴位 + 按下标钉住的那格背包格(下标无效为空)。身体没了照实留空。 */
    private static Ends endsAt(NumenPlayer body, String slotName, int ownerSlot) {
        if (body == null) {
            return Ends.NONE;
        }
        ItemStack worn = ItemStack.EMPTY;
        for (GearSlot s : NumenPlugins.gearSlots(body)) {
            if (s.name().equals(slotName)) {
                worn = s.worn().copy();
                break;
            }
        }
        Inventory inv = body.getInventory();
        ItemStack owner = ItemStack.EMPTY;
        if (ownerSlot >= 0 && ownerSlot < inv.items.size()) {
            owner = inv.items.get(ownerSlot).copy();
        }
        return new Ends(worn, owner);
    }

    /** 此刻的两端现状:点名的穿戴位 + 背包里与 expect 全等的那格(没给 expect 就只报穿戴位)。 */
    private static Ends endsNow(NumenPlayer body, String slotName, ItemStack expect) {
        if (body == null) {
            return Ends.NONE;
        }
        return endsAt(body, slotName, pinSlot(body.getInventory(), expect));
    }

    /**
     * 回执两端按方向选口径:转移(dir 2/3/4)是源格与目的格的现状,容器(dir 5/6)是盒内那格与背包
     * 那格的现状(from→to 序),穿戴(dir 0/1)照 M2 的老口径。
     */
    private static Ends endsNow(ServerPlayer owner, NumenPlayer body, GearSwapRequest req,
                                String slotName, boolean transfer, boolean box) {
        if (box) {
            return boxEndsNow(owner, body, req);
        }
        if (transfer) {
            return transferEnds(owner, body, req);
        }
        return endsNow(body, slotName, req.expectOwner());
    }

    /**
     * 转移两端此刻的现状:(fromAfter, toAfter)——源格与目的格按方向定死的那两边,照实拷贝;
     * 下标越界照实留空。回执与重放共用这一条(见类注释「M3」一节)。
     */
    private static Ends transferEnds(ServerPlayer owner, NumenPlayer body, GearSwapRequest req) {
        Ref from = fromRef(req);
        Ref to = toRef(req);
        return new Ends(itemAt(invOf(owner, body, from), from.index), itemAt(invOf(owner, body, to), to.index));
    }

    /** 一格现状:下标越界照实给空堆。 */
    private static ItemStack itemAt(Inventory inv, int index) {
        return inv == null || index < 0 || index >= inv.items.size() ? ItemStack.EMPTY : inv.items.get(index).copy();
    }

    /** 背包 36 格里第一格与 expect 全等({@code ItemStack.matches})的下标;没有或没给是 -1。 */
    private static int pinSlot(Inventory inv, ItemStack expect) {
        if (expect.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < inv.items.size(); i++) {
            if (ItemStack.matches(inv.items.get(i), expect)) {
                return i;
            }
        }
        return -1;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
