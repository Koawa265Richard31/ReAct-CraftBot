package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.sdk.EntityRef;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原语层的契约:每个原语的<b>纯函数求值</b>与它的参数校验,边界钉在这里 —— 阈值那一下算不算、距离
 * 恰好压在半径上算不算、连续为真只触发一次、装订时缺参数当场拒收。全程不碰世界:快照是手摆的
 * {@link WatchSnapshot},订阅是 {@link WatchEngine#build} 从参数归一出来的(校验路径也就此覆盖)。
 */
class WatchPrimitivesTest {

    /** 19 个原语,一个不多一个不少:集合有界是这场任务的硬约束,数变了先在这里炸给看。 */
    @Test
    void theSetIsBounded() {
        assertEquals(19, WatchPrimitive.values().length);
    }

    @Test
    void idsAreUnique() {
        assertEquals(19, WatchPrimitive.ids().split(", ").length);
    }

    // ---- 血量阈值 ----

    @Test
    void bloodBelowIsStrictAtTheThreshold() {
        WatchSub sub = sub("blood_below", k(10.0, null, null, null, null, null, null, null, null));
        assertFalse(sub.fire(snapHealth(10.0f)), "恰好压线不命中(严格小于)");
        assertTrue(sub.fire(snapHealth(9.9f)), "低于阈值,命中");
        assertFalse(sub.fire(snapHealth(9.9f)), "连续为真只触发一次");
        assertFalse(sub.fire(snapHealth(10.5f)), "回到线上,边沿复位");
        assertTrue(sub.fire(snapHealth(9.9f)), "又落下去,再触发");
    }

    @Test
    void bloodBelowNeverFiresOnADeadBody() {
        WatchSub sub = sub("blood_below", k(10.0, null, null, null, null, null, null, null, null));
        assertFalse(sub.fire(snapHealth(0.0f)), "她死透了,死亡事件另有去处,不空触发");
    }

    // ---- 饥饿 ----

    @Test
    void hungryBelowTakesTheThresholdAsReached() {
        WatchSub sub = sub("hungry_below", k(6.0, null, null, null, null, null, null, null, null));
        assertFalse(sub.fire(snapHealthFood(20, 7)), "7 > 6 不命中");
        assertTrue(sub.fire(snapHealthFood(20, 6)), "到线就算(<=)");
        assertFalse(sub.fire(snapHealthFood(20, 6)), "一直饿着不重发");
        assertFalse(sub.fire(snapHealthFood(20, 10)), "吃饱,边沿复位");
        assertTrue(sub.fire(snapHealthFood(20, 3)), "又饿下去,再触发");
    }

    // ---- 换气 ----

    @Test
    void airLowDefaultsTo30Percent() {
        WatchSub sub = sub("air_low", WatchApi.Knobs.EMPTY);
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 30, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 29, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void airLowRejectsAbsurdPercent() {
        assertThrows(ApiError.class, () -> sub("air_low", k(100.0, null, null, null, null, null, null, null, null)));
        assertThrows(ApiError.class, () -> sub("air_low", k(0.0, null, null, null, null, null, null, null, null)));
    }

    // ---- 坠落 ----

    @Test
    void fallingCountsBlocksAtOrBeyondTheDefault3() {
        WatchSub sub = sub("falling", WatchApi.Knobs.EMPTY);
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 2.9f, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 3.0f, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 3.0f, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    // ---- 着火 / 液体 / 背包满:不收参数 ----

    @Test
    void plainBooleansFireOnAndOnlyOnTheRisingEdge() {
        WatchSub fire = sub("on_fire", WatchApi.Knobs.EMPTY);
        assertFalse(fire.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(fire.fire(snap(9, 0, false, true, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertFalse(fire.fire(snap(9, 0, false, true, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "连着烧只报一次");

        WatchSub lava = sub("in_lava", WatchApi.Knobs.EMPTY);
        assertFalse(lava.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(lava.fire(snap(9, 0, false, false, false, true, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));

        WatchSub water = sub("in_water", WatchApi.Knobs.EMPTY);
        assertFalse(water.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(water.fire(snap(9, 0, false, false, true, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));

        WatchSub full = sub("inv_full", WatchApi.Knobs.EMPTY);
        assertFalse(full.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(full.fire(snap(9, 0, true, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void plainBooleansRejectAnyKnob() {
        assertThrows(ApiError.class, () -> sub("on_fire", k(5.0, null, null, null, null, null, null, null, null)));
        assertThrows(ApiError.class, () -> sub("inv_full", k(null, null, null, null, null, null, 3.0, null, null)));
    }

    // ---- 受到攻击 ----

    @Test
    void attackedFiresOncePerNewHit() {
        // 装订时她已挨 4 下(基线随 build 进订阅)
        WatchSub sub = sub("attacked", WatchApi.Knobs.EMPTY, 4);
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 4, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "没有新的挨打,不触发");
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 5, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 5, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "计数没动,不重发");
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 6, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "又挨了一下,再报");
    }

    // ---- 物品数量 ----

    @Test
    void itemAtLeastCountsWhatSheCarries() {
        WatchSub sub = sub("item_at_least",
                k(null, null, "minecraft:diamond", 3.0, null, null, null, null, null));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of("minecraft:diamond", 3))), "恰好够也算");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of("minecraft:diamond", 2))));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "一样没有就是 0");
    }

    @Test
    void itemAtMostIsTheOutOfStockAlarm() {
        WatchSub sub = sub("item_at_most", k(null, null, "minecraft:torch", 5.0, null, null, null, null, null));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of("minecraft:torch", 6))));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of("minecraft:torch", 5))));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of("minecraft:torch", 5))), "一直不够不重发");
    }

    @Test
    void itemKnobsAreValidated() {
        assertThrows(ApiError.class, () -> sub("item_at_least", k(null, null, null, 3.0, null, null, null, null,
                null)), "缺 item 拒收");
        assertThrows(ApiError.class, () -> sub("item_at_least", k(null, null, "minecraft:diamond", -1.0, null,
                null, null, null, null)), "负数 count 拒收");
    }

    // ---- 目标消失 ----

    @Test
    void targetGoneFiresOnTheSeenThenGoneEdge() {
        EntityRef target = EntityRef.id(184);
        WatchSub sub = sub("target_gone", k(null, target, null, null, null, null, null, null, null));
        String key = WatchSnapshot.key(target);
        // 首拍就不在:只记不发
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        // 来了:记下"在"
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(key, 5.0), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        // 走了:触发
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "一直不在不重发");
        // 又来又走:再触发
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(key, 5.0), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    // ---- 实体靠近 / 远离 ----

    @Test
    void entityNearTakesTheBoundaryAsInside() {
        EntityRef target = EntityRef.id(184);
        String key = WatchSnapshot.key(target);
        WatchSub near = sub("entity_near", k(null, target, null, null, null, 8.0, null, null, null));
        assertTrue(near.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(key, 8.0), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "压线算进");
        assertFalse(near.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(key, 8.1), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertFalse(near.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "不在场不算靠近");
    }

    @Test
    void entityFarIgnoresAnAbsentEntity() {
        EntityRef target = EntityRef.id(184);
        String key = WatchSnapshot.key(target);
        WatchSub far = sub("entity_far", k(null, target, null, null, null, 8.0, null, null, null));
        assertFalse(far.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "消失是 target_gone 的事");
        assertFalse(far.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(key, 8.0), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "压线不算出界");
        assertTrue(far.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(key, 8.1), Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    // ---- 附近有敌对生物 ----

    @Test
    void hostileNearFiresWhenOneIsWithinRange() {
        WatchSub sub = sub("hostile_near", WatchApi.Knobs.EMPTY);
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                16.0, Map.of(), Map.of(), Map.of())), "缺省半径 16,16 以内算");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "一个都没有(极远)不算");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void hostileNearRejectsNonPositiveRange() {
        assertThrows(ApiError.class, () -> sub("hostile_near", k(null, null, null, null, null, 0.0, null, null,
                null)));
    }

    // ---- 方块变化 ----

    @Test
    void blockChangedFiresOnEveryChangeButNotOnUnload() {
        BlockPos anchor = new BlockPos(120, 64, -35);
        String key = WatchSnapshot.key(anchor);
        WatchSub sub = sub("block_changed", k(null, null, null, null, anchor, null, null, null, null));
        // 首拍只记不发
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(key, "minecraft:stone"), Map.of())));
        // 变了:触发
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(key, "minecraft:dirt"), Map.of())));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(key, "minecraft:dirt"), Map.of())));
        // 那格读不到(区块没加载):不算变,旧值保留
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        // 回到 dirt:与保留的旧值相同,不触发
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(key, "minecraft:dirt"), Map.of())));
        // 变回 stone:再触发
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(key, "minecraft:stone"), Map.of())));
    }

    @Test
    void blockChangedRejectsMissingPos() {
        assertThrows(ApiError.class, () -> sub("block_changed", WatchApi.Knobs.EMPTY));
    }

    // ---- 位置越界 ----

    @Test
    void outOfBoundsIsStrictBeyondRange() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        String key = WatchSnapshot.key(anchor);
        WatchSub sub = sub("out_of_bounds", k(null, null, null, null, anchor, 8.0, null, null, null));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(key, 8.0), Map.of(), Map.of())), "压线不算出界");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(key, 7.9), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(key, 8.1), Map.of(), Map.of())));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(key, 8.1), Map.of(), Map.of())));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "锚点读不到,不发(不是数据当真)");
    }

    // ---- 超时 ----

    @Test
    void elapsedFiresAtTheSecondBoundary() {
        // 装订在 tick 1000
        WatchSub sub = sub("elapsed", k(null, null, null, null, null, null, 10.0, null, null), 0, 1000);
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1199, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1200, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "200 tick = 10 秒,到点就算");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1201, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "不 repeat,超时只报一次");
    }

    @Test
    void elapsedWithRepeatReArmsAfterEachFire() {
        WatchSub sub = sub("elapsed", k(null, null, null, null, null, null, 10.0, true, null), 0, 1000);
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1199, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1200, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "到点;相位起点推到当下");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1204, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "刚发过,边沿复位");
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1404, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "又过了 200 tick,再报");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 1405, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void elapsedRejectsNonPositiveSeconds() {
        assertThrows(ApiError.class, () -> sub("elapsed", k(null, null, null, null, null, null, 0.0, null, null)));
        assertThrows(ApiError.class, () -> sub("elapsed", WatchApi.Knobs.EMPTY));
    }

    // ---- 时间 ----

    @Test
    void daytimeFiresAtNightfallAndAgainTheNextNight() {
        WatchSub sub = sub("daytime", k(null, null, null, null, null, null, null, null, 13000.0));
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 12999, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 13000, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "到点就算(>=)");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 18000, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "黑着的那一整夜不重发");
        assertFalse(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 500, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "天亮,边沿复位");
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 13001, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())), "第二天的夜,再报");
    }

    @Test
    void daytimeTakesZeroAndRejectsBeyondTheDay() {
        WatchSub sub = sub("daytime", k(null, null, null, null, null, null, null, null, 0.0));
        assertTrue(sub.fire(snap(9, 0, false, false, false, false, 0, 100, 0, 0, 0, Map.of(),
                Double.MAX_VALUE, Map.of(), Map.of(), Map.of())));
        assertThrows(ApiError.class, () -> sub("daytime", k(null, null, null, null, null, null, null, null,
                24000.0)));
        assertThrows(ApiError.class, () -> sub("daytime", k(null, null, null, null, null, null, null, null,
                -1.0)));
    }

    // ---- 装订校验 ----

    @Test
    void unknownPrimitiveIsRejectedWithTheWholeList() {
        ApiError bad = assertThrows(ApiError.class, () ->
                sub("no_such_primitive", WatchApi.Knobs.EMPTY));
        assertEquals(ErrorKind.BAD_ARGUMENT, bad.kind());
        assertTrue(bad.getMessage().contains("blood_below"), "报错里要列出全部原语");
    }

    @Test
    void blankNameAndBlankWhyAreRejected() {
        assertThrows(ApiError.class, () -> WatchEngine.build("  ", "blood_below", WatchApi.Knobs.EMPTY, "why",
                "p1", 0, 0));
        assertThrows(ApiError.class, () -> WatchEngine.build("low", "blood_below", WatchApi.Knobs.EMPTY, " ",
                "p1", 0, 0));
        assertThrows(ApiError.class, () -> WatchEngine.build("low", null, WatchApi.Knobs.EMPTY, "why",
                "p1", 0, 0));
    }

    @Test
    void missingKnobIsRejectedWithTheShape() {
        ApiError bad = assertThrows(ApiError.class, () -> sub("blood_below", WatchApi.Knobs.EMPTY));
        assertEquals(ErrorKind.BAD_ARGUMENT, bad.kind());
        assertTrue(bad.getMessage().contains("threshold"));
    }

    // ---- 手摆快照与订阅的小工具 ----

    /** 血量可以挑的平静的我:food 20、满气、没着火、站平地。 */
    private static WatchSnapshot snapHealth(float health) {
        return snap(health, 20, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of());
    }

    /** 血量与饥饿都可以挑的平静的我。 */
    private static WatchSnapshot snapHealthFood(float health, int food) {
        return snap(health, food, false, false, false, false, 0, 100, 0, 0, 0,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of());
    }

    /** 全字段的快照:测试里不藏默认,要什么给什么。 */
    private static WatchSnapshot snap(float health, int food, boolean invFull, boolean onFire, boolean inWater,
                                      boolean inLava, float fallDistance, int airPercent, long dayTimeOfDay,
                                      int hurtCount, long nowTick,
                                      Map<String, Double> entityDistance, double nearestHostile,
                                      Map<String, Double> posDistance, Map<String, String> blockAt,
                                      Map<String, Integer> itemCounts) {
        return new WatchSnapshot(health, food, invFull, onFire, inWater, inLava, fallDistance, airPercent,
                dayTimeOfDay, hurtCount, nowTick, entityDistance, nearestHostile, posDistance, blockAt,
                itemCounts);
    }

    /** 全字段的参数表:要哪项给哪项。 */
    private static WatchApi.Knobs k(Double threshold, EntityRef entity, String item, Double count, BlockPos pos,
                                    Double range, Double seconds, Boolean repeat, Double tick) {
        return new WatchApi.Knobs(Optional.ofNullable(threshold), Optional.ofNullable(entity),
                Optional.ofNullable(item), Optional.ofNullable(count), Optional.ofNullable(pos),
                Optional.ofNullable(range), Optional.ofNullable(seconds), Optional.ofNullable(repeat),
                Optional.ofNullable(tick));
    }

    /** 从参数归一出一条订阅(与 WatchApi.add 同一条路):名字、why、程序号固定,装订在 tick 1000。 */
    private static WatchSub sub(String primitiveId, WatchApi.Knobs knobs) {
        return WatchEngine.build("w", primitiveId, knobs, "why", "p1", 1000, 0);
    }

    /** 带装订时挨打基线的版本(attacked 用)。 */
    private static WatchSub sub(String primitiveId, WatchApi.Knobs knobs, long hurtBaseline) {
        return WatchEngine.build("w", primitiveId, knobs, "why", "p1", 1000, hurtBaseline);
    }

    /** 带装订时刻的版本(elapsed 用)。 */
    private static WatchSub sub(String primitiveId, WatchApi.Knobs knobs, long ignoredBaseline, long armedTick) {
        return WatchEngine.build("w", primitiveId, knobs, "why", "p1", armedTick, 0);
    }
}
