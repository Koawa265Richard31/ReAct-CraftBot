package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.ClientPayloadSink;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Server-to-client payload: the one receipt for a {@link GearSwapRequest} — same {@code txId}
 * back, whether the move happened ({@code ok}), a plain-language {@code reason} either way, and the
 * two ends of the move <i>after</i> it ({@code wornAfter} on the named slot, {@code ownerAfter} in
 * the backpack slot the request pinned) so an optimistic panel can repaint from truth instead of
 * guessing. A transaction that ran answers with the state as it stands now — a rolled-back one:
 * the state from before it ran. The two ends are left empty only when nothing could be read at all:
 * a request refused before reaching the body (not the sender's companion, unknown direction), or a
 * receipt whose transaction was swept away without its body.
 *
 * <p>M3 起转移方向(dir 2 取回 / 3 交给 / 4 交换,协议 2)共用这张回执:{@code wornAfter} 与
 * {@code ownerAfter} 携带 (fromAfter, toAfter)——请求里源格与目的格之后的现状,与 from/to 同序。
 * M4 的容器方向(dir 5 取出 / 6 放入,协议 3)同口径:{@code wornAfter}/{@code ownerAfter} 照
 * from→to 携带盒内那格与背包那格的现状。容器其余内格不在回执里,面板随后拉一次
 * BoxReadRequest 重画整盒(读是另一对独立载荷)。
 * <b>协议 3:M4 起 payload 能指进物品自带容器,两端必须同版。</b>
 */
public record GearSwapResult(long txId, boolean ok, String reason,
                                    ItemStack wornAfter, ItemStack ownerAfter) implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "gear_swap_result");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(txId);
        buf.writeBoolean(ok);
        buf.writeUtf(reason);
        buf.writeItem(wornAfter);
        buf.writeItem(ownerAfter);
    }

    public static GearSwapResult read(FriendlyByteBuf buf) {
        return new GearSwapResult(buf.readLong(), buf.readBoolean(), buf.readUtf(),
                buf.readItem(), buf.readItem());
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(GearSwapResult p) {
        Constants.LOG.debug("[numen-net] gear_swap_result tx={} ok={} : {}", p.txId(), p.ok(), p.reason());
        ClientPayloadSink.gearSwap.accept(p);
    }
}
