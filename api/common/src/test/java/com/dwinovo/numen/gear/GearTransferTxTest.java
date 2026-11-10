package com.dwinovo.numen.gear;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.GearSwapRequest;
import com.dwinovo.numen.network.payload.GearSwapResult;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3 的三条转移方向(dir 2 取回 / 3 交给 / 4 交换)与 M2 的四条不变量,全部在无头 JVM 里过
 * {@code GearSwapService.submit} 的整条事务:空壳身体与空壳主人(Unsafe 起的 {@link ServerPlayer},
 * 只有背包、血量、位置与一张装着主人的 {@link PlayerList}),回执经测试假通道
 * {@code TestNetworkChannel.SENT} 真的发出去。判定引用 docs/hud-replace-draft.md 12.3/12.4 的口径:
 * 同一把锁(txId 去重)、同一套指纹(期望源格内容 + 期望目的格内容)、同一条守恒断言(两边合计)。
 *
 * <p>引导 MC 时 Brigadier 的内置报错被换成了 MC 的说法;测完换回引导之前的那一份
 * (与 {@code WireTest} 同一个做法)。穿戴(dir 0/1)的成功路径要经任务槽(core 模块),
 * 单测够不到,这里只锁它的失败路径与 M2 形状兼容;成功路径由 core 的 gametest 覆盖。
 */
@Tag("mc")
class GearTransferTxTest {

    private static BuiltInExceptionProvider brigadierWords;
    private static ServerPlayer owner;
    private static NumenPlayer body;
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BODY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeAll
    static void boot() throws Exception {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        boolean booted;
        try {
            // Forge 补过的 Bootstrap.bootStrap() 尾上还带 NetworkHooks.init(要 modlauncher 的运行时上下文,
            // 裸 JVM 没有);这几样单测真正用到的——注册表、方块与物品——提前把 isBootstrapped 标记立起来
            // 就各自初始化得动(与 WireTest 的完整引导同一个目的,路径更短)。
            net.minecraft.SharedConstants.tryDetectVersion();
            Field flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
            flag.setAccessible(true);
            flag.setBoolean(null, true);
            Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Class.forName("net.minecraft.world.level.block.Blocks");
            Class.forName("net.minecraft.world.item.Items");
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
        assumeTrue(booted, "Minecraft could not be bootstrapped headless");
        owner = allocateShell(ServerPlayer.class, OWNER_ID);
        body = allocateShell(NumenPlayer.class, BODY_ID);
        registerOwnerInServer(owner);
        NumenNetwork.register();   // 测试假通道在场:下行路由表就位,回执真的发进 SENT
    }

    @AfterAll
    static void restoreBrigadierWords() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    @BeforeEach
    void reset() throws Exception {
        clearInventory(body.getInventory());
        clearInventory(owner.getInventory());
        health(body, 20.0f);
        lastResult().clear();
        flight().clear();
        com.dwinovo.numen.platform.TestNetworkChannel.SENT.clear();
    }

    // ==================== M2 四条不变量,原样口径 ====================

    /** 不变量 1:txId 重放——第二次返回与第一次相同的 result,物品不动(两边合计)。 */
    @Test
    void replayedTxIdReturnsTheSameResultAndMovesNothing() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        GearSwapResult first = submit(takeBack(100, 3, 5));
        assertTrue(first.ok(), "the first take-back should move the dirt: " + first.reason());
        assertTrue(matches(her().getItem(3), ItemStack.EMPTY) && matches(my().getItem(5), new ItemStack(Items.DIRT, 10)),
                "the first tx did not land where it should");

        GearSwapResult again = submit(takeBack(100, 3, 5));
        assertEquals(first.txId(), again.txId(), "the replayed receipt carries another txId");
        assertEquals(first.ok(), again.ok(), "the replayed receipt flips ok");
        assertEquals(first.reason(), again.reason(), "the replayed receipt says something else");
        assertTrue(matches(again.wornAfter(), first.wornAfter()) && matches(again.ownerAfter(), first.ownerAfter()),
                "the replayed receipt's ends differ from the first");
        assertTrue(matches(her().getItem(3), ItemStack.EMPTY) && matches(my().getItem(5), new ItemStack(Items.DIRT, 10)),
                "the replay moved items a second time");
    }

    /** 不变量 2:指纹不匹配(期望格内容已变)——ok=false + 人话,双方物品一格都不动。 */
    @Test
    void fingerprintMismatchRefusesAndBothSidesStayPut() {
        // 源格的指纹旧了:她那格已经不是面板看见的那件。
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        GearSwapRequest staleSource = new GearSwapRequest(200, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.TAKE_BACK, 3, 5, new ItemStack(Items.DIRT, 8), ItemStack.EMPTY);
        GearSwapResult r1 = submit(staleSource);
        assertFalse(r1.ok(), "a stale source fingerprint should refuse");
        assertTrue(r1.reason().contains("her slot 3"), "the refusal does not name the changed slot: " + r1.reason());
        assertTrue(matches(her().getItem(3), new ItemStack(Items.DIRT, 10)) && matches(my().getItem(5), ItemStack.EMPTY),
                "a refused tx moved something anyway");

        // 目的格的指纹旧了:我这格已经不是面板看见的那件。
        my().setItem(5, new ItemStack(Items.APPLE, 2));
        GearSwapRequest staleDest = new GearSwapRequest(201, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.TAKE_BACK, 3, 5, new ItemStack(Items.DIRT, 10), new ItemStack(Items.APPLE, 5));
        GearSwapResult r2 = submit(staleDest);
        assertFalse(r2.ok(), "a stale destination fingerprint should refuse");
        assertTrue(r2.reason().contains("your slot 5"), "the refusal does not name the changed slot: " + r2.reason());
        assertTrue(matches(her().getItem(3), new ItemStack(Items.DIRT, 10))
                && matches(my().getItem(5), new ItemStack(Items.APPLE, 2)), "a refused tx moved something anyway");
    }

    /** 不变量 3:守恒——三条转移方向前后「两边合计」的件数与组件多重集都相等。 */
    @Test
    void bothSidesCombinedConservationHoldsOnEveryDirection() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        Map<String, Integer> before = combinedMultiset();
        submit(takeBack(300, 3, 5));
        assertEquals(before, combinedMultiset(), "take back broke the combined multiset");

        my().setItem(7, new ItemStack(Items.BREAD, 4));
        before = combinedMultiset();
        submit(give(301, 7, 9));
        assertEquals(before, combinedMultiset(), "give broke the combined multiset");

        her().setItem(1, new ItemStack(Items.IRON_INGOT, 2));
        my().setItem(4, new ItemStack(Items.APPLE, 1));
        before = combinedMultiset();
        submit(swap(302, 1, 4));
        assertEquals(before, combinedMultiset(), "swap broke the combined multiset");
    }

    /** 不变量 4:她死亡——提交被拒、什么也不动;死后的重放原样回上次的结论,两端照实留空,不串号。 */
    @Test
    void deadBodyRefusesAndReplaysDoNotCrossStreams() throws Exception {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        assertTrue(submit(takeBack(400, 3, 5)).ok(), "the setup take-back should succeed");

        // 她死了:背包清空(死亡掉落的那一路),之后同 txId 的重放得到记住的结论,物品不再动、两端不串号。
        health(body, 0.0f);
        clearInventory(body.getInventory());
        GearSwapResult replayed = submit(takeBack(400, 3, 5));
        assertTrue(replayed.ok(), "the remembered verdict should still be ok: " + replayed.reason());
        assertTrue(matches(replayed.wornAfter(), ItemStack.EMPTY),
                "the replayed receipt's source end should honestly show her cleared slot");
        assertTrue(matches(replayed.ownerAfter(), new ItemStack(Items.DIRT, 10)),
                "the replayed receipt's destination end should still show the owner's stack");
        assertTrue(matches(my().getItem(5), new ItemStack(Items.DIRT, 10)),
                "the replay moved items again after her death");

        // 死着的身体发一笔新的:当场被拒,两边的物品一格都不动。
        my().setItem(6, new ItemStack(Items.BREAD, 2));
        GearSwapResult fresh = submit(give(401, 6, 7));
        assertFalse(fresh.ok(), "a fresh tx for a dead body should refuse");
        assertTrue(fresh.reason().contains("dead"), "the refusal does not say she is dead: " + fresh.reason());
        assertTrue(matches(my().getItem(6), new ItemStack(Items.BREAD, 2)) && matches(her().getItem(7), ItemStack.EMPTY),
                "a tx refused for death moved something anyway");
    }

    // ==================== 三条方向各自的正向用例 ====================

    /** 取回:她的 inv:3 → 我的 inv:5,整叠过去,她的格子空了。 */
    @Test
    void takeBackMovesHerSlotIntoMySlot() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        GearSwapResult r = submit(takeBack(500, 3, 5));
        assertTrue(r.ok(), "take back refused: " + r.reason());
        assertTrue(r.reason().contains("took 10 minecraft:dirt") && r.reason().contains("her slot 3")
                        && r.reason().contains("your slot 5"), "the receipt says the wrong thing: " + r.reason());
        assertTrue(matches(her().getItem(3), ItemStack.EMPTY), "her slot is not empty after take back");
        assertTrue(matches(my().getItem(5), new ItemStack(Items.DIRT, 10)), "my slot did not receive the stack");
        assertTrue(matches(r.wornAfter(), ItemStack.EMPTY) && matches(r.ownerAfter(), new ItemStack(Items.DIRT, 10)),
                "the receipt's ends do not match the two slots after the move");
    }

    /** 交给:我的 inv:7 → 她的 inv:9。 */
    @Test
    void giveMovesMySlotIntoHerSlot() {
        my().setItem(7, new ItemStack(Items.BREAD, 4));
        GearSwapResult r = submit(give(501, 7, 9));
        assertTrue(r.ok(), "give refused: " + r.reason());
        assertTrue(r.reason().contains("gave 4 minecraft:bread") && r.reason().contains("your slot 7")
                        && r.reason().contains("her slot 9"), "the receipt says the wrong thing: " + r.reason());
        assertTrue(matches(my().getItem(7), ItemStack.EMPTY), "my slot is not empty after giving");
        assertTrue(matches(her().getItem(9), new ItemStack(Items.BREAD, 4)), "her slot did not receive the stack");
        assertTrue(matches(r.wornAfter(), ItemStack.EMPTY) && matches(r.ownerAfter(), new ItemStack(Items.BREAD, 4)),
                "the receipt's ends do not match the two slots after the move");
    }

    /** 交换:她的 inv:1 ⇄ 我的 inv:4,两叠照原样对调。 */
    @Test
    void swapTradesHerSlotWithMySlot() {
        her().setItem(1, new ItemStack(Items.IRON_INGOT, 2));
        my().setItem(4, new ItemStack(Items.APPLE, 1));
        GearSwapResult r = submit(swap(502, 1, 4));
        assertTrue(r.ok(), "swap refused: " + r.reason());
        assertTrue(r.reason().contains("swapped") && r.reason().contains("her slot 1")
                        && r.reason().contains("your slot 4"), "the receipt says the wrong thing: " + r.reason());
        assertTrue(matches(her().getItem(1), new ItemStack(Items.APPLE, 1)), "her slot did not receive my stack");
        assertTrue(matches(my().getItem(4), new ItemStack(Items.IRON_INGOT, 2)), "my slot did not receive her stack");
        assertTrue(matches(r.wornAfter(), new ItemStack(Items.APPLE, 1))
                && matches(r.ownerAfter(), new ItemStack(Items.IRON_INGOT, 2)),
                "the receipt's ends do not match the two slots after the move");
    }

    /** 取回并堆:同一件还放得下就并进那一叠(空格与满格之间也是一次转移)。 */
    @Test
    void takeBackMergesIntoAStackWithRoom() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        my().setItem(5, new ItemStack(Items.DIRT, 30));
        GearSwapResult r = submit(takeBack(503, 3, 5));
        assertTrue(r.ok(), "the merge take-back refused: " + r.reason());
        assertTrue(r.reason().contains("merged"), "a merge does not say so: " + r.reason());
        assertTrue(matches(her().getItem(3), ItemStack.EMPTY), "her slot did not empty out");
        assertTrue(matches(my().getItem(5), new ItemStack(Items.DIRT, 40)), "the stacks did not merge");
    }

    // ==================== 其余失败路径:ok=false + 人话 + 什么都不动 ====================

    /** 目标格不存在:inv 只有 0..35,越界如实拒。 */
    @Test
    void aSlotBeyond36DoesNotExist() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        GearSwapResult r = submit(takeBack(600, 3, 36));
        assertFalse(r.ok(), "an out-of-range slot should refuse");
        assertTrue(r.reason().contains("no slot 36") && r.reason().contains("inv:0-35"),
                "the refusal does not say the slot does not exist: " + r.reason());
        assertTrue(matches(her().getItem(3), new ItemStack(Items.DIRT, 10)), "a refused tx moved something anyway");

        GearSwapResult r2 = submit(takeBack(601, 42, 5));
        assertFalse(r2.ok(), "an out-of-range source should refuse");
        assertTrue(matches(my().getItem(5), ItemStack.EMPTY), "a refused tx moved something anyway");
    }

    /** 她背包满(放不下):交给的量在那一格里叠不下,如实拒。 */
    @Test
    void aStackThatDoesNotFitRefuses() {
        her().setItem(9, new ItemStack(Items.DIRT, 60));
        my().setItem(7, new ItemStack(Items.DIRT, 10));
        GearSwapResult r = submit(give(602, 7, 9));
        assertFalse(r.ok(), "a stack that does not fit should refuse");
        assertTrue(r.reason().contains("tops out at 64") && r.reason().contains("10 more will not fit"),
                "the refusal does not say why it does not fit: " + r.reason());
        assertTrue(matches(her().getItem(9), new ItemStack(Items.DIRT, 60))
                && matches(my().getItem(7), new ItemStack(Items.DIRT, 10)), "a refused tx moved something anyway");
    }

    /** 物品不可堆叠:一格一把的剑并不进另一把,如实拒。 */
    @Test
    void anUnstackableItemRefusesToMerge() {
        her().setItem(9, new ItemStack(Items.DIAMOND_SWORD));
        my().setItem(7, new ItemStack(Items.DIAMOND_SWORD));
        GearSwapResult r = submit(give(603, 7, 9));
        assertFalse(r.ok(), "an unstackable merge should refuse");
        assertTrue(r.reason().contains("tops out at 1"), "the refusal does not mention the stack limit: " + r.reason());
        assertTrue(matches(her().getItem(9), new ItemStack(Items.DIAMOND_SWORD))
                && matches(my().getItem(7), new ItemStack(Items.DIAMOND_SWORD)), "a refused tx moved something anyway");
    }

    /** 目的格被别的物品占着:取回/交给如实拒,并提示换成交换。 */
    @Test
    void anOccupiedDestinationRefusesAndNamesTheSwap() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        my().setItem(5, new ItemStack(Items.APPLE, 2));
        GearSwapResult r = submit(takeBack(604, 3, 5));
        assertFalse(r.ok(), "an occupied destination should refuse");
        assertTrue(r.reason().contains("minecraft:apple") && r.reason().contains("swap"),
                "the refusal does not name the blocker or the swap: " + r.reason());
        assertTrue(matches(her().getItem(3), new ItemStack(Items.DIRT, 10))
                && matches(my().getItem(5), new ItemStack(Items.APPLE, 2)), "a refused tx moved something anyway");
    }

    /** 未知方向照旧拒(M2 的口径,M3 把话说全)。 */
    @Test
    void anUnknownDirectionRefuses() {
        GearSwapRequest weird = new GearSwapRequest(605, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                (byte) 9, -1, -1, ItemStack.EMPTY, ItemStack.EMPTY);
        GearSwapResult r = submit(weird);
        assertFalse(r.ok(), "an unknown direction should refuse");
        assertTrue(r.reason().contains("unknown direction 9") && r.reason().contains("4 = swap"),
                "the refusal does not list the directions: " + r.reason());
    }

    /** M2 形状(dir 0/1)原样可用:老客户端的 6 参构造走到同一道门,穿戴位缺槽照旧拒。 */
    @Test
    void theM2ShapeStillWalksTheSameDoor() {
        her().setItem(3, new ItemStack(Items.DIRT, 10));
        GearSwapRequest legacy = new GearSwapRequest(606, BODY_ID, "head",
                ItemStack.EMPTY, new ItemStack(Items.DIRT, 10), GearSwapRequest.WEAR);
        GearSwapResult r = submit(legacy);
        assertFalse(r.ok(), "a wear without a registered slot should refuse");
        assertTrue(r.reason().contains("no slot named 'head'"), "the M2 refusal changed shape: " + r.reason());
        assertTrue(matches(her().getItem(3), new ItemStack(Items.DIRT, 10)), "a refused wear moved something anyway");
    }

    // ==================== 请求与观察 ====================

    private static GearSwapRequest takeBack(long txId, int fromSlot, int toSlot) {
        return new GearSwapRequest(txId, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.TAKE_BACK, fromSlot, toSlot,
                copyOf(her().getItem(fromSlot)), copyOf(my().getItem(toSlot)));
    }

    private static GearSwapRequest give(long txId, int fromSlot, int toSlot) {
        return new GearSwapRequest(txId, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.GIVE, fromSlot, toSlot,
                copyOf(my().getItem(fromSlot)), copyOf(her().getItem(toSlot)));
    }

    private static GearSwapRequest swap(long txId, int herSlot, int mySlot) {
        return new GearSwapRequest(txId, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.SWAP, herSlot, mySlot,
                copyOf(her().getItem(herSlot)), copyOf(my().getItem(mySlot)));
    }

    /** 走整条事务,回测试通道里最近的那张回执。 */
    private static GearSwapResult submit(GearSwapRequest req) {
        GearSwapService.submit(owner, body, req);
        List<com.dwinovo.numen.network.NumenPayload> sent = com.dwinovo.numen.platform.TestNetworkChannel.SENT;
        assertFalse(sent.isEmpty(), "no receipt was sent for tx " + req.txId());
        com.dwinovo.numen.network.NumenPayload last = sent.get(sent.size() - 1);
        assertTrue(last instanceof GearSwapResult, "the last sent payload was not a GearSwapResult: " + last);
        return (GearSwapResult) last;
    }

    private static Inventory her() {
        return body.getInventory();
    }

    private static Inventory my() {
        return owner.getInventory();
    }

    private static ItemStack copyOf(ItemStack stack) {
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static boolean matches(ItemStack a, ItemStack b) {
        return ItemStack.matches(a, b);
    }

    /** 两边合计的守恒多重集:与 GearSwapService 同一套键(物品 id + 组件),测试这边自己照公式再算一遍。 */
    private static Map<String, Integer> combinedMultiset() {
        Map<String, Integer> out = new TreeMap<>();
        mergeAll(out, her());
        mergeAll(out, my());
        return out;
    }

    private static void mergeAll(Map<String, Integer> multiset, Inventory inv) {
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            multiset.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                            + "#" + (stack.getTag() == null ? "" : stack.getTag()),
                    stack.getCount(), Integer::sum);
        }
    }

    private static void clearInventory(Inventory inv) {
        for (int i = 0; i < inv.items.size(); i++) {
            inv.items.set(i, ItemStack.EMPTY);
        }
    }

    private static void health(LivingEntity entity, float value) throws Exception {
        Field accField = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
        accField.setAccessible(true);
        @SuppressWarnings("unchecked")
        EntityDataAccessor<Float> acc = (EntityDataAccessor<Float>) accField.get(null);
        synchedData(entity).set(acc, value);
    }

    // ==================== 夹具:空壳身体 / 主人 / 世界 ====================

    /**
     * 空壳玩家(ThrowawayBlocksTest 同款做法,这里多血量、位置与一张世界):只有背包、同步数据、
     * UUID、位置与一张永远「已加载」的壳世界——事务真正用到的就这些。
     */
    private static <E extends Player> E allocateShell(Class<E> type, UUID id) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        E shell = (E) unsafe.allocateInstance(type);
        Field inventory = Player.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(shell, new Inventory(shell));
        SynchedEntityData data = new SynchedEntityData(shell);
        Field entityData = Entity.class.getDeclaredField("entityData");
        entityData.setAccessible(true);
        entityData.set(shell, data);
        Method define = Entity.class.getDeclaredMethod("defineSynchedData");
        define.setAccessible(true);
        define.invoke(shell);   // Player 一路的同步项全是 define 注册,壳上安全
        health((LivingEntity) shell, 20.0f);
        Field level = Entity.class.getDeclaredField("level");
        level.setAccessible(true);
        level.set(shell, ShellLevel.make());
        Field position = Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(shell, Vec3.ZERO);
        Field blockPosition = Entity.class.getDeclaredField("blockPosition");
        blockPosition.setAccessible(true);
        blockPosition.set(shell, net.minecraft.core.BlockPos.ZERO);   // 1.20.1 的 blockPosition() 读缓存字段
        shell.setUUID(id);
        return shell;
    }

    private static SynchedEntityData synchedData(Entity entity) throws Exception {
        Field entityData = Entity.class.getDeclaredField("entityData");
        entityData.setAccessible(true);
        return (SynchedEntityData) entityData.get(entity);
    }

    /** 主人能收到回执的那条最小通路:壳服务器的玩家表里装着主人,conclude 就把回执发给测试通道。 */
    private static void registerOwnerInServer(ServerPlayer owner) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        // MinecraftServer 是抽象类,jdk 的 Unsafe 起不了壳;专用服是它的具体子类,构造器不跑,一样只有字段。
        MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(
                net.minecraft.server.dedicated.DedicatedServer.class);
        PlayerList list = (PlayerList) unsafe.allocateInstance(
                net.minecraft.server.dedicated.DedicatedPlayerList.class);
        Field playersByUuid = PlayerList.class.getDeclaredField("playersByUUID");
        playersByUuid.setAccessible(true);
        playersByUuid.set(list, new HashMap<UUID, ServerPlayer>(Map.of(owner.getUUID(), owner)));
        Field playerList = MinecraftServer.class.getDeclaredField("playerList");
        playerList.setAccessible(true);
        playerList.set(server, list);
        Field serverField = ServerPlayer.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(owner, server);
    }

    private static Map<?, ?> lastResult() throws Exception {
        Field field = GearSwapService.class.getDeclaredField("LAST_RESULT");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = unsafe();
        unsafe.ensureClassInitialized(GearSwapService.class);   // 不先初始化,静态字段读到的是 null
        @SuppressWarnings("unchecked")
        Map<?, ?> map = (Map<?, ?>) unsafe.getObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field));
        return map;
    }

    private static Map<?, ?> flight() throws Exception {
        Field field = GearSwapService.class.getDeclaredField("FLIGHT");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = unsafe();
        unsafe.ensureClassInitialized(GearSwapService.class);
        @SuppressWarnings("unchecked")
        Map<?, ?> map = (Map<?, ?>) unsafe.getObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field));
        return map;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (sun.misc.Unsafe) theUnsafe.get(null);
    }

    /** 壳世界:原点永远算已加载,别的不应答。Unsafe 起壳(不跑 Level 的构造器——它要注册表里全套数据包注册项),
     * 事务真正走到的只有 {@code isLoaded}:isOutsideBuildHeight 用这里的上下界,getChunkSource 用壳区块源。 */
    static final class ShellLevel extends Level {

        private static final ChunkSource CHUNKS = new ShellChunks();

        /** 只为编译器立一条不跑的 super 链:Unsafe 起壳,这个构造器永不执行。 */
        private ShellLevel() {
            super(null, null, null, null, null, false, false, 0L, 0);
        }

        /** Unsafe 起壳:构造器一个字段都不初始化也用不着。 */
        static ShellLevel make() {
            try {
                Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
                return (ShellLevel) unsafe.allocateInstance(ShellLevel.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ChunkSource getChunkSource() {
            return CHUNKS;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }

        @Override
        public int getMaxBuildHeight() {
            return 320;
        }

        @Override public void sendBlockUpdated(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState old, net.minecraft.world.level.block.state.BlockState now, int flags) {}
        @Override public void playSeededSound(net.minecraft.world.entity.player.Player except, double x, double y, double z, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {}
        @Override public void playSeededSound(net.minecraft.world.entity.player.Player except, net.minecraft.world.entity.Entity entity, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {}
        @Override public String gatherChunkSourceStats() { return "shell"; }
        @Override public net.minecraft.world.entity.Entity getEntity(int id) { return null; }
        @Override public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(String name) { return null; }
        @Override public void setMapData(String name, net.minecraft.world.level.saveddata.maps.MapItemSavedData data) {}
        @Override public int getFreeMapId() { return 0; }
        @Override public void destroyBlockProgress(int breaker, net.minecraft.core.BlockPos pos, int progress) {}
        @Override public net.minecraft.world.scores.Scoreboard getScoreboard() { return null; }
        @Override public net.minecraft.world.item.crafting.RecipeManager getRecipeManager() { return null; }
        @Override protected net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> getEntities() { return null; }
        @Override public void gameEvent(net.minecraft.world.level.gameevent.GameEvent event, net.minecraft.world.phys.Vec3 pos, net.minecraft.world.level.gameevent.GameEvent.Context context) {}
        @Override public void levelEvent(net.minecraft.world.entity.player.Player except, int type, net.minecraft.core.BlockPos pos, int data) {}

        // —— 原版接口的其余抽象,与 Level 已实现的重名签名就是一次普通覆写,谁都不应答 ——
        public void addParticle(net.minecraft.core.particles.ParticleOptions options, double x, double y, double dx, double dy, double dz) {}
        public void playSound(net.minecraft.world.entity.player.Player except, net.minecraft.core.BlockPos pos, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch) {}
        public boolean setBlock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags, int recursion) { return false; }
        public boolean removeBlock(net.minecraft.core.BlockPos pos, boolean move) { return false; }
        public boolean destroyBlock(net.minecraft.core.BlockPos pos, boolean drop, net.minecraft.world.entity.Entity breaker, int recursion) { return false; }
        public boolean isFluidAtPosition(net.minecraft.core.BlockPos pos, java.util.function.Predicate<net.minecraft.world.level.material.FluidState> predicate) { return false; }
        public boolean isStateAtPosition(net.minecraft.core.BlockPos pos, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> predicate) { return false; }
        public boolean hasChunk(int chunkX, int chunkZ) { return true; }
        public boolean isClientSide() { return false; }
        public int getSeaLevel() { return 0; }
        public int getSkyDarken() { return 0; }
        public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) { return 0; }
        public long dayTime() { return 0L; }
        public long nextSubTickCount() { return 0L; }
        public java.util.List<? extends net.minecraft.world.entity.player.Player> players() { return java.util.List.of(); }
        public net.minecraft.core.BlockPos getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types type, net.minecraft.core.BlockPos pos) { return pos; }
        public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) { return null; }
        public net.minecraft.core.RegistryAccess registryAccess() { return net.minecraft.core.RegistryAccess.EMPTY; }
        public net.minecraft.server.MinecraftServer getServer() { return null; }
        public net.minecraft.util.RandomSource getRandom() { return net.minecraft.util.RandomSource.create(); }
        public net.minecraft.world.DifficultyInstance getCurrentDifficultyAt(net.minecraft.core.BlockPos pos) { return null; }
        public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() { return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS; }
        public net.minecraft.world.level.biome.BiomeManager getBiomeManager() { return null; }
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return null; }
        public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) { return null; }
        public net.minecraft.world.level.chunk.ChunkAccess getChunk(int chunkX, int chunkZ, net.minecraft.world.level.chunk.ChunkStatus status, boolean load) { return null; }
        public net.minecraft.world.level.dimension.DimensionType dimensionType() { return null; }
        public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) { return null; }
        public net.minecraft.world.level.storage.LevelData getLevelData() { return super.getLevelData(); }
        public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() { return net.minecraft.world.ticks.BlackholeTickAccess.emptyLevelList(); }
        public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() { return net.minecraft.world.ticks.BlackholeTickAccess.emptyLevelList(); }
        public float getShade(net.minecraft.core.Direction direction, boolean shade) { return 1.0f; }
        public java.util.List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity except, net.minecraft.world.phys.AABB box, java.util.function.Predicate<? super net.minecraft.world.entity.Entity> predicate) { return java.util.List.of(); }
        public <T extends net.minecraft.world.entity.Entity> java.util.List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, T> type, net.minecraft.world.phys.AABB box, java.util.function.Predicate<? super T> predicate) { return java.util.List.of(); }
        public <T extends net.minecraft.world.level.block.entity.BlockEntity> java.util.Optional<T> getBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.entity.BlockEntityType<T> type) { return java.util.Optional.empty(); }
        @Override public void close() {}
    }

    /** 壳区块源:任何区块都算在场。 */
    static final class ShellChunks extends ChunkSource {

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return true;
        }

        @Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z, net.minecraft.world.level.chunk.ChunkStatus status, boolean load) { return null; }
        @Override public void tick(java.util.function.BooleanSupplier hasTime, boolean tickChunks) {}
        @Override public String gatherStats() { return "shell"; }
        @Override public int getLoadedChunksCount() { return 1; }
        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() { return null; }
        @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) { return null; }
        @Override public net.minecraft.world.level.BlockGetter getLevel() { return null; }
        @Override public void close() {}
    }
}
