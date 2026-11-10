package com.dwinovo.numen.platform.services;

import com.dwinovo.numen.api.container.ContainerBox;
import net.minecraft.world.item.ItemStack;

/**
 * 把一件物品自带容器打开成 {@link ContainerBox}。容器内容住在一件 ItemStack 自身(潜影盒是
 * {@code BlockEntityTag} 里的 NBT,mod 背包是挂在堆上的物品级能力),怎么读它、怎么写回它是加载器
 * 的私事,所以解析住在平台服务后面——先例:方块侧的 {@link IBlockCapabilityReader}。
 *
 * <h2>判据次序(spec-m4-containers §2.1,A 圈的两步)</h2>
 * <ol>
 *   <li>潜影盒——BlockItem + BlockEntityTag,内容经 {@code ContainerHelper} 装/存;</li>
 *   <li>物品级物品栏能力——Forge 侧 {@code ForgeCapabilities.ITEM_HANDLER}(方块侧同能力先例:
 *       ForgeBlockCapabilityReader 的 ITEM_HANDLER 探测)。</li>
 * </ol>
 * 两者都不成立,给 null(「no openable container」)。只在服务端主线程调用。
 *
 * <p>{@code touched} 是动完内容后的落盘回调,由锚点属主提供(inv 锚点是
 * {@code Inventory.setChanged}):在它里面把改过的内格清单写回这一件堆自身,锚点格上那一件就是
 * 改过的那一件。
 *
 * <p>api/fabric(1.20.4 线)按 Transfer API 给自己的实现——与本服(Forge 1.20.1)互不相通,可后做;
 * 实现缺席时 {@code Services.CONTAINERS} 为 null,容器功能整体休眠而不是掀翻启动。
 */
public interface IItemContainerResolver {

    /** 打不开(不是容器)返回 null;打开了就给一个绑定这件堆的 {@link ContainerBox}。 */
    ContainerBox open(ItemStack container, Runnable touched);
}
