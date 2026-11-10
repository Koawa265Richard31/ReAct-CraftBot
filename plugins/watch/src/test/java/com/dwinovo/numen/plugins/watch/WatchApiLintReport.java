package com.dwinovo.numen.plugins.watch;

import com.dwinovo.numen.core.ApiLintReport;
import com.dwinovo.numen.core.CoreApiFixture;
import com.dwinovo.numen.sdk.ApiTester;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * watch 联动的 lint 报告,不是守卫:{@code numen.watch} 组的函数写法与随它发的技能文档里写着的调用,对着
 * 引擎与 core 的组读一遍。本组由 {@code NumenCore.registerTools()} 里那一行 {@code NumenWatch.install}
 * 经 {@code CoreApiFixture.install()}(它调 {@code NumenCore.init()})装上 —— 测试里不再重复登记,重复登记
 * 会撞组名。报告写进 {@code build/reports/api-lint.txt},不让构建失败。
 */
class WatchApiLintReport {

    @Test
    void writeTheReport() throws IOException, URISyntaxException {
        // CoreApiFixture.install() 会走 NumenCore.init()，那里要 Forge/Fabric 的 IPlatformHelper
        // （ServiceLoader 找 com.dwinovo.numen.platform.services.IPlatformHelper）。纯 JUnit 里没有那个
        // 服务，本报告在加载器环境里才有意义 —— 这里判跳过，不判失败;它在真环境里跑时照旧会抓
        // 「Lua 类型名撞车」这类会崩启动的错（2026-11-09 WatchApi$Dropped 撞 DropCompanionTask$Dropped 就是它抓到的）。
        try {
            CoreApiFixture.install();
        } catch (ExceptionInInitializerError | NoClassDefFoundError missingPlatform) {
            Assumptions.assumeTrue(false,
                    "needs the mod loader's IPlatformHelper service; run this report inside a loader env — "
                            + missingPlatform);
        }
        List<ApiTester.Lint> lint = new ArrayList<>(ApiTester.lint().stream()
                .filter(l -> l.where().startsWith("numen.watch.")).toList());
        lint.addAll(ApiTester.lint(ApiLintReport.documents("plugins/watch/skills")));
        ApiLintReport.write(lint);
    }
}
