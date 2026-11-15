// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.common.Core;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * 客户端界面入口。
 *
 * <p>方块/物品现在住在 {@code common} 包里（服务端也要能加载它们），
 * 但它们被右键时要开界面、切手柄——那些是纯客户端行为。
 * 统一收在这里，common 侧只在 {@code level.isClientSide} 分支里调用，
 * 专用服务端不会去解析这个类。
 */
public final class ClientUi {

    private ClientUi() {}

    /** 打开选游戏界面（方块/物品右键） */
    public static void openPicker(Core core, BlockPos seatPos, boolean inWorld) {
        Minecraft.getInstance().setScreen(new RomPickerScreen(core, null, inWorld, seatPos));
    }

    /** 手柄右键：切换操作模式 */
    public static boolean toggleGamepad(Player player) {
        return GamepadMode.toggle(player);
    }

    /** 掌机全屏界面 */
    public static void openHandheld(Core core, java.nio.file.Path game) {
        Minecraft.getInstance().setScreen(new EmulatorScreen(core, game));
    }

    /** 右键主机（在世界内）：申请操作席 → 找电视 → 打开选游戏界面 */
    public static void openConsole(com.november.minetendo.common.Core core, BlockPos pos,
                                   net.minecraft.core.Direction facing, Player player) {
        TvSeats.requestControl(pos, () -> {
            if (core == com.november.minetendo.common.Core.SFC
                    || core == com.november.minetendo.common.Core.GBA
                    || core == com.november.minetendo.common.Core.PS2
                    || core == com.november.minetendo.common.Core.PSP) {
                var tv = InWorldSession.activateFromConsole(pos, facing);
                if (tv == null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component
                            .literal("周围没有可用电视（4 格内需要摆一台 CRT 电视）")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                    TvSeats.release(pos);
                    return;
                }
                openPicker(core, pos, true);
            } else {
                openPicker(core, pos, false);
            }
        });
    }

    /** 电视方块实体：登记/注销可见电视 */
    public static void registerTv(BlockPos pos) { InWorldSession.registerTv(pos); }

    public static void unregisterTv(BlockPos pos) { InWorldSession.unregisterTv(pos); }

    /** 找最近的电视（不占用操作席），用于"没有电视就提示"的判断 */
    public static net.minecraft.core.BlockPos findTv(BlockPos consolePos,
                                                     net.minecraft.core.Direction facing) {
        return InWorldSession.activateFromConsole(consolePos, facing);
    }
}
