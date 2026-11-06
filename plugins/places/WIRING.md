# places 接线单

模块本身不进发行 jar、不在运行时注册 —— 内嵌联动是**闭合集合**,靠 core 侧显式接线
(`Builtin` 的类注释:"清单在这里,不做扫描")。本模块没有目标模组,而 `Gate` 的两种
`open` 都强制目标模组在场,过不了那道门;所以接线点只有一个:`NumenCore` 的注册 lambda。
下面每一行都标了确切位置,由掌握 core 的人逐条应用(本模块的开发约束是不改 core/api 现有文件)。

## 1. 注册并打组(必需,一行)

`core/common/src/main/java/com/dwinovo/numen/core/NumenCore.java` 的 `registerTools()` 里那扇
`NumenPlugins.register(NumenPlugins.NUMEN, numen -> { … })`(StatusApi 到 TimeApi 那一组 install
之间),加:

```java
com.dwinovo.numen.plugins.places.NumenPlaces.install(numen);
```

## 2. 让类进发行 jar(core 侧构建三件套)

照 `core/forge/build.gradle` 里其它 plugins 的写法(:149-153 / :183 / :243-248):

```gradle
// :149 旁
compileOnly project(':plugins:places')
// :183 dependsOn(':plugins:…:test') 清单里加 ':plugins:places:test'(本模块暂无用例,空 test 任务照样有效)
// :243-248 平铺清单里加
it.from({ project(':plugins:places').sourceSets.main.output })
```

`core/fabric/build.gradle` 同样三处(:27 / :72-77 的现状只有 ysm;places 无目标模组,三加载器都能跑,
fabric 侧也接)。

## 3. 技能文档送达她(可选)

本模块自带 `src/main/resources/plugins/places/skills/places/SKILL.md`,形状与 tlm 的技能文档相同。
两条送达路径任选:

- **NumenCore 路线**:core 自己的技能根在 `core/common/src/main/resources/skills/`(扁平目录)。
  把本模块的 `skills/places/SKILL.md` 作为新目录放进那里(core 侧改动);或在 NumenCore 捆技能处
  以 `ModJar.find` 定位 `plugins/places/skills` 后 `numen.bundleSkills(...)`。
- **插件式路线**:任何宿主模组在构造期调
  `com.dwinovo.numen.plugins.places.NumenPlaces.install(skillsRoot);`(skillsRoot 即
  `plugins/places/skills` 的 Path,可用 `ModJar.find("places")` 取),它自己开自己的门并捆上文档。

## 4. 为什么不走 Builtin

`Gate` 的两种 `open` 都先 `modLoaded.test(modId)`(Gate.java:54-55,65-66),places 没有目标模组,
门永远不开。它也不是对模组的联动 —— 引擎自己的内容走 `NumenPlugins.NUMEN` 正是家法
(`NumenPlugins` 类注释;`NumenCore.java:61,103` 引擎自己两处 `register(NUMEN, …)`)。
