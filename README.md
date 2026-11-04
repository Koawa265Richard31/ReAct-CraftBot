# ReAct-CraftBot

> **这不是 Numen 官方项目。** 本仓库是 [Numen（言出法随）](https://github.com/Dwinovo/minecraft-numen)
> 的二次开发分支，把 mod 的身体能力接到 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
> （DSH）上，让一个真正运行在 Minecraft 里的玩家角色由 ReAct 风格的 agent 驱动。
>
> 名字、图标与美术资源均属上游作者，本项目不使用其品牌标识。

## 它是什么

一个 Forge 1.20.1 的 Minecraft mod（上游 Numen 的衍生版），身体部分保持不变：
一个真正的 `ServerPlayer` 子类，会走路、挖矿、合成、战斗、开箱子。

大脑不在 mod 里。mod 只负责：

- 提供 Lua 动作 API（`numen.move` / `numen.inv` / `numen.scan` …）
- 通过 MCP 把身体暴露给外部 agent
- 用原生方式维护"活着"所需的状态与心跳

推理、规划、工具调度交给同机的 DSH sidecar。

## 相对上游的改动

| 文件 | 改动 |
|---|---|
| `MoveApi.java` | 新增 `numen.move.fly` / `numen.move.flyTo` |
| `FlyToCompanionTask.java` / `FlyToTaskRecord.java` | 飞行任务（直线 + 碰撞，不做地面寻路） |
| `InvApi.java` | 新增 `numen.inv.inspect(item)`：读物品 tooltip，用于比较装备/饰品 |
| `ConsentCards.java` | 征询请求同时写入聊天栏 |
| `Companions.java` | 死亡后优先在自己重生点复活，回不去才回主人身边 |

详见 [FORK.md](FORK.md)。

## 协议

- **代码**：LGPL-3.0-only（继承自上游，见 [LICENSE](LICENSE)、[licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)）
- **胶水**（`dsh/`、`scripts/`、`skills/`）：MIT（见 [LICENSE-MIT](LICENSE-MIT)）
- **上游美术资源**：All Rights Reserved，**不包含在本仓库内**（见 [LICENSING.md](LICENSING.md)）

由于不含上游贴图，从本仓库构建出的客户端 jar 不会有完整界面资源；
需要完整表现时请自行从上游官方发布补齐，或使用本项目自绘资源。

## 构建

```bash
JAVA_HOME=/opt/java17 ./gradlew build -x test --no-daemon
# 产物：core/forge/build/libs/numen-forge-1.20.1-0.1.4.1-all.jar
```

完整流程（含中文语言包注入、双端部署）见 [FORK.md](FORK.md)。

## 上游

- Numen（言出法随）—— <https://github.com/Dwinovo/minecraft-numen>
- 上游 README 存档：[UPSTREAM-README.md](UPSTREAM-README.md)
