package com.dwinovo.numen.plugins.places;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.places.PlacesStore.Stored;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Flatten;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code numen.place}:她自己记下的命名地点 —— 标一个点,以后自己照着走回去。
 *
 * <p>点的形状就是一个位置:{@code x/y/z} 经 {@link Flatten} 摊在表顶(收据第一个组件摊平是
 * {@code RecordCodec} 的规矩,先例 {@code ItemInfo(@Flatten EntityInfo entity, …)}),所以
 * {@code get} 交回的东西可以整表递给 {@code num.route.plan} 的 {@code to} —— {@code to} 的读法只认它要的
 * 键({@code x/y/z},或任何表里的 {@code pos} 字段),旁边多出来的 {@code dim}、{@code last_used} 不碍事。
 * 函数样式(numen.api、@Fn、记录参数、ApiError)照 {@code MoveApi}/{@code FtbqApi} 写,不发明新接口。
 */
public final class PlacesApi {

    /** 这个联动在她的 API 里的组名;拼上登记 id {@code numen},她的面就是 {@code numen.place}(见 NumenPlaces)。 */
    static final String NAMESPACE = "place";

    /** near 不给半径就找这么远。 */
    static final double NEAR_DEFAULT = 32;
    static final double NEAR_MIN = 1;
    static final double NEAR_MAX = 128;

    /** 原版世界的东西边缘(±3000 万格)。取整格前先验,超界或 NaN 都当场拒收:
     *  静默截回就是"把半截数据当真" —— 格点按位宽打包存档,y 超界会被无声卷回。 */
    static final double WORLD_EDGE = 30_000_000;

    private PlacesApi() {}

    static void install(NumenApi numen) {
        numen.api(NAMESPACE, "Your own named places: mark a spot, list them, and walk back to one later "
                + "with the route planner.", PlacesApi.class);
    }

    /** 一个点摊开。 */
    @Doc("One of your places.")
    public record Spot(@Doc("Where it is; hand it straight to plan({to = ...}).") BlockPos pos,
                       @Doc("Its name.") String name,
                       @Doc("Which dimension it lives in, e.g. minecraft:overworld.") String dim,
                       @Doc("How far it is, in blocks; none when it is in another dimension.")
                       Optional<Double> distance,
                       @Doc("When you last asked for it, in wall-clock ms; 0 = marked, never used.") long lastUsed) {}

    /** 标完(或盖掉)了什么。 */
    @Doc("What a mark did.")
    public record Marked(@Doc("Where it is.") BlockPos pos,
                         @Doc("The spot's name.") String name,
                         @Doc("Which dimension it is in.") String dim,
                         @Doc("true = a spot by this name in this dimension was already there and is overwritten.")
                         boolean replaced) {}

    /** 列出来的一个点。 */
    @Doc("One row of your place list.")
    public record Listed(@Doc("Where it is.") BlockPos pos,
                         @Doc("Its name.") String name,
                         @Doc("Which dimension it is in.") String dim,
                         @Doc("How far, in blocks; none in another dimension.") Optional<Double> distance,
                         @Doc("When you last used it; 0 = never since marking.") long lastUsed) {}

    /** 所有点。 */
    @Doc("Your places: the ones in your dimension first, nearest first, then the other dimensions.")
    public record Places(@Doc("Every spot you have marked.") List<Listed> places) {}

    /** 标哪个点。 */
    public record Mark(@Doc("What to call the spot.") @Positional @Omitted("you must name it, e.g. "
                               + "the mark form with a name") Optional<String> name,
                       @Doc("East/west.") @Omitted("where you stand") Optional<Double> x,
                       @Doc("Altitude.") @Omitted("where you stand") Optional<Double> y,
                       @Doc("North/south.") @Omitted("where you stand") Optional<Double> z,
                       @Doc("Which dimension to file it under.") @Omitted("the dimension you are in")
                       Optional<String> dim) {}

    /** 取哪个点。 */
    public record Get(@Doc("The spot's name, as you marked it.") String name) {}

    /** 删哪个点。 */
    public record Forget(@Doc("The spot's name.") String name) {}

    /** 删掉了什么。 */
    @Doc("What a forget did.")
    public record Forgot(@Doc("The spot's name.") String name,
                         @Doc("Which dimension it was in.") String dim) {}

    /** 找多远以内。 */
    public record Near(@Doc("Half-width of the search, in blocks ("
            + (int) NEAR_MIN + "-" + (int) NEAR_MAX + ").")
                       @Omitted("search " + (int) NEAR_DEFAULT + " blocks around you") Optional<Double> radius) {}

    /**
     * 标一个点:同名同维度是覆盖;不写坐标就标她脚下;{@code dim} 只在把点记到别的维度名下时才需要
     * (跨维度标必须带全 x/y/z)。名字、维度、坐标都先验再收:空名字、这台服务器上没有的维度、
     * 非数或越界的坐标当场拒收(bad_argument)—— 收进来再丢就是造死点。点存在存档世界里(PlacesStore),
     * 重启、换存档都按存档算。
     */
    @Fn("Mark a named spot so you can find your way back later.")
    @Example("numen.place.mark(\"home\")")
    @Example("numen.place.mark(\"home\", {x = 120, y = 64, z = -35})")
    @Example("numen.place.mark(\"nether_hub\", {x = 12, y = 70, z = 400, dim = \"minecraft:the_nether\"})")
    @Note("Instant. Same name in the same dimension overwrites; the same name in another dimension is its own "
            + "spot. get hands the spot back as a table with a pos, so plan({to = spot}) walks you home.")
    @Note("Your spots are yours alone: another companion does not see them, and they live in the save's world "
            + "data, not in any config folder.")
    @SeeAlso({"numen.place.get", "numen.place.list"})
    public static Marked mark(ServerCall call, Mark args) {
        String name = args.name().filter(named -> !named.isBlank()).orElseThrow(() ->
                badArgument("name the spot: mark(\"home\", {x = ..., y = ..., z = ...}) — the name must not be empty"));
        NumenPlayer her = call.her();
        requireFullCoords(args);
        String here = dimOf(her);
        String dim = args.dim().orElse(here);
        boolean hasCoords = args.x().isPresent();
        if (!hasCoords && !dim.equals(here)) {
            throw badArgument("you gave a dimension without coordinates: say where in " + dim
                    + " with {x = , y = , z = }");
        }
        MinecraftServer server = serverOf(her);
        if (args.dim().isPresent()) {
            requireKnownDim(server, dim);
        }
        BlockPos at = hasCoords ? checkedAt(her, args) : her.blockPosition();
        boolean replaced = PlacesStore.of(server).put(her.getUUID(), dim, name, at, System.currentTimeMillis());
        return new Marked(at, name, dim, replaced);
    }

    /**
     * 取一个点:缺省取她<b>当前维度</b>下的那个;当前维度没有但别的维度标过时,报 not_found 并把别的维度报给她
     * —— 不替她跨维度猜。取到即"用到",last_used 随之更新。
     */
    @Fn("One of your places, with a pos you can hand straight to the route planner's to.")
    @Example("local spot = numen.place.get(\"home\")\nnumen.route.plan({to = spot})")
    @Note("Spot carries a pos (x/y/z) plus name, dim and last_used as extra keys, which the "
            + "planner's to does not read.")
    @Note("It takes the spot in the dimension you stand in now. A same-named spot in another dimension is not "
            + "returned; the failure names where you did mark it.")
    @Note("Asking counts as using it: the spot's last_used moves to now — the spot you are handed already "
            + "carries this call's time.")
    @SeeAlso({"numen.place.mark", "numen.place.list", "numen.route.plan"})
    public static Spot get(ServerCall call, Get args) {
        NumenPlayer her = call.her();
        String here = dimOf(her);
        PlacesStore store = storeOf(her);
        store.touch(her.getUUID(), here, args.name(), System.currentTimeMillis());
        // touch 之后再取一遍:交回的那份就带着这一次的 last_used,不是仓里的旧值。
        Optional<Stored> row = store.find(her.getUUID(), here, args.name());
        if (row.isEmpty()) {
            throw notFound(store, her.getUUID(), args.name(), here);
        }
        return toSpot(row.get(), her);
    }

    /** 所有点:当前维度的在前(由近及远),别的维度按维度、名字排;跨维度的 distance 是 nil。 */
    @Fn("Every spot you have marked: name, dimension, coordinates, distance, last use.")
    @Example("for _, p in ipairs(numen.place.list().places) do print(p.name, p.dim, p.distance) end")
    @Note("Reads only. Spots in other dimensions list with dim filled and distance = nil: no number compares "
            + "across dimensions.")
    @SeeAlso({"numen.place.near", "numen.place.get"})
    public static Places list(ServerCall call) {
        NumenPlayer her = call.her();
        List<Stored> mine = storeOf(her).ownedBy(her.getUUID());
        List<Listed> rows = new ArrayList<>();
        for (Stored row : ordered(mine, her.position(), dimOf(her))) {
            rows.add(toListed(row, her));
        }
        return new Places(rows);
    }

    /** 删一个点:当前维度没有就报 not_found,并指路别的维度。 */
    @Fn("Forget one of your spots.")
    @Example("numen.place.forget(\"old_base\")")
    @Note("Instant. Only the spot in the dimension you stand in is removed; the failure names other dimensions "
            + "when the name lives there too.")
    @SeeAlso("numen.place.list")
    public static Forgot forget(ServerCall call, Forget args) {
        NumenPlayer her = call.her();
        String here = dimOf(her);
        PlacesStore store = storeOf(her);
        if (store.find(her.getUUID(), here, args.name()).isEmpty()) {
            throw notFound(store, her.getUUID(), args.name(), here);
        }
        store.remove(her.getUUID(), here, args.name());
        return new Forgot(args.name(), here);
    }

    /** 当前维度里、半径内的点,由近及远;跨维度的不算"近",不出现。 */
    @Fn("Your spots within radius of where you stand, in this dimension, nearest first.")
    @Example("numen.place.near()")
    @Example("local close = numen.place.near({radius = 32})\n"
            + "if #close.places > 0 then numen.move.go(numen.route.plan({to = close.places[1]})) end")
    @Note("Reads only. Spots in other dimensions are never near anything.")
    @SeeAlso({"numen.place.list", "numen.move.go"})
    public static Places near(ServerCall call, Near args) {
        double radius = Mth.clamp(args.radius().orElse(NEAR_DEFAULT), NEAR_MIN, NEAR_MAX);
        NumenPlayer her = call.her();
        String here = dimOf(her);
        Vec3 eyes = her.position();
        List<Stored> inRange = new ArrayList<>();
        for (Stored row : storeOf(her).inDim(her.getUUID(), here)) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(row.blockPos())) <= radius * radius) {
                inRange.add(row);
            }
        }
        inRange.sort(Comparator.comparingDouble(row ->
                eyes.distanceToSqr(Vec3.atCenterOf(row.blockPos()))));
        List<Listed> rows = new ArrayList<>();
        for (Stored row : inRange) {
            rows.add(toListed(row, her));
        }
        return new Places(rows);
    }

    // ---- 纯逻辑,不碰 Minecraft,单测钉得住 ----

    /** 坐标要么不给、要么给全:x/y/z 不许只来一半。 */
    static void requireFullCoords(Mark args) {
        boolean any = args.x().isPresent() || args.y().isPresent() || args.z().isPresent();
        boolean all = args.x().isPresent() && args.y().isPresent() && args.z().isPresent();
        if (any && !all) {
            throw badArgument("x, y and z go together: give all three or none (none marks where you stand)");
        }
    }

    /**
     * 落格前先验坐标:NaN/无穷、低于这个维度的建筑高度或越过世界边缘都当场拒收。
     * {@code BlockPos.containing} 对 NaN 会静默变成原点,越界的 y 存档打包时会被无声卷回
     * (标 y=5000,取回 y≈904 那种)—— 两者都是把半截数据当真。
     */
    private static BlockPos checkedAt(NumenPlayer her, Mark args) {
        double x = args.x().get();
        double y = args.y().get();
        double z = args.z().get();
        if (!Double.isFinite(x)) {
            throw badArgument("x is not a number");
        }
        if (!Double.isFinite(y)) {
            throw badArgument("y is not a number");
        }
        if (!Double.isFinite(z)) {
            throw badArgument("z is not a number");
        }
        int min = her.level().getMinBuildHeight();
        int max = her.level().getMaxBuildHeight();
        if (y < min || y >= max) {
            throw badArgument("y " + y + " is outside this dimension's build height (" + min + " to " + (max - 1)
                    + ")");
        }
        if (Math.abs(x) > WORLD_EDGE || Math.abs(z) > WORLD_EDGE) {
            throw badArgument("x " + x + " / z " + z + " is beyond the world edge (" + (long) WORLD_EDGE + ")");
        }
        return BlockPos.containing(new Vec3(x, y, z));
    }

    /** 维度名必须是这台服务器上真实存在的维度:乱写的维度只会造出永远取不回的死点。 */
    private static void requireKnownDim(MinecraftServer server, String dim) {
        ResourceLocation id;
        try {
            id = new ResourceLocation(dim);
        } catch (RuntimeException badSyntax) {
            throw badArgument("\"" + dim + "\" is not a dimension id (they look like minecraft:overworld): "
                    + badSyntax.getMessage());
        }
        if (server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) == null) {
            throw badArgument("there is no dimension called " + dim + " on this server");
        }
    }

    /** 名单顺序:当前维度的在前、由近及远;其余按维度名、再按点名。 */
    static List<Stored> ordered(List<Stored> rows, Vec3 here, String hereDim) {
        List<Stored> out = new ArrayList<>(rows);
        out.sort(Comparator
                .comparing((Stored row) -> row.dim().equals(hereDim) ? 0 : 1)
                .thenComparing(row -> row.dim().equals(hereDim)
                        ? here.distanceToSqr(Vec3.atCenterOf(row.blockPos()))
                        : 0.0)
                .thenComparing(Stored::dim)
                .thenComparing(Stored::name));
        return out;
    }

    // ---- 私有工具 ----

    private static Spot toSpot(Stored row, NumenPlayer her) {
        return new Spot(row.blockPos(), row.name(), row.dim(), distanceTo(her, row), row.lastUsed());
    }

    private static Listed toListed(Stored row, NumenPlayer her) {
        return new Listed(row.blockPos(), row.name(), row.dim(), distanceTo(her, row), row.lastUsed());
    }



    /** 距离,米;别的维度没有数可比,给空。量法照 MoveApi.flyTo(到格心)。 */
    private static Optional<Double> distanceTo(NumenPlayer her, Stored row) {
        if (!row.dim().equals(dimOf(her))) {
            return Optional.empty();
        }
        return Optional.of(Math.sqrt(her.position().distanceToSqr(Vec3.atCenterOf(row.blockPos()))));
    }

    /** 维度的字符串形状与核心一致:"minecraft:overworld"(照同伴跨维度事件的写法)。 */
    private static String dimOf(NumenPlayer her) {
        return her.level().dimension().location().toString();
    }

    private static MinecraftServer serverOf(NumenPlayer her) {
        MinecraftServer server = her.level().getServer();
        if (server == null) {
            throw refused("no server is running right now", null);
        }
        return server;
    }

    private static PlacesStore storeOf(NumenPlayer her) {
        return PlacesStore.of(serverOf(her));
    }

    /** 没找着:报当前维度没有,别处标过就指路。 */
    private static ApiError notFound(PlacesStore store, UUID who, String name, String hereDim) {
        List<Stored> other = store.elsewhere(who, name, hereDim);
        StringBuilder message = new StringBuilder("you have no place called \"").append(name)
                .append("\" in ").append(hereDim);
        if (!other.isEmpty()) {
            message.append("; you marked it in ");
            for (int i = 0; i < other.size(); i++) {
                if (i > 0) {
                    message.append(", ");
                }
                message.append(other.get(i).dim());
            }
            message.append(" — go there first; spots do not cross dimensions");
        }
        return new ApiError(ErrorKind.NOT_FOUND, message.toString(), "numen.place.list() to see what you remember");
    }

    private static ApiError refused(String message, String hint) {
        return new ApiError(ErrorKind.FAILED, message, hint);
    }

    /** 参数不对:名字、坐标、维度这类她传进来的东西没通过验,一律 bad_argument,把该给的样子说出来。 */
    private static ApiError badArgument(String message) {
        return new ApiError(ErrorKind.BAD_ARGUMENT, message,
                "numen.place.list() to see what you remember, or the mark form in numen.api.help(\"numen.place.mark\")");
    }
}
