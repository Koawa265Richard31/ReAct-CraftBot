package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * {@code numen.watch}:她的<b>常驻订阅</b> —— 挂上"条件 → 打断",条件满足就当场打断她正在跑的程序,
 * 全程不经过 LLM。外脑轮询一次就得一次 tools/call(那一下先把正在跑的程序切断),所以"守住底线"这件事
 * 交给体内的哨兵:订阅表在她的命里常驻,每几 tick(可配,缺省 4)对她的状态求值一轮,
 * <b>任一命中即触发</b>的 OR;订阅内的组合三件套 —— 与(恰两元)/ 或(订阅之间)/ 非(顶层一层)——
 * 在装订期校验并拒收非法组合(拍板口径 2026-11-07,见 {@link WatchEngine#build})。
 *
 * <h2>名字空间</h2>
 * 登记进引擎自己的名字空间 {@link NumenPlugins#NUMEN},Lua 面是 {@code numen.watch.*}
 * (组的拼法见 {@code NumenPlaces} 的类注释):哨兵是引擎侧的自身能力,不接任何目标模组,
 * 三个加载器通跑,装配点与 places 同一条家法 —— {@code NumenCore} 的注册 lambda。
 *
 * <h2>生命周期</h2>
 * 订阅存活于她这一条命:身体一离开世界({@link CompanionEvent#REMOVE},休眠/注销/死亡后消失)整份清空;
 * 换维度保留(键只有她的 UUID);服务器重启订阅不持久化,也不写进存档 —— 引擎进程内的静态表
 * (见 {@link WatchEngine})。命中动作:打断她当前程序({@code ServerPrograms.interrupt},按 add 它的
 * 那段程序的编号,换段不理)+ 命中写进每同伴的环形缓冲,她下一轮用 {@code numen.watch.events()} 取走
 * + 追加一条持久事件日志({@link WatchJournal}:JSONL、seq 跨重启续号,DSH 按水位拉增量,mod 侧
 * 零新网络面)。订阅声明了反射动作的,只有动作在主人的白名单({@link WatchWhitelist},默认空)里
 * 才自动起步;否则只停车并在回执里写明。
 *
 * <h2>装配(照 places 的样子)</h2>
 * <p><b>引擎侧</b>:{@code NumenCore} 的注册 lambda 里一行
 * {@code NumenWatch.install(numen);}(逐条接线单见模块根的 {@code WIRING.md});每刻的求值由 core 的
 * 服务器刻钩子调 {@link #tick}(places 不用 tick,哨兵要 —— 同样一行接线)。
 * <p><b>第三方模组构造期</b>:{@code NumenWatch.install(skillsRoot);} —— 自己开自己的门并捆上自带的
 * 技能文档(照 NumenPlaces.install 的收尾)。
 * <p>不装配就什么都不存在:类不被加载、组不注册、她的程序里没有 numen.watch。
 */
public final class NumenWatch {

    private NumenWatch() {}

    /** 引擎侧装配:门已由 NumenCore 打开,这里把组挂上、把生命周期接上、把白名单与事件日志接上。 */
    public static void install(NumenApi numen) {
        WatchApi.install(numen);
        // 订阅存活于她这一条命:身体一离开世界(REMOVE:休眠/注销/死亡后消失)整份清空。换维度没有事件,也不清。
        numen.on(CompanionEvent.REMOVE, body -> WatchEngine.forget(body.getUUID()));
        // 她挨了一下:attacked 原语的计数,这条命内单调(REMOVE 时与订阅一起清)。HURT 在伤害结算的
        // 同一 tick 内发出(主线程),所以发生的记账在事件当 tick 内,报告在求值轮。
        numen.on(CompanionEvent.HURT, hurt -> WatchEngine.hurt(hurt.companion().getUUID()));
        // 白名单与事件日志落在 config/numen/ 下(NumenApi.configDir() 的文档约定:插件持久数据放这儿,
        // 文件名带自己的 id)。解析不了配置目录就都不接:事件不落盘,白名单缺席 = 一律只停车(fail-closed)。
        try {
            java.nio.file.Path dir = numen.configDir();
            WatchEngine.attach(
                    WatchJournal.atFile(dir.resolve("numen_watch-events.jsonl")),
                    WatchWhitelist.atFile(dir.resolve("numen_watch-whitelist.json")));
        } catch (RuntimeException bad) {
            org.slf4j.LoggerFactory.getLogger("numen-watch")
                    .warn("[numen-watch] no config dir resolved — watch events stay in memory, "
                            + "reflex whitelist absent (stop only)", bad);
        }
    }

    /**
     * 插件式装配:自己开自己的门(同 id 重复登记是引擎自己在走的路,NumenCore 里不止一处),并照
     * NumenTlm/NumenPlaces 的收尾把自带的技能文档捆上;skillsRoot 为空就不捆。
     */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(NumenPlugins.NUMEN, numen -> {
            install(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }

    /** 每个服务器刻由 core 的刻钩子调(照 BlockSearch.tick 那一组):到点对有订阅的同伴求值一轮。 */
    public static void tick(MinecraftServer server) {
        WatchEngine.tick(server);
    }
}
