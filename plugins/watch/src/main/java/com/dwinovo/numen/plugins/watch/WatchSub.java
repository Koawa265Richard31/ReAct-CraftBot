package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.sdk.EntityRef;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 一条订阅:名字、条件、归一后的参数、add 它的那段程序、她写下的一行 why、可选的反射动作,以及求值记忆。
 *
 * <h2>条件的三种形式(拍板口径 2026-11-07:或 / 与(至多两元)/ 非)</h2>
 * <ul>
 *   <li>{@link Form#SINGLE}:单个原语;</li>
 *   <li>{@link Form#AND}:恰好两个原语相与({@link #a} 与 {@link #b});操作数只能是原语,不嵌套 ——
 *       {@code (A 且 B) 或 C} 合法(两条订阅,订阅之间默认 OR),{@code A 且 (B 或 C)} 在这个面上根本
 *       表达不出来(表不进去就不存在,非法组合里能表达的都在注册期被拒,见 {@link WatchEngine#build});</li>
 *   <li>{@link Form#NOT}:取反一个<b>电平</b>原语({@link #a}),只许独占顶层一层 —— 事件原语
 *       (attacked/target_gone/block_changed)不能取非,语义不成立。</li>
 * </ul>
 * 订阅之间默认 OR(表里任一命中即触发)—— 这一层在 {@link WatchEngine#evaluate},不在本类。
 *
 * <h2>求值顺序(v0 家法,AND/not 套同一条)</h2>
 * 先对每个操作数求一次真值、按形式合成整条的真值、按边沿与冷却决定是否触发,然后<b>才</b>推进操作数的
 * 轨迹记忆,再写回边沿记忆,触发后才推进相位记忆 —— 顺序不能乱:track 先于边沿判定写回,会吞掉
 * "从有到无"的那一拍。AND/not 操作数的相位记忆只在「孩子自身命中为真且整条订阅触发」时推进
 * (monitor §二 v1.1;例如 {@code not attacked} 触发时孩子命中为假,基线不刷新)。
 *
 * <h2>事件类的边沿、去重与冷却(拍板口径 2026-11-07)</h2>
 * 条件里含事件原语({@link WatchPrimitive.Kind#EVENT})的订阅:发生记账在事件当 tick 内(attacked 由
 * HURT 监听在主线程记账;target_gone/block_changed 的观察点是求值轮),报告在求值轮 ——
 * <ul>
 *   <li><b>合并</b>:同一轮发现的所有同类发生合并为一条命中;同一 tick 的多次同类发生合并为一条
 *       (挨几下都是一条,基线一把刷新);</li>
 *   <li><b>去重</b>:事件类单原语走<b>发生制</b> —— hit 本身就是「有未消费的发生」(attacked 的
 *       计数超基线在消费前一直为真,消费在触发;target_gone/block_changed 的转变真值天然单轮),
 *       不另设第二台边沿机;电平单原语与 and/not 走 v0 的订阅级边沿(连续为真只报一次);</li>
 *   <li><b>冷却</b>:JVM 属性 {@code numen.watch.event.cooldown},单位 tick,缺省 {@value WatchEngine#DEFAULT_EVENT_COOLDOWN}
 *       (= 关,v0 的「每下都报」语义保留;是否要非零缺省待实测),只在真正触发时起算 —— 压住期间
 *       事件类单原语的发生不消费(期满补发),and 压住的那次同时成立即弃(见 {@link #fire})。</li>
 * </ul>
 *
 * <p>存活于她这一条命:身体一离开世界(REMOVE)整份清空(见 {@link WatchEngine#forget});键只有她的
 * UUID,换维度不清;订阅本身不写任何存档 —— 引擎进程内的状态,服务器一停就散(白名单与事件日志的
 * 落盘是另一回事,见 {@link WatchWhitelist}/{@link WatchJournal},它们不是订阅的家当)。
 */
final class WatchSub {

    /** 条件的形式:单原语 / 两元与 / 顶层非。 */
    enum Form {
        /** 单个原语。 */
        SINGLE,
        /** 恰好两个原语相与;操作数只能是原语,不嵌套(第三层不存在)。 */
        AND,
        /** 取反一个电平原语;只许独占顶层一层。 */
        NOT
    }

    /**
     * 条件里的一个操作数:一个原语、它从共用参数表归一出的参数、它自己的求值记忆
     * (相位/轨迹记忆是<b>每个操作数一份</b> —— and 两侧的基线、相位起点各记各的)。
     */
    static final class Operand {
        final WatchPrimitive primitive;

        // ---- 归一后的参数(fill 之后只读;哪个原语读哪几项,由 WatchPrimitive.reads 说了算)----
        double threshold;
        EntityRef entity;
        String item;
        double count;
        BlockPos pos;
        double range;
        long secondsTicks;
        boolean repeat;
        long dayTick;

        // ---- 求值记忆(每个操作数一份)----
        /** elapsed 的相位起点(repeat 命中后推到当下);装上那一刻起算。 */
        long phaseStart;
        /** attacked 的基线(触发后推到当下的计数)。 */
        long baseline;
        /** target_gone:上一轮目标在不在(null = 还没量过,首轮只记不发)。 */
        Boolean lastPresent;
        /** block_changed:上一轮那格是什么;读不到的轮保留旧值,不当成"变了"。 */
        String lastBlock;

        Operand(WatchPrimitive primitive, long armedTick, long hurtBaseline) {
            this.primitive = primitive;
            this.phaseStart = armedTick;
            this.baseline = hurtBaseline;
        }

        void fill(WatchApi.Knobs k, boolean shared) {
            primitive.fill(this, k, shared);
        }

        boolean hits(WatchSnapshot s) {
            return primitive.hits(s, this);
        }

        void track(WatchSnapshot s) {
            primitive.track(s, this);
        }

        void afterFire(WatchSnapshot s) {
            primitive.afterFire(s, this);
        }
    }

    final Form form;
    /** SINGLE:那个原语;NOT:被取反的原语;AND:左侧操作数。 */
    final Operand a;
    /** AND 的右侧操作数;其余形式是 null。 */
    final Operand b;
    final String name;
    final String why;
    /** add 它的那段程序:命中就打断这一段;编号对不上(已结束、换了新的一段)引擎自己不理。 */
    final String programId;
    /** 可选的反射动作:命中后想自动起步的那段短 Lua。空串 = 没声明(命中只停车)。 */
    final String action;
    /** 装上那一刻的服务器刻。 */
    final long armedTick;

    // ---- 订阅级求值记忆 ----
    /** 边沿:上一轮是否已处于命中态(边沿制形式的「连续为真只触发一次」;事件类单原语不走它,见 fire)。 */
    private boolean wasHit;
    /** 上一次真正触发的 tick(冷却的起算点;从没触发过是 Long.MIN_VALUE)。 */
    private long lastFireTick = Long.MIN_VALUE;

    WatchSub(Form form, Operand a, Operand b, String name, String why, String programId, String action,
             long armedTick) {
        this.form = form;
        this.a = a;
        this.b = b;
        this.name = name;
        this.why = why;
        this.programId = programId;
        this.action = action == null ? "" : action;
        this.armedTick = armedTick;
    }

    /** 这条订阅声明了反射动作吗。 */
    boolean hasAction() {
        return !action.isBlank();
    }

    /** 条件的文本形状:报错、list、命中行与事件日志的 source 共用这一种写法。 */
    String condition() {
        return switch (form) {
            case SINGLE -> a.primitive.id();
            case NOT -> "not " + a.primitive.id();
            case AND -> a.primitive.id() + " and " + b.primitive.id();
        };
    }

    /** 条件里的操作数(快照按订阅备料用):一个或两个。 */
    List<Operand> operands() {
        return b == null ? List.of(a) : List.of(a, b);
    }

    /** 条件里含事件原语吗(事件类的冷却只对这些订阅生效;电平类保持 v0 原样,没有冷却)。 */
    boolean involvesEvent() {
        return a.primitive.kind() == WatchPrimitive.Kind.EVENT
                || (b != null && b.primitive.kind() == WatchPrimitive.Kind.EVENT);
    }

    /**
     * 这一刻命中并触发:先求各操作数真值、合成整条真值,再按形式决定触发,然后推进操作数轨迹记忆、
     * 写回边沿记忆,触发后推进相位记忆。顺序不能乱(见类注释)。冷却取引擎的 JVM 属性值
     * ({@code numen.watch.event.cooldown})。
     *
     * <p>触发判定分两支:<b>事件类单原语</b>(attacked/target_gone/block_changed)走<b>发生制</b> ——
     * hit 本身就是「有未消费的发生」(attacked 是计数超基线,消费在触发;另两条的转变真值天然单轮),
     * 冷却压住的触发在期满后由仍在的真值补发,不用第二台边沿机;其余形式(电平单原语、and、not)走
     * <b>边沿制</b>(v0 家法:连续为真只触发一次),这类若含事件原语且被冷却压住,压住即弃 ——
     * 补发会让「同一次成立」报两次,要「挨打就报」用单条 attacked。
     */
    boolean fire(WatchSnapshot s) {
        return fire(s, WatchEngine.eventCooldownTicks());
    }

    /** 同上,冷却由参数给(单测直接驱动不同冷却值;生产路径走 {@link #fire(WatchSnapshot)})。 */
    boolean fire(WatchSnapshot s, int cooldown) {
        boolean hitA = a.hits(s);
        boolean hitB = b != null && b.hits(s);
        boolean hit = switch (form) {
            case SINGLE -> hitA;
            case NOT -> !hitA;
            case AND -> hitA && hitB;
        };
        boolean occurrenceBased = form == Form.SINGLE && a.primitive.kind() == WatchPrimitive.Kind.EVENT;
        boolean fire = occurrenceBased
                ? hit && cooldownElapsed(s.nowTick(), cooldown)
                : hit && !wasHit && cooldownElapsed(s.nowTick(), cooldown);
        a.track(s);
        if (b != null) {
            b.track(s);
        }
        wasHit = hit;
        if (fire) {
            lastFireTick = s.nowTick();
            // 相位记忆:只有「孩子自身命中为真 且 整条订阅触发」才推进 —— not 触发时孩子为假,基线不刷新。
            if (hitA) {
                a.afterFire(s);
            }
            if (hitB) {
                b.afterFire(s);
            }
        }
        return fire;
    }

    /** 事件类的冷却到点了吗:0(关)放行一切;只在真正触发时起算,被压住的轮次不顺延冷却。 */
    private boolean cooldownElapsed(long nowTick, int cooldown) {
        if (cooldown <= 0 || !involvesEvent()) {
            return true;
        }
        return lastFireTick == Long.MIN_VALUE || nowTick - lastFireTick >= cooldown;
    }
}
