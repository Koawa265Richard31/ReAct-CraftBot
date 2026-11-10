package com.dwinovo.numen.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/** 单测的空壳方块能力读取:什么都读不到。只为让 {@code Services} 装得上。 */
public final class TestBlockCapabilityReader implements com.dwinovo.numen.platform.services.IBlockCapabilityReader {

    @Override
    public List<String> describe(Level level, BlockPos pos) {
        return List.of();
    }
}
