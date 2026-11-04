package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * 飞到坐标:直线,穿不过实心方块(移动走 {@link MoverType#SELF} 的碰撞),到点就收。
 *
 * <p>不做寻路。飞行本来就是"空中直线"这件事,绕开 {@code numen.route.plan} 那套地面规划,
 * 也就不会因为"要拆一格草"之类的原因整条路作废。
 *
 * <p>身体不允许飞时以 {@link FailureType#UNSUPPORTED} 收场,不给模型留"再试一次"的错觉。
 */
public final class FlyToCompanionTask extends AbstractCompanionTask<FlyToTaskRecord> {

    /** 交回给程序的值。 */
    public record Flew(String detail, Vec3 pos) {}

    /** 到点判定的容差:一格多一点,免得在目标附近来回蹭。 */
    private static final double ARRIVE = 1.2;

    private boolean flying;

    public FlyToCompanionTask(NumenPlayer player, FlyToTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        if (!player.getAbilities().mayfly) {
            fail("this body cannot fly: its game mode does not allow it", FailureType.UNSUPPORTED,
                    "ask the owner to switch the mode, or use numen.move.to instead");
            return;
        }
        player.controls().stop();
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.fallDistance = 0.0F;
        flying = true;
    }

    @Override
    protected TaskState onTick() {
        if (!flying) {
            return TaskState.FAILED;
        }
        // 能力位可能被外部切换(主人换模式),每刻确认一次,掉下来就如实收场
        if (!player.getAbilities().mayfly) {
            fail("lost flight while flying: the game mode changed", FailureType.UNSUPPORTED, null);
            return TaskState.FAILED;
        }
        player.getAbilities().flying = true;

        Vec3 target = Vec3.atCenterOf(r.to);
        Vec3 pos = player.position();
        Vec3 delta = target.subtract(pos);
        double distance = delta.length();
        if (distance <= ARRIVE) {
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
            succeed();
            return TaskState.SUCCESS;
        }
        // 一步最多 1 格,末段自动收小,避免冲过头来回摆
        double stepLength = Math.min(r.speed, 1.0);
        Vec3 step = delta.scale(Math.min(stepLength / distance, 1.0));
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.move(MoverType.SELF, step);
        player.hurtMarked = true;
        return TaskState.RUNNING;
    }

    @Override
    protected Object value() {
        return new Flew("flew to " + r.to.toShortString(), player.position());
    }

    @Override
    protected String successMessage() {
        return "flew to " + r.to.getX() + "," + r.to.getY() + "," + r.to.getZ();
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.hurtMarked = true;
    }
}
