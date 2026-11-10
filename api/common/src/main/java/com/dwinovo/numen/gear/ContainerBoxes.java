package com.dwinovo.numen.gear;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.container.ContainerBox;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.BoxReadResult;
import com.dwinovo.numen.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 容器的求值路径:深度上限、锚点解析、三方守恒账、按需读出。三条硬约束在这里落地
 * (spec-m4-containers §三),写事务的时序外壳在 {@link GearSwapService#boxTransfer}——它照 M3 转移
 * 的同一副骨架(双快照夹住、断言不等当场回滚),本类给它提供「格」这一层的全部判据与语句。
 *
 * <h2>硬约束一:嵌套深度上限(§3.1)</h2>{@link #MAX_BOX_DEPTH} 是唯一的一处常量。强制执行在两处:
 * 地址层在 {@link SlotRefs#parse}(锚点家族只收 {@code inv:}/{@code gear:},{@code box:} 锚点在文法里
 * 写不出来,第 2 层没有地址);求值层在本类的 {@link #refusePayload}(写进容器的载荷堆凡是自己也是
 * 容器就拒,<b>空容器也拒</b>——规则不看空满,规则就是层数)。两处由本类的静态检查钉在一起:改常量
 * 而不扩文法,类加载当场炸,不会静默放开。原版本来就禁「潜影盒进潜影盒」,mod 背包不一定禁——
 * 我们的闸是统一的那道,不依赖各 mod 自觉。
 *
 * <h2>硬约束二:守恒断言(§3.2)</h2>{@link #boxAccount}:她的 36 格 + 主人的 36 格 + 该容器的内格,
 * 固定三方,无论一次操作实际碰哪几方口径不变。键沿用 {@code GearSwapService.merge} 的「物品 id + 组件」,
 * <b>唯独锚点堆那一项换用掩蔽键</b> {@link ContainerBox#conservationToken()}——潜影盒的内容编在它自己的
 * NBT 里,按原样计的话内格任何搬动都会改它的键、三方合计永远对不上、断言永假警报。gear 锚点的堆不在
 * 任何网格里,由 {@link #boxAccount} 经 {@link GearSlot#worn()} 现读为独立一项、同样掩蔽——不把它入账,
 * 写回丢了东西就没有任何一方能发现。
 *
 * <h2>硬约束三:「容器在别人手里」的读权限(§3.3)</h2>唯一一条规则:只有她的和主人的物品可以展开,
 * 其他人的连格数都不给。落点三个,全不在本文件之外:文法里 holder 只有 {@code my|her}
 * ({@link SlotRefs});服务端 {@link #resolve} 的锚点堆只从两方的 {@code getInventory()} 或
 * {@code NumenPlugins.gearSlots} 现取,任何别的来源在这里落空;发包人是她主人的信任门在载荷
 * {@code handle} 里原样前置。她把盒放进村民/第三方背包后,面板上没有这个格——不可寻址,不是灰格。
 *
 * <h2>读路径(§4.1)</h2>{@link #read} 无事务:解析 → resolve → 主线程快照内格,回一张
 * {@link BoxReadResult};txId 只作回执配对,重发 = 重读,天然幂等;不查程序互斥——读是快照,
 * 她跑程序时读到的就是那一刻的真实,与面板常推状态同一性质。
 *
 * <p>只在服务端主线程调用(能力探测、穿戴位现读都是主线程的事)。
 */
public final class ContainerBoxes {

    private ContainerBoxes() {}

    /**
     * 三条硬约束之一:嵌套深度上限。锚点(她/我的背包格、穿戴格)是第 0 层,锚点上的容器内格是
     * 第 1 层,到此为止——容器里再放容器(第 2 层)一律拒,空容器也拒。改这个值必须同步改
     * {@link SlotRefs#parse} 的锚点家族表(见下面的静态检查)。
     */
    public static final int MAX_BOX_DEPTH = 1;

    /**
     * 读路径一次最多快照多少内格:潜影盒 27 格绰绰有余;能力背包的格数由物品自己声明,这里是
     * 防滥用上界(对付恶意声明的格数),不是功能上限——超出的格如实置 truncated,不装看不见。
     */
    public static final int MAX_INNER_SLOTS = 128;

    /** 文法与常量钉在一起:MAX_BOX_DEPTH 只能是 1,因为 SlotRefs.parse 的锚点家族不收 box:。 */
    static {
        if (MAX_BOX_DEPTH != 1) {
            throw new IllegalStateException("ContainerBoxes.MAX_BOX_DEPTH changed to " + MAX_BOX_DEPTH
                    + " but SlotRefs.parse still accepts only inv:/gear: anchors — widen the address grammar"
                    + " (and the runtime checks) or revert the constant; a silent widening would let items nest");
        }
    }

    /** isContainer 的探测不落盘:只问「开不开得开」,不动内容。 */
    private static final Runnable NO_TOUCH = () -> {};

    // ---------------------------------------------------------------------
    // 锚点与解析
    // ---------------------------------------------------------------------

    /**
     * 容器的锚点:挂在谁的哪一格上(inv 锚点,带侧别与下标)或她身上的哪个穿戴位(gear 锚点)。
     * 二者取一:invIndex >= 0 即 inv 锚点,gear != null 即 gear 锚点;resolve() 验过锚点后才产出。
     */
    public record BoxAnchor(boolean hers, int invIndex, GearSlot gear) {}

    /** resolve 的结果:error 非 null 即拒(error 是 §五清单里的原句);否则三样俱全。 */
    public record Resolve(BoxAnchor anchor, ContainerBox box, ItemStack stack, String error) {}

    /** §五第 3 条:锚点格是空的(读,或 gear 锚点上没戴东西)。 */
    public static final String EMPTY_ANCHOR =
            "nothing in that slot — there is no container to open there";

    /** §五第 4 条:没能力——既不是潜影盒也没有物品级物品栏能力。 */
    public static final String NO_CONTAINER =
            "that item has no openable container — it's not a shulker box and exposes no inventory";

    /** §五第 5 条:深度超限(第 2 层,空容器也算)。 */
    public static final String PAYLOAD_DEPTH =
            "that item is itself a container — a box inside a box is not allowed";

    /** §五第 6 条:容器整堆指纹不符(锚点被挪动/动过)。 */
    public static final String STALE_BOX =
            "the container in that slot is not what you saw — something changed; refresh and try again";

    /** §五第 2 条:程序互斥(任一端在她那边且她在跑程序);判序在 parse 之后、resolve 之前。 */
    public static final String PROGRAM_BUSY =
            "she is running a program on the server — let it finish (or stop it) before moving items";

    /** 这件物品是不是一个可展开容器(§2.1 的两步判据:潜影盒,或物品级物品栏能力)。 */
    public static boolean isContainer(ItemStack stack) {
        return !stack.isEmpty() && Services.CONTAINERS != null
                && Services.CONTAINERS.open(stack, NO_TOUCH) != null;
    }

    /** 写侧的深度检查:放进容器的载荷堆落进内格就是第 MAX_BOX_DEPTH + 1 层——凡是自己也是容器就拒。 */
    public static String refusePayload(ItemStack payload) {
        return isContainer(payload) ? PAYLOAD_DEPTH : null;
    }

    /**
     * 把一条 {@code box:} 地址解析成可读写的容器实例:SlotRefs.parse → 锚点存在性与能力 → 深度
     * (§3.1 的三件事)。锚点堆只从两方的背包或她的穿戴位现取(§1.3:两方 UUID 现取,第三方在文法里
     * 没有写法,改包伪造在这里落空);锚点是空的回 §五第 3 条,没能力的回第 4 条。touched 回调按锚点
     * 归属给:inv 锚点 = 锚点属主背包的 setChanged;gear 锚点 = 空操作(堆在穿戴位处理器里,主线程
     * 就地改 NBT,实体存档自然带上;客户端的外堆视图由既有穿戴同步与面板重读刷新)。
     */
    public static Resolve resolve(ServerPlayer owner, NumenPlayer body, String ref) {
        SlotRefs.Parsed parsed = SlotRefs.parse(ref);
        if (parsed.error() != null) {
            return new Resolve(null, null, ItemStack.EMPTY, parsed.error());
        }
        if (parsed.boxInner() < 0) {
            return new Resolve(null, null, ItemStack.EMPTY, SlotRefs.PARSE_REFUSAL);   // 不是 box 地址
        }
        ItemStack anchorStack;
        BoxAnchor anchor;
        Runnable touched;
        if (parsed.gearName() != null) {
            GearSlot slot = gearSlot(body, parsed.gearName(), parsed.gearOrdinal());
            if (slot == null) {
                return new Resolve(null, null, ItemStack.EMPTY, "she has no slot named '"
                        + parsed.gearName() + "' — no gear slot there to hold a container");
            }
            anchorStack = slot.worn();
            anchor = new BoxAnchor(true, -1, slot);
            touched = () -> {
                // gear 锚点的落盘:内容就地写进了穿戴位那一件堆自身,实体存档自然带上(§2.2:永不经
                // GearSlot.swap——那是整堆替换 + onEquipFromUse 的穿戴语义,改内容不是穿戴)。
            };
        } else {
            Inventory inv = parsed.hers() ? body.getInventory() : owner.getInventory();
            if (parsed.invIndex() < 0 || parsed.invIndex() >= inv.items.size()) {
                return new Resolve(null, null, ItemStack.EMPTY, SlotRefs.PARSE_REFUSAL);   // 文法已限 0-35,兜一遍
            }
            anchorStack = inv.items.get(parsed.invIndex());
            anchor = new BoxAnchor(parsed.hers(), parsed.invIndex(), null);
            touched = inv::setChanged;
        }
        if (anchorStack.isEmpty()) {
            return new Resolve(null, null, ItemStack.EMPTY, EMPTY_ANCHOR);
        }
        if (Services.CONTAINERS == null) {
            return new Resolve(null, null, ItemStack.EMPTY, NO_CONTAINER);
        }
        ContainerBox box = Services.CONTAINERS.open(anchorStack, touched);
        if (box == null) {
            return new Resolve(null, null, ItemStack.EMPTY, NO_CONTAINER);
        }
        return new Resolve(anchor, box, anchorStack, null);
    }

    /** 同名穿戴位按 {@code NumenPlugins.gearSlots} 现序取第 n 个(§1.4 的「#n」判据)。 */
    private static GearSlot gearSlot(NumenPlayer body, String name, int ordinal) {
        int seen = 0;
        for (GearSlot slot : NumenPlugins.gearSlots(body)) {
            if (slot.name().equals(name)) {
                if (seen == ordinal) {
                    return slot;
                }
                seen++;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // 硬约束二:三方守恒账
    // ---------------------------------------------------------------------

    /**
     * 三方合计的守恒多重集(§3.2)。计法:
     * <ul>
     *   <li>两个背包照原样计(merge 键:物品 id + 组件),<b>唯独 anchor 指的那一项换
     *       {@code box.conservationToken()} 作键</b>:inv 锚点 = 两方网格里 anchor.invIndex 那一格;</li>
     *   <li>gear 锚点:不在任何网格里,把 {@code anchor.gear().worn()} 现读出来作为独立一项并入,
     *       同样用掩蔽键(现读与 GearSwapService 的 snapshot 读穿戴位同一做法:两次调用都在本条
     *       调用栈里,同一个 tick,之间只夹着这笔交易自己);</li>
     *   <li>容器内格照原样计;</li>
     *   <li>anchor.invIndex 越界或两个成分同时给/同时不给是程序错误(resolve 挡在前面),直接抛。</li>
     * </ul>
     * 前后两个多重集相等 = 谁也没丢、谁也没复制,锚点堆内容以外的组件也没变。调用方不必会算掩蔽键:
     * 本方法手里有 anchor,知道哪一项换键;键值只问 provider 的 {@code conservationToken()}。
     */
    public static Map<String, Integer> boxAccount(List<ItemStack> herGrid, List<ItemStack> myGrid,
                                                  BoxAnchor anchor, ContainerBox box) {
        boolean invAnchor = anchor.invIndex() >= 0;
        if (invAnchor == (anchor.gear() != null)) {
            throw new IllegalArgumentException("a box anchor is an inv anchor or a gear anchor, exactly one");
        }
        Map<String, Integer> out = new TreeMap<>();
        mergeGrid(out, herGrid, anchor, box, true);
        mergeGrid(out, myGrid, anchor, box, false);
        if (anchor.gear() != null) {
            mergeMasked(out, anchor.gear().worn(), box);
        }
        for (int i = 0; i < box.slots(); i++) {
            merge(out, box.slot(i));
        }
        return out;
    }

    /** 一方网格入账:锚点在谁那边就换谁那一格的键,其余照原样计。 */
    private static void mergeGrid(Map<String, Integer> out, List<ItemStack> grid, BoxAnchor anchor,
                                  ContainerBox box, boolean hers) {
        for (int i = 0; i < grid.size(); i++) {
            ItemStack stack = grid.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (anchor.gear() == null && anchor.hers() == hers && anchor.invIndex() == i) {
                mergeMasked(out, stack, box);
            } else {
                merge(out, stack);
            }
        }
    }

    private static void merge(Map<String, Integer> multiset, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        multiset.merge(stack.getItem() + "#" + (stack.getTag() == null ? "" : stack.getTag()),
                stack.getCount(), Integer::sum);
    }

    /** 掩蔽键的那一项:件数照计,键换成 provider 的 conservationToken(内格怎么重排都不变)。 */
    private static void mergeMasked(Map<String, Integer> multiset, ItemStack stack, ContainerBox box) {
        if (stack.isEmpty()) {
            return;
        }
        multiset.merge(box.conservationToken(), stack.getCount(), Integer::sum);
    }

    // ---------------------------------------------------------------------
    // 读路径:打开容器看内容(§4.1,无事务)
    // ---------------------------------------------------------------------

    /**
     * 读:解析 → resolve → 主线程快照内格,回一张 {@link BoxReadResult}。读路径只走 §五清单 #1–#5 里
     * 适用的行(地址、锚点、能力——互斥与指纹、收放各条不适用)。整盒超过单包上限时的截断在
     * {@code BoxReadResult.shrunk} 里做(那里如实置 truncated,这里不预裁)。格数超过
     * {@link #MAX_INNER_SLOTS} 的(恶意声明)只读前一段,同样如实置 truncated。
     */
    public static BoxReadResult read(ServerPlayer owner, NumenPlayer body, long txId, String ref) {
        SlotRefs.Parsed parsed = SlotRefs.parse(ref);
        if (parsed.error() != null || parsed.boxInner() < 0) {
            return refused(txId, ref, parsed.error() != null ? parsed.error() : SlotRefs.PARSE_REFUSAL);
        }
        Resolve resolved = resolve(owner, body, ref);
        if (resolved.error() != null) {
            return refused(txId, ref, resolved.error());
        }
        ContainerBox box = resolved.box();
        int total = box.slots();
        int shown = Math.min(total, MAX_INNER_SLOTS);
        List<ItemStack> contents = new ArrayList<>(shown);
        int filled = 0;
        for (int i = 0; i < shown; i++) {
            ItemStack stack = box.slot(i);
            if (!stack.isEmpty()) {
                filled++;
            }
            contents.add(stack.copy());
        }
        String reason = total > shown
                ? "the box has " + total + " inner slots — only the first " + shown + " are read at all;"
                  + " the rest are never shown"
                : "the box has " + total + " inner slots, " + filled + " of them filled";
        return new BoxReadResult(txId, true, reason, ref, resolved.stack().copy(), contents, total > shown);
    }

    /** 读失败的那一张:ok=false + §五清单里的原句;内容与整堆照实留空(不是半截内容)。 */
    public static BoxReadResult refused(long txId, String ref, String reason) {
        return new BoxReadResult(txId, false, reason, ref, ItemStack.EMPTY, List.of(), false);
    }

    // ---------------------------------------------------------------------
    // §五清单的收放语句:provider 们的公共口径,只在这里写一遍
    // ---------------------------------------------------------------------

    /** §五第 7 条:内格序号越界。 */
    public static String noInnerSlot(int index, int slots) {
        return "the box has no inner slot " + index + " — it has " + slots + " slots, numbered 0-"
                + (slots - 1);
    }

    /** §五第 8 条:内格指纹不符。 */
    public static String staleInner(int index) {
        return "inner slot " + index + " is not what you saw — something changed; refresh and try again";
    }

    /** §五第 9 条:内格为空(取)。 */
    public static String emptyInner(int index) {
        return "inner slot " + index + " is empty — nothing to take out of the box";
    }

    /** §五第 10 条:内格已有别的物。 */
    public static String holdsOther(int index, ItemStack occupant) {
        return "inner slot " + index + " holds " + idOf(occupant)
                + " — put it on an empty inner slot; boxes don't swap";
    }

    /** §五第 11 条:同物品并 stack 顶(含 maxStackSize=1 的物)。 */
    public static String topsOut(int index, ItemStack occupant, int incoming) {
        return "inner slot " + index + " holds " + occupant.getCount() + " " + idOf(occupant)
                + " and a stack tops out at " + occupant.getMaxStackSize() + " — " + incoming
                + " more will not fit";
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
