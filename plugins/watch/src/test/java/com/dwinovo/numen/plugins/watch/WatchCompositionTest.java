package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合三件套的契约(拍板口径 2026-11-07:或 / 与(至多两元)/ 非),全部在注册期与求值期钉死:
 * <b>与</b> —— 恰两元、操作数只能是原语、单轮边沿禁入、共用一张参数表且每个参数都得有人读;
 * <b>非</b> —— 只包电平原语(事件不能取非)、独占顶层一层;非法组合在 {@code build} 当场拒收,
 * 回执说清哪一条不合法。<b>事件类的边沿/去重/冷却</b> —— 同一 tick 多次同类发生合并为一条、
 * 冷却压住的边沿对持有型事件(attacked)在期满后补发、单轮边沿错过即丢。全程不碰世界:快照手摆。
 */
class WatchCompositionTest {

    @AfterEach
    void cleanUp() {
        WatchEngine.dropAll();
    }

    private static final UUID HER = UUID.randomUUID();

    // ---- 与:attacked and blood_below(#15 的受击∧血量低)----

    @Test
    void andFiresOnlyWhenBothHold() {
        WatchSub sub = andHurtLow();
        // 只掉血没挨打:不触发
        assertFalse(sub.fire(snap(0, 9, 1000)), "血低但没挨打,不触发");
        // 只挨打没掉血:不触发(受击的命中态持有,等血掉下来)
        assertFalse(sub.fire(snap(1, 15, 1004)), "挨打但血量尚可,不触发");
        // 先挨打后掉血:两件事同时成立的那一拍,触发一次
        assertTrue(sub.fire(snap(1, 9, 1008)), "受击且血量低于阈值,触发");
        // 持续低血 + 继续挨打:不重发(边沿;触发后基线已刷新)
        assertFalse(sub.fire(snap(2, 9, 1012)), "持续成立不重发");
        // 血回到线上:边沿复位
        assertFalse(sub.fire(snap(2, 15, 1016)));
        // 又挨打又掉血:再触发
        assertTrue(sub.fire(snap(3, 9, 1020)), "再同时成立,再触发");
    }

    @Test
    void andFiresWhenBloodDropsFirstAndTheHitComesLater() {
        WatchSub sub = andHurtLow();
        // 先掉血后挨打(受击命中态由计数持有,间隔任意都会等到同时成立)
        assertFalse(sub.fire(snap(0, 9, 1000)));
        assertTrue(sub.fire(snap(1, 9, 1004)), "掉血在前、挨打在后,同一拍成立即触发");
    }

    // ---- 与:注册期校验 ----

    @Test
    void andTakesExactlyTwoPrimitives() {
        ApiError one = assertThrows(ApiError.class, () -> buildBoth(List.of("attacked")));
        assertTrue(one.getMessage().contains("exactly two"), one.getMessage());
        ApiError three = assertThrows(ApiError.class, () ->
                buildBoth(List.of("attacked", "blood_below", "on_fire")));
        assertTrue(three.getMessage().contains("exactly two"), three.getMessage());
        assertTrue(three.getMessage().contains("a third layer does not exist"), three.getMessage());
    }

    @Test
    void andRejectsSingleRoundEdges() {
        ApiError gone = assertThrows(ApiError.class, () -> buildBoth(List.of("target_gone", "blood_below")));
        assertEquals(ErrorKind.BAD_ARGUMENT, gone.kind());
        assertTrue(gone.getMessage().contains("target_gone cannot take part in and"), gone.getMessage());
        ApiError block = assertThrows(ApiError.class, () -> buildBoth(List.of("blood_below", "block_changed")));
        assertTrue(block.getMessage().contains("block_changed cannot take part in and"), block.getMessage());
    }

    @Test
    void andRejectsUnknownPrimitiveAndOrphanKnobs() {
        ApiError unknown = assertThrows(ApiError.class, () -> buildBoth(List.of("nope", "blood_below")));
        assertTrue(unknown.getMessage().contains("blood_below (her health drops"),
                "报错里要列出全部原语: " + unknown.getMessage());
        // attacked 与 inv_full 都不读 threshold:共用表里的参数必须有人读
        ApiError orphan = assertThrows(ApiError.class, () ->
                WatchEngine.build("w", Optional.empty(), Optional.of(List.of("attacked", "inv_full")),
                        Optional.empty(), kThreshold(10.0), "why", "", "p1", 1000, 0));
        assertTrue(orphan.getMessage().contains("neither attacked nor inv_full reads"), orphan.getMessage());
    }

    @Test
    void andSharesOneArgsTableWithoutTheNoKnobVeto() {
        // attacked 不收参数,但 and 的共用表里有 blood_below 要的 threshold:不再被 takeNone 拒掉
        WatchSub sub = andHurtLow();
        assertEquals(WatchSub.Form.AND, sub.form);
        assertEquals("attacked and blood_below", sub.condition());
    }

    // ---- 非:只包电平原语、独占顶层 ----

    @Test
    void notFiresWhileTheLevelDoesNotHold() {
        // not in_water:没在水里就成立 —— 「守渡口:先保住退路再接敌」
        WatchSub sub = buildNegate("in_water");
        assertEquals("not in_water", sub.condition());
        assertFalse(sub.fire(snap(0, 20, 1000, true)), "在水里,not 不成立");
        assertTrue(sub.fire(snap(0, 20, 1004, false)), "出了水,触发一次");
        assertFalse(sub.fire(snap(0, 20, 1008, false)), "一直没在水里,不重发");
        assertFalse(sub.fire(snap(0, 20, 1012, true)), "又下水,边沿复位");
        assertTrue(sub.fire(snap(0, 20, 1016, false)), "再出水,再触发");
    }

    @Test
    void notRejectsEventPrimitives() {
        for (String event : List.of("attacked", "target_gone", "block_changed")) {
            ApiError bad = assertThrows(ApiError.class, () -> buildNegate(event), event);
            assertEquals(ErrorKind.BAD_ARGUMENT, bad.kind(), event);
            assertTrue(bad.getMessage().contains("not cannot wrap " + event), event + ": " + bad.getMessage());
            assertTrue(bad.getMessage().contains("it is an event, not a level"), event + ": " + bad.getMessage());
        }
    }

    @Test
    void notIsTopLevelOnlyAndTheThreeShapesAreMutuallyExclusive() {
        // negate 与 primitive 同时给:拒(三选一)
        ApiError mixed = assertThrows(ApiError.class, () ->
                WatchEngine.build("w", Optional.of("in_water"), Optional.empty(), Optional.of("on_fire"),
                        WatchApi.Knobs.EMPTY, "why", "", "p1", 1000, 0));
        assertTrue(mixed.getMessage().contains("give exactly one"), mixed.getMessage());
        // negate 与 both 同时给:拒(三选一)—— not 不能套在组合上,顶层一层由形状保证
        ApiError nested = assertThrows(ApiError.class, () ->
                WatchEngine.build("w", Optional.empty(), Optional.of(List.of("attacked", "on_fire")),
                        Optional.of("on_fire"), WatchApi.Knobs.EMPTY, "why", "", "p1", 1000, 0));
        assertTrue(nested.getMessage().contains("give exactly one"), nested.getMessage());
        // not not:形面上表达不出来 —— negate 只收一个原语名,把它包进去的写法不存在
    }

    @Test
    void aConditionShapeIsMandatory() {
        ApiError none = assertThrows(ApiError.class, () ->
                WatchEngine.build("w", Optional.empty(), Optional.empty(), Optional.empty(),
                        WatchApi.Knobs.EMPTY, "why", "", "p1", 1000, 0));
        assertTrue(none.getMessage().contains("one of primitive"), none.getMessage());
        assertTrue(none.getMessage().contains("both = {\"attacked\", \"blood_below\"}"), none.getMessage());
    }

    // ---- 事件类:同一 tick 合并、冷却(可配)----

    @Test
    void sameTickHitsMergeIntoOneReport() {
        // 同一 tick 挨两下:一轮只报一条(同一 tick 的多次同类发生合并为一条)
        WatchEngine.arm(HER, WatchEngine.build("hit", "attacked", WatchApi.Knobs.EMPTY, "why", "p1", 1000, 0));
        WatchEngine.hurt(HER);
        WatchEngine.hurt(HER);
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, snap(2, 20, 1000, false));
        assertEquals(1, fired.size(), "同一 tick 的两下合并为一条命中");
        assertEquals("attacked", fired.get(0).primitive());
    }

    @Test
    void twoSubscriptionsHittingInTheSameRoundShareTheTick() {
        WatchEngine.arm(HER, WatchEngine.build("hit", "attacked", WatchApi.Knobs.EMPTY, "why", "p1", 1000, 0));
        WatchEngine.arm(HER, WatchEngine.build("low", "blood_below", kThreshold(10.0), "why", "p1", 1000, 0));
        // 挨打且血低:同一轮两条命中,tick 相同
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, snap(1, 9, 1000, false));
        assertEquals(2, fired.size());
        assertEquals(1000, fired.get(0).tick());
        assertEquals(1000, fired.get(1).tick());
    }

    @Test
    void eventCooldownSuppressesThenReleasesTheHeldEdge() {
        WatchSub sub = WatchEngine.build("hit", "attacked", WatchApi.Knobs.EMPTY, "why", "p1", 1000, 0);
        // 冷却 10 tick:tick 100 触发
        assertTrue(sub.fire(snap(1, 20, 100, false), 10));
        // tick 105(冷却内)又挨一下:边沿被压住,不触发
        assertFalse(sub.fire(snap(2, 20, 105, false), 10), "冷却期内不重发");
        // 冷却期满:被压住的边沿(命中态持有)补发一次 —— 报警不丢
        assertTrue(sub.fire(snap(2, 20, 112, false), 10), "冷却期满,压住的边沿补发");
        assertFalse(sub.fire(snap(2, 20, 116, false), 10), "补发后不再重发");
    }

    @Test
    void eventCooldownZeroKeepsTheV0PerHitReporting() {
        WatchSub sub = WatchEngine.build("hit", "attacked", WatchApi.Knobs.EMPTY, "why", "p1", 1000, 0);
        assertTrue(sub.fire(snap(1, 20, 100, false), 0));
        assertTrue(sub.fire(snap(2, 20, 104, false), 0), "缺省 0(关):下一拍挨打照报,v0 语义");
        assertFalse(sub.fire(snap(2, 20, 108, false), 0));
    }

    @Test
    void theCooldownPropertyIsParsedLikeTheEveryProperty() {
        assertEquals(0, WatchEngine.cooldownTicks("0"));
        assertEquals(0, WatchEngine.cooldownTicks("-3"));
        assertEquals(7, WatchEngine.cooldownTicks("7"));
        assertEquals(WatchEngine.DEFAULT_EVENT_COOLDOWN, WatchEngine.cooldownTicks("not-a-number"));
        assertEquals(0, WatchEngine.DEFAULT_EVENT_COOLDOWN, "缺省 0 = 冷却关,v0 语义保留");
        assertTrue(WatchEngine.eventCooldownTicks() >= 0);
    }

    @Test
    void thePrimitiveSetKeepsItsThreeEventsAndTheirClassifications() {
        // 事件类恰是拍板点名的三个;其余十六个是电平
        assertEquals(3, countKind(WatchPrimitive.Kind.EVENT));
        for (String id : List.of("attacked", "target_gone", "block_changed")) {
            assertEquals(WatchPrimitive.Kind.EVENT, WatchPrimitive.of(id).kind(), id);
            assertFalse(WatchPrimitive.of(id).isLevel(), id + " 不是电平,不能被 not 包");
        }
        assertEquals(WatchPrimitive.Kind.LEVEL, WatchPrimitive.of("daytime").kind(), "daytime 真值持有,按电平");
        assertEquals(WatchPrimitive.Kind.LEVEL, WatchPrimitive.of("elapsed").kind());
        for (WatchPrimitive p : WatchPrimitive.values()) {
            if (p.kind() == WatchPrimitive.Kind.EVENT) {
                assertFalse(p.andAdmits() && !p.isLevel() && p != WatchPrimitive.ATTACKED,
                        p.id() + ": 事件里只有 attacked 兼入 and");
            }
        }
        assertTrue(WatchPrimitive.ATTACKED.andAdmits(), "attacked 命中态由计数持有,准入 and(#15 的受击∧血低)");
    }

    private static long countKind(WatchPrimitive.Kind kind) {
        long n = 0;
        for (WatchPrimitive p : WatchPrimitive.values()) {
            if (p.kind() == kind) {
                n++;
            }
        }
        return n;
    }

    // ---- 小工具 ----

    /** and 的受击∧血低(hurt_low,#15 的形状):共用表里给 blood_below 的 threshold。 */
    private static WatchSub andHurtLow() {
        return WatchEngine.build("hurt_low", Optional.empty(),
                Optional.of(List.of("attacked", "blood_below")), Optional.empty(), kThreshold(10.0),
                "挨了打且血量过半,撤", "", "p1", 1000, 0);
    }

    private static WatchSub buildBoth(List<String> pair) {
        return WatchEngine.build("w", Optional.empty(), Optional.of(pair), Optional.empty(),
                WatchApi.Knobs.EMPTY, "why", "", "p1", 1000, 0);
    }

    private static WatchSub buildNegate(String id) {
        return WatchEngine.build("w", Optional.empty(), Optional.empty(), Optional.of(id),
                WatchApi.Knobs.EMPTY, "why", "", "p1", 1000, 0);
    }

    private static WatchApi.Knobs kThreshold(double v) {
        return new WatchApi.Knobs(Optional.of(v), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static WatchSnapshot snap(int hurtCount, float health, long nowTick) {
        return snap(hurtCount, health, nowTick, false);
    }

    private static WatchSnapshot snap(int hurtCount, float health, long nowTick, boolean inWater) {
        return new WatchSnapshot(health, 20, false, false, inWater, false, 0, 100, 0, hurtCount, nowTick,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of());
    }
}
