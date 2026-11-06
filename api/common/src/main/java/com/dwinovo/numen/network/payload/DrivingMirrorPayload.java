package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.task.DrivingMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server:主人客户端把驾驶席口径的现值({@code McpMode.instance().driving()})
 * 推过来,服务端那份镜像({@link DrivingMirror})照单收下。只在进世界与翻转的边沿上发,
 * 不逐刻刷。纯状态信号,丢了漂了都无害——下一次边沿自然对齐;最坏是反射层多让位或少
 * 让位一会儿。
 *
 * <h2>驾驶席翻到外接的边沿</h2>
 * 从「没有」翻到「有」的这一刻,内脑派下去、还在服务端任务槽里的活一并叫停
 * (走主人按 Stop 的同一条 {@code cancelFor} 路):驾驶席换人的瞬间,旧命令不该再推身体。
 * 这一出只发生在边沿上——外脑接手后经工具口派的活不经过这条边沿,照常执行。
 */
public record DrivingMirrorPayload(boolean driving) implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "driving_mirror");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(driving);
    }

    public static DrivingMirrorPayload read(FriendlyByteBuf buf) {
        return new DrivingMirrorPayload(buf.readBoolean());
    }

    /** Server main thread. 只认真人玩家发来的现值(同伴身体没有客户端,发不出这个)。 */
    public static void handle(DrivingMirrorPayload p, ServerPlayer sender) {
        if (sender instanceof NumenPlayer) {
            return;   // 同伴不给自己报驾驶席
        }
        boolean flippedOn = p.driving() && !DrivingMirror.isDriving(sender.getUUID());
        DrivingMirror.set(sender.getUUID(), p.driving());
        if (!flippedOn) {
            return;
        }
        // 边沿:内脑派的活让位。提前派过的常驻跟随、挖矿在槽里的,此刻一并停。
        int cancelled = 0;
        for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer ap && ap.isOwnedByPlayer(sender.getUUID())) {
                com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(ap);
                cancelled++;
            }
        }
        Constants.LOG.debug("[numen-net] driving mirror flipped on for {}: cancelled {} companion task slot(s)",
                sender.getName().getString(), cancelled);
    }
}
