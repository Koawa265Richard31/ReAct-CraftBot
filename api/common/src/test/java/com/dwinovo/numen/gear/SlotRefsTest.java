package com.dwinovo.numen.gear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlotRefs} 的文法:第三方持有者与一切不合文法的地址一律 {@code error != null}
 * (spec-m4-containers §六第 8 条的机器判据——§3.3 的读权限第一条落点:第三方在文法里没有写法),
 * 合文法的地址解析出准确的侧别、锚点与内格。不引导 MC,纯字符串测试。
 */
class SlotRefsTest {

    /** 权限:holder 只有 my|her——第三方的地址没有写法,一律拒。 */
    @Test
    void aThirdPartyHolderHasNoWayToAddressABox() {
        for (String ref : new String[] {
                "box:steve::inv:3::0", "box:alex::inv:3::0", "box::inv:3::0",
                "box:her::inv:3::0::1", "box:my:inv:3::0"}) {
            assertTrue(SlotRefs.parse(ref).error() != null, "a third-party/malformed box address parsed: " + ref);
            assertEquals(SlotRefs.PARSE_REFUSAL, SlotRefs.parse(ref).error());
        }
    }

    /** 文法:分段、锚点家族、内格序号——每一个坏形状都是同一句拒。 */
    @Test
    void malformedAddressesAllRefuseWithTheOneSentence() {
        for (String ref : new String[] {
                "", "   ", null,
                "box:her::inv:3",            // 少一段
                "box:her::inv:3::0::1",      // 多一段
                "box:her::inv:abc::0",       // 背包格不是数字
                "box:her::inv:36::0",        // 越过 0-35
                "box:her::inv:-1::0",
                "box:her::inv:3::x",         // 内格不是数字
                "box:her::inv:3::-1",        // 内格为负
                "box:her::gear::0",          // gear 锚点没有名字
                "box:her::box:her::inv:3::0",// box: 锚点——深度恒为 1 的文法死规矩,第 2 层没有地址
                "inv:5",                     // 裸 inv(无侧别)是 M3 int 字段的事,这个家族不认
                "my:inv:36",                 // counterparty 同样限 0-35
                "my:inv:abc",
                "her:inv",
                "chest:her::inv:3::0"}) {
            assertTrue(SlotRefs.parse(ref).error() != null, "a malformed address parsed: " + ref);
        }
    }

    /** 反向:合文法的地址解析准确——背包锚点、gear 锚点(带/不带 #n)、counterparty 两族。 */
    @Test
    void wellFormedAddressesParseExactly() {
        SlotRefs.Parsed herInv = SlotRefs.parse("box:her::inv:12::3");
        assertNull(herInv.error());
        assertTrue(herInv.hers());
        assertEquals(12, herInv.invIndex());
        assertNull(herInv.gearName());
        assertEquals(3, herInv.boxInner());

        SlotRefs.Parsed myInv = SlotRefs.parse("box:my::inv:0::27");
        assertNull(myInv.error());
        assertTrue(!myInv.hers());
        assertEquals(0, myInv.invIndex());
        assertEquals(27, myInv.boxInner());

        SlotRefs.Parsed curio = SlotRefs.parse("box:her::gear:curios:back#0::1");
        assertNull(curio.error());
        assertTrue(curio.hers());
        assertEquals(-1, curio.invIndex());
        assertEquals("curios:back", curio.gearName());
        assertEquals(0, curio.gearOrdinal());
        assertEquals(1, curio.boxInner());

        // gear 锚点的 #n 可省(= 第 0 个);名字里带 # 但后缀不是数字时,整段按名字算。
        SlotRefs.Parsed plainGear = SlotRefs.parse("box:her::gear:head::2");
        assertNull(plainGear.error());
        assertEquals("head", plainGear.gearName());
        assertEquals(0, plainGear.gearOrdinal());
        assertEquals(2, plainGear.boxInner());

        SlotRefs.Parsed ring1 = SlotRefs.parse("box:her::gear:curios:ring#1::0");
        assertNull(ring1.error());
        assertEquals("curios:ring", ring1.gearName());
        assertEquals(1, ring1.gearOrdinal());

        SlotRefs.Parsed mine = SlotRefs.parse("my:inv:5");
        assertNull(mine.error());
        assertTrue(!mine.hers());
        assertEquals(5, mine.invIndex());
        assertEquals(-1, mine.boxInner());

        SlotRefs.Parsed hers = SlotRefs.parse("her:inv:9");
        assertNull(hers.error());
        assertTrue(hers.hers());
        assertEquals(9, hers.invIndex());
        assertEquals(-1, hers.boxInner());
    }

    /** 反拼:formatBox 的产物原样解析回来,侧别、锚点、内格三样不丢。 */
    @Test
    void formatBoxRoundTripsThroughParse() {
        for (boolean hers : new boolean[] {true, false}) {
            for (String anchor : new String[] {"inv:12", "gear:head", "gear:curios:back#1"}) {
                for (int inner : new int[] {0, 26}) {
                    String ref = SlotRefs.formatBox(hers, anchor, inner);
                    SlotRefs.Parsed parsed = SlotRefs.parse(ref);
                    assertNull(parsed.error(), "formatBox produced an unparseable address: " + ref);
                    assertEquals(hers, parsed.hers(), ref);
                    assertEquals(inner, parsed.boxInner(), ref);
                    assertEquals(anchor, SlotRefs.anchorText(parsed), ref);
                }
            }
        }
    }
}
