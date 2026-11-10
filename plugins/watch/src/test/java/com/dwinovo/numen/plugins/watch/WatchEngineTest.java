package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎层的契约(不碰世界,快照手摆):<b>OR 组合</b> —— 表里任一命中即触发、没命中的不搭车;
 * <b>订阅注销</b> —— add/list/drop 的形状与容量;环形缓冲的"取走即清空"与容量;
 * 生命周期 —— REMOVE 清空、关服清空、换维度(键只有 UUID)天然保留。
 * 命中路径会调 {@code ServerPrograms.interrupt}:测试进程里没有程序在跑,按编号匹配不上,是无害的空操作
 * (api 的 ServerProgramsTest 早已证明引擎类在原版单测环境里装得起来)。
 */
class WatchEngineTest {

    @AfterEach
    void cleanUp() {
        WatchEngine.dropAll();
    }

    private static final UUID HER = UUID.randomUUID();

    private static void arm(String name, String primitive, WatchApi.Knobs knobs, String why) {
        WatchEngine.arm(HER, WatchEngine.build(name, primitive, knobs, why, "p1", 1000, 0));
    }

    private static WatchSnapshot snap(float health) {
        return snap(health, 100);
    }

    private static WatchSnapshot snap(float health, long nowTick) {
        return new WatchSnapshot(health, 20, false, false, false, false, 0, 100, 0, 0, nowTick,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of());
    }

    private static WatchSnapshot snapWith(Map<String, Integer> itemCounts) {
        return new WatchSnapshot(20, 20, false, false, false, false, 0, 100, 0, 0, 100,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), itemCounts);
    }

    // ---- OR 组合 ----

    @Test
    void anyHitFiresAndOnlyTheHitOnesFire() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        arm("dark", "daytime", kTick(13000.0), "nightfall");
        arm("rich", "item_at_least", kItem("minecraft:diamond", 3.0), "rich enough");

        // 血 15、白天、钻石 3:只有 item_at_least 命中(>= 含等于),OR 出一条
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, new WatchSnapshot(15, 20, false, false, false,
                false, 0, 100, 1000, 0, 100, Map.of(), Double.MAX_VALUE, Map.of(), Map.of(),
                Map.of("minecraft:diamond", 3)));
        assertEquals(1, fired.size());
        assertEquals("rich", fired.get(0).name());

        // 血 9、入夜、钻石 4:两条同时命中,OR 出两条
        fired = WatchEngine.evaluate(HER, new WatchSnapshot(9, 20, false, false, false, false, 0, 100,
                13000, 0, 104, Map.of(), Double.MAX_VALUE, Map.of(), Map.of(),
                Map.of("minecraft:diamond", 4)));
        assertEquals(Set.of("low", "dark"), Set.of(fired.get(0).name(), fired.get(1).name()));
    }

    @Test
    void aHitBufferedAndInterruptedCarriesTheWhy() {
        arm("low", "blood_below", kThreshold(10.0), "my blood is low: stop and eat");
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, snap(9));
        assertEquals("my blood is low: stop and eat", fired.get(0).why());
        assertEquals("blood_below", fired.get(0).primitive());
        assertEquals(100, fired.get(0).tick(), "事件的 tick 是快照的 tick");
    }

    @Test
    void anUnknownCompanionEvaluatesToNothing() {
        assertTrue(WatchEngine.evaluate(UUID.randomUUID(), snap(1)).isEmpty());
    }

    // ---- 订阅注销 ----

    @Test
    void addListDropRoundTrip() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        arm("dark", "daytime", kTick(13000.0), "nightfall");

        List<WatchApi.Row> rows = WatchEngine.rows(HER);
        assertEquals(2, rows.size());
        assertEquals("low", rows.get(0).name(), "登记序");
        assertEquals("dark", rows.get(1).name());
        assertEquals("blood_below", rows.get(0).primitive());
        assertEquals("nightfall", rows.get(1).why());
        assertEquals(2, WatchEngine.carrying(HER));

        WatchApi.WatchDropped dropped = WatchEngine.drop(HER, "low");
        assertEquals("low", dropped.name());
        assertEquals("blood_below", dropped.primitive());
        assertEquals(1, WatchEngine.carrying(HER));

        // 注销之后不再命中
        WatchEngine.evaluate(HER, snap(1));
        assertTrue(WatchEngine.drain(HER).hits().isEmpty());
    }

    @Test
    void droppingAnUnknownNameIsNotFoundWithWhatSheCarries() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        ApiError bad = assertThrows(ApiError.class, () -> WatchEngine.drop(HER, "nope"));
        assertEquals(ErrorKind.NOT_FOUND, bad.kind());
        assertTrue(bad.getMessage().contains("low"), "报错里说出她挂着的名字");
        assertFalse(WatchEngine.drop(HER, "low").name().isEmpty());
    }

    @Test
    void reAddingTheSameNameOverwrites() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        arm("low", "daytime", kTick(13000.0), "nightfall instead");
        assertEquals(1, WatchEngine.carrying(HER), "同名覆盖,不占新位");
        assertEquals("daytime", WatchEngine.rows(HER).get(0).primitive());
    }

    @Test
    void theTableIsBounded() {
        for (int i = 0; i < WatchEngine.MAX_SUBS; i++) {
            arm("w" + i, "blood_below", kThreshold(5.0), "why " + i);
        }
        ApiError bad = assertThrows(ApiError.class, () ->
                arm("one-more", "blood_below", kThreshold(5.0), "one too many"));
        assertEquals(ErrorKind.FAILED, bad.kind());
        assertTrue(bad.getMessage().contains(String.valueOf(WatchEngine.MAX_SUBS)));
        assertEquals(WatchEngine.MAX_SUBS, WatchEngine.carrying(HER), "拒绝后表不动");
        // 同名覆盖不占新位,满了也放行
        arm("w0", "daytime", kTick(13000.0), "overwrite is fine");
        assertEquals(WatchEngine.MAX_SUBS, WatchEngine.carrying(HER));
    }

    // ---- 环形缓冲 ----

    @Test
    void eventsDrainOnceAndComeBackEmpty() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        WatchEngine.evaluate(HER, snap(9));

        WatchApi.WatchEvents first = WatchEngine.drain(HER);
        assertEquals(1, first.hits().size());
        assertTrue(WatchEngine.drain(HER).hits().isEmpty(), "取走即清空:每条只交一次");
    }

    @Test
    void theBufferIsBoundedAndDropsTheOldest() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        // 反复掉下去、抬上来:一轮一个事件(tick 每轮 +4),堆满缓冲还多挤两个
        for (int round = 0; round < WatchEngine.EVENT_BUFFER + 2; round++) {
            WatchEngine.evaluate(HER, snap(9, 100 + round * 4));
            WatchEngine.evaluate(HER, snap(11, 100 + round * 4));
        }
        WatchApi.WatchEvents events = WatchEngine.drain(HER);
        assertEquals(WatchEngine.EVENT_BUFFER, events.hits().size(), "容量有界");
        // 34 个进来,最老的两个被挤掉:剩下的从第 3 个开始(tick 108)
        assertEquals(100 + 2 * 4, events.hits().get(0).tick(), "旧的前丢");
    }

    // ---- 生命周期 ----

    @Test
    void forgetClearsThisLifeAndTheHurtCounter() {
        arm("hurted", "attacked", WatchApi.Knobs.EMPTY, "she takes a hit");
        WatchEngine.hurt(HER);
        WatchEngine.evaluate(HER, snapHurt(1));
        assertEquals(1, WatchEngine.drain(HER).hits().size(), "挨一下,报一次");

        WatchEngine.forget(HER);   // REMOVE:休眠/注销/死亡后消失

        assertTrue(WatchEngine.rows(HER).isEmpty(), "订阅清空");
        assertTrue(WatchEngine.drain(HER).hits().isEmpty(), "命中也清空");
        // 这条命的挨打计数一并清:新的命里,基线从 0 起也算新账
        WatchEngine.arm(HER, WatchEngine.build("hurted", "attacked", WatchApi.Knobs.EMPTY, "she takes a hit",
                "p1", 2000, WatchEngine.hurtCount(HER)));
        WatchEngine.evaluate(HER, snapHurt(0));
        assertTrue(WatchEngine.drain(HER).hits().isEmpty(), "计数已清,0 不大于 0");
    }

    @Test
    void theHurtCounterIsPerCompanion() {
        WatchEngine.hurt(HER);
        assertEquals(1, WatchEngine.hurtCount(HER));
        assertEquals(0, WatchEngine.hurtCount(UUID.randomUUID()), "别人没挨打,计数是 0");
    }

    @Test
    void dropAllEmptiesEverything() {
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        WatchEngine.evaluate(HER, snap(9));
        WatchEngine.dropAll();
        assertTrue(WatchEngine.rows(HER).isEmpty());
        assertTrue(WatchEngine.drain(HER).hits().isEmpty());
    }

    @Test
    void eachCompanionEvaluatesOnlyItsOwnSubscriptions() {
        UUID other = UUID.randomUUID();
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        WatchEngine.arm(other, WatchEngine.build("other", "daytime", kTick(13000.0), "why", "p1", 1000, 0));
        // 她的血掉了、时刻还是白天:只有她的订阅命中;别人的 daytime 不搭车
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, new WatchSnapshot(9, 20, false, false, false, false,
                0, 100, 1000, 0, 100, Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of()));
        assertEquals(List.of("low"), fired.stream().map(WatchApi.Hit::name).toList());
        assertTrue(WatchEngine.evaluate(other, new WatchSnapshot(1, 20, false, false, false, false,
                0, 100, 1000, 0, 100, Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())).isEmpty(),
                "别人的订阅看别人的快照,血再低也不归它管");
        WatchEngine.forget(other);
    }

    @Test
    void forgetRemovesOnlyThatCompanion() {
        // 换维度不在表里(键只有她的 UUID),这条命结束(REMOVE)才清 —— forget 清的是那一个 UUID,不是全部。
        UUID other = UUID.randomUUID();
        arm("low", "blood_below", kThreshold(10.0), "blood low");
        WatchEngine.arm(other, WatchEngine.build("other", "blood_below", kThreshold(10.0), "why", "p1", 1000, 0));
        WatchEngine.forget(other);
        assertTrue(WatchEngine.rows(other).isEmpty());
        assertEquals(1, WatchEngine.carrying(HER), "她不搭车");
    }

    @Test
    void theEveryPropertyIsClampedToOne() {
        assertEquals(1, WatchEngine.everyTicks("0"));
        assertEquals(1, WatchEngine.everyTicks("-5"));
        assertEquals(7, WatchEngine.everyTicks("7"));
        assertEquals(WatchEngine.DEFAULT_EVERY, WatchEngine.everyTicks("not-a-number"));
        assertTrue(WatchEngine.everyTicks() >= 1, "缺省(或 JVM 属性)至少每 tick 一次");
    }

    // ---- 小工具 ----

    private static WatchApi.Knobs kThreshold(double v) {
        return new WatchApi.Knobs(java.util.Optional.of(v), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    private static WatchApi.Knobs kTick(double v) {
        return new WatchApi.Knobs(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(v));
    }

    private static WatchApi.Knobs kItem(String item, double count) {
        return new WatchApi.Knobs(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(item),
                java.util.Optional.of(count), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    private static WatchSnapshot snapHurt(int hurtCount) {
        return new WatchSnapshot(20, 20, false, false, false, false, 0, 100, 0, hurtCount, 100,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of());
    }
}
