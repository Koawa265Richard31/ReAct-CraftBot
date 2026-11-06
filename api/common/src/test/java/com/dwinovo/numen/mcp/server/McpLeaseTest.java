package com.dwinovo.numen.mcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约判据的边界。单一口径:外脑在不在场只看这一个是/否——
 *
 * <ul>
 *   <li>有 {@code tools/call} 在飞 → 在场(长程序调用会挂几分钟);</li>
 *   <li>否则距上一次成功的调用短于租约 → 在场;</li>
 *   <li>从没成功调用过、或到点 → 不在场(身体进安全待机,但绝不交还内置大脑)。</li>
 * </ul>
 */
class McpLeaseTest {

    private static final long LEASE = 90_000;

    @Test
    void aCallInFlightIsPresenceEvenWhenTheLastSuccessIsLongPast() {
        // 一段长程序正在服务端跑、它的 tools/call 还挂着:外脑就在线上,不能判失联
        assertTrue(McpMode.leaseOkAt(10 * LEASE, 0, LEASE, 1));
        assertTrue(McpMode.leaseOkAt(10 * LEASE, 1_000, LEASE, 2));
    }

    @Test
    void aSuccessInsideTheWindowHoldsTheLease() {
        assertTrue(McpMode.leaseOkAt(1_000, 1_000, LEASE, 0), "刚续约");
        assertTrue(McpMode.leaseOkAt(1_000 + LEASE - 1, 1_000, LEASE, 0), "差 1 毫秒到期");
    }

    @Test
    void theLeaseExpiresExactlyAtTheBoundary() {
        assertFalse(McpMode.leaseOkAt(1_000 + LEASE, 1_000, LEASE, 0));
        assertFalse(McpMode.leaseOkAt(1_000 + LEASE + 1, 1_000, LEASE, 0));
    }

    @Test
    void neverRenewedIsNotPresence() {
        // 模式开着、外脑还没成功调用过:身体待机,而不是让内置大脑接管
        assertFalse(McpMode.leaseOkAt(5_000, 0, LEASE, 0));
    }
}
