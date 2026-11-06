package com.dwinovo.numen.task;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端那份「谁在驾驶」镜像。口径的真源在主人客户端的 {@code McpMode#driving()}——
 * 外接大脑模式开着,且失联回退没有翻假——但那是客户端源码集的类,服务端类在编译期
 * 就看不见它(见 {@code api/common/build.gradle} 的 client 源码集)。两脑仲裁的服务端
 * 一侧(重放闸、反射层闸、{@code /numen drive} 的二次确认)读的都是这份镜像。
 *
 * <h2>镜像不是第二个口径</h2>
 * 全进程只有一位写入方:主人客户端在进世界与驾驶席翻转的边沿上经
 * {@code DrivingMirrorPayload} 把 {@code McpMode.instance().driving()} 现值推过来,
 * 这里照单收下。不落盘、不读配置、没有别处能写——真源翻,镜像就跟着翻,不会出现
 * 两边各说各话。
 *
 * <h2>按主人记,不按同伴记</h2>
 * McpMode 是主人客户端进程级的状态:驾驶席对她的全部同伴一起翻。主人下线就清
 * ({@code ServerPrograms.ownerLeft})——镜像证明的是「她的客户端此刻在场并握着驾驶席」,
 * 人不在了证明也不在了,身体回到「没有外脑」的默认(反射照常护身)。世界没了同样清
 * (见 {@code ServerLifecycle},与 {@code CompanionSpeech} 同一规矩)。
 *
 * <p>读不到登记(主人没来过、外接模式从没开过)一律视作<b>没有外脑驾驶</b>——这正是
 * 旧行为,内置大脑照常自己驱动。
 */
public final class DrivingMirror {

    /** 每位主人一份:他的客户端此刻是否握着驾驶席。 */
    private static final Map<UUID, Boolean> DRIVING = new ConcurrentHashMap<>();

    static {
        // 记的是这个世界里哪位主人的客户端握着驾驶席;世界没了就不算数,否则一个没翻回来的
        // true 会让下一个存档里的反射层一直让位。见 ServerLifecycle。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(DRIVING::clear);
    }

    private DrivingMirror() {}

    /** 主人客户端推来的现值(边沿才推,不逐刻刷)。服务端不推断,只照抄。 */
    public static void set(UUID ownerUuid, boolean driving) {
        DRIVING.put(ownerUuid, driving);
    }

    /** 主人下线:他的驾驶席声明随连接一起作废,重登时客户端会重推。 */
    public static void forget(UUID ownerUuid) {
        DRIVING.remove(ownerUuid);
    }

    /** 这位主人的客户端此刻是否握着驾驶席;没有他的登记就是没有。 */
    public static boolean isDriving(UUID ownerUuid) {
        return ownerUuid != null && DRIVING.getOrDefault(ownerUuid, Boolean.FALSE);
    }

    /** 这具身体的主人的客户端此刻是否握着驾驶席;无主的身体恒为没有。 */
    public static boolean isDriving(com.dwinovo.numen.entity.NumenPlayer companion) {
        return isDriving(companion.getOwnerUuid());
    }
}
