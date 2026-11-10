package com.dwinovo.numen.gear;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.gear.ContainerBoxes.Resolve;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.BoxReadRequest;
import com.dwinovo.numen.network.payload.BoxReadResult;
import com.dwinovo.numen.network.payload.GearSwapRequest;
import com.dwinovo.numen.network.payload.GearSwapResult;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.ContainerHelper;
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
 * M4 的两个容器方向(dir 5 取出 / 6 放入)走 {@code GearSwapService.submit} 的整条事务,六条不变量在
 * 无头 JVM 里验(spec 的三条硬约束 + 收放两态 + txId 重放):容器里再放容器一律拒(空满不看);
 * 第三方持有与不合文法的 box 地址在解析段就拒;「她 36 + 我 36 + 盒内」三方合计的件数与组件多重集
 * 相等(锚点堆换守恒掩蔽键,内格怎么重排都不假警报);容器满/不可堆叠如实拒;重放的 txId 原样回
 * 上次的结论、物品不再动。判定引用 spec-m4-containers §五的失败清单原句。
 *
 * <p>夹具与 {@code GearTransferTxTest} 同款:空壳身体与空壳主人(Unsafe 起的 ServerPlayer,只有背包、
 * 血量、位置与一张装着两方的 PlayerList),回执经测试假通道 {@code TestNetworkChannel.SENT} 真的发出去;
 * 容器解析走测试假实现 {@code TestItemContainerResolver}(tag 里带 BoxTest 标记的物品是容器;
 * 潜影盒的真实解析是 api/forge 的事,裸 JVM 起不了 Forge 能力)。读路径(BoxReadRequest)不查 tx 表、
 * 不查互斥,重发 = 重读,也在这里一并验。
 */
@Tag("mc")
class ContainerBoxTxTest {

    private static BuiltInExceptionProvider brigadierWords;
    private static ServerPlayer owner;
    private static NumenPlayer body;
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BODY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** 测试里盒固定躺在她背包的 inv:12;box 地址照 SlotRefs 的文法拼。 */
    private static final String BOX_REF_3 = "box:her::inv:12::3";
    private static final String BOX_REF_5 = "box:her::inv:12::5";
    private static final int ANCHOR = 12;

    @BeforeAll
    static void boot() throws Exception {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        boolean booted;
        try {
            // Forge 补过的 Bootstrap.bootStrap() 尾上还带 NetworkHooks.init(要 modlauncher 的运行时上下文,
            // 裸 JVM 没有);这几样单测真正用到的——注册表、方块与物品——提前把 isBootstrapped 标记立起来
            // 就各自初始化得动(与 GearTransferTxTest 同一个做法)。
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
        body.setOwnerUuid(OWNER_ID);   // 读路径的信任门(handle)要过 isOwnedByPlayer
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

    // ==================== 硬约束一:深度上限(spec §3.1 / §五 #5) ====================

    /** 容器里再放容器不开:装着东西的小盒拒,空盒同样拒——规则不看空满,规则就是层数;拒了三方一格不动。 */
    @Test
    void aBoxInsideABoxIsRefusedEmptyOrNot() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 5));
        ItemStack payload = testContainer(27, new ItemStack(Items.APPLE, 1));
        my().setItem(0, payload.copy());
        GearSwapResult r = submit(putIntoBox(700, false, 0, BOX_REF_3));
        assertFalse(r.ok(), "putting a container into a container should refuse");
        assertEquals(ContainerBoxes.PAYLOAD_DEPTH, r.reason(),
                "the depth refusal is not the §五 #5 sentence: " + r.reason());
        assertTrue(matches(my().getItem(0), payload) && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 5)),
                "a refused depth check moved something anyway");

        // 空容器同样拒。
        ItemStack empty = testContainer(27);
        my().setItem(0, empty.copy());
        GearSwapResult r2 = submit(putIntoBox(701, false, 0, BOX_REF_3));
        assertFalse(r2.ok(), "an empty container is still a container");
        assertEquals(ContainerBoxes.PAYLOAD_DEPTH, r2.reason());
        assertTrue(matches(my().getItem(0), empty), "a refused depth check moved something anyway");
    }

    // ==================== 硬约束三:读权限(spec §3.3 / §五 #1) ====================

    /** 第三方持有在解析段就拒:box:steve 没有写法;协议直发得到 §五 #1 原句,一格不动。 */
    @Test
    void aThirdPartyBoxAddressRefusesAtTheParseGate() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 5));
        my().setItem(0, new ItemStack(Items.DIRT, 10));
        GearSwapRequest forged = new GearSwapRequest(800, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.BOX_PUT, -1, -1, copyOf(my().getItem(0)), ItemStack.EMPTY,
                "my:inv:0", "box:steve::inv:12::3", copyOf(boxStack()));
        GearSwapResult r = submit(forged);
        assertFalse(r.ok(), "a third-party box address should refuse");
        assertEquals(SlotRefs.PARSE_REFUSAL, r.reason(), "the parse refusal changed shape: " + r.reason());
        assertTrue(matches(my().getItem(0), new ItemStack(Items.DIRT, 10))
                && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 5)), "a refused tx moved something anyway");

        // 盒内格 ⇄ 盒内格不构成移动,两端类型不对同样在解析段拒。
        GearSwapRequest boxToBox = new GearSwapRequest(801, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.BOX_PUT, -1, -1, ItemStack.EMPTY, ItemStack.EMPTY,
                "box:her::inv:12::3", "box:her::inv:12::5", copyOf(boxStack()));
        GearSwapResult r2 = submit(boxToBox);
        assertFalse(r2.ok(), "box-to-box should refuse");
        assertEquals(SlotRefs.PARSE_REFUSAL, r2.reason());
    }

    /** 读路径的信任门与「不是容器不静默」:对一块圆石发 box 地址,ok=false + §五 #4 原句——不是静默无反应。 */
    @Test
    void anItemWithNoContainerRefusesTheReadHonestly() {
        her().setItem(ANCHOR, new ItemStack(Items.COBBLESTONE, 1));
        BoxReadResult r = read(900, "box:her::inv:12::0");
        assertFalse(r.ok(), "a cobblestone is not a container");
        assertEquals(ContainerBoxes.NO_CONTAINER, r.reason(), "the no-capability refusal changed shape");
        assertTrue(r.contents().isEmpty() && matches(r.boxAfter(), ItemStack.EMPTY),
                "a failed read leaked half contents");
    }

    // ==================== 硬约束二:守恒(spec §3.2 / §六 #3 的掩蔽键专项) ====================

    /** 放入 + 掩蔽键专项:从我背包取一叠进她的盒,三方合计不变;盒内再从第 3 格挪到第 5 格(两次面板
     * 操作)全程无假警报——没有掩蔽键,挪内格必触发。 */
    @Test
    void boxMovesConserveHerYoursAndTheBoxIncludingInnerRearrangements() {
        putBoxInHerPack(ANCHOR, 27);   // 空盒
        my().setItem(0, new ItemStack(Items.DIRT, 64));

        Map<String, Integer> before = account();
        GearSwapResult put = submit(putIntoBox(1000, false, 0, BOX_REF_3));
        assertTrue(put.ok(), "the put refused: " + put.reason());
        assertEquals(before, account(), "putting into the box broke the three-party multiset");
        assertTrue(matches(my().getItem(0), ItemStack.EMPTY) && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 64)),
                "the put did not land where it should");

        // 掩蔽键专项:盒内圆石从第 3 格挪回我背包(取出),再放回第 5 格——内格重排,锚点堆的键不许变。
        GearSwapResult take = submit(takeFromBox(1001, false, 0, BOX_REF_3));
        assertTrue(take.ok(), "the take refused: " + take.reason());
        assertEquals(before, account(), "taking out of the box broke the three-party multiset");

        GearSwapResult put5 = submit(putIntoBox(1002, false, 0, BOX_REF_5));
        assertTrue(put5.ok(), "the second put refused: " + put5.reason());
        assertEquals(before, account(), "the inner rearrangement broke the three-party multiset");
        assertTrue(matches(inner(BOX_REF_5, 5), new ItemStack(Items.DIRT, 64))
                && matches(inner(BOX_REF_3, 3), ItemStack.EMPTY), "the dirt did not land in inner slot 5");

        // 她背包里那格锚点堆还是原来那件盒(内容之外 nothing changed)。
        assertEquals(com.dwinovo.numen.platform.TestItemContainerResolver.maskedToken(
                        testContainer(27, new ItemStack(Items.DIRT, 64))),
                com.dwinovo.numen.platform.TestItemContainerResolver.maskedToken(boxStack()),
                "the anchor stack lost components outside its contents");
    }

    // ==================== 收放两态:容器满 / 不可堆叠(§五 #10/#11) ====================

    /** 容器满:内格已有 60 圆石,再放 10 圆石叠不上,如实拒,两边与盒内一格不动。 */
    @Test
    void aFullInnerSlotRefusesToMergeBeyondTheStackCap() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 60));
        my().setItem(0, new ItemStack(Items.DIRT, 10));
        GearSwapResult r = submit(putIntoBox(1100, false, 0, BOX_REF_3));
        assertFalse(r.ok(), "a merge beyond the stack cap should refuse");
        assertTrue(r.reason().contains("inner slot 3") && r.reason().contains("tops out at 64")
                        && r.reason().contains("10 more will not fit"),
                "the tops-out refusal does not say why: " + r.reason());
        assertTrue(matches(my().getItem(0), new ItemStack(Items.DIRT, 10))
                && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 60)),
                "a refused put moved something anyway");
    }

    /** 不可堆叠:内格有一把剑,另一把剑并不进去(maxStackSize=1 的物并叠顶在 §五 #11),如实拒。 */
    @Test
    void anUnstackableItemTakesAWholeInnerSlot() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIAMOND_SWORD, 1));
        my().setItem(0, new ItemStack(Items.DIAMOND_SWORD, 1));
        GearSwapResult r = submit(putIntoBox(1101, false, 0, BOX_REF_3));
        assertFalse(r.ok(), "an unstackable merge should refuse");
        assertTrue(r.reason().contains("holds 1 minecraft:diamond_sword") && r.reason().contains("tops out at 1")
                        && r.reason().contains("1 more will not fit"),
                "the tops-out refusal does not name the stack limit: " + r.reason());
        assertTrue(matches(my().getItem(0), new ItemStack(Items.DIAMOND_SWORD, 1))
                && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIAMOND_SWORD, 1)),
                "a refused put moved something anyway");
    }

    /** 内格已有别的物:圆石占着的那格进不去剑,§五 #10 原句点名占据者与「boxes don't swap」。 */
    @Test
    void anOccupiedInnerSlotRefusesAndNamesTheBlocker() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 3));
        my().setItem(0, new ItemStack(Items.DIAMOND_SWORD, 1));
        GearSwapResult r = submit(putIntoBox(1102, false, 0, BOX_REF_3));
        assertFalse(r.ok(), "a differently-occupied inner slot should refuse");
        assertTrue(r.reason().contains("holds minecraft:dirt") && r.reason().contains("boxes don't swap"),
                "the occupied-slot refusal does not name the blocker: " + r.reason());
        assertTrue(matches(my().getItem(0), new ItemStack(Items.DIAMOND_SWORD, 1))
                && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 3)),
                "a refused put moved something anyway");
    }

    // ==================== txId 重放(M2/M3 同一把锁) ====================

    /** 重放的 txId 原样回上次的结论(ok、reason、两端),盒内与背包不再动。 */
    @Test
    void aReplayedBoxTxReturnsTheRememberedVerdictAndMovesNothing() {
        putBoxInHerPack(ANCHOR, 27);
        my().setItem(0, new ItemStack(Items.DIRT, 10));
        GearSwapRequest req = putIntoBox(1200, false, 0, BOX_REF_3);
        GearSwapResult first = submit(req);
        assertTrue(first.ok(), "the first put refused: " + first.reason());
        assertTrue(matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 10))
                && matches(my().getItem(0), ItemStack.EMPTY), "the first put did not land where it should");

        GearSwapResult again = submit(req);
        assertEquals(first.txId(), again.txId(), "the replayed receipt carries another txId");
        assertEquals(first.ok(), again.ok(), "the replayed receipt flips ok");
        assertEquals(first.reason(), again.reason(), "the replayed receipt says something else");
        assertTrue(matches(again.wornAfter(), first.wornAfter()) && matches(again.ownerAfter(), first.ownerAfter()),
                "the replayed receipt's ends differ from the first");
        assertTrue(matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 10))
                && matches(my().getItem(0), ItemStack.EMPTY), "the replay moved items a second time");

        // 换 txId 的失败也被记住:同一把 (主人, txId) 复合键,毒不到别人的号。
        GearSwapResult failed = submit(putIntoBox(1201, false, 0, BOX_REF_3));
        assertFalse(failed.ok(), "an empty source should refuse");
        GearSwapResult failedAgain = submit(putIntoBox(1201, false, 0, BOX_REF_3));
        assertEquals(failed.reason(), failedAgain.reason(), "a remembered failure said something else");
    }

    // ==================== 正向:取出(§六 #4 的机器版)与指纹 ====================

    /** 取出:她的盒 → 我的背包,整叠过去,盒内那格空了;回执两端按 from→to 序携带现状。 */
    @Test
    void takeOutMovesAnInnerSlotIntoMySlot() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 10));
        GearSwapResult r = submit(takeFromBox(1300, false, 5, BOX_REF_3));
        assertTrue(r.ok(), "the take refused: " + r.reason());
        assertTrue(r.reason().contains("took 10 minecraft:dirt") && r.reason().contains("inner slot 3")
                        && r.reason().contains("your slot 5"), "the receipt says the wrong thing: " + r.reason());
        assertTrue(matches(inner(BOX_REF_3, 3), ItemStack.EMPTY), "the inner slot is not empty after the take");
        assertTrue(matches(my().getItem(5), new ItemStack(Items.DIRT, 10)), "my slot did not receive the stack");
        assertTrue(matches(r.wornAfter(), ItemStack.EMPTY) && matches(r.ownerAfter(), new ItemStack(Items.DIRT, 10)),
                "the receipt's ends are not (inner slot, my slot) as it stands now");
    }

    /** 指纹:expectBox(整堆含内容 NBT)旧了就拒(§五 #6);内格序号越界如实拒(§五 #7);内格现状旧了拒(§五 #8)。 */
    @Test
    void staleFingerprintsRefuseBeforeAnythingMoves() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 10));
        my().setItem(0, ItemStack.EMPTY);

        // §五 #6:容器整堆被挪过/动过(这里把盒挪到另一格再挪回来,数量变了)。
        ItemStack staleBox = testContainer(27, new ItemStack(Items.DIRT, 8));
        GearSwapRequest wrongBox = new GearSwapRequest(1400, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.BOX_TAKE, -1, 5, copyOf(inner(BOX_REF_3, 3)), ItemStack.EMPTY,
                BOX_REF_3, "my:inv:5", staleBox);
        GearSwapResult r = submit(wrongBox);
        assertFalse(r.ok(), "a stale whole-box fingerprint should refuse");
        assertEquals(ContainerBoxes.STALE_BOX, r.reason());
        assertTrue(matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 10)), "a refused tx moved something anyway");

        // §五 #7:内格序号越界(27 格的盒没有第 40 格)。
        GearSwapResult far = submit(takeFromBox(1401, false, 5, "box:her::inv:12::40"));
        assertFalse(far.ok(), "an out-of-range inner slot should refuse");
        assertTrue(far.reason().contains("no inner slot 40") && far.reason().contains("27 slots"),
                "the range refusal does not say the box's shape: " + far.reason());

        // §五 #8:内格现状旧了(面板看见的是 10 件,盒里已是别的)。
        GearSwapRequest staleInner = new GearSwapRequest(1402, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.BOX_TAKE, -1, 5, new ItemStack(Items.DIRT, 4), ItemStack.EMPTY,
                BOX_REF_3, "my:inv:5", copyOf(boxStack()));
        GearSwapResult r2 = submit(staleInner);
        assertFalse(r2.ok(), "a stale inner fingerprint should refuse");
        assertTrue(r2.reason().contains("inner slot 3 is not what you saw"),
                "the inner-fingerprint refusal changed shape: " + r2.reason());
        assertTrue(matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 10)), "a refused tx moved something anyway");
    }

    // ==================== 读路径(BoxReadRequest:无事务,重发 = 重读) ====================

    /** 读:27 格内容按需展开,内格下标与真值一致;同 txId 重发原样再答(天然幂等),格数如实。 */
    @Test
    void theReadPathSnapshotsTheWholeBoxAndIsIdempotent() {
        putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 5), new ItemStack(Items.APPLE, 2));
        BoxReadResult first = read(1500, BOX_REF_3);
        assertTrue(first.ok(), "the read refused: " + first.reason());
        assertEquals(27, first.contents().size(), "a 27-slot box did not read as 27 inner slots");
        assertTrue(matches(first.contents().get(3), new ItemStack(Items.DIRT, 5)), "inner slot 3 read wrong");
        assertTrue(matches(first.contents().get(4), new ItemStack(Items.APPLE, 2)), "inner slot 4 read wrong");
        assertTrue(matches(first.boxAfter(), boxStack()), "boxAfter is not the anchor stack as it stands");
        assertFalse(first.truncated(), "an ordinary box read claims truncation");

        BoxReadResult again = read(1500, BOX_REF_3);
        assertEquals(first.contents().size(), again.contents().size(), "a replayed read answered differently");
        assertTrue(matches(again.contents().get(3), first.contents().get(3)), "a replayed read drifted");

        // 读不动世界:读完盒内、背包照旧。
        assertTrue(matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 5)), "a read moved something");
    }

    // ==================== 程序互斥不被构造地址绕过(spec §4.2 口径的回归) ====================

    /** gear 锚点恒为她的:holder 词写成 box:my::gear:… 的构造地址(面板永不拼出,纯改包可达)也必须被
     *  互斥拦下——她跑程序时,「my:inv:0 ⇄ box:my::gear:…」当场拒,一格不动。互斥判在 resolve 之前,
     *  所以回执的 reason 是互斥原句,而不是「she has no slot named 'curios:back'」。 */
    @Test
    void theMutexCatchesAGearAnchorAddressedAsMine() throws Exception {
        setProgramRunning(true);
        try {
            putBoxInHerPack(ANCHOR, 27, new ItemStack(Items.DIRT, 5));
            my().setItem(0, new ItemStack(Items.DIRT, 10));
            GearSwapRequest crafted = new GearSwapRequest(1600, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                    GearSwapRequest.BOX_PUT, -1, -1, copyOf(my().getItem(0)), ItemStack.EMPTY,
                    "my:inv:0", "box:my::gear:curios:back#0::3", copyOf(boxStack()));
            GearSwapResult r = submit(crafted);
            assertFalse(r.ok(), "a gear anchor addressed as mine must still hit the program mutex");
            assertEquals(ContainerBoxes.PROGRAM_BUSY, r.reason(),
                    "the crafted address bypassed the mutex (or was refused by a later gate): " + r.reason());
            assertTrue(matches(my().getItem(0), new ItemStack(Items.DIRT, 10))
                    && matches(inner(BOX_REF_3, 3), new ItemStack(Items.DIRT, 5)),
                    "a refused tx moved something anyway");
        } finally {
            setProgramRunning(false);
        }
    }

    /** 反向对照:同一段程序在跑,纯我这边的容器操作(我的格 ⇄ 我的盒)不查互斥,照常放行——
     *  证明修的是侧别判据,不是「一律拦」。 */
    @Test
    void aPurelyMineBoxMoveStillRunsWhileHerProgramRuns() throws Exception {
        setProgramRunning(true);
        try {
            my().setItem(20, testContainer(27));   // 我的盒躺在我背包 inv:20
            my().setItem(0, new ItemStack(Items.DIRT, 10));
            GearSwapRequest mine = new GearSwapRequest(1601, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                    GearSwapRequest.BOX_PUT, -1, -1, copyOf(my().getItem(0)), ItemStack.EMPTY,
                    "my:inv:0", "box:my::inv:20::3", copyOf(my().getItem(20)));
            GearSwapResult r = submit(mine);
            assertTrue(r.ok(), "a purely-my-side box move must not be blocked by her program: " + r.reason());
            assertTrue(matches(my().getItem(0), ItemStack.EMPTY)
                    && matches(my().getItem(20), testContainer(27, new ItemStack(Items.DIRT, 10))),
                    "the my-side put did not land where it should");
        } finally {
            setProgramRunning(false);
        }
    }

    // ==================== 请求与观察 ====================

    /** dir 6(放入):fromRef = 背包格,toRef = box 地址;期望值照面板口径从当前真值拍。 */
    private static GearSwapRequest putIntoBox(long txId, boolean fromHer, int bagSlot, String boxRef) {
        return new GearSwapRequest(txId, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.BOX_PUT, -1, -1,
                copyOf(bag(fromHer).getItem(bagSlot)), copyOf(inner(boxRef, innerOf(boxRef))),
                fromHer ? "her:inv:" + bagSlot : "my:inv:" + bagSlot, boxRef, copyOf(boxStack(boxRef)));
    }

    /** dir 5(取出):fromRef = box 地址,toRef = 背包格;期望值同上。 */
    private static GearSwapRequest takeFromBox(long txId, boolean toHer, int bagSlot, String boxRef) {
        return new GearSwapRequest(txId, BODY_ID, "", ItemStack.EMPTY, ItemStack.EMPTY,
                GearSwapRequest.BOX_TAKE, -1, -1,
                copyOf(inner(boxRef, innerOf(boxRef))), copyOf(bag(toHer).getItem(bagSlot)),
                boxRef, toHer ? "her:inv:" + bagSlot : "my:inv:" + bagSlot, copyOf(boxStack(boxRef)));
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

    /** 走读路径的入口(handle,含信任门),回测试通道里最近的那张 BoxReadResult。 */
    private static BoxReadResult read(long txId, String boxRef) {
        BoxReadRequest.handle(new BoxReadRequest(txId, BODY_ID, boxRef, copyOf(boxStack(boxRef))), owner);
        List<com.dwinovo.numen.network.NumenPayload> sent = com.dwinovo.numen.platform.TestNetworkChannel.SENT;
        assertFalse(sent.isEmpty(), "no read result was sent for tx " + txId);
        com.dwinovo.numen.network.NumenPayload last = sent.get(sent.size() - 1);
        assertTrue(last instanceof BoxReadResult, "the last sent payload was not a BoxReadResult: " + last);
        return (BoxReadResult) last;
    }

    private static Inventory her() {
        return body.getInventory();
    }

    private static Inventory my() {
        return owner.getInventory();
    }

    private static Inventory bag(boolean hers) {
        return hers ? her() : my();
    }

    private static int innerOf(String boxRef) {
        SlotRefs.Parsed parsed = SlotRefs.parse(boxRef);
        if (parsed.error() != null) {
            throw new IllegalArgumentException(boxRef);
        }
        return parsed.boxInner();
    }

    private static ItemStack boxStack() {
        return her().getItem(ANCHOR);
    }

    private static ItemStack boxStack(String boxRef) {
        Resolve resolved = ContainerBoxes.resolve(owner, body, boxRef);
        if (resolved.error() != null) {
            return ItemStack.EMPTY;   // 面板对着一个已经开不成的盒:期望值如实给空
        }
        return resolved.stack();
    }

    /** 盒内第 n 格现状(经 resolve,与面板的读同一条路)。 */
    private static ItemStack inner(String boxRef, int inner) {
        Resolve resolved = ContainerBoxes.resolve(owner, body, boxRef);
        if (resolved.error() != null) {
            return ItemStack.EMPTY;
        }
        return inner >= resolved.box().slots() ? ItemStack.EMPTY : resolved.box().slot(inner).copy();
    }

    /** 各用例盯住的内格下标:box 地址用 inner slot 3,内容也写在这里起。 */
    private static final int INNER = 3;

    /** 一件测试容器物品(测试假解析器的标记格式);第 i 件内容写进 inner slot {@link #INNER} + i。 */
    private static ItemStack testContainer(int slots, ItemStack... contents) {
        ItemStack box = new ItemStack(Items.BUNDLE, 1);
        CompoundTag marker = box.getOrCreateTagElement(com.dwinovo.numen.platform.TestItemContainerResolver.KEY);
        marker.putInt("Slots", slots);
        NonNullList<ItemStack> list = NonNullList.withSize(slots, ItemStack.EMPTY);
        for (int i = 0; i < contents.length && INNER + i < slots; i++) {
            list.set(INNER + i, contents[i].copy());
        }
        ContainerHelper.saveAllItems(marker, list);
        return box;
    }

    /** 把一件测试盒放进她背包的锚点格(内容落在第 0..n 格)。 */
    private static void putBoxInHerPack(int slot, int slots, ItemStack... contents) {
        her().setItem(slot, testContainer(slots, contents));
    }

    private static ItemStack copyOf(ItemStack stack) {
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static boolean matches(ItemStack a, ItemStack b) {
        return ItemStack.matches(a, b);
    }

    /**
     * 三方合计的守恒多重集,测试这边照公式再算一遍(与 {@code GearTransferTxTest} 同一惯例):
     * 两个背包的键是「物品 id + 组件」,唯独锚点堆那一项换掩蔽键——掩蔽键测试自己从 NBT 剥
     * (id + 剥掉 BoxTest.Items 的 tag),不借 provider 的实现,剥错了测试当场发现。
     */
    private static Map<String, Integer> account() {
        Map<String, Integer> out = new TreeMap<>();
        mergeGrid(out, her(), ANCHOR);
        mergeGrid(out, my(), -1);
        Resolve resolved = ContainerBoxes.resolve(owner, body, BOX_REF_3);
        if (resolved.error() == null) {
            for (int i = 0; i < resolved.box().slots(); i++) {
                mergeNormal(out, resolved.box().slot(i));
            }
        }
        return out;
    }

    private static void mergeGrid(Map<String, Integer> out, Inventory inv, int anchorSlot) {
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (i == anchorSlot) {
                out.merge(TestItemContainerResolverMasked.key(stack), stack.getCount(), Integer::sum);
            } else {
                mergeNormal(out, stack);
            }
        }
    }

    private static void mergeNormal(Map<String, Integer> out, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        out.merge(stack.getItem() + "#" + (stack.getTag() == null ? "" : stack.getTag()),
                stack.getCount(), Integer::sum);
    }

    /** 测试自己的掩蔽键(id + 剥掉内容清单的 tag),独立于 provider 的实现。 */
    private static final class TestItemContainerResolverMasked {
        static String key(ItemStack stack) {
            CompoundTag copy = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
            copy.getCompound(com.dwinovo.numen.platform.TestItemContainerResolver.KEY)
                    .remove(com.dwinovo.numen.platform.TestItemContainerResolver.ITEMS);
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()) + "#" + copy;
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

    // ==================== 夹具:空壳身体 / 主人 / 世界(与 GearTransferTxTest 同款) ====================

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

    /**
     * 两方都能收到回执/被读路径找到的那条最小通路:壳服务器的玩家表里装着主人与身体,
     * GearSwapService 的 conclude 与 BoxReadRequest.handle 的 findByUuid 都能应答。
     */
    private static void registerOwnerInServer(ServerPlayer owner) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        // MinecraftServer 是抽象类,jdk 的 Unsafe 起不了壳;专用服是它的具体子类,构造器不跑,一样只有字段。
        MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(
                net.minecraft.server.dedicated.DedicatedServer.class);
        PlayerList list = (PlayerList) unsafe.allocateInstance(
                net.minecraft.server.dedicated.DedicatedPlayerList.class);
        Field playersByUuid = PlayerList.class.getDeclaredField("playersByUUID");
        playersByUuid.setAccessible(true);
        HashMap<UUID, ServerPlayer> players = new HashMap<>();
        players.put(owner.getUUID(), owner);
        players.put(BODY_ID, body);   // 读路径的 findByUuid(companion) 要找到她
        playersByUuid.set(list, players);
        Field playerList = MinecraftServer.class.getDeclaredField("playerList");
        playerList.setAccessible(true);
        playerList.set(server, list);
        Field serverField = ServerPlayer.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(owner, server);
        SERVER = server;
    }

    /** 壳世界要应答 {@code level().getServer()}(findByUuid 的入口),registerOwnerInServer 填进来。 */
    private static MinecraftServer SERVER;

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

    /**
     * 无头 JVM 里让 {@code ServerPrograms.running(body)} 为真:Unsafe 起一个 Running 壳塞进 RUNNING 表
     * ({@code running()} 只查 {@code containsKey},壳够用;字段全不碰)。测完必须摘掉,别毒化别的用例。
     */
    private static void setProgramRunning(boolean running) throws Exception {
        Class<?> sp = Class.forName("com.dwinovo.numen.program.ServerPrograms");
        Field mapField = sp.getDeclaredField("RUNNING");
        mapField.setAccessible(true);
        sun.misc.Unsafe unsafe = unsafe();
        unsafe.ensureClassInitialized(sp);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> map = (Map<UUID, Object>) unsafe.getObject(
                unsafe.staticFieldBase(mapField), unsafe.staticFieldOffset(mapField));
        if (running) {
            Class<?> runningClass = Class.forName("com.dwinovo.numen.program.ServerPrograms$Running");
            map.put(BODY_ID, unsafe.allocateInstance(runningClass));
        } else {
            map.remove(BODY_ID);
        }
    }

    /** 壳世界:原点永远算已加载,getServer 应答夹具服务器(读路径的信任门要查玩家表)。 */
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

        @Override
        public net.minecraft.server.MinecraftServer getServer() {
            return SERVER;
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
        public float getShade(net.minecraft.core.Direction direction, boolean shade) { return 0.0f; }
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
