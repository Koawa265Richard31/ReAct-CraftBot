package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.sdk.EntityRef;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 固定原语集,<b>有界</b>:19 个,不为哪个模组单独长。每个原语 = 名字 + 类属({@link Kind}:电平/事件)
 * + 参数({@link WatchApi.Knobs} 里的几项)+ 一次<b>纯函数求值</b>({@link #hits},只读快照与操作数记忆,
 * 不碰 Minecraft)。
 *
 * <h2>类属(拍板口径,2026-11-07)</h2>
 * <ul>
 *   <li><b>电平</b>({@link Kind#LEVEL},16 个):真值从身体/世界上持续读得,命中态自己持有 ——
 *       触发是订阅级边沿(连续为真只触发一次,掉回假再上来才再触发),v0 家法原样。</li>
 *   <li><b>事件</b>({@link Kind#EVENT},恰 3 个:{@code attacked}/{@code target_gone}/{@code block_changed}):
 *       是「发生」不是「状态」—— 一件事在这一拍发生了。发生记账在事件当 tick 内(attacked 由
 *       {@code CompanionEvent.HURT} 监听在主线程记账,{@code CompanionEvent.java} 的 HURT 行;另两条在求值轮
 *       观察到转变的那一拍记账),报告走求值轮:同一轮发现的所有同类发生合并为一条命中,同一 tick 的多次合并为
 *       一条,配冷却(缺省 0 = 关;JVM 属性 {@code numen.watch.event.cooldown},单位 tick)。</li>
 * </ul>
 *
 * <h2>组合准入(注册期检查,一条不过当场拒收)</h2>
 * <ul>
 *   <li><b>and</b>(至多两元):{@link #andAdmits} —— {@code target_gone}/{@code block_changed} 禁入:
 *       它们的「真」只存在于看见转变的那一轮,配进 and 会丢边沿(monitor §二 v1.1 的禁入名单);
 *       {@code attacked} 准入(命中态由计数持有,{@code hurtCount > baseline} 在基线刷新前一直为真)。</li>
 *   <li><b>not</b>:只许包电平原语({@link #isLevel})且只许独占顶层一层 —— 事件原语不能取非,语义不成立
 *       (拍板口径 2026-11-07;spec-monitor §二 v1.1 曾把 attacked 列为可取非的持有型,按拍板收紧)。</li>
 * </ul>
 *
 * <p>触发是<b>边沿</b>:上一轮没命中、这一轮命中才触发;连续为真只触发一次,掉回假再上来才再触发。
 * 每轮求值后轨迹记忆按 {@link #track} 推进(轨迹类原语每轮都要跟,如"目标上一拍在不在");触发后相位
 * 记忆按 {@link #afterFire} 推进(如受伤计数的基线、超时的起点)。
 */
public enum WatchPrimitive {

    // ---- 活命 ----
    /** 血量阈值:结算后、严格低于阈值才算(阈值那一下不算,她死透了不再空触发)。 */
    BLOOD_BELOW("blood_below", "her health drops strictly below threshold", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.threshold = positive(k.threshold(), "blood_below needs {threshold = ...}, a health value above 0 "
                    + "(e.g. 10)");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.health() > 0 && s.health() < op.threshold;
        }
    },
    /** 饥饿:food_level 到阈值或以下(0 = 快饿死,也照报)。 */
    HUNGRY_BELOW("hungry_below", "her food level is at or below threshold", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.threshold = positive(k.threshold(), "hungry_below needs {threshold = ...}, a food value above 0 "
                    + "(0-20, e.g. 6)");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.food() <= op.threshold;
        }
    },
    /** 换气:剩气百分比低于阈值(默认 30)。 */
    AIR_LOW("air_low", "her breath is below a percent of full — she is drowning", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            double given = positiveWithDefault(k.threshold(), 30, "air_low takes {threshold = ...}, a percent "
                    + "1-99 (default 30)");
            if (given < 1 || given >= 100) {
                throw badArgument("air_low's threshold is a percent between 1 and 99; got " + given);
            }
            op.threshold = given;
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.airPercent() < op.threshold;
        }
    },
    /** 坠落:本段坠落距离够到了阈值(默认 3 格;落地的瞬间清零,不会误报上一跳)。 */
    FALLING("falling", "she has fallen at least so many blocks in this fall", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.threshold = positiveWithDefault(k.threshold(), 3, "falling takes {threshold = ...}, blocks above "
                    + "0 (default 3)");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.fallDistance() >= op.threshold;
        }
    },
    /** 着火。 */
    ON_FIRE("on_fire", "she is on fire", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            takeNone(k, shared);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.onFire();
        }
    },
    /** 脚下液体之一:水里。 */
    IN_WATER("in_water", "she is standing in water", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            takeNone(k, shared);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.inWater();
        }
    },
    /** 脚下液体之一:岩浆里。 */
    IN_LAVA("in_lava", "she is standing in lava", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            takeNone(k, shared);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.inLava();
        }
    },
    /**
     * 受到攻击:<b>事件</b>。这条命的挨打计数比基线多(基线在装订与每次触发时刷新);发生的记账在
     * {@code CompanionEvent.HURT} 的监听里、伤害结算的同一 tick 内(主线程),报告在求值轮:
     * 同一轮发现的全部新挨打合并为一条命中(同一 tick 的几下算一条),一下只报一次。
     */
    ATTACKED("attacked", "she takes a hit", Kind.EVENT) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            takeNone(k, shared);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.hurtCount() > op.baseline;
        }
        @Override void afterFire(WatchSnapshot s, WatchSub.Operand op) {
            op.baseline = s.hurtCount();
        }
    },

    // ---- 背包 ----
    /** 背包满:没有一个空位。 */
    INV_FULL("inv_full", "her inventory has no free slot left", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            takeNone(k, shared);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.inventoryFull();
        }
    },
    /** 物品数量,至少:这种物品她拿够了吗。 */
    ITEM_AT_LEAST("item_at_least", "she holds at least count of item", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.item = requireItem(k);
            op.count = nonNegative(k.count(), "item_at_least needs {item = \"minecraft:diamond\", count = 3}; "
                    + "count is how many, 0 or more");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.itemCounts().getOrDefault(op.item, 0) >= op.count;
        }
    },
    /** 物品数量,至多:这种物品她还剩几个(装订时就已经少,第一轮就报 —— 缺货警报本该如此)。 */
    ITEM_AT_MOST("item_at_most", "she holds at most count of item", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.item = requireItem(k);
            op.count = nonNegative(k.count(), "item_at_most needs {item = \"minecraft:torch\", count = 5}; "
                    + "count is how many, 0 or more");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.itemCounts().getOrDefault(op.item, 0) <= op.count;
        }
    },

    // ---- 实体 ----
    /**
     * 目标消失:<b>事件</b>。上一拍还在、这一拍不在(装订时就不在,不算消失);观察点是求值轮,
     * 发生记账在看见转变的那一拍,单轮边沿 —— 错过那一拍就没了,无补发(v0 家法照抄)。
     */
    TARGET_GONE("target_gone", "an entity she was watching is no longer there", Kind.EVENT) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.entity = requireEntity(k, "target_gone needs {entity = ...}, the entity as numen.scan.entities "
                    + "lists it");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return op.lastPresent != null && op.lastPresent && !s.entityDistance().containsKey(keyOf(op));
        }
        @Override void track(WatchSnapshot s, WatchSub.Operand op) {
            op.lastPresent = s.entityDistance().containsKey(keyOf(op));
        }
    },
    /** 实体靠近:进了半径(边界那一下算进)。 */
    ENTITY_NEAR("entity_near", "an entity comes within range of her", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.entity = requireEntity(k, "entity_near needs {entity = ..., range = 8}");
            op.range = positive(k.range(), "entity_near needs {entity = ..., range = ...}, a radius above 0");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            Double d = s.entityDistance().get(keyOf(op));
            return d != null && d <= op.range;
        }
    },
    /** 实体远离:出了半径(不在场不算"远离",消失是 target_gone 的事)。 */
    ENTITY_FAR("entity_far", "an entity gets beyond range of her", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.entity = requireEntity(k, "entity_far needs {entity = ..., range = 8}");
            op.range = positive(k.range(), "entity_far needs {entity = ..., range = ...}, a radius above 0");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            Double d = s.entityDistance().get(keyOf(op));
            return d != null && d > op.range;
        }
    },
    /** 附近有敌对生物:最近的敌对生物进了半径(默认 16 格)。 */
    HOSTILE_NEAR("hostile_near", "a hostile mob comes within range of her", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.range = positiveWithDefault(k.range(), 16, "hostile_near takes {range = ...}, a radius above 0 "
                    + "(default 16)");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.nearestHostile() <= op.range;
        }
    },

    // ---- 世界 ----
    /**
     * 方块变化:<b>事件</b>。那格的方块跟上一拍不一样(格没加载不算变,保留旧值等它回来);观察点是
     * 求值轮,发生记账在看见转变的那一拍,单轮边沿 —— 错过不补(v0 家法照抄)。
     */
    BLOCK_CHANGED("block_changed", "the block at a cell changes", Kind.EVENT) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.pos = requirePos(k, "block_changed needs {pos = ...}, the cell to keep an eye on");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            String cur = s.blockAt().get(keyOf(op));
            return op.lastBlock != null && cur != null && !cur.equals(op.lastBlock);
        }
        @Override void track(WatchSnapshot s, WatchSub.Operand op) {
            String cur = s.blockAt().get(keyOf(op));
            if (cur != null) {
                op.lastBlock = cur;
            }
        }
    },
    /** 位置越界:她离锚点格的距离超过半径(0 = 必须就站在那儿)。 */
    OUT_OF_BOUNDS("out_of_bounds", "she gets farther than range from a cell", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            op.pos = requirePos(k, "out_of_bounds needs {pos = ..., range = 8}");
            op.range = nonNegative(k.range(), "out_of_bounds needs {pos = ..., range = ...}, blocks 0 or more");
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            Double d = s.posDistance().get(keyOf(op));
            return d != null && d > op.range;
        }
    },

    // ---- 时间 ----
    /** 超时:装订(或上次触发)起过了这么多秒;repeat 才周期重发,否则只报一次。 */
    ELAPSED("elapsed", "so many seconds have passed since she armed it — a timeout", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            double seconds = positive(k.seconds(), "elapsed needs {seconds = 120}, seconds above 0");
            op.secondsTicks = Math.round(seconds * 20);
            op.repeat = k.repeat().orElse(false);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.nowTick() - op.phaseStart >= op.secondsTicks;
        }
        @Override void afterFire(WatchSnapshot s, WatchSub.Operand op) {
            if (op.repeat) {
                op.phaseStart = s.nowTick();
            }
        }
    },
    /** 时间:一天里的时刻(0-23999)走够了 —— 天黑报一次,天亮复位,第二天再报。 */
    DAYTIME("daytime", "the time of day reaches a tick, 0-23999 — e.g. nightfall", Kind.LEVEL) {
        @Override void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared) {
            double given = nonNegative(k.tick(), "daytime needs {tick = 13000}, a time of day between 0 and "
                    + "23999 (0 = dawn)");
            if (given > 23999) {
                throw badArgument("daytime's tick is a time of day between 0 and 23999; got " + given);
            }
            op.dayTick = Math.round(given);
        }
        @Override boolean hits(WatchSnapshot s, WatchSub.Operand op) {
            return s.dayTimeOfDay() >= op.dayTick;
        }
    };

    /** 原语的类属:电平(真值持续持有)还是事件(一次发生)。 */
    enum Kind {
        /** 电平:血量、距离、背包满这类 —— 真值从世界上持续读得,触发走订阅级边沿。 */
        LEVEL,
        /** 事件:attacked / target_gone / block_changed —— 是「发生」不是「状态」,配发生记账、
         * 同 tick 合并与冷却;不能进 not(取非语义不成立),target_gone/block_changed 也不能进 and。 */
        EVENT
    }

    /** 原语的 id,她在 Lua 里写的那个名字。 */
    private final String id;
    private final String help;
    private final Kind kind;

    WatchPrimitive(String id, String help, Kind kind) {
        this.id = id;
        this.help = help;
        this.kind = kind;
    }

    /** 纯求值:这一刻原语是否命中(只读快照与操作数记忆)。 */
    abstract boolean hits(WatchSnapshot s, WatchSub.Operand op);

    /**
     * 装订参数:校验 Knobs、把用到的项归一进操作数;缺项、离谱值当场拒收(bad_argument)。
     * {@code shared} = and 里的共用参数表:两个操作数读同一张表,不收参数的一方不再独占校验
     * (多余参数由引擎的"每个参数都得有人读"检查兜住)。
     */
    abstract void fill(WatchSub.Operand op, WatchApi.Knobs k, boolean shared);

    /** 每轮求值都要推进的轨迹记忆(默认无;只有 target_gone/block_changed 有,而它们禁入 and/not)。 */
    void track(WatchSnapshot s, WatchSub.Operand op) { }

    /** 触发后推进的相位记忆(默认无)。 */
    void afterFire(WatchSnapshot s, WatchSub.Operand op) { }

    String id() {
        return id;
    }

    String help() {
        return help;
    }

    /** 类属(单测与文档读)。 */
    Kind kind() {
        return kind;
    }

    /** 能不能进 and(至多两元的操作数):单轮边沿(target_gone/block_changed)禁入 —— 真值不持有,
     * 配进 and 会丢边沿(monitor §二 v1.1 的禁入名单);attacked 持有,准入。 */
    boolean andAdmits() {
        return this != TARGET_GONE && this != BLOCK_CHANGED;
    }

    /** 能不能被 not 包:只许电平原语(拍板口径 2026-11-07:边沿原语不能取非,语义不成立)。 */
    boolean isLevel() {
        return kind == Kind.LEVEL;
    }

    /** 这个原语读不读这个参数名(and 的共用参数表里每个参数都得有人读)。 */
    boolean reads(String knob) {
        return switch (this) {
            case BLOOD_BELOW, HUNGRY_BELOW, AIR_LOW, FALLING -> knob.equals("threshold");
            case ON_FIRE, IN_WATER, IN_LAVA, ATTACKED, INV_FULL -> false;
            case ITEM_AT_LEAST, ITEM_AT_MOST -> knob.equals("item") || knob.equals("count");
            case TARGET_GONE -> knob.equals("entity");
            case ENTITY_NEAR, ENTITY_FAR -> knob.equals("entity") || knob.equals("range");
            case HOSTILE_NEAR -> knob.equals("range");
            case BLOCK_CHANGED -> knob.equals("pos");
            case OUT_OF_BOUNDS -> knob.equals("pos") || knob.equals("range");
            case ELAPSED -> knob.equals("seconds") || knob.equals("repeat");
            case DAYTIME -> knob.equals("tick");
        };
    }

    /** id 原语;没有是 null(装订时把全部名字报给她)。 */
    static WatchPrimitive of(String id) {
        for (WatchPrimitive p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 全部 id,一排,报错与文档用。 */
    static String ids() {
        return Arrays.stream(values()).map(p -> p.id).collect(Collectors.joining(", "));
    }

    // ---- 键(与 WatchSnapshot 共用同一种形状)----

    private static String keyOf(WatchSub.Operand op) {
        return op.entity != null ? WatchSnapshot.key(op.entity) : WatchSnapshot.key(op.pos);
    }

    // ---- 参数校验的原语(纯,单测钉得住)----

    /** 必填、有限、严格为正;缺了或离谱都当场拒收,把该给的形状说出来。 */
    static double positive(Optional<Double> given, String shape) {
        Double v = given.orElse(null);
        if (v == null || !Double.isFinite(v) || v <= 0) {
            throw badArgument(shape);
        }
        return v;
    }

    /** 可省、省了用缺省;给了就得有限且为正。 */
    static double positiveWithDefault(Optional<Double> given, double dflt, String shape) {
        Double v = given.orElse(null);
        if (v == null) {
            return dflt;
        }
        if (!Double.isFinite(v) || v <= 0) {
            throw badArgument(shape);
        }
        return v;
    }

    /** 必填、有限、不小于 0。 */
    static double nonNegative(Optional<Double> given, String shape) {
        Double v = given.orElse(null);
        if (v == null || !Double.isFinite(v) || v < 0) {
            throw badArgument(shape);
        }
        return v;
    }

    /** 必填的实体点名。 */
    static EntityRef requireEntity(WatchApi.Knobs k, String shape) {
        EntityRef ref = k.entity().orElse(null);
        if (ref == null) {
            throw badArgument(shape);
        }
        return ref;
    }

    /** 必填的格子。 */
    static BlockPos requirePos(WatchApi.Knobs k, String shape) {
        BlockPos pos = k.pos().orElse(null);
        if (pos == null) {
            throw badArgument(shape);
        }
        return pos;
    }

    /** 必填的物品 id(存在性由 WatchApi.add 对着注册表验,这里只验形状)。 */
    static String requireItem(WatchApi.Knobs k) {
        String item = k.item().orElse(null);
        if (item == null || item.isBlank()) {
            throw badArgument("name the item, e.g. {item = \"minecraft:diamond\"}");
        }
        return item;
    }

    /**
     * 这个原语不收参数;给了任何一项都拒收(写错的参数最会埋雷)。and 的共用参数表({@code shared})
     * 例外:不收参数的一方不再独占校验,多余参数由引擎的"每个参数都得有人读"检查兜住。
     */
    static void takeNone(WatchApi.Knobs k, boolean shared) {
        if (shared) {
            return;
        }
        if (k.threshold().isPresent() || k.entity().isPresent() || k.item().isPresent() || k.count().isPresent()
                || k.pos().isPresent() || k.range().isPresent() || k.seconds().isPresent() || k.repeat().isPresent()
                || k.tick().isPresent()) {
            throw badArgument("this primitive takes no knobs: give an empty table, "
                    + "add(name, {primitive = ..., args = {}})");
        }
    }

    private static ApiError badArgument(String message) {
        return new ApiError(ErrorKind.BAD_ARGUMENT, message,
                "numen.api.help(\"numen.watch.add\") lists every primitive and its knobs");
    }
}
