package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.gear.GearSwapService;
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
 * Client-to-server payload: the gear panel asks to move one piece between two of the four places the
 * panel shows — the body's worn slot, her backpack, and the owner's backpack.
 *
 * <p><b>协议 2：M3 起 payload 带方向(取回/交给/交换,dir 2–4),两端必须同版。</b>M2 的两个方向
 * (dir 0 穿 / 1 脱)与 M3 的三个转移方向共用本载荷;通道两侧用 {@code PROTOCOL_VERSION} 严格比对,
 * 版本不同连不上——旧客户端连新服务端能连上但状态静默错乱,比连不上更难查,所以 payload 一改就升版。
 * <b>协议 3:M4 起 payload 能指进物品自带容器,两端必须同版。</b>末尾追加了 {@code fromRef}/{@code toRef}
 * (SlotRef 字符串)与 {@code expectBox}(容器锚点指纹)三个字段,新增 dir 5/6(见下)。
 *
 * <h2>M2:两个穿戴方向(dir 0/1)</h2>{@code dir} says which way (0 = wear, 1 = take off); the move is
 * between the named worn slot ({@code slot}) and her backpack. It is an <b>optimistic</b> request: the
 * client sends what it last saw on the two ends of the move ({@code expectWorn} on the slot,
 * {@code expectOwner} in her backpack) and a {@code txId} that names this one transaction.
 *
 * <h2>M3:三个转移方向(dir 2/3/4,docs/hud-replace-draft.md 12.3)</h2>全部走两边的背包格,寻址照
 * 该文档的 {@code inv:<0-35>}(0–8 快捷栏、9–35 主背包;两边的物品栏同构,同一套编号):
 * <ul>
 *   <li>dir 2 取回:她的 {@code inv:<n>} → 我的 {@code inv:<m>};fromSlot=n,toSlot=m。</li>
 *   <li>dir 3 交给:我的 {@code inv:<m>} → 她的 {@code inv:<n>};fromSlot=m,toSlot=n。</li>
 *   <li>dir 4 交换:两侧都是 {@code inv:*},格对格互换;fromSlot=她那格 n,toSlot=我这格 m。</li>
 * </ul>
 * 指纹扩成「期望源格内容 + 期望目的格内容」:{@code expectFrom} 钉住 fromSlot,{@code expectTo} 钉住
 * toSlot(空格就给空堆)——任一与现状合不上,服务端什么也不动,如实回一句。dir 2/3/4 时 M2 的
 * {@code slot}/{@code expectWorn}/{@code expectOwner} 留空,dir 0/1 时 M3 的四个字段留空(-1 / 空堆)。
 *
 * <h2>M4:两个容器方向(dir 5/6,协议 3)</h2>一端是物品自带容器({@code box:} 地址,SlotRefs 的文法),
 * 另一端是带侧别的背包格({@code my:inv:<n>} / {@code her:inv:<n>};她的背包格也收——容器操作本就经
 * 主人发起,互斥与守恒都罩得住):
 * <ul>
 *   <li>dir 5 取出(BOX_TAKE):fromRef = box: 地址,toRef = 背包格;{@code expectFrom} = 盒内那格现状,
 *       {@code expectTo} = 背包那格现状。</li>
 *   <li>dir 6 放入(BOX_PUT):fromRef = 背包格,toRef = box: 地址;{@code expectFrom} = 背包那格现状,
 *       {@code expectTo} = 盒内那格现状。</li>
 * </ul>
 * {@code expectBox} 钉住容器锚点上的<b>整堆</b>(含内容 NBT——潜影盒的内容就编在它自己身上);
 * {@code expectFrom}/{@code expectTo} 按 from→to 各钉一格,判据与 dir 2/3/4 同一条。dir 5/6 时 M2 的
 * {@code slot}/{@code expectWorn}/{@code expectOwner} 与 M3 的 int 字段留空(-1 / 空堆);
 * dir 0–4 时 M4 的三个新字段留空("" / 空堆)。盒内格 ⇄ 盒内格不做(不构成移动),两端类型检查在
 * 服务端解析段就拒。写走 GearSwapService 同一条调用栈的双快照路径(与 M3 转移一致,不经任务槽);
 * 读是另一对独立载荷(BoxReadRequest/BoxReadResult)。
 *
 * <p>服务端把整件事在 {@link GearSwapService} 的主线程上跑完,答一张 {@link GearSwapResult} 带同一个
 * {@code txId};重放的 txId 原样回上一次的结论,不再动手。
 *
 * <p>The snapshots ride as whole item stacks, so their NBT can be arbitrarily large — a written book,
 * a stuffed shulker box — sizes this payload does not control. It implements {@link Wire.Oversized}:
 * when the whole thing does not fit one uplink packet, the snapshots shrink to bare-item stand-ins,
 * which the server's state checks then honestly refuse ("not what you saw"). They never shrink to a
 * lie that passes.
 *
 * <p>Trust model is {@link StopCurrentProgramPayload}'s: the companion must be the sender's own.
 * No distance check — the panel manages the roster across dimensions.
 */
public record GearSwapRequest(long txId, UUID companion, String slot,
                              ItemStack expectWorn, ItemStack expectOwner,
                              byte dir, int fromSlot, int toSlot,
                              ItemStack expectFrom, ItemStack expectTo,
                              String fromRef, String toRef,
                              ItemStack expectBox)
        implements NumenPayload, Wire.Oversized<GearSwapRequest> {

    /** Wear: the piece goes from her backpack onto the named slot. */
    public static final byte WEAR = 0;
    /** Take off: the piece goes from the named slot back into her backpack. */
    public static final byte REMOVE = 1;
    /** Take back (M3): her inv slot {@code fromSlot} moves into my inv slot {@code toSlot}. */
    public static final byte TAKE_BACK = 2;
    /** Give (M3): my inv slot {@code fromSlot} moves into her inv slot {@code toSlot}. */
    public static final byte GIVE = 3;
    /** Swap (M3): her inv slot {@code fromSlot} and my inv slot {@code toSlot} trade places verbatim. */
    public static final byte SWAP = 4;
    /** Take out of a container (M4, protocol 3): fromRef = {@code box:} address, toRef = backpack slot. */
    public static final byte BOX_TAKE = 5;
    /** Put into a container (M4, protocol 3): fromRef = backpack slot, toRef = {@code box:} address. */
    public static final byte BOX_PUT = 6;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "gear_swap_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    /** M2 面板的旧形状(dir 0/1,M3 起等价于转移字段留空的一笔)。 */
    public GearSwapRequest(long txId, UUID companion, String slot,
                           ItemStack expectWorn, ItemStack expectOwner, byte dir) {
        this(txId, companion, slot, expectWorn, expectOwner, dir, -1, -1, ItemStack.EMPTY, ItemStack.EMPTY);
    }

    /** M3 面板的转移形状(dir 2/3/4;协议 3 起等价于容器字段留空的一笔)。 */
    public GearSwapRequest(long txId, UUID companion, String slot,
                           ItemStack expectWorn, ItemStack expectOwner, byte dir,
                           int fromSlot, int toSlot, ItemStack expectFrom, ItemStack expectTo) {
        this(txId, companion, slot, expectWorn, expectOwner, dir, fromSlot, toSlot,
                expectFrom, expectTo, "", "", ItemStack.EMPTY);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(txId);
        buf.writeUUID(companion);
        buf.writeUtf(slot);
        buf.writeItem(expectWorn);
        buf.writeItem(expectOwner);
        buf.writeByte(dir);
        buf.writeVarInt(fromSlot);
        buf.writeVarInt(toSlot);
        buf.writeItem(expectFrom);
        buf.writeItem(expectTo);
        buf.writeUtf(fromRef);
        buf.writeUtf(toRef);
        buf.writeItem(expectBox);
    }

    public static GearSwapRequest read(FriendlyByteBuf buf) {
        return new GearSwapRequest(buf.readLong(), buf.readUUID(), buf.readUtf(),
                buf.readItem(), buf.readItem(), buf.readByte(),
                buf.readVarInt(), buf.readVarInt(), buf.readItem(), buf.readItem(),
                buf.readUtf(), buf.readUtf(), buf.readItem());
    }

    @Override
    public GearSwapRequest shrunk(Predicate<GearSwapRequest> fits, int bytes, int budget) {
        GearSwapRequest slim = new GearSwapRequest(txId, companion, slot,
                bounded(expectWorn), bounded(expectOwner), dir, fromSlot, toSlot,
                bounded(expectFrom), bounded(expectTo), fromRef, toRef, bounded(expectBox));
        if (fits.test(slim)) {
            return slim;
        }
        return new GearSwapRequest(txId, companion, slot, ItemStack.EMPTY, ItemStack.EMPTY, dir,
                fromSlot, toSlot, ItemStack.EMPTY, ItemStack.EMPTY, fromRef, toRef, ItemStack.EMPTY);
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
