// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

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

/** SFC 主机方块（与 1.20 的 SfcConsoleBlock 对齐：facing、窄碰撞盒、右键插卡）。 */
public class SfcConsoleBlock extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public SfcConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.box(0.0, 0.0, 0.12, 1.0, 0.65, 0.88);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            var tv = InWorldSession.activateFromConsole(pos, state.getValue(FACING));
            if (tv == null) {
                // 规定：主机必须投屏到电视；周围没有电视就不让开局，只在物品栏上方提示
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("周围没有可用电视（4 格内需要摆一台 CRT 电视）")
                                .withStyle(net.minecraft.ChatFormatting.RED), true);
                return InteractionResult.SUCCESS;
            }
            net.minecraft.client.Minecraft.getInstance().setScreen(new RomPickerScreen(Core.SFC, null, true));
        } else if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            SfcSeating.sit(sl, pos, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}