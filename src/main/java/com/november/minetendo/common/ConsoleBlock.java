// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 游戏机方块（SFC / GBA / NDS / 3DS 通用）。
 *
 * <p>行为：
 * <ul>
 *   <li><b>SFC / GBA</b>：右键开机 → 按规则找相邻最近的电视投屏（没有电视则提示）；</li>
 *   <li><b>NDS / 3DS</b>：有上下两屏，不适合投屏 —— 右键直接全屏游玩（方块本身作装饰）。</li>
 * </ul>
 */
public class ConsoleBlock extends HorizontalDirectionalBlock {

    private final Core core;
    private final VoxelShape shape;

    public ConsoleBlock(Core core, VoxelShape shape, Properties properties) {
        super(properties);
        this.core = core;
        this.shape = shape;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Core core() { return core; }

    public static final com.mojang.serialization.MapCodec<ConsoleBlock> CODEC = simpleCodec(ConsoleBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    /** 供 MapCodec 使用的默认构造（SFC 机身）。 */
    public ConsoleBlock(Properties properties) { this(Core.SFC, flatShape(), properties); }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // 模型零旋转对应 facing=north，碰撞箱跟着 FACING 转
        return BlockShapes.rotate(shape.min(Direction.Axis.X), shape.min(Direction.Axis.Y), shape.min(Direction.Axis.Z),
                shape.max(Direction.Axis.X), shape.max(Direction.Axis.Y), shape.max(Direction.Axis.Z),
                Direction.NORTH, state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide) return InteractionResult.SUCCESS;

        // 席位与界面都在客户端（ClientUi），服务端这里只回成功
        com.november.minetendo.client.ClientUi.openConsole(core, pos, state.getValue(FACING), player);
        return InteractionResult.SUCCESS;
    }

    /** 常用机身碰撞盒。 */
    public static VoxelShape flatShape() { return Shapes.box(0.03, 0.0, 0.03, 0.97, 0.36, 0.97); }
    public static VoxelShape clamshellShape() { return Shapes.box(0.02, 0.0, 0.13, 0.98, 0.54, 0.87); }
}
