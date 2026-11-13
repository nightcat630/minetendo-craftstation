// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** CRT 电视（与 1.20 的 CrtTvBlock 对齐：2×2 碰撞盒、MODEL 渲染、屏幕由 BER 贴图）。 */
public class CrtTvBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final MapCodec<CrtTvBlock> CODEC = simpleCodec(CrtTvBlock::new);

    public CrtTvBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // 与 crt_tv.obj 实测包围盒一致（X 0..2、Y 0..2、Z -0.67..1.059），并跟随 FACING 旋转
        return BlockShapes.rotate(0.0, 0.0, -0.67, 2.0, 2.0, 1.059, Direction.SOUTH, facing(state));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CrtTvBlockEntity(pos, state); }

    public static Direction facing(BlockState state) { return state.getValue(FACING); }

    /**
     * 右键电视：**只**给提示，不激活任何东西。
     *
     * <p>电视是纯显示器 —— 谁出画面由主机决定（主机开机时按"相邻/最近/同距前方优先"
     * 的规则挑一台电视）。这样电视不与任何机种绑定，以后 GBA/3DS 做成主机方块时
     * 也用同一套规则，不必改电视。
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
                                                                  BlockPos pos, net.minecraft.world.entity.player.Player player,
                                                                  net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("电视是显示器：请对旁边的 SFC 主机右键开机")
                            .withStyle(net.minecraft.ChatFormatting.GRAY), true);
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
}
