package com.dwinovo.numen.platform;

/**
 * 单测的空壳平台服务:无头 JVM 里 {@code Services} 的四个静态服务都得有实现才装得上网络收发
 * (回执要经 {@code NumenNetwork.sendToPlayer} 真的发出去,测试才好断言)。只回答问题,不做什么。
 */
public final class TestPlatformHelper implements com.dwinovo.numen.platform.services.IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "test";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "numen-test-config");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return true;
    }
}
