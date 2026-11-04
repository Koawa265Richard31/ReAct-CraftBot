# Numen Fork — 维护说明

本仓库是 [Numen（言出法随）](https://github.com/) 的二次开发分支，目标是把 mod 退化成一个**纯身体**，
大脑交给同机的 DeepSeek Harness（DSH）sidecar，并复用 mod 已有的 ReAct 工具注册表。

- 上游基线：`1.20.1` 分支（codeload 拉取，非 git clone）
- 当前产物：`numen-forge-1.20.1-0.1.4.1-all.jar`
- 部署位置：客户端 `mods/` + 服务器 `/data/mcpack/mods/`（双端同一个 jar）

---

## 1. 已有补丁

| 文件 | 改动 | 原因 |
|---|---|---|
| `core/common/.../tools/work/MoveApi.java` | 新增 `numen.move.fly({on})`、`numen.move.flyTo({to,speed,seconds})` | 原版没有飞行；`flyTo` 走直线 + `MoverType.SELF` 碰撞，不做地面寻路 |
| `core/common/.../task/move/FlyToCompanionTask.java` | 新增飞行任务（tick 推进、到点收尾、不许飞时报 `UNSUPPORTED`） | 配合 `flyTo` |
| `core/common/.../task/move/FlyToTaskRecord.java` | 新增任务描述记录 | 配合 `flyTo` |
| `core/common/.../tools/inventory/InvApi.java` | 新增 `numen.inv.inspect(item)` → 名字 + tooltip 每行 | 模型看得到"这件饰品有什么效果"才能判断哪件更好；静态表跟不上模组版本 |
| `api/common/.../client/consent/ConsentCards.java` | 征询请求同时写一行到聊天栏 | 右上角 toast 一闪而过，主人会错过"她在等批准" |
| `api/common/.../entity/Companions.java` | 死亡时记下她自己的重生点（床/`/spawnpoint`），复活优先回那里，不可用才回主人身边 | 上游写死"复活在主人身边"，和原版玩家行为不一致 |

---

## 2. 构建（在服务器上做，/tmp 会被清理）

```bash
cd /tmp/numen-build/minecraft-numen-1.20.1
JAVA_HOME=/opt/java17 ./gradlew build -x test --no-daemon    # 增量约 1 分钟，全量约 14 分钟
# 产物：core/forge/build/libs/numen-forge-1.20.1-0.1.4.1-all.jar
```

**中文语言包是构建后注入的，不在源码里**（源码只有 `ModLanguageData` 的常量）：

```bash
python3 /tmp/gen_zh_lang.py . core/forge/build/libs/numen-forge-1.20.1-0.1.4.1-all.jar /tmp/numen-final.jar
# → assets/numen/lang/zh_cn.json (473 条)
```

> 注入脚本在 `D:\A_Dev_Projects\bot\fixbuild\gen_zh_lang.py`。
> 忘了这一步的表现：UI 全变英文。

---

## 3. 部署

```bash
# 服务端
cp -f /tmp/numen-final.jar /data/mcpack/mods/numen-forge-1.20.1-0.1.4.1-all.jar
systemctl restart mc

# 客户端（保持 sha1 一致）
scp -i ~/.ssh/<key> -P <port> root@<host>:/tmp/numen-final.jar \
    "/d/mc/mc/versions/香草纪元：食旅纪行/mods/numen-forge-1.20.1-0.1.4.1-all.jar"

sha1sum <两侧 jar>   # 必须一致
```

---

## 4. 踩过的坑

1. **API 参数必须是一个 record**，不能是裸 `boolean`/`int`。
   写成 `fly(boolean)` 会在 mod 初始化阶段抛
   `IllegalArgumentException: ... its arguments are a record, not boolean`，**整个服务端起不来**。
2. **`/ysm model reload` 会把所有人的模型分配重置成 `default`**。
   重载后要重新 `/ysm model set <玩家> <模型> <贴图> true`。
3. **YSM 的 `.ysm` 文件名就是模型 id**，带后缀（`murasame.ysm`）。
4. **语言包不在源码里**，每次构建后都要注入，否则丢中文。
5. `/tmp` 会被重启清理，源码树要定期打包回本地仓库。
6. Numen 的 `lua` 回执是**给玩家看的文本**，不是 JSON；要结构化数据就让 Lua 返回 `key=value` 行再正则读。

---

## 5. 下一步（P1）

把 `ApiRegistry`（组 + 函数 + `@Fn`/`@Note`/`@Doc`）**逐函数暴露成 MCP 工具**：

- `tools/list` 从注册表生成，例如 `move_go {to,arrive,costs}`、`inv_count {item}`
- `tools/call` 直接派发到 Java 方法（`Binder` 已有的绑定路径），不再让模型写 Lua 字符串
- 保留 `lua` 作为复合动作的逃生通道

收益：dsl 侧从 9 个工具变成 40+ 个带 JSON Schema 的原生工具，参数校验在 Java 侧，省 token、少语法错误。

后续：
- **P2**：mod 启动时自动拉起 DSH sidecar，注入端口/token，进程挂了重启
- **P3**：内置脑（`agent/` 26 文件 2952 行）改为开关，默认关但保留 fallback
