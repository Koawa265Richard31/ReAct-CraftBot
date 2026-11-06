package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.program.ServerPrograms;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "stop whatever program this body is running right now, whatever started it".
 * The external brain's stop tool carries the owner's token and is the owner for this one action
 * ({@code HaltReason} 表里只有 OWNER_STOP 叫停身体,这一路走的就是它的语义),但它未必握着程序编号——
 * {@code /numen drive}、重启重放起的那段不经它的手。所以不像 {@link StopProgramPayload} 那样按编号匹配:
 * 一只同伴至多一段在跑({@code ServerPrograms#admit}),「当下这段」没有歧义。范围只在<b>这一具</b>身体上,
 * 不是无差别的「全服停一切」。
 *
 * <h2>与 {@link CancelTasksPayload} 的分工</h2>
 * 本包只管程序那一道;身体叫停是 {@link CancelTasksPayload} 的事。{@code stopBody} 只是让程序的回执照实说
 * 等着的那件身体活停没停({@code Program#cancel}):为真,调用方必须同一口气把身体叫停也发了——两道闸一起到,
 * 谁也不许只发一半。
 *
 * <p>Trust model is {@link CancelTasksPayload}'s: owner check only, no distance check — cancelling is the
 * one action that is always safe to allow from anywhere. 没有程序在跑就什么都不做(幂等,不是错误)。
 */
public record StopCurrentProgramPayload(UUID entityUuid, boolean stopBody) implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "stop_current_program");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeBoolean(stopBody);
    }

    public static StopCurrentProgramPayload read(FriendlyByteBuf buf) {
        return new StopCurrentProgramPayload(buf.readUUID(), buf.readBoolean());
    }

    /** Handler invoked on the server main thread. */
    public static void handle(StopCurrentProgramPayload p, ServerPlayer player) {
        // Cross-dimension lookup, same as cancel_tasks: the owner must be able to stop a
        // companion that has wandered into another dimension or out of view distance.
        NumenPlayer companion = NumenPlayer.findByUuid(player.level().getServer(), p.entityUuid());
        if (companion == null || !companion.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.debug("[numen-net] stop_current_program for {} ignored: not the sender's companion",
                    p.entityUuid());
            return;
        }
        String stopped = ServerPrograms.stopCurrent(p.entityUuid(), p.stopBody());
        Constants.LOG.info("[numen-net] ✓ stop_current_program on entity {} for {} → {}",
                p.entityUuid(), player.getName().getString(),
                stopped == null ? "nothing was running" : stopped);
    }
}
