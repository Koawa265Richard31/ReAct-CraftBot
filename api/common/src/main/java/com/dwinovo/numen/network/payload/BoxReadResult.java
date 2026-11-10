package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.ClientPayloadSink;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.Wire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Server-to-client payload: the answer to one {@link BoxReadRequest} — the container's inner slots as
 * they stand now (spec-m4-containers §4.1), plus the whole box stack ({@code boxAfter}) so the panel
 * can tell whether what it saw is still what's there. A failed read (bad address, no container, no
 * permission) answers ok=false with the plain-language reason and leaves contents and boxAfter empty —
 * never a half answer.
 *
 * <p><b>协议 3:M4 起 payload 能指进物品自带容器,两端必须同版。</b>
 *
 * <h2>体积与诚实截断</h2>整盒写满的书能把一个包顶大(下行上限约 1 MiB)。装不下时的降级是
 * <b>截断内容并置 {@code truncated=true}</b>,reason 里如实说只有前 K 格送到了——面板角上画一个
 * 「…」,绝不把没送到的格画成空格。哲学与 M2 的收缩一致:they never shrink to a lie that passes。
 * 连截断都装不下的极端(单独一件内容就顶满整包),整包降为「什么都没画出来」的失败——比画半截诚实。
 */
public record BoxReadResult(long txId, boolean ok, String reason, String boxRef,
                            ItemStack boxAfter, List<ItemStack> contents, boolean truncated)
        implements NumenPayload, Wire.Oversized<BoxReadResult> {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "box_read_result");

    /** 内容清单的护栏:与读路径的 {@code ContainerBoxes.MAX_INNER_SLOTS} 同量级,防恶意长度。 */
    private static final int MAX_CONTENTS = 128;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(txId);
        buf.writeBoolean(ok);
        buf.writeUtf(reason);
        buf.writeUtf(boxRef);
        buf.writeItem(boxAfter);
        int n = Math.min(contents.size(), MAX_CONTENTS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeItem(contents.get(i));
        }
        buf.writeBoolean(truncated);
    }

    public static BoxReadResult read(FriendlyByteBuf buf) {
        long txId = buf.readLong();
        boolean ok = buf.readBoolean();
        String reason = buf.readUtf();
        String boxRef = buf.readUtf();
        ItemStack boxAfter = buf.readItem();
        int n = Math.min(buf.readVarInt(), MAX_CONTENTS);
        List<ItemStack> contents = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            contents.add(buf.readItem());
        }
        boolean truncated = buf.readBoolean();
        return new BoxReadResult(txId, ok, reason, boxRef, boxAfter, contents, truncated);
    }

    /**
     * 下行装不下的降级阶梯:先整包试一次;然后从整盒开始逐格截,每一级都置 {@code truncated=true} 并
     * 把「只有前 K 格送到了」写进 reason;全空还装不下,整包降为 ok=false 的「什么都没画出来」。
     */
    @Override
    public BoxReadResult shrunk(Predicate<BoxReadResult> fits, int bytes, int budget) {
        if (fits.test(this)) {
            return this;
        }
        for (int k = contents.size(); k >= 0; k--) {
            BoxReadResult cut = new BoxReadResult(txId, ok, truncatedReason(k), boxRef, boxAfter,
                    contents.subList(0, k), true);
            if (fits.test(cut)) {
                return cut;
            }
        }
        return new BoxReadResult(txId, false, Wire.TO_CLIENT.tooBig("this box's contents", bytes)
                + " — nothing was drawn; the box itself is untouched", boxRef, ItemStack.EMPTY,
                List.of(), true);
    }

    /** 截到 K 格时的那句:没送到的格必须被画成「不知道」,不是空格。 */
    private String truncatedReason(int k) {
        return "the box has " + contents.size() + " inner slots — only the first " + k
                + " arrived (one packet tops out); draw the rest as unknown, not empty";
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(BoxReadResult p) {
        Constants.LOG.debug("[numen-net] box_read_result tx={} ok={} : {}", p.txId(), p.ok(), p.reason());
        ClientPayloadSink.boxRead.accept(p);
    }
}
