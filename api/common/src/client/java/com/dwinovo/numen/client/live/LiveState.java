package com.dwinovo.numen.client.live;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.mcp.server.McpMode;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * /live 只读小门的采样器:客户端主线程每 5 tick(4Hz)把一份<b>不可变快照</b>采成 JSON 字符串,
 * {@code volatile} 发布;HTTP 线程只读那个 String——跨线程能碰到的只有一段定型的文本,
 * 摸不到任何 Minecraft 对象。这是整个 /live 的线程安全模型,别在这里加"顺手多读一个字段"。
 *
 * <p>字段口径照 {@code docs/live-endpoint-8766.md} §三 v1 + §10.2 的补充:
 * 所有字段"拿不到就整个省略",绝不填 0 或猜——
 * <ul>
 *   <li>她是谁/死没死: {@link NumenRoster}(服务端推的名册镜像,死了也一直在册);</li>
 *   <li>她在哪/体征: 只在她被客户端追踪到({@link ClientNumenLookup} 解析得到实体)时才填——
 *       传送到 200 格外,位置与血量整段消失,不出现 0/null 混填;</li>
 *   <li>背包聚合: {@link ClientNumenState} 的 {@code BodySnapshot}(服务端变化即推的缓存,
 *       {@code loaded} 才算数);</li>
 *   <li>在做什么/目标: {@link AgentLoopRegistry} 的 {@code status().activity()} 与 {@code goal()};</li>
 *   <li>程序在不在跑: {@code status().bodyTaskRunning()}(服务端推来的任务镜像);
 *       {@code NumenActuator} 的外脑程序表是私有的,不开新口子;</li>
 *   <li>租约: {@link McpMode} 的公开只读值,模式关着就整段省略。</li>
 * </ul>
 *
 * <p>串的生成只发生在这里(主线程);Gson 的 {@link JsonObject} 不跨线程——转成 String 之后再发布。
 * 判定 API 与公式全部照本树已有的用法写:biome/air/水火 {@code StatusApi.self},
 * 实体与物品 id {@code BodyDelta}/{@code NumenPlugins},日与钟点 {@code NumenEvents#clockOf},
 * 名册择人 {@code YuukaStatusHud#pick} 的兜底(准星不参与——/live 没有准星,取名册第一只)。
 */
public final class LiveState {

    /** 采样周期:5 个 client tick 一次 = 4Hz,同 {@code YuukaStatusHud} 的节奏。 */
    private static final int SAMPLE_EVERY_TICKS = 5;
    /** 敌对实体扫她周围多大半径(格)。设计稿 §六:16 格。 */
    private static final double FOE_RADIUS = 16.0;
    /** 载荷目标 < 1.5KB:敌对实体至多列这么多只,多了是聚合数据的事,不是 /live 的事。 */
    private static final int MAX_FOES = 8;
    /** 目标正文进快照的截断长度。 */
    private static final int MAX_GOAL_CHARS = 120;

    /**
     * 已发布的快照(定型的 JSON 文本),null = 采样器不在世界里(标题画面/刚断线)。
     * 唯一的跨线程通道:主线程写,HTTP 线程读。
     */
    private static volatile String published;

    private static int tickCounter;

    /** 与 {@code McpServer} 同款:不 pretty-print,快照走线越短越好。只在主线程用。 */
    private static final Gson GSON = new Gson();

    private LiveState() {}

    /** 每个客户端 tick 调一次(两个 loader 的 client tick 钩子),每 5 tick 采样一次。 */
    public static void clientTick() {
        tickCounter++;
        if (tickCounter % SAMPLE_EVERY_TICKS != 0) {
            return;
        }
        sample();
    }

    /** 最近一份快照的 JSON 文本;{@code null} = 不在世界里(HTTP 侧回 {@code self:null, reason})。 */
    public static String snapshotJson() {
        return published;
    }

    // ---- 采样(主线程,4Hz;所有 Minecraft 读取只发生在这里) ----

    private static void sample() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.level == null) {
            published = null;   // 标题画面/断线:HTTP 侧如实回 not in world
            return;
        }
        var level = mc.level;
        long now = System.currentTimeMillis();
        JsonObject root = new JsonObject();
        root.addProperty("t", now);
        long dayTime = level.getDayTime();
        root.addProperty("day", dayTime / 24000L);
        root.addProperty("time", clockOf(dayTime));

        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        NumenRoster.Entry her = entries.isEmpty() ? null : entries.get(0);
        if (her == null) {
            // 没召唤同伴:self 整段省略,主人位置照给——插件的状态行据此降级。
            root.add("owner", ownerOf(mc, null));
            published = GSON.toJson(root);
            return;
        }

        JsonObject self = new JsonObject();
        self.addProperty("name", her.name());
        self.addProperty("id", her.uuid().toString());
        if (her.dead()) {
            self.addProperty("dead", true);
            self.addProperty("respawn_in_ms", her.remainingMs());
        }
        AbstractClientPlayer body = ClientNumenLookup.resolve(her.uuid());
        if (body != null) {
            fillBody(self, body);
        }
        var snap = ClientNumenState.get(her.uuid()).orElse(null);
        if (snap != null && snap.loaded()) {
            fillBackpack(self, snap);
        }
        var loop = AgentLoopRegistry.get(her.uuid()).orElse(null);
        if (loop != null) {
            var status = loop.status();
            if (status.activity() != null && !status.activity().isBlank()) {
                self.addProperty("activity", status.activity());
            }
            var goal = loop.goal();
            if (goal != null) {
                self.addProperty("goal", clamp(goal.objective(), MAX_GOAL_CHARS));
                self.addProperty("goal_ms", goal.elapsedMs(now));
            }
            JsonObject program = new JsonObject();
            program.addProperty("running", status.bodyTaskRunning());
            root.add("program", program);
        }
        root.add("self", self);
        root.add("owner", ownerOf(mc, body));
        root.add("foes", foesOf(mc, body));
        leaseOf(root);

        published = GSON.toJson(root);
    }

    /** 她被追踪到时才有的实体读数:位置/维度/群系/体征/手。拿不到的字段整个不写。 */
    private static void fillBody(JsonObject self, AbstractClientPlayer body) {
        self.addProperty("x", body.getX());
        self.addProperty("y", body.getY());
        self.addProperty("z", body.getZ());
        self.addProperty("dim", body.level().dimension().location().toString());
        self.addProperty("biome", body.level().getBiome(body.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown"));
        self.addProperty("hp", body.getHealth());
        self.addProperty("max_hp", body.getMaxHealth());
        self.addProperty("on_ground", body.onGround());
        self.addProperty("in_water", body.isInWater());
        self.addProperty("in_lava", body.isInLava());
        self.addProperty("air", body.getAirSupply());
        ItemStack hand = body.getMainHandItem();
        if (!hand.isEmpty()) {
            self.addProperty("hand", BuiltInRegistries.ITEM.getKey(hand.getItem()).toString());
        }
    }

    /** 客户端背包缓存({@code BodySnapshot})里有就带的聚合:饥饿/饱和/骑乘/服务端拼的 bodyState。 */
    private static void fillBackpack(JsonObject self,
                                     com.dwinovo.numen.agent.request.BodySnapshot snap) {
        self.addProperty("food", snap.foodLevel());
        self.addProperty("saturation", snap.saturation());
        if (snap.bodyState() != null && !snap.bodyState().isBlank()) {
            self.addProperty("body_state", snap.bodyState());
        }
        if (snap.offhand() != null && !snap.offhand().isEmpty()) {
            self.addProperty("offhand", BuiltInRegistries.ITEM.getKey(snap.offhand().getItem()).toString());
        }
        if (snap.vehicleType() != null && !snap.vehicleType().isEmpty()) {
            self.addProperty("vehicle", snap.vehicleType());
            self.addProperty("vehicle_id", snap.vehicleId());
        }
    }

    /** 主人(=本机玩家)永远在线;她被追踪到时才补距离与可见。 */
    private static JsonObject ownerOf(Minecraft mc, AbstractClientPlayer body) {
        JsonObject owner = new JsonObject();
        owner.addProperty("online", true);
        owner.addProperty("name", mc.player.getName().getString());
        owner.addProperty("x", mc.player.getX());
        owner.addProperty("y", mc.player.getY());
        owner.addProperty("z", mc.player.getZ());
        owner.addProperty("hp", mc.player.getHealth());
        if (body != null) {
            owner.addProperty("distance", Math.round(mc.player.distanceTo(body) * 10.0) / 10.0);
            owner.addProperty("visible", true);
        } else {
            owner.addProperty("visible", false);
        }
        return owner;
    }

    /** 她周围 {@value #FOE_RADIUS} 格内的敌对实体(客户端世界,不过服务端);没有给空数组。 */
    private static JsonArray foesOf(Minecraft mc, AbstractClientPlayer body) {
        JsonArray foes = new JsonArray();
        if (body == null || mc.level == null) {
            return foes;
        }
        List<Enemy> found = mc.level.getEntitiesOfClass(Enemy.class,
                body.getBoundingBox().inflate(FOE_RADIUS), e -> true);
        for (Enemy foe : found) {
            if (foes.size() >= MAX_FOES) {
                break;
            }
            JsonObject f = new JsonObject();
            f.addProperty("id", foe.getId());
            f.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(foe.getType()).toString());
            f.addProperty("distance", Math.round(body.distanceTo(foe) * 10.0) / 10.0);
            f.addProperty("hp", foe.getHealth());
            foes.add(f);
        }
        return foes;
    }

    /** 外接大脑的租约:模式开着才带,关着整段省略。只读 McpMode 的公开方法,不续约、不触碰。 */
    private static void leaseOf(JsonObject root) {
        McpMode mcp = McpMode.instance();
        if (!mcp.enabled()) {
            return;
        }
        JsonObject lease = new JsonObject();
        String client = mcp.clientName();
        if (client != null && !client.isBlank()) {
            lease.addProperty("holder", client);
        }
        lease.addProperty("remaining", mcp.leaseRemainingSeconds());
        root.add("lease", lease);
    }

    /** 压成单行并截到 max 字,超了加省略号。 */
    private static String clamp(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * 游戏内时刻 HH:mm。原版 0 刻 = 早上 6 点——公式照抄本树 {@code NumenEvents#clockOf}
     * (那个是包私有,跨包不引,抄公式并注明出处,两处口径以它为准)。
     */
    private static String clockOf(long dayTime) {
        long inDay = Math.floorMod(dayTime, 24000L);
        long minutes = (inDay * 60L / 1000L + 6L * 60L) % (24L * 60L);
        return String.format("%02d:%02d", minutes / 60L, minutes % 60L);
    }
}
