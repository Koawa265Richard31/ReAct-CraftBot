package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.gear.ContainerBoxes;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.Wire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Client-to-server payload: the panel asks to open one item-borne container and read its inner slots
 * (a {@code box:} address, spec-m4-containers §4.1).
 *
 * <p><b>协议 3:M4 起 payload 能指进物品自带容器,两端必须同版。</b>通道两侧用 {@code PROTOCOL_VERSION}
 * 严格比对,不同版连不上——旧客户端连新服务端能连上但状态静默错乱,比连不上更难查。
 *
 * <p><b>无事务</b>:读不动世界,没有 tx 表、没有在飞;txId 只作回执配对,重发 = 重读,天然幂等。
 * 不查程序互斥——读是快照,她跑程序时读到的就是那一刻的真实。{@code expectBox} 是面板自己看见的那
 * 一整盒,服务端<b>不</b>据它拒读(失败清单里指纹各条不适用读路径):读永远回答此刻的真值,回执带
 * {@code boxAfter},面板自己对得出「我看见的还是不是现在这个」。收不收信任门在 {@link #handle}:
 * 发包人必须是她主人,与 {@code GearSwapRequest.handle} 同一道门。
 */
public record BoxReadRequest(long txId, UUID companion, String boxRef, ItemStack expectBox)
        implements NumenPayload, Wire.Oversized<BoxReadRequest> {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "box_read_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(txId);
        buf.writeUUID(companion);
        buf.writeUtf(boxRef);
        buf.writeItem(expectBox);
    }

    public static BoxReadRequest read(FriendlyByteBuf buf) {
        return new BoxReadRequest(buf.readLong(), buf.readUUID(), buf.readUtf(), buf.readItem());
    }

    /**
     * 上行装不下的降级:整盒(可能是一只装满的潜影盒)降为裸物品替身。替身本来就不被服务端校验
     * (读路径不看 expectBox),降级不改变读的语义;地址字符串留在——那是这次读的唯一入口。
     */
    @Override
    public BoxReadRequest shrunk(Predicate<BoxReadRequest> fits, int bytes, int budget) {
        ItemStack bare = expectBox.isEmpty() ? expectBox : new ItemStack(expectBox.getItem(), 1);
        BoxReadRequest slim = new BoxReadRequest(txId, companion, boxRef, bare);
        if (fits.test(slim)) {
            return slim;
        }
        return new BoxReadRequest(txId, companion, "", ItemStack.EMPTY);
    }

    /** Handler invoked on the server main thread; answers one {@link BoxReadResult} on the same thread. */
    public static void handle(BoxReadRequest p, ServerPlayer player) {
        // Cross-dimension lookup + trust gate, same as gear_swap_request: the panel manages the
        // roster across dimensions, and only her owner may open what she carries (spec §3.3).
        NumenPlayer companion = NumenPlayer.findByUuid(player.level().getServer(), p.companion());
        if (companion == null || !companion.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.debug("[numen-net] box_read_request for {} ignored: not the sender's companion",
                    p.companion());
            NumenNetwork.sendToPlayer(player, ContainerBoxes.refused(p.txId(), p.boxRef(),
                    "that companion is not yours"));
            return;
        }
        NumenNetwork.sendToPlayer(player, ContainerBoxes.read(player, companion, p.txId(), p.boxRef()));
    }
}
