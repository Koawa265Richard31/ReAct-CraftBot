package com.dwinovo.numen.plugins.places;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * {@code numen.place}:她自己记下的命名地点 —— 标一个点、以后自己走回去。点存在存档世界里
 * ({@link PlacesStore},跟同伴名册 {@code numen_companions} 同一套 {@code SavedData} 家法),
 * 换存档各是各的;同名不同维度是两个点,取的时候缺省取她当前维度下的那个。
 *
 * <h2>名字空间</h2>
 * 登记进引擎自己的名字空间 {@link NumenPlugins#NUMEN},Lua 面是 {@code numen.place.*}:
 * {@code api()} 的组名按登记 id 拼前缀({@code NumenPlugins} 的类注释:"组 {@code maid} 就是 {@code tlm.maid}",
 * "引擎自己的内容用 {@code NUMEN}"),她的这些点本来就是引擎侧的自身能力,不是对哪个模组的联动。
 * 组名 {@code place} 在引擎里没有主:各加载器现有组是 status/scan/locate/route/move/…,引擎的
 * {@code numen.build.place} 是 build 组下的函数名,不是组。
 *
 * <h2>装配(二选一,都走会因组已有主而启动失败 —— 见 ApiRegistry.register 的查重)</h2>
 * <p><b>引擎侧(推荐)</b>:内嵌联动是闭合集合({@code Builtin} 的类注释:"清单在这里,不做扫描"),
 * 而 {@code Gate} 的两种 {@code open} 都强制目标模组在场(Gate.java:55,66 两处 modLoaded.test),
 * 本模块没有目标模组,过不了那道门 —— 所以正确装配点是 {@code NumenCore} 的注册 lambda(引擎自己的
 * 内容走 {@code NUMEN} 正是这条家法):在 {@code core/common/.../core/NumenCore.java} 的
 * {@code registerTools()} 里那扇 {@code NumenPlugins.register(NUMEN, …)} 的 lambda 中加一行
 * {@code NumenPlaces.install(numen);}(具体到行的接线单见模块根的 {@code WIRING.md})。模块 jar
 * 还需照 {@code core/forge/build.gradle} 里其它 plugins 的三件套(compileOnly / from / dependsOn)消费。
 *
 * <p><b>第三方模组构造期</b>(任何想内嵌本模块的宿主):{@code NumenPlaces.install(skillsRoot);} ——
 * 自己开自己的门并捆上自带的技能文档(照 NumenTlm.install 的收尾:skillsRoot 非空才 bundle)。
 *
 * <p>不装配就什么都不存在:类不被加载、组不注册、她的程序里没有 numen.place —— 这正是静态闸门
 * 之外必须人工接线的原因,接线单随模块走。
 */
public final class NumenPlaces {

    private NumenPlaces() {}

    /** 引擎侧装配:门已由 NumenCore 打开(那扇 register(NUMEN, …) 的 lambda),这里只把组挂上去。 */
    public static void install(NumenApi numen) {
        PlacesApi.install(numen);
    }

    /**
     * 插件式装配:自己开自己的门(同 id 重复登记是引擎自己在走的路,NumenCore 里不止一处),
     * 并照 NumenTlm.install 的收尾把自带的技能文档捆上;skillsRoot 为空就不捆。
     */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(NumenPlugins.NUMEN, numen -> {
            PlacesApi.install(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
