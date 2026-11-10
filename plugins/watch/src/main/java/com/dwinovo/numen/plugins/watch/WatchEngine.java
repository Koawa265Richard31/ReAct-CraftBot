package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.ServerLifecycle;
import com.dwinovo.numen.program.ServerPrograms;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅表与求值引擎 —— {@code numen.watch} 的机件。装订(add)与求值只做两件事:表里的订阅逐个按自己的
 * 形式求值(<b>任一命中即触发</b>的 OR,订阅之间默认 OR);组合只有三种形式 —— 单原语、恰两元的
 * <b>与</b>、顶层的<b>非</b>(拍板口径 2026-11-07:或 / 与(至多两元)/ 非三件套)。非法组合在
 * <b>注册时</b>就拒收并说清哪一条不合法(见 {@link #build}),不许拖到求值时静默不触发。
 *
 * <h2>命中动作与反射白名单(拍板口径 2026-11-07)</h2>
 * 命中默认<b>只能停</b>:调 {@link ServerPrograms#interrupt} 打断 add 它的那段程序(编号对不上 =
 * 已结束/换段,引擎自己不理)。订阅可以用 {@code action} 声明一段反射 Lua —— 只有当这段动作的
 * SHA-256 在主人的白名单({@link WatchWhitelist},默认<b>空</b>,文件在 config/numen/ 下)里,才在
 * 下一 tick 经 {@link ServerPrograms#launch} 自动起步(照 {@code /numen drive} 的延期一格写法);
 * <b>不在白名单 → 停下 + 记事件,不启动任何任务</b>,回执里写明「白名单未批准,只停车」。
 * 白名单外没有任何绕行:检查发生在起步之前,起步与否的结论写进回执与事件日志。
 *
 * <h2>事件落盘供拉取(拍板口径 2026-11-07)</h2>
 * 每条命中除环形缓冲外追加一条持久事件日志({@link WatchJournal}:append-only JSONL、八字段
 * {@code {seq, tick, type, dim, pos, subject, source, note}}、seq 单调且<b>跨重启续号</b> ——
 * 重启 tick 归零,seq 从盘上最大值之后接着数,不用 tick 当 seq)。DSH 按水位拉增量,mod 侧
 * 零新网络面(不开端口、不出网)。进程内状态照旧不写存档:订阅随 REMOVE/关服清空,
 * 白名单与事件日志是主人的家当,在盘上不随进程没。
 *
 * <h2>线程</h2>
 * 表只在<b>服务端主线程</b>上动:add/list/drop/events 是服务端调用(车道里排着,每刻开头执行),
 * tick 由核心的服务器刻钩子叫,REMOVE/HURT 事件也在主线程发,日志与白名单只被主线程写。
 * 外层 Map 用并发容器只是给未来可能的旁路读兜底,不为常态(照 ServerPrograms 的写法)。
 *
 * <h2>生命周期(说清,也照此实现)</h2>
 * <ul>
 *   <li>订阅存活于她这一条命:身体一离开世界(REMOVE,休眠/注销/死亡后消失)整份清空 ——
 *       {@link #forget},由 {@code NumenWatch.install} 挂在 {@link CompanionEvent#REMOVE} 上;</li>
 *   <li>换维度保留:键只有她的 UUID,没有维度;换维度照旧求值,实体与方块各从当前这层读;</li>
 *   <li>服务器重启订阅不持久化:没有任何 SavedData、不写世界存档;进程内状态随服务器停止作废
 *       ({@link ServerLifecycle#onStopped},照 CompanionTickDispatcher 的家法:谁持有谁报到)。
 *       白名单与事件日志两个文件在 config/numen/ 下,不随进程没(见上)。</li>
 * </ul>
 *
 * <h2>求值</h2>
 * 每 {@value #DEFAULT_EVERY} tick(JVM 属性 {@code numen.watch.every} 可配)对每只有订阅的同伴求值一轮:
 * 先从活身体上量一份 {@link WatchSnapshot}(按订阅备料,没人订阅的料不量),再逐个问命中。触发做四件事:
 * 命中写进她的环形缓冲({@code numen.watch.events()} 下轮取走)、追加一条持久事件日志、
 * 调 {@link ServerPrograms#interrupt} 打断(白名单未批准时回执写明「只停车」)、推进该订阅的相位记忆。
 * 开销是订阅数的常数级;一条同伴至多 {@value #MAX_SUBS} 条订阅。
 */
public final class WatchEngine {

    private static final Logger LOG = LoggerFactory.getLogger("numen-watch");

    /** 一条同伴至多挂几条订阅(有界,防外脑写循环越挂越多)。 */
    static final int MAX_SUBS = 16;
    /** 环形缓冲的容量:旧的先丢,每条命中至少有一个取走它的机会。 */
    static final int EVENT_BUFFER = 32;
    /** 求值周期的 JVM 属性名。 */
    static final String EVERY_PROPERTY = "numen.watch.every";
    /** 求值周期缺省值:每 4 tick 一次。 */
    static final int DEFAULT_EVERY = 4;
    /** 事件类冷却的 JVM 属性名(单位 tick;缺省 0 = 关,v0 语义保留)。 */
    static final String EVENT_COOLDOWN_PROPERTY = "numen.watch.event.cooldown";
    /** 事件类冷却缺省值:0 = 不冷却。 */
    static final int DEFAULT_EVENT_COOLDOWN = 0;

    /** 求值周期:每几 tick 一次。JVM 属性 {@code numen.watch.every} 可配,缺省 4,至少 1。 */
    private static final int EVERY = everyTicks(System.getProperty(EVERY_PROPERTY,
            Integer.toString(DEFAULT_EVERY)));
    /** 事件类冷却:几 tick 内不重发。JVM 属性 {@code numen.watch.event.cooldown} 可配,缺省 0,至少 0。 */
    private static final int EVENT_COOLDOWN = cooldownTicks(System.getProperty(EVENT_COOLDOWN_PROPERTY,
            Integer.toString(DEFAULT_EVENT_COOLDOWN)));

    /** 她(UUID)→ 她的订阅与事件。 */
    private static final Map<UUID, Companion> COMPANIONS = new ConcurrentHashMap<>();
    /** 她(UUID)→ 这条命挨过的次数:attacked 原语的计数,与订阅表分开,先挨打后挂订阅也对得上。 */
    private static final Map<UUID, Integer> HURTS = new ConcurrentHashMap<>();

    /** 持久事件日志(install 时注入;未装配 = 不落盘,单测路径)。主线程唯一写者。 */
    private static volatile WatchJournal journal;
    /** 反射白名单(install 时注入;null = 没有白名单 = 一律只停车,fail-closed)。 */
    private static volatile WatchWhitelist whitelist;

    static {
        // 服务器停了,订阅跟着没:它们是世界作用域的状态(照 CompanionTickDispatcher 的家法,谁持有谁报到)。
        // 订阅不写存档 —— 它们本来就是"这一条命"的家当;白名单与事件日志在盘上,不随进程没。
        ServerLifecycle.onStopped(WatchEngine::dropAll);
    }

    private WatchEngine() {}

    /** 一个同伴的全部哨兵家当:订阅表(登记序)与命中环形缓冲。 */
    private static final class Companion {
        final Map<String, WatchSub> subs = new LinkedHashMap<>();
        final ArrayDeque<WatchApi.Hit> events = new ArrayDeque<>();
    }

    /** 求值周期怎么读:不是整数就按缺省算,小于 1 当 1(每 tick)。 */
    static int everyTicks(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (RuntimeException bad) {
            LOG.warn("[numen-watch] {} = \"{}\" is not a number, assuming {}", EVERY_PROPERTY, raw, DEFAULT_EVERY);
            return DEFAULT_EVERY;
        }
    }

    /** 求值周期(单测与文档读)。 */
    static int everyTicks() {
        return EVERY;
    }

    /** 事件类冷却怎么读:不是整数就按缺省算,小于 0 当 0(关)。 */
    static int cooldownTicks(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (RuntimeException bad) {
            LOG.warn("[numen-watch] {} = \"{}\" is not a number, assuming {}", EVENT_COOLDOWN_PROPERTY, raw,
                    DEFAULT_EVENT_COOLDOWN);
            return DEFAULT_EVENT_COOLDOWN;
        }
    }

    /** 事件类冷却(单测与文档读)。 */
    static int eventCooldownTicks() {
        return EVENT_COOLDOWN;
    }

    // ---- 装订(add / list / drop / events 的后台)----

    /**
     * 装一条<b>单原语</b>订阅(v0 形状,单测直接驱动):解析原语、校验并归一参数、同名覆盖。
     * 参数不对、原语不存在,抛 bad_argument,订阅表不动。纯函数;{@code nowTick} 与 {@code hurtBaseline}
     * 由调用方从身体上量。
     */
    static WatchSub build(String name, String primitiveId, WatchApi.Knobs knobs, String why, String programId,
                          long nowTick, long hurtBaseline) {
        return build(name, primitiveId == null ? Optional.empty() : Optional.of(primitiveId),
                Optional.empty(), Optional.empty(), knobs, why, "", programId, nowTick, hurtBaseline);
    }

    /**
     * 装一条订阅(全形状):三选一 —— {@code primitive}(单原语)、{@code both}(恰两个原语相与)、
     * {@code negate}(取反一个电平原语)。非法组合在这里当场拒收,回执说清是哪一条不合法:
     * <ul>
     *   <li>三选一不是恰好一个 —— 拒(说清要 primitive / both / negate 之一);</li>
     *   <li>{@code both} 不是恰好两个、成员不是原语 —— 拒(and 至多两元,操作数只能是原语);</li>
     *   <li>{@code both} 里有 {@code target_gone}/{@code block_changed} —— 拒(单轮边沿,真值不持有,
     *       配进 and 会丢边沿);</li>
     *   <li>{@code negate} 包了事件原语(attacked/target_gone/block_changed)—— 拒(边沿原语不能取非,
     *       语义不成立);not 只许独占顶层一层 —— 由参数形状保证:negate 只收一个原语名,
     *       {@code not (A 且 B)} 与 {@code not not A} 在这个面上表达不出来;</li>
     *   <li>and 的共用参数表里出现两个操作数都不读的参数 —— 拒(写错的参数最会埋雷)。</li>
     * </ul>
     * 纯函数(测试直接驱动);{@code nowTick} 与 {@code hurtBaseline} 由调用方从身体上量。
     */
    static WatchSub build(String name, Optional<String> primitiveId, Optional<List<String>> both,
                          Optional<String> negate, WatchApi.Knobs knobs, String why, String action,
                          String programId, long nowTick, long hurtBaseline) {
        if (name == null || name.isBlank()) {
            throw badArgument("name the watch: add(\"low_blood\", {primitive = \"blood_below\", ...})");
        }
        if (why == null || why.isBlank()) {
            throw badArgument("say why in one line: the interruption hands it to whatever runs next");
        }
        // 空串 = 没声明动作(add 那一侧负责把"给了但空白"拒掉;这里只归一)。
        String reflex = action == null ? "" : action.strip();
        int forms = (primitiveId.isPresent() ? 1 : 0) + (both.isPresent() ? 1 : 0) + (negate.isPresent() ? 1 : 0);
        if (forms == 0) {
            throw badArgument("say what to watch: one of primitive = \"blood_below\", "
                    + "both = {\"attacked\", \"blood_below\"}, or negate = \"in_water\"");
        }
        if (forms > 1) {
            throw badArgument("primitive, both and negate are three shapes of one condition — give exactly one, "
                    + "not several");
        }
        if (primitiveId.isPresent()) {
            WatchPrimitive p = resolve(primitiveId.get());
            WatchSub.Operand op = new WatchSub.Operand(p, nowTick, hurtBaseline);
            p.fill(op, knobs, false);
            return new WatchSub(WatchSub.Form.SINGLE, op, null, name, why, programId, reflex, nowTick);
        }
        if (negate.isPresent()) {
            WatchPrimitive p = resolve(negate.get());
            if (!p.isLevel()) {
                throw badArgument("not cannot wrap " + p.id() + ": it is an event, not a level — negating an "
                        + "event does not hold. Events: attacked, target_gone, block_changed");
            }
            WatchSub.Operand op = new WatchSub.Operand(p, nowTick, hurtBaseline);
            p.fill(op, knobs, false);
            return new WatchSub(WatchSub.Form.NOT, op, null, name, why, programId, reflex, nowTick);
        }
        List<String> pair = both.get();
        if (pair == null || pair.size() != 2) {
            throw badArgument("both takes exactly two primitives to and together (a and b), got "
                    + (pair == null ? "nothing" : pair.size() + " entries") + " — a third layer does not exist");
        }
        WatchPrimitive left = resolve(pair.get(0));
        WatchPrimitive right = resolve(pair.get(1));
        for (WatchPrimitive p : List.of(left, right)) {
            if (!p.andAdmits()) {
                throw badArgument(p.id() + " cannot take part in and: its truth is a single-round edge — "
                        + "it does not hold between rounds, so the and would lose the edge. "
                        + "(and admits every level primitive plus attacked)");
            }
        }
        for (String knob : presentKnobs(knobs)) {
            if (!left.reads(knob) && !right.reads(knob)) {
                throw badArgument("neither " + left.id() + " nor " + right.id() + " reads the knob \"" + knob
                        + "\" — an and shares one args table, and every knob must be read by an operand");
            }
        }
        WatchSub.Operand a = new WatchSub.Operand(left, nowTick, hurtBaseline);
        WatchSub.Operand b = new WatchSub.Operand(right, nowTick, hurtBaseline);
        left.fill(a, knobs, true);
        right.fill(b, knobs, true);
        return new WatchSub(WatchSub.Form.AND, a, b, name, why, programId, reflex, nowTick);
    }

    /** 参数表里给了的参数名(and 的"每个参数都得有人读"检查用)。 */
    private static List<String> presentKnobs(WatchApi.Knobs k) {
        List<String> out = new ArrayList<>();
        if (k.threshold().isPresent()) out.add("threshold");
        if (k.entity().isPresent()) out.add("entity");
        if (k.item().isPresent()) out.add("item");
        if (k.count().isPresent()) out.add("count");
        if (k.pos().isPresent()) out.add("pos");
        if (k.range().isPresent()) out.add("range");
        if (k.seconds().isPresent()) out.add("seconds");
        if (k.repeat().isPresent()) out.add("repeat");
        if (k.tick().isPresent()) out.add("tick");
        return out;
    }

    /** 原语名 → 原语;没有就报 bad_argument,把全部名字报给她。 */
    private static WatchPrimitive resolve(String id) {
        WatchPrimitive p = id == null ? null : WatchPrimitive.of(id);
        if (p == null) {
            throw badArgument("\"" + id + "\" is not a primitive I can watch; they are: " + listPrimitives());
        }
        return p;
    }

    /** 把 build 好的订阅挂上表(容量有界;同名覆盖,算在旧名头上,不另占位)。 */
    static void arm(UUID who, WatchSub sub) {
        Companion c = COMPANIONS.computeIfAbsent(who, k -> new Companion());
        if (!c.subs.containsKey(sub.name) && c.subs.size() >= MAX_SUBS) {
            throw new com.dwinovo.numen.agent.script.ApiError(com.dwinovo.numen.agent.script.ErrorKind.FAILED,
                    "you already carry " + c.subs.size() + " watches, the most one companion may carry ("
                            + MAX_SUBS + "); drop one first",
                    "numen.watch.list() to see what you carry");
        }
        c.subs.put(sub.name, sub);
        LOG.info("[numen-watch] {} arms \"{}\" ({}): {}", who, sub.name, sub.condition(), sub.why);
    }

    /** 她现在挂着几条。 */
    static int carrying(UUID who) {
        Companion c = COMPANIONS.get(who);
        return c == null ? 0 : c.subs.size();
    }

    /** 她挂着的订阅,登记序:名字、条件、why。 */
    static List<WatchApi.Row> rows(UUID who) {
        Companion c = COMPANIONS.get(who);
        if (c == null) {
            return List.of();
        }
        List<WatchApi.Row> out = new ArrayList<>();
        for (WatchSub sub : c.subs.values()) {
            out.add(new WatchApi.Row(sub.name, sub.condition(), sub.why));
        }
        return out;
    }

    /** 注销一条;没有这条就报 not_found,把她现在挂着的名字说出来。 */
    static WatchApi.WatchDropped drop(UUID who, String name) {
        Companion c = COMPANIONS.get(who);
        WatchSub removed = c == null ? null : c.subs.remove(name);
        if (removed == null) {
            throw new com.dwinovo.numen.agent.script.ApiError(com.dwinovo.numen.agent.script.ErrorKind.NOT_FOUND,
                    "you carry no watch called \"" + name + "\"" + (c == null || c.subs.isEmpty()
                            ? ""
                            : "; you carry " + c.subs.keySet() + " — drop one of those"),
                    "numen.watch.list() to see what you carry");
        }
        LOG.info("[numen-watch] {} drops \"{}\"", who, name);
        return new WatchApi.WatchDropped(name, removed.condition());
    }

    /** 取走这一轮的命中(取走即清空:每条只交一次,旧的先交)。 */
    static WatchApi.WatchEvents drain(UUID who) {
        Companion c = COMPANIONS.get(who);
        List<WatchApi.Hit> out = new ArrayList<>();
        if (c != null) {
            WatchApi.Hit hit;
            while ((hit = c.events.pollFirst()) != null) {
                out.add(hit);
            }
        }
        return new WatchApi.WatchEvents(List.copyOf(out));
    }

    // ---- 求值 ----

    /** 每个服务器刻由核心的刻钩子调一次:到点(每 {@code EVERY} tick)对每只有订阅的同伴求值。 */
    public static void tick(MinecraftServer server) {
        if (COMPANIONS.isEmpty() || server.getTickCount() % EVERY != 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer her) {
                Companion c = COMPANIONS.get(her.getUUID());
                if (c != null && !c.subs.isEmpty()) {
                    List<WatchApi.Hit> fired = evaluate(her.getUUID(),
                            WatchSnapshot.of(her, c.subs.values()));
                    fired.forEach(hit -> launchIfWhitelisted(server, her, c, hit));
                }
            }
        }
    }

    /**
     * 求值一轮:快照进来,订阅逐个问命中,OR 到一个算一个 —— 命中的写环形缓冲、追加一条持久事件日志、
     * 喊停 add 它的程序。订阅声明了反射动作的,回执与事件里写明白名单结论:批准了才起步(起步本身在
     * {@link #launchIfWhitelisted},那里拿得到服务器),没批准只停车。
     * 返回本轮命中的(单测直接驱动;服务端路径经 {@link #tick})。
     */
    static List<WatchApi.Hit> evaluate(UUID who, WatchSnapshot s) {
        Companion c = COMPANIONS.get(who);
        if (c == null) {
            return List.of();
        }
        List<WatchApi.Hit> fired = new ArrayList<>();
        for (WatchSub sub : c.subs.values()) {
            if (sub.fire(s)) {
                String actionNote = actionNote(sub);
                WatchApi.Hit hit = new WatchApi.Hit(sub.name, sub.condition(), sub.why, s.nowTick(),
                        s.dayTimeOfDay(), actionNote);
                fired.add(hit);
                if (c.events.size() >= EVENT_BUFFER) {
                    c.events.pollFirst();
                }
                c.events.addLast(hit);
                WatchJournal log = journal;
                if (log != null) {
                    log.append(s.nowTick(), "watch-hit", "", "", sub.name, sub.condition(), sub.why + actionNote);
                }
                ServerPrograms.interrupt(who, sub.programId, message(sub) + actionNote);
            }
        }
        return fired;
    }

    /** 反射动作的白名单结论:没声明是空串;声明了,批准与否各有一句写明白的话(回执与事件共用)。 */
    private static String actionNote(WatchSub sub) {
        if (!sub.hasAction()) {
            return "";
        }
        String id = WatchWhitelist.idOf(sub.action);
        WatchWhitelist allowed = whitelist;
        if (allowed != null && allowed.allows(id)) {
            return "; reflex " + id + " is on the owner's whitelist, starting it";
        }
        return "; the action is not on the owner's whitelist (id " + id + ") — whitelist not approved, "
                + "stopped only, nothing started";
    }

    /**
     * 白名单内的反射动作:下一 tick 经 {@link ServerPrograms#launch} 起步(照 /numen drive 的延期一格
     * 写法;起步前再查一次白名单)。单次尝试、不排队:槽被上一段收尾占住就这一拍放弃,回执进日志 ——
     * 反射任务必须是短任务、不依赖跨调用存活。白名单外的命中不进这里(evaluate 已经只停车并写明)。
     */
    private static void launchIfWhitelisted(MinecraftServer server, NumenPlayer her, Companion c,
                                            WatchApi.Hit hit) {
        WatchSub sub = c.subs.get(hit.name());
        if (sub == null || !sub.hasAction()) {
            return;
        }
        String id = WatchWhitelist.idOf(sub.action);
        WatchWhitelist allowed = whitelist;
        if (allowed == null || !allowed.allows(id)) {
            return;
        }
        int at = server.getTickCount() + 1;
        server.tell(new TickTask(at, () -> {
            if (COMPANIONS.get(her.getUUID()) != c || whitelist == null || !whitelist.allows(id)) {
                return;   // 一 tick 之内订阅没了或白名单变了:这一拍不起步
            }
            LOG.info("[numen-watch] {} starts whitelisted reflex {} (watch \"{}\")", her.getUUID(), id, sub.name);
            ServerPrograms.launch(her, "watch", sub.action, com.dwinovo.numen.program.CallObserver.NONE,
                    receipt -> LOG.info("[numen-watch] {} reflex {} receipt: {}", her.getUUID(), id, receipt));
        }));
    }

    /** 打断递给回执的那句:命中哪条、什么条件、她写下的一行 why。 */
    private static String message(WatchSub sub) {
        return "watch " + sub.name + " hit (" + sub.condition() + "): " + sub.why;
    }

    // ---- 装配 ----

    /** install 时注入持久事件日志与反射白名单(路径由装配方解析后注入,照 JsonlJournal 的家法)。 */
    static void attach(WatchJournal events, WatchWhitelist reflexes) {
        journal = events;
        whitelist = reflexes;
    }

    // ---- 生命周期 ----

    /** 她挨了一下({@link CompanionEvent#HURT}):这条命的挨打计数 +1。HURT 在伤害结算的同一 tick 内
     * 发出(主线程),所以发生的记账在事件当 tick 内;报告在求值轮。 */
    public static void hurt(UUID who) {
        HURTS.merge(who, 1, Integer::sum);
    }

    /** 这条命挨过的次数(快照的 hurtCount;没有记录就是 0)。 */
    static int hurtCount(UUID who) {
        Integer n = HURTS.get(who);
        return n == null ? 0 : n;
    }

    /** 她的身体离开了世界(REMOVE:休眠/注销/死亡后消失):这条命的订阅与命中整份清空。换维度不走这里。 */
    public static void forget(UUID who) {
        COMPANIONS.remove(who);
        HURTS.remove(who);
    }

    /** 服务器停了:一切作废,下一个存档从空表开始(照 CompanionTickDispatcher.dropAll;幂等)。
     * 摘的只有订阅与挨打计数这些<b>这条命/这个世界作用域</b>的家当。日志与白名单<b>不摘</b>:
     * 它们是主人的盘上家当(声明为「不随进程没」),而且单人模式里「退出存档」不结束进程
     * (ServerLifecycle 的 javadoc:单人退存档、专用服关服、切存档走的都是同一次 onStopped)——
     * 同一 JVM 重进世界时 install 不会重跑(它挂在 mod 构造期,每 JVM 一次),这里若摘了,
     * 重进世界后命中会静默不再写 JSONL、已批动作会静默变回「只停车」。真重启 = 新 JVM,
     * 那条路才由 install 重新接上。 */
    static void dropAll() {
        COMPANIONS.clear();
        HURTS.clear();
    }

    /** 单测专用:摘下日志与白名单。生产路径<b>永不</b>摘——attach 每 JVM 一次(NumenWatch.install,
     * 挂在 mod 构造期),dropAll(关服/单人退存档)之后重进世界也要照常落盘、照常认白名单。 */
    static void detachForTests() {
        journal = null;
        whitelist = null;
    }

    // ---- 私有工具 ----

    private static String listPrimitives() {
        StringBuilder out = new StringBuilder();
        for (WatchPrimitive p : WatchPrimitive.values()) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(p.id()).append(" (").append(p.help()).append(')');
        }
        return out.toString();
    }

    private static com.dwinovo.numen.agent.script.ApiError badArgument(String message) {
        return new com.dwinovo.numen.agent.script.ApiError(com.dwinovo.numen.agent.script.ErrorKind.BAD_ARGUMENT,
                message, "numen.api.help(\"numen.watch.add\") lists every primitive and its knobs");
    }
}
