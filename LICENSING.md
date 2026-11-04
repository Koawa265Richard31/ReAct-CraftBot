# 协议说明 / Licensing

本仓库是 **Numen（言出法随）** 的二次开发分支，不是官方项目。

## 各部分协议

| 路径 | 内容 | 协议 |
|---|---|---|
| 除下列以外的全部源码 | 源自 Numen 的 Java 代码及其修改 | **LGPL-3.0-only**（见 `LICENSE`） |
| `dsh/` | 把 Numen 接到 DeepSeek Harness 的胶水：心跳插件、preset 片段 | **MIT**（见 `LICENSE-MIT`） |
| `scripts/` | 构建、语言包注入、部署脚本 | **MIT** |
| `skills/` | 给 agent 用的技能文档 | **MIT** |

> `dsh/`、`scripts/`、`skills/` 三个目录随 P1（工具逐函数暴露）一并加入；当前提交里可能尚未存在。
>
> **人格文件（`persona*.md`）不含在本仓库内**：它是本项目自己的角色设定，属于私人内容。
> 仓库里的 preset 片段会引用一个空的 `persona.md` 占位，使用者自己写。

## 为什么是 LGPL-3.0

上游 Numen 的 `gradle.properties` 声明 `license=LGPL-3.0-only`，其 `LICENSE` 为 LGPL-3.0 全文。
LGPL 是弱著佐权：**分发衍生版本时，这部分代码必须继续以 LGPL-3.0 发布并提供源码**。
本仓库公开源码即满足该义务；本文件与 `FORK.md` 说明了相对上游的修改。

## 上游美术资源（重要）

`licenses/ASSETS.txt` 规定：Numen 的**贴图、图标、logo、品牌图**为 **All Rights Reserved**，
禁止在其它项目或产品中复制、再分发、修改，唯一例外是"未修改的官方构建"或**版权方书面许可**。
`Numen` / `言出法随` 这两个名字与品牌同样保留，不得用于标识衍生版本。

因此本仓库的公开分发**不包含**这些美术资源：

- 当前源码树里仍留有上游贴图，仅用于本地构建；
- 公开 release 前会替换为本项目自绘的界面资源（面板重做计划的一部分），
  或在上游作者书面许可后保留；
- 你也可以直接向上游申请授权：
  <https://github.com/Dwinovo/minecraft-numen/issues>

## 第三方

- DeepSeek Harness（`@deepseek-ai/dsh`）为 MIT。
- 仓库内不含任何整合包、第三方模型（如 YSM 模型）或他人 mod 的 jar。
