# watch 接线单

模块本身不进发行 jar、不在运行时注册 —— 内嵌联动是**闭合集合**,靠 core 侧显式接线(`Builtin` 的
类注释:"清单在这里,不做扫描")。本模块没有目标模组,而 `Gate` 的两种 `open` 都强制目标模组在场,
过不了那道门;所以装配点与 places 同一条:`NumenCore` 的注册 lambda。下面每一行都标了确切位置。

## 1. 注册并打组(必需,一行)

`core/common/src/main/java/com/dwinovo/numen/core/NumenCore.java` 的 `registerTools()` 里那扇
`NumenPlugins.register(NumenPlugins.NUMEN, numen -> { … })` 的 lambda 里,`NumenPlaces.install(numen)`
那一行之后,加:

```java
com.dwinovo.numen.plugins.watch.NumenWatch.install(numen);
```

这一行同时把 `numen.watch` 组挂上、把两条生命周期接上(`CompanionEvent.REMOVE` → 订阅整份清空;
`CompanionEvent.HURT` → attacked 原语的计数)。

## 2. 每刻求值(必需,各一行)

places 不用 tick,哨兵要。core 自己的每刻工作挂在加载器入口(见 `NumenCoreForge.onServerTickPost`
里的 `BlockSearch.tick` 那一组;`CompanionTickDispatcher` 在 api/common,api 看不见插件模块 ——
api/common 的编译类路径上没有 `plugins:watch`,这条边只能是 core → 插件,所以挂 core 的刻钩子,
即"CompanionTickDispatcher 或等价位置"的等价位置):

- `core/forge/src/main/java/com/dwinovo/numen/core/NumenCoreForge.java` 的 `onServerTickPost`(END 相)
  里,`PathDebugRenderer.serverTick(server)` 之后加:

  ```java
  com.dwinovo.numen.plugins.watch.NumenWatch.tick(server);
  ```

- `core/fabric/src/main/java/com/dwinovo/numen/core/NumenCoreFabric.java` 的 `onInitialize` 里,
  其余 `ServerTickEvents.END_SERVER_TICK.register(...)` 旁边加:

  ```java
  ServerTickEvents.END_SERVER_TICK.register(com.dwinovo.numen.plugins.watch.NumenWatch::tick);
  ```

## 3. 让类进发行 jar(core 侧构建三件套)

照 `core/forge/build.gradle` 里其它 plugins 的写法(compileOnly 清单、check 的测试清单、jar 平铺清单):

```gradle
// compileOnly 清单(:154 旁)
compileOnly project(':plugins:watch')
// tasks.named('check') 的 dependsOn 清单里加 ':plugins:watch:test'(本模块有单测与 lint 报告)
// jar/jarJar 平铺清单(:250 旁)
it.from({ project(':plugins:watch').sourceSets.main.output })
```

`core/fabric/build.gradle` 同样三处(compileOnly 清单、jar 平铺清单、check 的 dependsOn 清单)。
`core/common/build.gradle` 另需一行(NumenCore.java 要看得见本模块,照 places 的 :23):

```gradle
compileOnly project(':plugins:watch')
```

## 4. settings.gradle(必需,一行)

`include('plugins:places')` 之后:

```gradle
include('plugins:watch')
```

## 5. 为什么本模块的 build.gradle 多一份完整 api jar

第三方联动只编译对着瘦 api jar(buildSrc `api-common.gradle` 的 include 清单是契约),但 watch 的命中
路径要调 `ServerPrograms.interrupt` —— 它住在引擎内部包 `com.dwinovo.numen.program`,不在瘦 jar 里;
`ServerLifecycle`(关服清理报到)同理。所以本模块在 places 的形状之外**多一行**:

```gradle
compileOnly files(project(':api:common').tasks.named('jar'))
```

这是本模块唯一偏离 places 样板的地方:api 的公共面照旧经瘦 jar 看,这一行只为那一个喊停入口;
第三方联动照旧只见瘦 jar,不跟着变。

## 6. 技能文档送达她

本模块自带 `src/main/resources/plugins/watch/skills/watch/SKILL.md`,形状与 places 的相同。已随
NumenCore 的 install 走 core 自己的技能根(core/common/src/main/resources/skills/ 的扁平目录,core 侧
改动);插件式路线则任何宿主模组在构造期调 `NumenWatch.install(skillsRoot)`(skillsRoot 即
`plugins/watch/skills` 的 Path,可用 `ModJar.find("watch")` 取),它自己开自己的门并捆上文档。

## 7. 生命周期与持久化(本模块自身行为,接线者无需再做什么)

- 订阅存活于她这一条命:`CompanionEvent.REMOVE`(休眠/注销/死亡后消失)整份清空;
- 换维度保留:键只有她的 UUID;
- 服务器重启**订阅不回来**:不写存档、不写世界数据,`ServerLifecycle.onStopped` 在 WatchEngine 的静态块里报到;
- **两个例外在盘上,不随进程没**(拍板口径 2026-11-07 的落盘件,装配点就是第 1 节那行 install ——
  `NumenWatch.install(NumenApi)` 内部取 `numen.configDir()`(即 `NumenPaths.config()`,`config/numen/`),
  解析不了就都不接:事件不落盘、白名单缺席 = 一律只停车(fail-closed),**不新增任何挂点**):
  关服/单人退存档/切存档(`ServerLifecycle.onStopped` 的三种来路,单人退存档**不结束进程**)**不摘这两件**
  —— install 挂在 mod 构造期、每 JVM 只跑一次,摘了重进世界就会静默不落盘、已批动作静默变回只停车
  (独立评审 2026-11-07 抓的正是这个;同 JVM 重进世界时订阅照旧随 REMOVE 清空、重新装,这两件原样续用,
  seq 也随之跨关服续号;真重启 = 新 JVM,由 install 重接并从盘上续号):
  - `numen_watch-events.jsonl` —— 持久事件日志(WatchJournal:append-only,一行一条八字段 JSON
    `{seq, tick, type, dim, pos, subject, source, note}`,seq 单调、**跨重启续号**(从盘上最大 seq 之后
    接着数,不用 tick 当 seq —— 重启 tick 归零);满 200 行压缩、留一条 `type=compaction` 记账行);
  - `numen_watch-whitelist.json` —— 反射白名单(WatchWhitelist:主人逐条批准的"命中 → 自动起步"动作表,
    条目是动作文本的 SHA-256,**默认空**;`grant`/`revoke` 是唯一写入口,主人手编这份文件即时生效)。

## 8. 反射白名单:闸门在 mod 侧,批准面在主人手里

- 闸门(已实现,WatchEngine):命中默认**只能停** —— 调 `ServerPrograms.interrupt` 打断 add 它的程序;
  订阅声明了 `action`(一段短 Lua)的,先算动作文本的 SHA-256 查白名单:在表里 → 下一 tick 经
  `ServerPrograms.launch` 起步(照 `/numen drive` 的 `server.tell(TickTask)` 延期一格写法,起步前再查一次
  白名单);不在表里 → **不启动任何任务**,回执与事件日志里写明「白名单未批准,只停车」。
  她的 Lua 面只有 add/list/drop/events 四个函数,**没有任何批准入口 —— 她批不了自己**。
- 批准面(主人,v0 载体 = 配置文件本身,与 providers.json/skin.json 同一家法):手编
  `config/numen/numen_watch-whitelist.json`,条目 `{"id": "<64 hex>", "note": "..."}`;id 从命中回执/事件
  日志的 note 里抄。若将来挂命令,挂点是 core 的 `NumenCommands`(命令树带 `FOR_PLAYERS` 门,
  她/外脑进不来),调用 `WatchWhitelist.grant/revoke` 这两个唯一写入口 —— 那是 core 侧一行接线,
  按第 1 节的同一条逻辑做,本模块不自带命令。

## 9. 事件怎么被 DSH 拉走(拍板口径:拉取,零新网络面)

- mod 侧只追加 `config/numen/numen_watch-events.jsonl`,**不开任何监听端口、不出网**;`/live`(8766)
  与任何推送通道都不在本模块的出现路径上。
- DSH 按水位拉增量:记 `lastEventSeq`,心跳时**文件直读**(DSH 与服务端同机,零 MCP 往返),把
  `seq > lastEventSeq` 的行取走、推进水位;同水位重拉零增量。读事件的定时调用遵守 monitor §六的
  忙时跳过(她忙则这轮不读,事件留在日志里,下个空闲心跳按水位补读 —— append-only 保证不丢)。
- 行的字段约定:v0 的 watch 行 `dim`/`pos` 暂为空串(WatchSnapshot 不携带维度与格子文本),
  `subject`=订阅名、`source`=条件形状(如 `attacked and blood_below`)、`note`=why + 白名单结论;
  type 现有 `watch-hit` 与 `compaction` 两种。

## 10. 与规格(v1.1)和上轮实现的偏离,逐条给理由

- **Lua 键名 `both`/`negate`**,不是规格 §二 v1.1 写的 `and`/`not`:`and` 与 `not` 是 Lua 保留字,
  `{and = {...}}`、`{not = "..."}` 是语法错误,规格示例在她的面上根本写不出来;语义一一对应
  (both = 恰两元与,negate = 顶层非)。
- **not 的准入收紧**:拍板口径(2026-11-07)"非只作用于电平原语,边沿原语不能取非" —— spec-monitor
  §二 v1.1 曾把 attacked(计数持有)列为可取非,按拍板收紧;`not attacked` 现在注册期拒收。
  elapsed/daytime 的真值由相位持有,按拍板口径的三事件枚举(不含它们)归电平,可取非。
- **事件类语义**:attacked 的发生记账在 `CompanionEvent.HURT` 监听里、伤害结算的同一 tick 内(主线程),
  报告在求值轮(延迟 ≤ 求值周期,缺省 4 tick = 200ms@20TPS,`WatchEngine.java` 的 DEFAULT_EVERY);
  target_gone/block_changed 的观察点是求值轮(这是它们唯一的观测面),单轮边沿、错过不补 —— 与 v0 相同。
  **要 ≤1 tick 的报告延迟得给事件类单独挂每刻求值点,那是新挂点,没做**(不新增挂点的拍板约束),
  列入台账候选。顺带修了 v0 的一个缺陷:两轮之间落地的新挨打,若恰在上一拍触发后的下一个轮次,
  v0 的布尔边沿会把它吞掉;发生制(计数超基线,消费在触发)下按"同一轮合并为一条"照报。
- **冷却**:JVM 属性 `numen.watch.event.cooldown`(单位 tick),**缺省 0 = 关**(v0"每下都报"保留;
  非零缺省无出处,待实测/台账再定)。事件类单原语被压住的发生不消费,期满补发;and 压住的那次
  同时成立即弃(补发会把"同一次成立"报两次)。
- **压缩线上的一对一咬合**:规格 §五.3"超 200 压缩、保留最新 200 条 + 一条记账行"字面上会在 201 行
  处反复触发;实现取"压后整本恰 200 行(数据 199 + 记账 1)",再涨过 200 才再压 —— 上界不变,咬合消除。
- **FAULTED(spec-monitor §三 v1.1 的求值异常语义)未实现**:不在本次拍板四件事内,v0 亦未实现,
  规格与代码的这一差距原样保留,未在本轮扩大也未缩小。
- **`ServerPrograms.launch` 的起步是单次尝试、不排队**:槽被上一段收尾占住就这一拍放弃(回执进日志),
  反射任务必须写成短任务、自带条件句柄、不依赖跨调用存活 —— spec-monitor §六 v1.1 的硬规矩照抄。
