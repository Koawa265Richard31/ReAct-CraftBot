package com.dwinovo.numen.gear;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.GearSwapRequest;
import com.dwinovo.numen.network.payload.GearSwapResult;
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

    /** 回执上的两端现状:点名的穿戴位 + 钉住的那格背包格。 */
    private record Ends(ItemStack worn, ItemStack owner) {
        static final Ends NONE = new Ends(ItemStack.EMPTY, ItemStack.EMPTY);
    }

    /** 动手前后的现状:点名的那一个穿戴位 + 她背包 36 格,都是拷贝。 */
    private record Snapshot(String slotName, ItemStack worn, List<ItemStack> backpack) {}

    /** 已受理、还没等到回执的一笔。 */
    private record Flight(MinecraftServer server, UUID body, String slotName, int ownerSlot,
                          long submittedMs) {}

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
     * 面板的一条穿脱请求:七道校验按序判,任一不过就回一句人话、什么也不动。全过了才拍快照、经
     * {@link Dispatcher#serve} 把活交给任务槽。主线程。
     */
    public static void submit(ServerPlayer owner, NumenPlayer body, GearSwapRequest req) {
        sweep();
        OwnerTx key = new OwnerTx(owner.getUUID(), req.txId());
        String slotName = req.slot() == null ? "" : req.slot().trim().toLowerCase(Locale.ROOT);

        // ① txId 见过:原样回上次的结论,两端按此刻现状如实附上(不是上次那一刻的)。
        Verdict remembered = LAST_RESULT.get(key);
        if (remembered != null) {
            reply(owner, req.txId(), remembered, endsNow(body, slotName, req.expectOwner()));
            return;
        }
        if (FLIGHT.containsKey(key)) {
            reply(owner, req.txId(), new Verdict(false, "a request with this id is still being carried out"),
                    endsNow(body, slotName, req.expectOwner()));
            return;
        }

        boolean wear = req.dir() == GearSwapRequest.WEAR;
        if (req.dir() != GearSwapRequest.WEAR && req.dir() != GearSwapRequest.REMOVE) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "unknown direction " + req.dir() + " (0 = wear, 1 = take off)"));
            return;
        }

        // ② 身体在加载区、未死亡。
        if (body.isDeadOrDying() || !body.isAlive()) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "she is dead — nothing to wear or take off right now"));
            return;
        }
        if (!body.level().isLoaded(body.blockPosition())) {
            refuse(owner, body, req.txId(), slotName, req.expectOwner(),
                    new Verdict(false, "she is in unloaded chunks — bring her back into view first"));
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
                System.currentTimeMillis()));
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
        conclude(key, flight.server(), verdict, endsAt(body, flight.slotName(), flight.ownerSlot()));
    }

    /** 校验不过的当场结论:记下、回执,不动任何东西。 */
    private static void refuse(ServerPlayer owner, NumenPlayer body, long txId, String slotName,
                               ItemStack expect, Verdict verdict) {
        conclude(new OwnerTx(owner.getUUID(), txId), owner.server, verdict,
                endsNow(body, slotName, expect));
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
