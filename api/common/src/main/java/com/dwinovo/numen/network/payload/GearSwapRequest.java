package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.gear.GearSwapService;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.Wire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Client-to-server payload: the gear panel asks to move one piece between the body's worn slot and
 * her backpack — {@code dir} says which way (0 = wear, 1 = take off).
 *
 * <p>It is an <b>optimistic</b> request: the client sends what it last saw on the two ends of the
 * move ({@code expectWorn} on the slot, {@code expectOwner} in her backpack) and a {@code txId} that
 * names this one transaction. The server runs the whole thing through {@link GearSwapService} on the
 * main thread — seven checks in order, then the wear itself goes through the same
 * {@code numen.gear.wear} / {@code numen.gear.remove} path the model's tool call takes (task slot
 * included) — and answers with a {@link GearSwapResult} carrying the same {@code txId}. A
 * replayed txId gets the previous answer back instead of acting twice.
 *
 * <p>The two snapshots ride as whole item stacks, so their NBT can be arbitrarily large — a written
 * book, a stuffed shulker box — sizes this payload does not control. It implements
 * {@link Wire.Oversized}: when the whole thing does not fit one uplink packet, the snapshots shrink
 * to bare-item stand-ins, which the server's state checks then honestly refuse ("not what you saw").
 * They never shrink to a lie that passes.
 *
 * <p>Trust model is {@link StopCurrentProgramPayload}'s: the companion must be the sender's own.
 * No distance check — the panel manages the roster across dimensions.
 */
public record GearSwapRequest(long txId, UUID companion, String slot,
                              ItemStack expectWorn, ItemStack expectOwner,
                              byte dir) implements NumenPayload, Wire.Oversized<GearSwapRequest> {

    /** Wear: the piece goes from her backpack onto the named slot. */
    public static final byte WEAR = 0;
    /** Take off: the piece goes from the named slot back into her backpack. */
    public static final byte REMOVE = 1;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "gear_swap_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(txId);
        buf.writeUUID(companion);
        buf.writeUtf(slot);
        buf.writeItem(expectWorn);
        buf.writeItem(expectOwner);
        buf.writeByte(dir);
    }

    public static GearSwapRequest read(FriendlyByteBuf buf) {
        return new GearSwapRequest(buf.readLong(), buf.readUUID(), buf.readUtf(),
                buf.readItem(), buf.readItem(), buf.readByte());
    }

    @Override
    public GearSwapRequest shrunk(Predicate<GearSwapRequest> fits, int bytes, int budget) {
        GearSwapRequest slim = new GearSwapRequest(txId, companion, slot,
                bounded(expectWorn), bounded(expectOwner), dir);
        if (fits.test(slim)) {
            return slim;
        }
        return new GearSwapRequest(txId, companion, slot, ItemStack.EMPTY, ItemStack.EMPTY, dir);
    }

    /** 装不下时的替身:同物品、单件、不带组件——服务端的现状校验合不上时,如实回「刷新再试」。 */
    private static ItemStack bounded(ItemStack stack) {
        return stack.isEmpty() ? stack : new ItemStack(stack.getItem(), 1);
    }

    /** Handler invoked on the server main thread. */
    public static void handle(GearSwapRequest p, ServerPlayer player) {
        // Cross-dimension lookup, same as stop_current_program: the panel must reach a
        // companion that has wandered into another dimension or out of view distance.
        NumenPlayer companion = NumenPlayer.findByUuid(player.level().getServer(), p.companion());
        if (companion == null || !companion.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.debug("[numen-net] gear_swap_request for {} ignored: not the sender's companion",
                    p.companion());
            GearSwapService.rejectNotOwned(p.txId(), player);
            return;
        }
        GearSwapService.submit(player, companion, p);
    }
}
