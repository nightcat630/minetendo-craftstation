// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 游戏机方块物品：
 * <ul>
 *   <li><b>右键</b> → 直接全屏游玩（不放置）；</li>
 *   <li><b>Shift + 右键</b> → 放置到地上（之后右键方块才是投屏/开机）。</li>
 * </ul>
 */
public class ConsoleBlockItem extends BlockItem {

    private final Core core;

    public ConsoleBlockItem(Block block, Core core, Properties properties) {
        super(block, properties);
        this.core = core;
    }

    public Core core() { return core; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            return super.use(level, player, hand);   // Shift：交给原版 → 放置
        }
        // 主机类（PS2）拿在手上不能玩：必须放下并与电视搭配（与 SFC 方块同一套逻辑）
        if (core == Core.PS2) {
            if (level.isClientSide) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("PS2 是主机：Shift+右键放到地上，再右键方块开机（旁边需有 CRT 电视）")
                                .withStyle(net.minecraft.ChatFormatting.RED), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (level.isClientSide) {
            Minecraft.getInstance().setScreen(new RomPickerScreen(core, null, false));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public net.minecraft.world.InteractionResult useOn(UseOnContext context) {
        if (!context.getPlayer().isShiftKeyDown()) {
            if (core == Core.PS2) {
                if (context.getLevel().isClientSide) {
                    context.getPlayer().displayClientMessage(
                            net.minecraft.network.chat.Component.literal("PS2 是主机：Shift+右键放置")
                                    .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                return net.minecraft.world.InteractionResult.sidedSuccess(context.getLevel().isClientSide);
            }
            if (context.getLevel().isClientSide) {
                Minecraft.getInstance().setScreen(new RomPickerScreen(core, null, false));
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        return super.useOn(context);
    }
}
