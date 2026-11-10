package com.dwinovo.numen.platform;

import com.dwinovo.numen.api.container.ContainerBox;
import com.dwinovo.numen.gear.ContainerBoxes;
import com.dwinovo.numen.platform.services.IItemContainerResolver;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.List;
import java.util.Optional;

/**
 * Forge (1.20.1) implementation of {@link IItemContainerResolver} — opens an
 * item-borne container as a {@link ContainerBox}, by the two criteria of
 * spec-m4-containers §2.1, in order:
 * <ol>
 *   <li><b>Shulker box</b> — a {@link BlockItem} whose block is a
 *       {@link ShulkerBoxBlock}; the contents live in the stack's own
 *       {@code BlockEntityTag} (the same NBT a placed box persists), read via
 *       {@link ContainerHelper#loadAllItems} and written back via
 *       {@link ContainerHelper#saveAllItems} — the same path the player's GUI uses.</li>
 *   <li><b>Item-level item-handler capability</b> —
 *       {@link ForgeCapabilities#ITEM_HANDLER} on the stack (the same capability
 *       pipes and hoppers read; block-side precedent:
 *       {@link ForgeBlockCapabilityReader}). {@code isItemValid} and the slot
 *       capacity are used verbatim as our "does it fit" criteria — no second
 *       copy of any mod's rules.</li>
 * </ol>
 * Neither applies: {@code null} ("no openable container"). Server main thread only.
 *
 * <h2>写回契约(§2.1/§2.3)</h2>两种盒都<b>就地</b>写回锚点格上那一件堆自身:潜影盒在
 * {@code touched()} 里把内格清单存回 {@code BlockEntityTag}(同一件 ItemStack 的 NBT,锚点格上
 * 那一件就是改过的那一件);能力盒经处理器自己写(生态里标准的物品级处理器直接把内容编进堆 NBT),
 * {@code touched()} 只补锚点属主给的落盘回调。守恒掩蔽键:{@link ShulkerBoxBlockEntity#ITEMS_TAG}
 * 与能力盒常见的整包清单键({@code Items}/{@code Inventory})从键里剥掉——内格怎么重排键都不变;
 * 其余组件(附魔、耐久、改名)留在键里,写回丢了它们守恒断言当场发现。mod 私有的内容键是
 * C 圈挂账(spec §七 1),不在这里猜。
 */
public final class ForgeItemContainerResolver implements IItemContainerResolver {

    @Override
    public ContainerBox open(ItemStack container, Runnable touched) {
        if (container.isEmpty()) {
            return null;
        }
        if (container.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) {
            return new ShulkerBoxContents(container, touched);
        }
        IItemHandler handler = container.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        return handler == null ? null : new CapabilityContents(container, handler, touched);
    }

    /** 潜影盒:27 格,内容在堆自身 {@code BlockEntityTag} 的内容清单里。 */
    private static final class ShulkerBoxContents implements ContainerBox {

        private final ItemStack stack;
        private final Runnable touched;
        private final NonNullList<ItemStack> items =
                NonNullList.withSize(ShulkerBoxBlockEntity.CONTAINER_SIZE, ItemStack.EMPTY);

        ShulkerBoxContents(ItemStack stack, Runnable touched) {
            this.stack = stack;
            this.touched = touched;
            CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
            if (blockEntityTag != null) {
                ContainerHelper.loadAllItems(blockEntityTag, items);
            }
        }

        @Override public int slots() { return items.size(); }

        @Override public ItemStack slot(int index) { return items.get(index); }

        @Override
        public Optional<String> refusePut(int index, ItemStack stack) {
            ItemStack cur = items.get(index);
            if (cur.isEmpty()) {
                return Optional.empty();
            }
            if (!ItemStack.isSameItemSameTags(cur, stack)) {
                return Optional.of(ContainerBoxes.holdsOther(index, cur));
            }
            if (cur.getCount() + stack.getCount() > cur.getMaxStackSize()) {
                return Optional.of(ContainerBoxes.topsOut(index, cur, stack.getCount()));
            }
            return Optional.empty();
        }

        @Override
        public Optional<String> refuseTake(int index) {
            return items.get(index).isEmpty() ? Optional.of(ContainerBoxes.emptyInner(index)) : Optional.empty();
        }

        @Override
        public ItemStack put(int index, ItemStack in) {
            ItemStack old = items.get(index).copy();
            if (old.isEmpty()) {
                items.set(index, in.copy());
            } else {
                items.get(index).grow(in.getCount());
            }
            return old;
        }

        @Override
        public ItemStack take(int index) {
            ItemStack out = items.get(index).copy();
            items.set(index, ItemStack.EMPTY);
            return out;
        }

        @Override
        public void set(int index, ItemStack stack) {
            items.set(index, stack.copy());
        }

        @Override
        public String conservationToken() {
            // 现算,不缓存:掩蔽键取的是锚点堆<b>此刻</b>的形态,前后两次 boxAccount 各取各的——
            // 纯内格重排剥掉内容清单后键不变;touched() 把内容之外的组件弄丢了,键当场对不上。
            return maskedToken(stack, ShulkerBoxBlockEntity.ITEMS_TAG);
        }

        @Override
        public void touched() {
            CompoundTag blockEntityTag = stack.getOrCreateTagElement("BlockEntityTag");
            blockEntityTag.remove(ShulkerBoxBlockEntity.ITEMS_TAG);   // saveAllItems 会写回,不先清会留旧条目
            ContainerHelper.saveAllItems(blockEntityTag, items);
            touched.run();
        }
    }

    /** 能力盒:格数与收放全部听物品级 {@link IItemHandler} 的,不另写一份规则。 */
    private static final class CapabilityContents implements ContainerBox {

        private final ItemStack stack;
        private final IItemHandler handler;
        private final Runnable touched;

        CapabilityContents(ItemStack stack, IItemHandler handler, Runnable touched) {
            this.stack = stack;
            this.handler = handler;
            this.touched = touched;
        }

        @Override public int slots() { return handler.getSlots(); }

        @Override public ItemStack slot(int index) { return handler.getStackInSlot(index); }

        @Override
        public Optional<String> refusePut(int index, ItemStack stack) {
            ItemStack cur = handler.getStackInSlot(index);
            if (!handler.isItemValid(index, stack)) {
                return Optional.of("inner slot " + index + " refuses " + itemId(stack)
                        + " — the container's own rules won't take it");
            }
            ItemStack rest = handler.insertItem(index, stack.copy(), true);   // simulate:收放判据原样用
            if (rest.isEmpty()) {
                return Optional.empty();
            }
            if (!cur.isEmpty() && !ItemStack.isSameItemSameTags(cur, stack)) {
                return Optional.of(ContainerBoxes.holdsOther(index, cur));
            }
            if (!cur.isEmpty()) {
                return Optional.of(ContainerBoxes.topsOut(index, cur, rest.getCount()));
            }
            return Optional.of("inner slot " + index + " takes at most " + (stack.getCount() - rest.getCount())
                    + " " + itemId(stack) + " — " + rest.getCount() + " will not fit");
        }

        @Override
        public Optional<String> refuseTake(int index) {
            ItemStack cur = handler.getStackInSlot(index);
            if (cur.isEmpty()) {
                return Optional.of(ContainerBoxes.emptyInner(index));
            }
            return handler.extractItem(index, cur.getCount(), true).isEmpty() ? Optional.empty()
                    : Optional.of("inner slot " + index + " keeps its " + itemId(cur)
                    + " — the container's own rules won't let it out");
        }

        @Override
        public ItemStack put(int index, ItemStack in) {
            ItemStack old = handler.getStackInSlot(index).copy();
            ItemStack rest = handler.insertItem(index, in.copy(), false);   // refusePut 验过放得下
            if (!rest.isEmpty()) {
                throw new IllegalStateException("capability box put " + itemId(in) + " into inner slot "
                        + index + " left a remainder of " + rest.getCount() + " after refusePut passed");
            }
            return old;
        }

        @Override
        public ItemStack take(int index) {
            ItemStack cur = handler.getStackInSlot(index);
            return handler.extractItem(index, cur.getCount(), false);
        }

        @Override
        public void set(int index, ItemStack stack) {
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(index, stack.copy());
                return;
            }
            // 没有 set 的处理器:清格再插入,尽力放回(断言失败后的回滚路径,前面已有 ERROR 记录)。
            while (!handler.getStackInSlot(index).isEmpty()) {
                handler.extractItem(index, handler.getStackInSlot(index).getCount(), false);
            }
            handler.insertItem(index, stack.copy(), false);
        }

        @Override
        public String conservationToken() {
            // 现算,不缓存:与潜影盒同一条理由(掩蔽键取锚点堆此刻的形态;生态里标准的物品级处理器
            // 把内容编在根上的 {@code Items},剥掉它纯重排前后键不变;mod 私有键是 C 圈挂账)。
            return maskedToken(stack, CONTENT_KEYS.toArray(new String[0]));
        }

        @Override
        public void touched() {
            // 内容经处理器就地写进了堆 NBT(生态里标准的物品级处理器如此);这里只补锚点属主的落盘回调。
            touched.run();
        }
    }

    /** 内容清单的常见键:ItemStackHandler 的 {@code Items} 与老式整包清单的 {@code Inventory}。 */
    private static final List<String> CONTENT_KEYS = List.of("Items", "Inventory");

    /**
     * 守恒掩蔽键:id + 内容清单剥掉后的 tag。潜影盒剥 {@code BlockEntityTag} 下 {@link
     * ShulkerBoxBlockEntity#ITEMS_TAG} 那一张单;能力盒剥根上的 {@link #CONTENT_KEYS}(mod 私有键是
     * C 圈挂账)。内容<b>之外</b>的组件全部留在键里。
     */
    private static String maskedToken(ItemStack stack, String... contentKeys) {
        String id = itemId(stack);
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return id + "#";
        }
        CompoundTag copy = tag.copy();
        for (String key : contentKeys) {
            copy.remove(key);
            if (copy.contains("BlockEntityTag")) {
                copy.getCompound("BlockEntityTag").remove(key);
            }
        }
        return id + "#" + copy;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
