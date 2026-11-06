# ReAct-CraftBot

> **这不是 Numen 官方项目。** 本仓库是 [Numen（言出法随）](https://github.com/Dwinovo/minecraft-numen)
> 的二次开发分支：把 mod 的身体能力接到一个**外部 agent 大脑**
> （[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)，下称 DSH）上，
> 让 Minecraft 里那个"假玩家"由真正的外部推理驱动。
>
> 名字、图标与美术资源均属上游作者，本项目不使用其品牌标识。
> English: [README_EN.md](README_EN.md)

## 它是什么

一个 Forge 1.20.1 mod（上游 Numen 的衍生版）。**身体**保持上游设计：一个真正的 `ServerPlayer`
子类，会走路、挖矿、合成、战斗、开箱子、戴装备。

**大脑不在 mod 里。** mod 只负责三件事：

1. 提供 Lua 动作 API（`numen.move` / `numen.inv` / `numen.scan` …）与穿戴抽象（`GearSource`）；
2. 通过 **MCP（HTTP，默认 `127.0.0.1:8765`）** 把身体暴露给外部 agent；
3. 维护"活着"所需的本地状态，并另开一条**只读**小门给外部读取（`127.0.0.1:8766`）。

```
外部 agent（DSH / 你自己的 MCP 客户端）
        │  MCP 8765：工具调用（说话、脚本、截图、事件）
        ▼
  客户端 mod ── 8766 GET /live ──► 只读状态快照（不占租约、不切程序）
        │
        ▼
  服务端（同伴身体 / 脚本执行 / 穿戴槽 / Curios 等来源）
```

## 能力现状

诚实分档：**已落地**＝代码在仓库里并通过离线闸门；**未验证**＝还没编译成 jar、没在真机跑过。

| 能力 | 状态 |
|---|---|
| 上游全部身体能力（移动/挖掘/合成/战斗/容器/FTB 任务） | 已落地 |
| `numen.move.fly` / `flyTo`、`numen.inv.inspect` | 已落地（本分支早期改动） |
| MCP 服务器 + 租约（90 秒无调用即待机） | 已落地 |
| **游戏内面板**（F9，七页：角色/任务/对话/技能/用量/通知/设置） | 已落地 · **未验证** |
| **角色页只读**：血量/饥饿/效果/活动、穿戴槽（含 Curios）、36 格背包 | 已落地 · **未验证**（需服务端同版：载荷新增 `health/maxHealth/air/gear`） |
| **只读小门 `/live`（8766）**：4 Hz 快照，独立 token，永不续租约 | 已落地 · **未验证** |
| 设置页搬迁（供应商/人设/声线/语音输入/皮肤/外接大脑/大脑/主题） | 已落地 · **未验证** |
| 截图保留策略（`keepShots`，默认 10 张）与按需缩放 | 已落地 · **未验证** |
| 穿戴与背包**写入**（装/卸/转移） | **开发中**，不在本版本 |
| 协议版本化（跨版本连接只降级、不崩） | 规划中 |
| 公共聊天入口（`/yuuka <话>` 走原版聊天栏） | 开发中 |

> 面板与 `/live` 依赖的载荷字段是**新增**的，因此**客户端与服务端必须同版**；
> 跨版本行为与降级矩阵见后续的协议版本化工作。

## 安装与对接

1. **客户端 + 服务端装同版本的 jar**（跨版本会握手失败）。
2. 打开 MCP：`config/numen/mcp_server.json` 里 `enabled: true`（**默认关闭**，第一次跑完会生成一个随机 token）。
3. 你的 agent 连 `http://127.0.0.1:8765/mcp`，`Authorization: Bearer <token>`。
4. 想要只读状态：`config/numen/mcp_server.json` 里开 `live.enabled`，然后
   `curl -H "X-Numen-Live: <live_token>" http://127.0.0.1:8766/live`。

### 接自己的 MCP 客户端时必须知道的两条

- **租约**：任何 `tools/call` 都会续租约（默认 90 秒）；**其他客户端的调用会切断正在跑的程序**
  （回执是 `this turn was cut off`）。所以定时轮询要低速、且不要与动作并发。
- **只读小门不是 MCP**：`/live` 不走 JSON-RPC、不续租约、不动程序槽，专门给"她忙时也想看一眼"的场合用。

## 面板（F9）

版式照**整合包里实际的视频设置界面**（Embaddium/Sodium 风格：黑色半透明 + 左侧分类 + 右侧行）。

| 页 | 内容 |
|---|---|
| 角色 | 状态条、穿戴槽（含 Curios，未登记来源显示为只读虚线格）、背包 27+9 |
| 任务 | 当前目标、轮次、已花 token、卡住次数（`CurrentTaskPayload` + `GoalState`） |
| 对话 | 会话入口与未读（点开进现有聊天屏） |
| 技能 | 启用/禁用她学到的技能 |
| 用量 | token 账本（只读） |
| 通知 | 事件历史（环形缓冲，上限 200） |
| 设置 | 八个分区，原样复用既有设置面板 |

左栏底部两格常驻：**常驻小条开关**、**叫停**（危险色，二次确认）。

## 穿戴槽为什么能"兼容所有 mod"

上游/本分支提供的抽象是**可插拔的来源**，不是一张写死的槽位表：

- `GearSource#slots(body)` 给出一组 `GearSlot`（`name / worn / refuseWear / refuseRemove / swap`）；
- 原版四件甲 + 副手是一处来源，Curios 是另一处（`plugins/curios`）；
- 面板遍历所有来源渲染，**不认识任何具体 mod**；没有登记的来源显示为只读。

加一个新 mod 的槽位系统 = 写一个 `GearSource` 插件，不需要改面板。

## 相对上游的改动

| 位置 | 改动 |
|---|---|
| `MoveApi` / `FlyTo*` | 新增 `numen.move.fly` / `flyTo`（直线 + 碰撞，不做地面寻路） |
| `InvApi` | 新增 `numen.inv.inspect(item)`：读 tooltip，用于比较装备与饰品 |
| `ConsentCards` | 征询请求同时写入聊天栏 |
| `Companions` | 死亡后优先在自己重生点复活，回不去才回主人身边 |
| `client/screen/panel/**` | 新增游戏内面板（本分支原创） |
| `client/live/**` | 新增只读状态小门（本分支原创） |
| `network/payload/NumenStatePayload` | 新增 `health / maxHealth / air / gear`（面板用） |
| `AgentLoop` | 修复 `aside` 泄漏：切断时把失败回执交回调用方，不再卡住程序槽 |

完整历史见 [FORK.md](FORK.md)。

## 已知限制

- 面板与 `/live` **尚未在真机验证**；本版本之前的构建不含它们。
- 旧版服务端下血量/穿戴无法显示（载荷是新增字段）——跨版本会降级，但要等协议版本化落地。
- 沙箱、自迭代工具写入、密钥围栏等属于**宿主机侧**（外部 agent 那半边），不在本仓库。
- 上游美术资源不入库，自建 jar 的界面资源不完整（见 [LICENSING.md](LICENSING.md)）。

## 许可

| 范围 | 许可 |
|---|---|
| 继承自上游的源码（`api/`、`core/`、`agent/`、`plugins/`、`pathing/`、`ui/`、`lua/`、`ai/`） | **LGPL-3.0-only**（见 [LICENSE](LICENSE)） |
| 本分支原创：`api/common/src/client/java/com/dwinovo/numen/client/screen/panel/**`、`client/live/**` | **MIT** |
| 上游美术资源 | All Rights Reserved，**不包含在本仓库内** |

新文件请带 `SPDX-License-Identifier: MIT` 头。

## 构建

```bash
JAVA_HOME=/opt/java17 ./gradlew build -x test --no-daemon
# 产物：core/forge/build/libs/numen-forge-1.20.1-0.1.4.1-all.jar
```

完整流程（含中文语言包注入、双端部署）见 [FORK.md](FORK.md)。

## 上游与致谢

- Numen（言出法随）—— <https://github.com/Dwinovo/minecraft-numen>
- DeepSeek Harness —— <https://github.com/deepseek-ai/deepseek-harness>
- 上游 README 存档：[UPSTREAM-README.md](UPSTREAM-README.md)
