package com.dwinovo.numen.platform;

import com.dwinovo.numen.api.container.ContainerBox;
import com.dwinovo.numen.gear.ContainerBoxes;
import com.dwinovo.numen.platform.services.IItemContainerResolver;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 单测的容器解析器:tag 里带 {@link #KEY} 标记的物品打开成容器(格数标记里给,内容经
 * {@code ContainerHelper} 同一份格式装/存)。潜影盒的真实解析在 api/forge 的
 * {@code ForgeItemContainerResolver}(本裸 JVM 起不了 Forge 能力),测试假实现证明的是
 * {@code ContainerBoxes}/{@code GearSwapService} 这一层对 provider 契约的用法——收放问 provider、
 * 掩蔽键问 provider、写回经 touched。
 */
public final class TestItemContainerResolver implements IItemContainerResolver {

    /** 物品 tag 里携带的容器标记键:整个 CompoundTag 就是容器自己的 NBT(同潜影盒的 BlockEntityTag)。 */
    public static final String KEY = "BoxTest";

    /** 内容清单在标记里的键名,与 {@code ContainerHelper} 的格式一致。 */
    public static final String ITEMS = "Items";

    @Override
    public ContainerBox open(ItemStack container, Runnable touched) {
        CompoundTag tag = container.getTag();
        if (tag == null || !tag.contains(KEY)) {
            return null;
        }
        return new TestBox(container, touched);
    }

    /** tag 里剥掉内容清单后的掩蔽键(与 Forge 实现同一条契约:纯内格重排前后不变)。 */
    public static String maskedToken(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        CompoundTag copy = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        copy.getCompound(KEY).remove(ITEMS);
        return id + "#" + copy;
    }

    /** 一件测试容器:27 格(或标记里 Slots 给的格数)。 */
    private static final class TestBox implements ContainerBox {

        private final ItemStack stack;
        private final Runnable touched;
        private final NonNullList<ItemStack> items;

        TestBox(ItemStack stack, Runnable touched) {
            this.stack = stack;
            this.touched = touched;
            CompoundTag marker = stack.getTag().getCompound(KEY);
            int slots = marker.contains("Slots") ? marker.getInt("Slots") : 27;
            this.items = NonNullList.withSize(slots, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(marker, items);
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
            // 现算,不缓存:掩蔽键取锚点堆此刻的形态,touched() 弄丢内容之外的组件时键当场对不上。
            return TestItemContainerResolver.maskedToken(stack);
        }

        @Override
        public void touched() {
            CompoundTag marker = stack.getOrCreateTagElement(KEY);
            marker.remove(ITEMS);
            ContainerHelper.saveAllItems(marker, items);
            touched.run();
        }
    }
}
