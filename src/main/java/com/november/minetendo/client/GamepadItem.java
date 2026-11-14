// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 游戏手柄（物品）。
 *
 * <p>作用：**只有拿着它并且右键进入"操作模式"时**，才能控制电视上正在跑的游戏。
 * 这样和别的模组混装时，电视操作的按键优先级高于其他模组的按键；不拿手柄就完全不能操作。
 *
 * <p>进入：手持右键。退出：再右键一次，或把主手切到别的物品（自动退出）。
 * 全屏游玩（拿掌机右键开全屏）不受此限制。
 */
public class GamepadItem extends Item {

    public GamepadItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            boolean now = GamepadMode.toggle(player);
            player.displayClientMessage(
                    Component.literal(now
                                    ? "手柄已连接：现在可以操作电视上的游戏（再按右键断开）"
                                    : "手柄已断开：无法操作电视上的游戏")
                            .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
