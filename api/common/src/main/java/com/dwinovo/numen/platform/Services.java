package com.dwinovo.numen.platform;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.platform.services.INetworkChannel;
import com.dwinovo.numen.platform.services.IPlatformHelper;
import com.dwinovo.numen.platform.services.IBlockCapabilityReader;
import com.dwinovo.numen.platform.services.IItemContainerResolver;

import java.util.ServiceLoader;

public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkChannel NETWORK = load(INetworkChannel.class);
    public static final INumenConfig CONFIG = load(INumenConfig.class);
    public static final IBlockCapabilityReader CAPS = load(IBlockCapabilityReader.class);
    /**
     * M4 的物品自带容器解析器(协议 3):把一件潜影盒/能力背包打开成可读写的 ContainerBox。
     * 实现可缺席——api/fabric(1.20.4 线)的按其 Transfer API 后做(spec-m4-containers §2.1),
     * 缺席时容器功能整体休眠({@code ContainerBoxes.isContainer} 恒 false、resolve 如实回
     * 「no openable container」),而不是在 mod 构造期掀翻,所以这里是 tryLoad 不是 load。
     */
    public static final IItemContainerResolver CONTAINERS = tryLoad(IItemContainerResolver.class);
    // 语音工厂是客户端专属服务,注册在 client 包的 ClientServices 里——
    // 其实现类引用 client-only 的 Sound 类,放这里急加载会在专用服务端
    // 掀翻 mod 构造(dist 边界由类结构表达,共享注册表只放两侧通用的服务)。

    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }

    /** 同 {@link #load},但没有实现时记一条 debug、给 null,不抛——可缺席的服务用这个。 */
    private static <T> T tryLoad(Class<T> clazz) {
        try {
            return load(clazz);
        } catch (RuntimeException absent) {
            Constants.LOG.debug("No implementation for {} — the optional capability stays off", (Object) clazz);
            return null;
        }
    }
}
