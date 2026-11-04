package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * {@code numen.move.flyTo} 的任务描述:飞到某个坐标,走直线,不管地形。
 *
 * <p>和 {@code move.to} 的区别:那条路是地面寻路、要一份计划;这条只管空中直线前进,
 * 只在身体允许飞行(创造模式/mayfly)时受理。速度按"每刻几格"记,0.5 约等于创造模式飞行。
 */
public final class FlyToTaskRecord extends TaskRecord {

    /** 兜底期限:按路程算出来的秒数之外再留一点余量,由 {@link FlyToCompanionTask} 用。 */
    public final BlockPos to;
    /** 每刻移动的格数。 */
    public final double speed;

    public FlyToTaskRecord(ServerCall source, BlockPos to, double speed, int seconds) {
        super(source, source.her().level().getGameTime() + seconds * 20L);
        this.to = to;
        this.speed = speed;
    }

    /** 给主人看的一行人话(面板、气泡、task status 都印它)。 */
    @Override
    public String describe() {
        return "飞到 " + to.getX() + "," + to.getY() + "," + to.getZ();
    }
}
