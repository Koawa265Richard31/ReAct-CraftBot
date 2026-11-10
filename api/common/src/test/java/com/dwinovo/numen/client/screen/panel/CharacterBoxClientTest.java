package com.dwinovo.numen.client.screen.panel;

import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.payload.BoxReadRequest;
import com.dwinovo.numen.network.payload.BoxReadResult;
import com.dwinovo.numen.network.payload.GearSwapResult;
import com.dwinovo.numen.network.payload.RequestStatePayload;
import com.dwinovo.numen.platform.TestNetworkChannel;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板角色页(M4 容器)的<b>客户端状态机</b>在无头 JVM 里验:读回执的认领与真值落地、失败读不掀
 * 已展开的盒、截断照实亮出、容器写回执落她那格背包真值并顺势拉「状态 + 整盒重读」、写权限闸关着
 * 内格点了不发任何请求、一次一单(读在飞时点内格只提示不动手)。判定的是
 * {@code CharacterPage} 的私有静态状态(反射直读)与 {@code TestNetworkChannel.SENT_C2S} 里
 * 真的发出去的上行载荷——不做乐观更新、内容只来自服务端事实这两条,是这里守着的底线。
 *
 * <p>夹具与 {@code ContainerBoxTxTest} 同款:裸 JVM 把 {@code isBootstrapped} 标记立起来,注册表、
 * 方块与物品各自初始化得动;网络走测试假通道。纯状态机,不起任何界面。
 */
class CharacterBoxClientTest {

    private static final UUID OWNER_ID = UUID.fromString("c1a5ab00-0000-0000-0000-000000000001");

    /** 测试用的一盒:地址、锚点堆、内容(1 格土 x2、1 格空、1 格苹果 x5)。 */
    private static final String REF = "box:her::inv:3::0";

    @BeforeAll
    static void boot() throws Exception {
        // Forge 补过的 Bootstrap.bootStrap() 尾上带 NetworkHooks.init(要 modlauncher 的运行时上下文,
        // 裸 JVM 没有);单测真正用到的几样——注册表、方块与物品——把标记立起来就各自初始化得动
        // (与 ContainerBoxTxTest 同一个做法)。
        SharedConstants.tryDetectVersion();
        Field flag = Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true);
        flag.setBoolean(null, true);
        Class.forName("net.minecraft.core.registries.BuiltInRegistries");
        Class.forName("net.minecraft.world.level.block.Blocks");
        Class.forName("net.minecraft.world.item.Items");
        NumenNetwork.register();   // 测试假通道在场:路由表就位,上行动作真的发进 SENT_C2S
    }

    @AfterAll
    static void restoreGate() throws Exception {
        setField("com.dwinovo.numen.client.data.ClientPrefs", "gearWriteAllowed", false);
    }

    @BeforeEach
    void reset() throws Exception {
        setLong("pendingTx", 0);
        setBool("pendingBoxRead", false);
        setBool("pendingBoxWrite", false);
        setRef("pendingBoxRef", null);
        setInt("pendingBoxInner", -1);
        setInt("pendingDir", 0);
        setInt("pendingPackIdx", -1);
        setInt("pendingMyIdx", -1);
        setRef("pendingCompanion", null);
        setRef("openBoxRef", null);
        setRef("boxStackAfter", ItemStack.EMPTY);
        ((List<?>) field("BOX_CONTENTS")).clear();
        setBool("boxTruncated", false);
        setInt("selBoxInner", -1);
        setInt("selMyIdx", -1);
        setRef("messageKey", null);
        setRef("messageRaw", null);
        Arrays.fill((ItemStack[]) field("PACK_TRUTH"), null);
        setField("com.dwinovo.numen.client.data.ClientPrefs", "gearWriteAllowed", false);
        TestNetworkChannel.SENT.clear();
        TestNetworkChannel.SENT_C2S.clear();
    }

    // ==================== 读回执:认领与真值 ====================

    /** 旁牌不认:txId 对不上的读回执不进状态,在飞的那笔原样挂着。 */
    @Test
    void aBoxReadReceiptForAnotherTxIsNotClaimed() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxRead", true);
        acceptBoxRead(new BoxReadResult(2, true, "the box has 27 inner slots, 0 of them filled",
                REF, new ItemStack(Items.SHULKER_BOX), List.of(ItemStack.EMPTY), false));
        assertEquals(1L, field("pendingTx"), "a mismatched receipt must not free the pending tx");
        assertNull(field("openBoxRef"), "a mismatched receipt must not open anything");
        assertEquals(0, TestNetworkChannel.SENT_C2S.size(), "a mismatched receipt must not trigger follow-ups");
    }

    /** 成功的读落整盒真值:地址、锚点堆、内格清单(EMPTY 格也是事实)各就各位,在飞位放开。 */
    @Test
    void aSuccessfulReadLandsTheWholeBoxAsTruth() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxRead", true);
        setRef("pendingBoxRef", REF);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        List<ItemStack> contents = List.of(
                new ItemStack(Items.DIRT, 2), ItemStack.EMPTY, new ItemStack(Items.APPLE, 5));
        acceptBoxRead(new BoxReadResult(1, true, "the box has 27 inner slots, 2 of them filled",
                REF, box, contents, false));
        assertEquals(REF, field("openBoxRef"));
        assertEquals(0L, field("pendingTx"), "the receipt must free the pending slot");
        List<?> landed = (List<?>) field("BOX_CONTENTS");
        assertEquals(3, landed.size());
        assertEquals(ItemStack.EMPTY, landed.get(1), "an empty inner slot is a fact, not an absence");
        assertTrue(ItemStack.matches(box, (ItemStack) field("boxStackAfter")),
                "the anchor stack from the receipt is the staleness baseline");
        assertNull(field("messageKey"), "a clean read says nothing");
    }

    /** 失败的读(不可展开等)不掀已展开的盒:画着的东西原样留着,reason 原样上页头。 */
    @Test
    void aFailedReadKeepsWhatWasDrawnAndSaysWhy() throws Exception {
        expandViaRead();
        // 同一只盒再读一次,这次服务端拒(resolve 不过)
        setLong("pendingTx", 2);
        setBool("pendingBoxRead", true);
        setRef("pendingBoxRef", REF);
        acceptBoxRead(new BoxReadResult(2, false, "that item has no openable container — it's not a shulker box"
                + " and exposes no inventory", REF, ItemStack.EMPTY, List.of(), false));
        assertEquals(REF, field("openBoxRef"), "a failed read must not collapse the open box");
        assertEquals(3, ((List<?>) field("BOX_CONTENTS")).size(), "a failed read must not wipe what was drawn");
        assertEquals("that item has no openable container — it's not a shulker box and exposes no inventory",
                field("messageRaw"), "the server's plain-language reason goes to the header verbatim");
        assertEquals(0L, field("pendingTx"));
    }

    /** 截断的读置 trunc 标记并把话亮出来:没送到的格画「…」,绝不画成空格。 */
    @Test
    void aTruncatedReadMarksTheUnknowns() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxRead", true);
        setRef("pendingBoxRef", REF);
        acceptBoxRead(new BoxReadResult(1, true,
                "the box has 27 inner slots — only the first 2 arrived (one packet tops out); draw the rest"
                        + " as unknown, not empty",
                REF, new ItemStack(Items.SHULKER_BOX),
                List.of(new ItemStack(Items.DIRT, 1), new ItemStack(Items.APPLE, 1)), true));
        assertTrue((boolean) field("boxTruncated"));
        assertEquals(ModLanguageData.Keys.PANEL_CHAR_BOX_TRUNC, field("messageKey"));
        assertEquals(2, ((List<?>) field("BOX_CONTENTS")).size());
    }

    // ==================== 容器写(dir 5/6)的回执 ====================

    /**
     * dir 5(取出)的回执:她那格背包落真值(回执的 ownerAfter 是 from→to 序里的背包端),随后
     * 两件上行——一份最新状态(对齐 gear 锚点那件堆的 NBT)+ 一次容器重读(整盒重画,回执只带
     * 动的那一格);重读占着在飞位,一次一单继续。
     */
    @Test
    void aBoxTakeReceiptLandsHerBagEndAndRepullsTheWholeBox() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxWrite", true);
        setInt("pendingDir", 5);   // GearSwapRequest.BOX_TAKE
        setInt("pendingPackIdx", 7);
        setRef("pendingCompanion", OWNER_ID);
        setRef("pendingBoxRef", REF);
        CharacterPage.acceptGearSwapResult(new GearSwapResult(1, true, "took 2 dirt out of the box",
                ItemStack.EMPTY, new ItemStack(Items.DIRT, 2)));
        ItemStack[] truth = (ItemStack[]) field("PACK_TRUTH");
        assertTrue(ItemStack.matches(new ItemStack(Items.DIRT, 2), truth[7]),
                "her bag end lands as receipt truth");
        List<NumenPayload> sent = TestNetworkChannel.SENT_C2S;
        assertEquals(2, sent.size(), "the receipt pulls state + a box re-read");
        assertTrue(sent.get(0) instanceof RequestStatePayload, "first: refresh her state (gear NBT moved)");
        assertTrue(sent.get(1) instanceof BoxReadRequest, "then: re-read the whole box");
        BoxReadRequest repull = (BoxReadRequest) sent.get(1);
        assertEquals(REF, repull.boxRef(), "the re-read targets the same box");
        assertEquals(OWNER_ID, repull.companion());
        long pending = (Long) field("pendingTx");
        assertTrue(pending != 0 && pending != 1L,
                "the re-read occupies the one-at-a-time slot with a fresh tx id, got " + pending);
        assertEquals(pending, repull.txId(), "the in-flight slot holds exactly the re-read it sent");
        assertTrue((boolean) field("pendingBoxRead"));
        assertFalse((boolean) field("pendingBoxWrite"));
    }

    /** dir 6(放入)且背包端在我这边:不本地写我的背包(原版容器同步带回),照旧拉状态与整盒重读。 */
    @Test
    void aBoxPutReceiptForMyBagLandsNothingLocalAndStillRepulls() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxWrite", true);
        setInt("pendingDir", 6);   // GearSwapRequest.BOX_PUT
        setInt("pendingPackIdx", -1);
        setInt("pendingMyIdx", 4);
        setRef("pendingCompanion", OWNER_ID);
        setRef("pendingBoxRef", REF);
        CharacterPage.acceptGearSwapResult(new GearSwapResult(1, true, "put 3 apples into the box",
                new ItemStack(Items.APPLE, 3), new ItemStack(Items.APPLE, 3)));
        for (int i = 0; i < 36; i++) {
            assertNull(((ItemStack[]) field("PACK_TRUTH"))[i],
                    "my own backpack is never written locally — vanilla sync carries the truth");
        }
        List<NumenPayload> sent = TestNetworkChannel.SENT_C2S;
        assertEquals(2, sent.size());
        assertTrue(sent.get(0) instanceof RequestStatePayload);
        assertTrue(sent.get(1) instanceof BoxReadRequest);
    }

    /** 失败的容器写:什么都不落、什么都不拉,reason 原样上页头(服务端守恒/指纹拒的那句)。 */
    @Test
    void aFailedBoxWriteLandsNothingAndRepullsNothing() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxWrite", true);
        setInt("pendingDir", 5);
        setInt("pendingPackIdx", 7);
        setRef("pendingCompanion", OWNER_ID);
        setRef("pendingBoxRef", REF);
        CharacterPage.acceptGearSwapResult(new GearSwapResult(1, false,
                "the container in that slot is not what you saw — something changed; refresh and try again",
                ItemStack.EMPTY, ItemStack.EMPTY));
        for (int i = 0; i < 36; i++) {
            assertNull(((ItemStack[]) field("PACK_TRUTH"))[i], "a refused write lands nothing");
        }
        assertEquals(0, TestNetworkChannel.SENT_C2S.size(), "a refused write pulls nothing");
        assertEquals(0L, field("pendingTx"));
        assertEquals("the container in that slot is not what you saw — something changed; refresh and try again",
                field("messageRaw"));
    }

    // ==================== 写权限闸与一次一单 ====================

    /** 闸关着:点盒内格只提示「写权限关着」,不起选格,更不发任何请求(展开只读同一条的写侧)。 */
    @Test
    void theWriteGateOffAnInnerCellClickArmsNothingAndSendsNothing() throws Exception {
        expandViaRead();
        setField("com.dwinovo.numen.client.data.ClientPrefs", "gearWriteAllowed", false);
        Object page = new CharacterPage();
        Method inner = CharacterPage.class.getDeclaredMethod("innerCellClicked", int.class);
        inner.setAccessible(true);
        assertTrue((Boolean) inner.invoke(page, 0), "the click is consumed either way");
        assertEquals(-1, field("selBoxInner"), "no box selection with the gate off");
        assertEquals(ModLanguageData.Keys.PANEL_CHAR_WRITE_LOCKED, field("messageKey"));
        assertEquals(0, TestNetworkChannel.SENT_C2S.size(), "the gate off must not send any write request");
    }

    /** 一次一单:有读在飞时点盒内格只提示「上一个操作还没回来」,不起选格、不发请求。 */
    @Test
    void anInnerClickWhileABusySlotIsTakenOnlyHints() throws Exception {
        expandViaRead();
        setField("com.dwinovo.numen.client.data.ClientPrefs", "gearWriteAllowed", true);
        setLong("pendingTx", 99);   // 上一单(读)还在飞
        Object page = new CharacterPage();
        Method inner = CharacterPage.class.getDeclaredMethod("innerCellClicked", int.class);
        inner.setAccessible(true);
        assertTrue((Boolean) inner.invoke(page, 0));
        assertEquals(-1, field("selBoxInner"));
        assertEquals(ModLanguageData.Keys.PANEL_CHAR_WRITE_PENDING, field("messageKey"));
        assertEquals(0, TestNetworkChannel.SENT_C2S.size());
    }

    // ==================== 夹具 ====================

    /** 走一遍成功读,把展开态立起来(地址、真值、内容 3 格)。 */
    private static void expandViaRead() throws Exception {
        setLong("pendingTx", 1);
        setBool("pendingBoxRead", true);
        setRef("pendingBoxRef", REF);
        acceptBoxRead(new BoxReadResult(1, true, "the box has 27 inner slots, 2 of them filled",
                REF, new ItemStack(Items.SHULKER_BOX),
                List.of(new ItemStack(Items.DIRT, 2), ItemStack.EMPTY, new ItemStack(Items.APPLE, 5)),
                false));
    }

    private static void acceptBoxRead(BoxReadResult p) {
        CharacterPage.acceptBoxReadResult(p);
    }

    private static Object field(String name) throws Exception {
        Field f = CharacterPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setLong(String name, long v) throws Exception {
        Field f = CharacterPage.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(null, v);
    }

    private static void setInt(String name, int v) throws Exception {
        Field f = CharacterPage.class.getDeclaredField(name);
        f.setAccessible(true);
        if (f.getType() == byte.class) {
            f.setByte(null, (byte) v);   // pendingDir 是 byte
        } else {
            f.setInt(null, v);
        }
    }

    private static void setBool(String name, boolean v) throws Exception {
        Field f = CharacterPage.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, v);
    }

    private static void setRef(String name, Object v) throws Exception {
        Field f = CharacterPage.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void setField(String className, String name, Object v) throws Exception {
        Field f = Class.forName(className).getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }
}
