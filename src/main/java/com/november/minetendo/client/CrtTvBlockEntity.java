// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** CRT 电视方块实体：把自身位置登记给投屏渲染层（参考项目也是按方块坐标绘制）。 */
public class CrtTvBlockEntity extends BlockEntity {

    public CrtTvBlockEntity(BlockPos pos, BlockState state) {
        super(CrtTvRegistry.TV_BE.get(), pos, state);
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide) InWorldSession.registerTv(getBlockPos());
    }

    @Override
    public void setRemoved() {
        InWorldSession.unregisterTv(getBlockPos());
        super.setRemoved();
    }
}