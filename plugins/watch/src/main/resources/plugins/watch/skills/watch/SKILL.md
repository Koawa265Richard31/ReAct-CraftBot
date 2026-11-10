---
name: watch
description: 你要一边做事一边守住某条底线(血量别太低、别离锚点太远、别超时、有人动某个方块),或想让意外(挨打、着火、掉岩浆、天黑)随时把你从手上的活里拽出来,或想把两件事拼成一个条件(挨了打且血低才撤)时用。
---

# 常驻订阅:命中就打断,不经外脑

`numen.watch` 组让你挂上几条"条件 → 打断"。条件每几 tick(缺省 4)对一次你的状态求值,任一命中就当场打断
你正在跑的程序,原因是你写下的一行 why —— 不用外脑轮询(它每问一次都要先切断你的程序),也不用你在程序里
写循环自查。

## 条件的三种形状(三选一)

挂:`numen.watch.add("low_blood", {primitive = "blood_below", args = {threshold = 10}, why = "血量见底:停下进食或撤退"})`。

1. **单原语** `primitive = "..."`:19 个里选一个 ——
   blood_below(血量低于阈值)、hungry_below(饥饿到线)、air_low(换气不足)、falling(坠落够格)、on_fire(着火)、
   in_water(落水)、in_lava(落岩浆)、attacked(挨了一下)、inv_full(背包满)、item_at_least(物品够数)、
   item_at_most(物品见底)、target_gone(盯着的实体不在了)、entity_near(实体进入半径)、entity_far(实体出了半径)、
   hostile_near(附近有敌对生物)、block_changed(某格方块变了)、out_of_bounds(离锚点超过半径)、elapsed(超时了)、
   daytime(到了一天里的某时刻)。
2. **与(至多两元)** `both = {"attacked", "blood_below"}`:恰好两个原语,两件事同时成立的那一拍触发。
   操作数只能是原语,没有第三层 —— `(A 且 B) 或 C` 合法(两条订阅,订阅之间本来就是"任一命中即触发"),
   `A 且 (B 或 C)` 写不出来。两个原语共用一张 `args` 表,各取所需。
   target_gone 与 block_changed 不能进 both:它们的"真"只存在于看见转变的那一拍,配上与就把边沿丢了。
3. **非** `negate = "in_water"`:取反一个**电平**原语,独占整条条件 —— 例如"没在水里"期间成立。
   事件类(attacked、target_gone、block_changed)不能取非:取反一个"发生"不成立。

参数写进 `args = {...}`,只给那个原语要的几项:threshold / entity / item / count / pos / range / seconds /
repeat / tick。不收参数的原语(args 可省)有:attacked、inv_full、on_fire、in_water、in_lava。
某个原语要什么,用 `numen.api.help("numen.watch.add")` 查。写错的组合当场被拒,报错会说清是哪一条不合法 ——
照着改,别硬凑。

## 电平与事件

血量、距离、背包满这类是**电平**:条件连续为真只打断一次,掉回假再上来才会再打断(item_at_most、
out_of_bounds 这类"装上时就已成立"的,第一次检查就报)。
attacked、target_gone、block_changed 是**事件**:是"发生了一拍",不是"持续的状态"。同一拍里的多次同类
发生合并成一条报告(挨几下都是一条);报告之间有冷却(主人可配)。错过单轮边沿的那一拍不补发。

## 反射动作:默认只停车

add 可以带 `action = "<一小段 Lua>"`:命中后想自动起步的反射(比如后撤)。**它默认不会起步** —— 只有主人把
这段动作逐字批准进白名单(config/numen/ 下的 numen_watch-whitelist.json,初始为空),它才会在命中后起步;
没批准的命中照常停车打断你,回执里写明"白名单未批准,只停车"。所以要写动作就写短、写明确;要不要批,
是主人的事,不是你的事。

读命中:`numen.watch.events()` 把上轮以来的命中一次取走(取走即清空,每条只交一次,旧的先交)。每条带着
名字、条件、你的 why、当时的刻与时刻,以及动作的批准结论。被中断的那段程序的回执里已经是同一条 why ——
先读 events 再决定下一步,别重复问。

看存货与注销:`numen.watch.list()` 列出你挂着的(名字、条件、why,登记序);`numen.watch.drop("low_blood")`
注销一条。技能的规矩:**开头挂,结尾摘** —— 正常走完要摘,失败路径也要摘(pcall 的收尾行里 drop)。
不摘的话它继续替你守着,命中时打断的是当时在跑的程序,原因还是那一行 why。

边界:订阅是这条命的家当 —— 身体离开世界(休眠、注销、死亡)整份清空;换维度不清;服务器重启不回来,
也不写进存档(命中的事件日志与主人的白名单在盘上,那是另一回事)。同名再挂是覆盖;一条同伴至多挂 16 条。

几个顺手的样子:

- 挖矿时守住血量与饱食:`numen.watch.add("hp", {primitive = "blood_below", args = {threshold = 8}, why = "血量见底:回家吃饭"})`
- 挨了打且血量过半就撤:`numen.watch.add("hurt_low", {both = {"attacked", "blood_below"}, args = {threshold = 10}, why = "受击且血量过半:撤退"})`
- 守一个箱子没人动:`numen.watch.add("chest", {primitive = "block_changed", args = {pos = numen.shape.pos(120, 64, -35)}, why = "储物箱被动过了"})`
- 一趟活超过两分钟就重新打算:`numen.watch.add("slow", {primitive = "elapsed", args = {seconds = 120, repeat = true}, why = "这趟太久了,重新评估"})`
- 天黑前收工:`numen.watch.add("dusk", {primitive = "daytime", args = {tick = 12500}, why = "天快黑了,收工回家"})`
