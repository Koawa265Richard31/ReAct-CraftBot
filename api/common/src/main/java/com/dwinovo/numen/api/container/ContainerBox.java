package com.dwinovo.numen.api.container;

import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 一件已打开的物品自带容器(潜影盒的内格、挂在物品能力上的背包格)。{@code put}/{@code take} 只动
 * 容器自身——从哪来、到哪去、两边的背包,由调用方管(分工与 {@code GearSlot.swap} 的 javadoc 同款);
 * 谁提供它、锚点怎么找,见 {@code com.dwinovo.numen.platform.services.IItemContainerResolver}。
 *
 * <p>协议 3:M4 起 payload 能指进物品自带容器,两端必须同版。本接口只活在服务端主线程(打开、判收放、
 * 动手都在 {@code ContainerBoxes} 的求值路径上),不上线;上线的是内格现状的快照
 * ({@code BoxReadResult})。
 *
 * <h2>提供方的三条义务</h2>
 * <ol>
 *   <li><b>就地写回</b>:内容就编在这件物品自己身上(潜影盒在 {@code BlockEntityTag} 的内容清单里,
 *       能力背包在物品级处理器后面)。{@link #touched} 之后,锚点格上那一件就是改过的那一件——
 *       不换对象、不另存副本。</li>
 *   <li><b>守恒掩蔽键</b>({@link #conservationToken()}):契约是「纯内格重排前后该键不变」——
 *       内容清单编在堆自身 NBT 里的容器,把内容从键里剥掉(哪个 mod 把内容写进哪个键只有它自己知道,
 *       所以这个键由提供方实现);内容不在堆 NBT 里的,原样给 id + tag 也行。内容<b>之外</b>的组件
 *       (附魔、耐久、改名)必须在键里:写回把它们弄丢了,守恒断言要能当场发现。</li>
 *   <li><b>先问后动</b>:调用方先问过 {@link #refusePut}/{@link #refuseTake} 才调
 *       {@link #put}/{@link #take};refuse 的原文(给面板的一句话)由提供方给——收不收的判据与玩家在
 *       游戏里往这个容器里拖东西是同一条,不另写一份规则。</li>
 * </ol>
 */
public interface ContainerBox {

    /** 内格数,以打开那一刻为准;M4 内容器不会中途变格数(潜影盒恒 27)。 */
    int slots();

    /** 第 i 格现状(容器自己的活对象;读它的组件可以,调用方不得改它——要改经 put/take/set)。 */
    ItemStack slot(int index);

    /**
     * 这堆放得进第 i 格吗:空 = 能;否则给面板的一句话(内格已有别的物、并不下、槽自拒绝)。
     */
    Optional<String> refusePut(int index, ItemStack stack);

    /** 第 i 格现在的东西整叠取得出吗:空 = 能(空格也空);否则给面板的一句话。 */
    Optional<String> refuseTake(int index);

    /**
     * 放:返回被顶出来的原来那件(空 = 落进去了);不碰两边背包。调用方先问过 {@link #refusePut}。
     */
    ItemStack put(int index, ItemStack in);

    /** 取:整叠拿出。调用方先问过 {@link #refuseTake}。 */
    ItemStack take(int index);

    /** 仅守恒断言失败后的回滚用:不经校验整格覆写。 */
    void set(int index, ItemStack stack);

    /** 守恒掩蔽键:内格怎么重排都不变的那半个键(见类的「提供方的三条义务」第 2 条)。 */
    String conservationToken();

    /** 动完内容落盘(Inventory.setChanged / 穿戴侧的通知);每次 put/take/set 之后由调用方调一次。 */
    void touched();
}
