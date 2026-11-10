package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.sdk.EntityRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 求值那一刻她的状态,全是一批<b>纯数据</b>:引擎每轮求值先从活身体上量出这一份,原语的求值函数只读它,
 * 不再碰 Minecraft —— 原语层的单测因此不需要世界。字段按订阅表里用到的原语备料:没人订阅的料不量
 * (附近敌对生物要一次实体查询、物品数要扫一遍背包、格子要读世界),一轮求值的开销是订阅数的常数级。
 *
 * <p>Map 的键:实体按 {@link EntityRef#toString()}(运行期编号或 UUID 的文本),格子按
 * {@code BlockPos.asLong()} 的十进制文本 —— 键的形状只有 {@link #key} 两处说了算,原语侧用同一个
 * helper 生成,不各自拼。坐标一律 {@link BlockPos}(Lua 的类型名是全局的,自造 {@code record Pos}
 * 这类名字会撞上 LuaCodecs 的共用类),读格时按整格算,与 numen.shape 的 Pos 同一规矩。
 */
public record WatchSnapshot(
        float health,
        int food,
        boolean inventoryFull,
        boolean onFire,
        boolean inWater,
        boolean inLava,
        float fallDistance,
        int airPercent,
        long dayTimeOfDay,
        int hurtCount,
        long nowTick,
        Map<String, Double> entityDistance,
        double nearestHostile,
        Map<String, Double> posDistance,
        Map<String, String> blockAt,
        Map<String, Integer> itemCounts) {

    /** 实体键:EntityRef 的文本形状(编号或 UUID)。 */
    static String key(EntityRef ref) {
        return ref.toString();
    }

    /** 格子键:BlockPos.asLong 的十进制文本。 */
    static String key(BlockPos pos) {
        return Long.toString(pos.asLong());
    }

    /**
     * 从活身体上量一份快照,按订阅备料。服务端主线程上调用(求值只在主线程);她所在的那层世界就是
     * 判定的世界 —— 换了维度,实体与方块各从当前这层读,订阅本身不清。备料按<b>操作数</b>走:
     * 一条 and 订阅有两个操作数,两个的料都量。
     */
    public static WatchSnapshot of(com.dwinovo.numen.entity.NumenPlayer her, Collection<WatchSub> subs) {
        Set<EntityRef> refs = new HashSet<>();
        Set<BlockPos> anchors = new HashSet<>();
        Set<String> items = new HashSet<>();
        double hostileRange = 0;
        for (WatchSub sub : subs) {
            for (WatchSub.Operand op : sub.operands()) {
                switch (op.primitive) {
                    case TARGET_GONE, ENTITY_NEAR, ENTITY_FAR -> refs.add(op.entity);
                    case BLOCK_CHANGED, OUT_OF_BOUNDS -> anchors.add(op.pos);
                    case ITEM_AT_LEAST, ITEM_AT_MOST -> items.add(op.item);
                    case HOSTILE_NEAR -> hostileRange = Math.max(hostileRange, op.range);
                    default -> { }
                }
            }
        }

        Map<String, Double> entityDistance = new HashMap<>();
        for (EntityRef ref : refs) {
            Entity target = ref.in(her.serverLevel());
            if (target != null && target != her) {
                entityDistance.put(key(ref), distance(her, target.position()));
            }
        }

        double nearestHostile = Double.MAX_VALUE;
        if (hostileRange > 0) {
            AABB box = her.getBoundingBox().inflate(hostileRange);
            // 查的就是 Monster,她(玩家)本来就不在这类里;isAlive 滤掉尸体。
            for (Monster mob : her.serverLevel().getEntitiesOfClass(Monster.class, box, Monster::isAlive)) {
                nearestHostile = Math.min(nearestHostile, distance(her, mob.position()));
            }
        }

        Map<String, Double> posDistance = new HashMap<>();
        Map<String, String> blockAt = new HashMap<>();
        for (BlockPos pos : anchors) {
            posDistance.put(key(pos), distance(her, Vec3.atCenterOf(pos)));
            // 只读已加载区块里的方块:够不着的格子当"读不到"处理,不为它同步拉区块。
            if (her.serverLevel().getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                blockAt.put(key(pos),
                        BuiltInRegistries.BLOCK.getKey(her.serverLevel().getBlockState(pos).getBlock()).toString());
            }
        }

        Map<String, Integer> itemCounts = new HashMap<>();
        for (String id : items) {
            ResourceLocation ided = unchecked(id);
            if (ided == null) {
                continue;
            }
            BuiltInRegistries.ITEM.getOptional(ided).ifPresent(item -> {
                int n = 0;
                for (int i = 0; i < her.getInventory().getContainerSize(); i++) {
                    ItemStack stack = her.getInventory().getItem(i);
                    if (!stack.isEmpty() && stack.getItem() == item) {
                        n += stack.getCount();
                    }
                }
                itemCounts.put(id, n);
            });
        }

        int air = her.getMaxAirSupply() <= 0 ? 100
                : (int) Math.round(her.getAirSupply() * 100.0 / her.getMaxAirSupply());
        boolean invFull = her.getInventory().getFreeSlot() == -1;
        long dayTimeOfDay = her.serverLevel().getDayTime() % 24000L;
        long nowTick = her.getServer() != null ? her.getServer().getTickCount() : 0;
        return new WatchSnapshot(her.getHealth(), her.getFoodData().getFoodLevel(), invFull,
                her.isOnFire(), her.isInWater(), her.isInLava(), her.fallDistance, air,
                dayTimeOfDay, WatchEngine.hurtCount(her.getUUID()), nowTick,
                entityDistance, nearestHostile, posDistance, blockAt, itemCounts);
    }

    private static double distance(com.dwinovo.numen.entity.NumenPlayer her, Vec3 there) {
        return Math.sqrt(her.position().distanceToSqr(there));
    }

    /** 登记时验过形状;量快照再抛就把它当成读不到(物品计 0),不当成失败。 */
    private static ResourceLocation unchecked(String id) {
        try {
            return new ResourceLocation(id);
        } catch (RuntimeException bad) {
            return null;
        }
    }
}
