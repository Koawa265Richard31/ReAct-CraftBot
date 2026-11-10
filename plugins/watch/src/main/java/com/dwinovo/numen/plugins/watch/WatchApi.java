package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.watch}:她的常驻订阅面 —— add / list / drop / events 四个函数。函数样式(numen.api、
 * {@code @Fn}、记录参数、ApiError)照 {@code MoveApi}/{@code PlacesApi} 写,不发明新接口:必填的
 * {@code name} 是位置参数,其余都是选项表里的键,所以她写的就是
 * {@code add("low_blood", {primitive = "blood_below", args = {threshold = 10}, why = "..."})}。
 *
 * <p>坐标用 {@link BlockPos}(Lua 的类型名是全局的,自造 {@code record Pos} 这类名字会撞上
 * LuaCodecs 的共用类,登记不出去);实体点名用 {@link EntityRef}。命中动作在 {@link WatchEngine}:
 * 打断 + 环形缓冲 + 持久事件日志,反射动作经白名单把守;本类只装订、只读。
 */
public final class WatchApi {

    /** 这个联动在她的 API 里的组名;拼上登记 id {@code numen},她的面就是 {@code numen.watch}(见 NumenWatch)。 */
    static final String NAMESPACE_GROUP = "watch";

    private WatchApi() {}

    static void install(NumenApi numen) {
        numen.api(NAMESPACE_GROUP, "Resident watches: arm a condition once and get pulled out of whatever you "
                + "are running the moment it holds — no model in the loop.", WatchApi.class);
    }

    /** 一个原语的参数,每项可省;哪个原语要哪几项,由 {@link WatchPrimitive} 说了算。 */
    public record Knobs(
            @Doc("blood_below: health under this. hungry_below: food at or under this. falling: blocks already "
                    + "fallen. air_low: percent of breath left.")
            @Omitted("the primitive's default, if it has one") Optional<Double> threshold,
            @Doc("target_gone, entity_near, entity_far: which entity, as numen.scan.entities lists it.")
            Optional<EntityRef> entity,
            @Doc("item_at_least, item_at_most: which item, e.g. \"minecraft:diamond\".")
            Optional<String> item,
            @Doc("item_at_least, item_at_most: how many.") Optional<Double> count,
            @Doc("block_changed, out_of_bounds: which cell.") Optional<BlockPos> pos,
            @Doc("entity_near, entity_far, hostile_near: the radius, in blocks. out_of_bounds: how far you may "
                    + "drift.") Optional<Double> range,
            @Doc("elapsed: how many seconds since you armed it.") Optional<Double> seconds,
            @Doc("elapsed: true = keep firing every so many seconds instead of once.") Optional<Boolean> repeat,
            @Doc("daytime: the time of day to wait for, 0-23999.") Optional<Double> tick) {

        /** 空参数表:不收参数的原语用它(add 的 args 可省,同一回事)。 */
        public static final Knobs EMPTY = new Knobs(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** add 的第二件(选项表):除了名字之外的一切。条件的形状三选一:primitive(单原语)/
     * both(恰两个原语相与)/ negate(取反一个电平原语)。 */
    public record New(
            @Doc("What to call the watch; arming the same name again overwrites it.") String name,
            @Doc("Which primitive to watch: blood_below, hungry_below, air_low, falling, on_fire, in_water, "
                    + "in_lava, attacked, inv_full, item_at_least, item_at_most, target_gone, entity_near, "
                    + "entity_far, hostile_near, block_changed, out_of_bounds, elapsed, daytime.")
            Optional<String> primitive,
            @Doc("The knobs the primitive reads; primitives that take none take an empty table. "
                    + "An and shares one table between its two primitives.")
            @Omitted("no knobs") Optional<Knobs> args,
            @Doc("One line saying why this watch exists — it is what the interruption tells her next program, "
                    + "and what list echoes back.") Optional<String> why,
            @Doc("And: exactly two primitive ids, e.g. {\"attacked\", \"blood_below\"} — fires when both hold "
                    + "at once. target_gone and block_changed cannot take part (their truth is a single-round "
                    + "edge). A third layer does not exist: operands are primitives, not combinations.")
            @Omitted("no and") Optional<List<String>> both,
            @Doc("Not: one level primitive id, e.g. \"in_water\" — fires while it does not hold, and stands "
                    + "alone as the whole condition. Events (attacked, target_gone, block_changed) cannot be "
                    + "negated.") @Omitted("no not") Optional<String> negate,
            @Doc("Optional reflex: a short Lua program this watch starts when it fires. It runs only if the "
                    + "owner put this exact action on the whitelist (config/numen/numen_watch-whitelist.json, "
                    + "empty by default); otherwise the watch only stops the program and says so in the "
                    + "receipt.") @Omitted("stop only") Optional<String> action) {}

    /** 挂上了什么。 */
    @Doc("What an add armed.")
    public record Added(@Doc("The watch's name.") String name,
                        @Doc("Which primitive it watches.") String primitive,
                        @Doc("Your why, as the interruption will carry it.") String why,
                        @Doc("How many watches you now carry.") int carrying) {}

    /** 一条挂着的订阅。 */
    @Doc("One watch you carry.")
    public record Row(@Doc("Its name.") String name,
                      @Doc("Which primitive it watches.") String primitive,
                      @Doc("The why you armed it with.") String why) {}

    /** 所挂订阅。 */
    @Doc("The watches you carry, in the order you armed them.")
    public record WatchList(@Doc("Every watch, oldest first.") List<Row> rows) {}

    /** 注销哪条。 */
    public record Drop(@Doc("The watch's name.") String name) {}

    /** 注销了什么。 */
    @Doc("What a drop disarmed.")
    public record WatchDropped(@Doc("The watch's name.") String name,
                          @Doc("Which primitive it watched.") String primitive) {}

    /** 一次命中。 */
    @Doc("One watch hit.")
    public record Hit(@Doc("The watch's name.") String name,
                      @Doc("The condition that fired: a primitive id, \"a and b\", or \"not a\".") String primitive,
                      @Doc("The why the watch was armed with — the interruption receipt carries the same line.")
                      String why,
                      @Doc("Server tick when it fired.") long tick,
                      @Doc("In-game time of day (0-23999) when it fired.") long daytime,
                      @Doc("What happened to the declared reflex action: empty when none was declared; "
                              + "\"...whitelist not approved, stopped only, nothing started\" when the owner "
                              + "had not approved it; \"...on the owner's whitelist, starting it\" when approved.")
                      String action) {}

    /** 取走的一批命中。 */
    @Doc("The watch hits since you last asked, oldest first.")
    public record WatchEvents(@Doc("Every hit since your last ask; taking them clears them.") List<Hit> hits) {}

    /**
     * 挂一条常驻订阅:从此每几 tick(可配,缺省 4)条件对她的状态求值一次,任一命中当场触发 ——
     * 打断她正在跑的程序(原因就是这条 why),命中本身记进环形缓冲,{@code events()} 下轮取走,
     * 并追加一条持久事件日志。条件的形状三选一(注册期校验,非法组合当场拒收):
     * {@code primitive}(单原语)、{@code both}(恰两个原语相与,操作数只能是原语)、
     * {@code negate}(取反一个电平原语,独占顶层)。触发是边沿:连续为真只触发一次,掉回假再上来
     * 才再触发;事件类(attacked/target_gone/block_changed)是「发生」不是「状态」,同一轮的同类发生
     * 合并为一条,配冷却。订阅是这条命的家当:身体一离开世界就清空,换维度不清,重启不回来。
     * 声明了 {@code action} 的命中,只有动作在主人白名单里才会自动起步;否则只停车并在回执里写明。
     */
    @Fn("Arm a resident watch: name it, pick a condition (a primitive, an and of two, or a not of one), and "
            + "the moment it holds, whatever program she is running is interrupted with your why.")
    @Example("numen.watch.add(\"low_blood\", {primitive = \"blood_below\", args = {threshold = 10}, "
            + "why = \"my blood is low: stop and eat or retreat\"})")
    @Example("numen.watch.add(\"hurt_low\", {both = {\"attacked\", \"blood_below\"}, args = {threshold = 10}, "
            + "why = \"hit and bleeding: retreat now\"})")
    @Example("numen.watch.add(\"dry_fight\", {negate = \"in_water\", args = {range = 8}, "
            + "why = \"enemy close and I am not wet: hold the crossing\"})")
    @Example("numen.watch.add(\"stash_touched\", {primitive = \"block_changed\", "
            + "args = {pos = numen.shape.pos(120, 64, -35)}, why = \"someone altered the stash floor\"})")
    @Example("numen.watch.add(\"too_slow\", {primitive = \"elapsed\", args = {seconds = 120, repeat = true}, "
            + "why = \"this haul is taking too long, reassess\"})")
    @Note("The watch outlives this program: it fires while she runs anything, until you drop it or her body "
            + "leaves the world. Re-adding a name overwrites the old watch.")
    @Note("Firing is edge-triggered: a condition that stays true fires once, and again only after it has been "
            + "false in between. item_at_most and out_of_bounds fire at once when they are already true at the "
            + "first check. attacked, target_gone and block_changed are events, not levels: what happened in "
            + "one tick merges into one report, with a cooldown between reports.")
    @Note("Illegal combinations are refused here, at arming — the error names the exact rule.")
    @Note("An action starts only if the owner whitelisted this exact action text; a watch without a whitelisted "
            + "action only stops the program, and the receipt says so.")
    @Note("A skill arms its watches when it starts and drops them when it ends — on the failure path too "
            + "(drop in the pcall's last line).")
    @SeeAlso({"numen.watch.list", "numen.watch.drop", "numen.watch.events"})
    public static Added add(ServerCall call, New args) {
        NumenPlayer her = call.her();
        Knobs knobs = args.args().orElse(Knobs.EMPTY);
        if (knobs.item().isPresent()) {
            requireItem(knobs.item().get());
        }
        String why = args.why().filter(w -> !w.isBlank()).orElseThrow(() -> badArgument(
                "say why in one line: the interruption hands it to whatever runs next, e.g. "
                        + "why = \"my blood is low: stop and eat\""));
        String action = args.action().orElse("");
        if (args.action().isPresent() && action.strip().isEmpty()) {
            throw badArgument("an action, if given, is the reflex Lua to start when this watch fires — "
                    + "it cannot be blank");
        }
        long nowTick = her.getServer() != null ? her.getServer().getTickCount() : 0;
        WatchSub sub = WatchEngine.build(args.name(), args.primitive(), args.both(), args.negate(), knobs, why,
                args.action().orElse(""), programId(call), nowTick, WatchEngine.hurtCount(her.getUUID()));
        WatchEngine.arm(her.getUUID(), sub);
        return new Added(sub.name, sub.condition(), sub.why, WatchEngine.carrying(her.getUUID()));
    }

    /** 她挂着的订阅,登记序。 */
    @Fn("The watches you carry: name, primitive, why — in the order you armed them.")
    @Example("for _, w in ipairs(numen.watch.list().rows) do print(w.name, w.primitive) end")
    @Note("Reads only. Watches survive across programs and dimensions; they end when the body leaves the "
            + "world, and they are not saved across restarts.")
    @SeeAlso({"numen.watch.drop", "numen.watch.add", "numen.watch.events"})
    public static WatchList list(ServerCall call) {
        return new WatchList(WatchEngine.rows(call.her().getUUID()));
    }

    /** 注销一条:名字对不上报 not_found,把她挂着的那排名字说出来。 */
    @Fn("Disarm one of your watches by name.")
    @Example("numen.watch.drop(\"low_blood\")")
    @Note("Instant. A skill that armed watches drops them when it ends — on the failure path too.")
    @SeeAlso({"numen.watch.list", "numen.watch.events"})
    public static WatchDropped drop(ServerCall call, Drop args) {
        return WatchEngine.drop(call.her().getUUID(), args.name());
    }

    /** 取走上轮以来的命中(取走即清空)。 */
    @Fn("The watch hits since you last asked: each one interrupted some program with its why.")
    @Example("for _, hit in ipairs(numen.watch.events().hits) do print(hit.name, hit.why) end")
    @Note("Taking them clears them: each hit is handed over once, oldest first. A hit carries the watch's "
            + "name, condition, why, the tick and time of day it fired, and what became of a declared reflex "
            + "action (empty when none was declared).")
    @Note("A hit interrupts whatever program was running with the watch's why — this list is for deciding "
            + "what to do next, not for learning that something happened: the interrupted program already "
            + "saw the reason.")
    @SeeAlso({"numen.watch.list", "numen.watch.add"})
    public static WatchEvents events(ServerCall call) {
        return WatchEngine.drain(call.her().getUUID());
    }

    // ---- 私有工具 ----

    /**
     * add 这一下所在的那段程序:callId 是 programId#第几次调用(见 Program 的派发),劈开取前半。
     * 命中就打断这一段;劈不出 #(防护性质)就整串留着,引擎按编号匹配,匹配不上就是不理。
     */
    static String programId(ServerCall call) {
        String callId = call.callId();
        int cut = callId.lastIndexOf('#');
        return cut > 0 ? callId.substring(0, cut) : callId;
    }

    /** 物品 id 得是这台服务器上真有的:乱写的 id 会造出永远等不来的订阅。 */
    private static void requireItem(String id) {
        ResourceLocation rl;
        try {
            rl = new ResourceLocation(id);
        } catch (RuntimeException badSyntax) {
            throw badArgument("\"" + id + "\" is not an item id (they look like minecraft:diamond): "
                    + badSyntax.getMessage());
        }
        if (BuiltInRegistries.ITEM.getOptional(rl).isEmpty()) {
            throw badArgument("there is no item called " + id + " on this server");
        }
    }

    /** 参数不对:原语名、参数、why 这类她传进来的东西没通过验,一律 bad_argument,把该给的样子说出来。 */
    private static ApiError badArgument(String message) {
        return new ApiError(ErrorKind.BAD_ARGUMENT, message,
                "numen.watch.list() to see what you carry, or numen.api.help(\"numen.watch.add\")");
    }
}
