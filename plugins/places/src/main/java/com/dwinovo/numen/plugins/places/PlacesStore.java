package com.dwinovo.numen.plugins.places;

import com.dwinovo.numen.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 她的命名地点,按<b>存档</b>存:挂着 overworld 的 {@link SavedData},跟同伴名册
 * {@code CompanionRegistry}("numen_companions")同一个思路、同一套经典
 * {@code computeIfAbsent(loadFn, factory, name)} 家法 —— 玩家 .dat、配置目录都不放,换存档各是各的。
 *
 * <p>数据分四层认领:<b>她</b>(同伴 UUID)→ <b>维度</b>(如 {@code minecraft:overworld})→
 * <b>名字</b> → 一个格点。同名不同维度是两个点;同一维度同名是覆盖。索引就是这三层嵌套的 Map,
 * 不做任何字符串拼接键 —— 维度与名字是 Lua 自由传入的,拼接键会在分隔符撞车时让"同名不同维度"
 * 互相覆盖。注意"她的"以身体 UUID 计:换了一只同伴,点不共享 —— 这些点是她自己的,不是那只身体的。
 *
 * <p>调用都发生在服务端主线程(服务端只在每刻开头执行车道里排着的调用),每个公开方法标
 * synchronized 只是给未来可能的旁路读兜底,不为常态。
 */
public final class PlacesStore extends SavedData {

    /** 存档里的键,与同伴名册的 "numen_companions" 同风格。 */
    private static final String KEY = "numen_places";
    private static final String ROWS = "places";

    private static final Codec<Stored> ROW_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("who").forGetter(Stored::who),
            Codec.STRING.fieldOf("dim").forGetter(Stored::dim),
            Codec.STRING.fieldOf("name").forGetter(Stored::name),
            Codec.LONG.fieldOf("pos").forGetter(Stored::pos),
            Codec.LONG.fieldOf("lastUsed").forGetter(Stored::lastUsed)
    ).apply(instance, Stored::new));
    private static final Codec<List<Stored>> CODEC = ROW_CODEC.listOf();

    /** 一行:谁、哪个维度、叫什么、哪一格(BlockPos.asLong)、上次用到的墙钟毫秒(0 = 标完还没用过)。 */
    public record Stored(String who, String dim, String name, long pos, long lastUsed) {

        public UUID owner() {
            return UUID.fromString(who);
        }

        public BlockPos blockPos() {
            return BlockPos.of(pos);
        }

        public Stored usedAt(long when) {
            return new Stored(who, dim, name, pos, when);
        }
    }

    /** 她 → 维度 → 名字 → 行;三层嵌套就是索引,没有拼接键。 */
    private final Map<UUID, Map<String, Map<String, Stored>>> rows = new HashMap<>();

    /** 存取入口,照 CompanionRegistry.get(MinecraftServer):overworld 的数据仓,读不进就给空的。 */
    public static PlacesStore of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(PlacesStore::load, PlacesStore::new, KEY);
    }

    /** 读不回来时整批放弃,只报一句日志,绝不把半截数据当真的(照同伴名册与建房子记录的 load)。 */
    public static PlacesStore load(CompoundTag tag) {
        PlacesStore store = new PlacesStore();
        if (!tag.contains(ROWS)) {
            return store;
        }
        CODEC.parse(NbtOps.INSTANCE, tag.get(ROWS)).resultOrPartial(error ->
                        Constants.LOG.error("[numen-places] 她记下的地点读不回来: {}", error))
                .ifPresent(stored -> {
                    for (Stored row : stored) {
                        store.byDim(row.owner(), row.dim()).put(row.name(), row);
                    }
                });
        return store;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        List<Stored> all = new ArrayList<>();
        for (Map<String, Map<String, Stored>> byDim : rows.values()) {
            for (Map<String, Stored> byName : byDim.values()) {
                all.addAll(byName.values());
            }
        }
        CODEC.encodeStart(NbtOps.INSTANCE, all).resultOrPartial(error ->
                        Constants.LOG.error("[numen-places] 她的地点写不进存档: {}", error))
                .ifPresent(encoded -> tag.put(ROWS, encoded));
        return tag;
    }

    /** 记(或覆盖)一个点;返回是否覆盖了同名同维度的旧点。 */
    public synchronized boolean put(UUID who, String dim, String name, BlockPos at, long wallClockMs) {
        boolean replaced = byDim(who, dim).containsKey(name);
        byDim(who, dim).put(name, new Stored(who.toString(), dim, name, at.asLong(), wallClockMs));
        setDirty();
        return replaced;
    }

    /** 只读地找某维度下的一个点。 */
    public synchronized Optional<Stored> find(UUID who, String dim, String name) {
        return Optional.ofNullable(at(who, dim).get(name));
    }

    /** "用到"一笔:把她这次按名字取点记成最近一次使用。读到即用到 —— 她要的就是以后走回来。 */
    public synchronized void touch(UUID who, String dim, String name, long wallClockMs) {
        Stored row = at(who, dim).get(name);
        if (row == null || row.lastUsed() == wallClockMs) {
            return;
        }
        byDim(who, dim).put(name, row.usedAt(wallClockMs));
        setDirty();
    }

    /** 同一个名字在<b>别的</b>维度里的行(取/删没找着时告诉她去哪找)。 */
    public synchronized List<Stored> elsewhere(UUID who, String name, String notThisDim) {
        List<Stored> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Map<String, Stored>>> e : rows.entrySet()) {
            if (!e.getKey().equals(who)) {
                continue;
            }
            for (Map.Entry<String, Map<String, Stored>> d : e.getValue().entrySet()) {
                if (!d.getKey().equals(notThisDim)) {
                    out.addAll(d.getValue().values());
                }
            }
        }
        return out;
    }

    /** 她在某个维度下的全部点。 */
    public synchronized List<Stored> inDim(UUID who, String dim) {
        return new ArrayList<>(at(who, dim).values());
    }

    /** 她的全部点(所有维度),名单用。 */
    public synchronized List<Stored> ownedBy(UUID who) {
        List<Stored> out = new ArrayList<>();
        for (Map<String, Stored> byName : rows.getOrDefault(who, Map.of()).values()) {
            out.addAll(byName.values());
        }
        return out;
    }

    /** 删掉;返回删没删着。读路径不建空壳,只有写路径走 byDim。 */
    public synchronized boolean remove(UUID who, String dim, String name) {
        boolean gone = at(who, dim).remove(name) != null;
        if (gone) {
            setDirty();
        }
        return gone;
    }

    /** (她, 维度)下的名字层:名字 → 行,只读视图;没有就是空图,不往索引里塞空壳。 */
    private Map<String, Stored> at(UUID who, String dim) {
        return rows.getOrDefault(who, Map.of()).getOrDefault(dim, Map.of());
    }

    /** (她, 维度)下的名字层,写路径用,按需建。 */
    private Map<String, Stored> byDim(UUID who, String dim) {
        return rows.computeIfAbsent(who, k -> new HashMap<>()).computeIfAbsent(dim, k -> new HashMap<>());
    }
}
