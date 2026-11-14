// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 横向平板液晶电视：4 格宽 × 3 格高 × 1 格厚（含背部支架）。
 *
 * <p>模型空间（方块坐标，由 {@code lcd_tv.obj} 实测）：
 * <pre>
 *   机身  X -1.5..2.5（以方块中心 0.5 为轴，转方向不偏移）   Y 0..3   Z -0.020..0.980
 *   屏幕  中心 X 0.5000  Y 1.6562  半宽 1.8750  半高 1.0547  玻璃面 Z 0.7425
 * </pre>
 * 屏幕是 16:9（3.75 × 2.11 格），投屏按核心比例 contain 缩放，4:3 的 PS2 画面会左右留黑边。
 */
public class LcdTvBlock extends CrtTvBlock {

    public static final MapCodec<LcdTvBlock> CODEC_LCD = simpleCodec(LcdTvBlock::new);

    public LcdTvBlock(Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return (MapCodec) CODEC_LCD;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // 与 lcd_tv.obj 实测包围盒一致（X 0..4、Y 0..3、Z -0.02..0.98）
        return BlockShapes.rotate(-1.5, 0.0, -0.02, 2.5, 3.0, 0.98, Direction.SOUTH, facing(state));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CrtTvBlockEntity(pos, state);
    }

    // ── 屏幕矩形（LCD 实测）──
    @Override public float screenCenterX() { return 0.5000f; }
    @Override public float screenCenterY() { return 1.6562f; }
    @Override public float screenHalfW() { return 1.8750f; }
    @Override public float screenHalfH() { return 1.0547f; }
    @Override public float screenFaceZ() { return 0.7460f; }
}