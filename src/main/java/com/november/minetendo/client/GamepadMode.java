// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 手柄操作模式：决定"现在这一帧的键盘输入归电视还是归 Minecraft"。
 *
 * <p>为什么需要它：直接抢全局按键会和别的模组冲突（比如 T 开聊天）。这套的做法是：
 * <ul>
 *   <li>**只有**手持手柄并右键进入操作模式，才会把输入喂给电视；空手/收起手柄时电视收不到输入</li>
 *   <li>操作模式下，原版的键盘映射（移动/跳跃/攻击/使用/丢弃/切换副手…）被取消
 *       （{@link InputEvent.InteractionKeyMappingTriggered} 是可取消的）</li>
 *   <li>每 tick 清空所有 {@link KeyMapping} 的点击队列，别的模组用 {@code consumeClick()} 轮询时
 *       读不到输入 —— 这就是"我们的优先级更高"的实现方式</li>
 *   <li>按 ESC 或把主手切到非手柄物品 → 自动退出</li>
 * </ul>
 */
public final class GamepadMode {

    private static boolean active;
    /** 上次检查时主手是否为手柄，用于"切走自动断开" */
    private static boolean watching;

    private GamepadMode() {}

    public static boolean active() { return active; }

    /** 手柄右键：切换。返回切换后的状态。 */
    public static boolean toggle(Player player) {
        if (active) {
            exit();
        } else {
            if (!isHoldingGamepad(player)) return false;
            active = true;
            watching = true;
        }
        return active;
    }

    public static void exit() {
        active = false;
        watching = false;
    }

    private static boolean isHoldingGamepad(Player player) {
        return player != null && player.getMainHandItem().getItem() instanceof GamepadItem;
    }

    /** 客户端每 tick：切物品/换手/开界面 → 退出。 */
    public static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { exit(); return; }
        if (mc.screen != null) { exit(); return; }                 // 打开任何界面（含聊天）就退出
        if (!isHoldingGamepad(mc.player)) { exit(); return; }      // 切到别的物品
    }

    /**
     * 操作模式下每个客户端 tick 调用：把所有 KeyMapping 的点击排队清掉，
     * 让别的模组"看不到"这些按键（我们的输入走 GLFW 原始状态查询，不受影响）。
     */
    public static void drainVanillaKeys() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        for (KeyMapping mapping : mc.options.keyMappings) {
            try {
                while (mapping.consumeClick()) {
                    // 丢掉
                }
                mapping.setDown(false);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 拦截原版按键映射触发的动作（攻击/使用/跳跃/背包/丢弃/交换副手）。
     * 事件可取消，这是"优先级高于其他模组"的关键。
     */
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active) return;
        // 右键"使用物品"必须放行：否则手柄自己的 use() 收不到，永远关不掉操作模式
        if (event.isUseItem()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && isHoldingGamepad(mc.player)) return;
        }
        event.setCanceled(true);
    }

    /** 鼠标左/右键也拦掉（否则会挖方块/用物品）。 */
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active) return;
        // 只拦左键（挖方块/攻击）；右键放行给手柄的 use()
        if (event.getButton() == 1) return;
        event.setCanceled(true);
    }
}
