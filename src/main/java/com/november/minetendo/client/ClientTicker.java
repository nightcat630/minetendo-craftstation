// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.Minetendo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** 客户端 tick：驱动世界内会话（电视画面持续更新）。 */
@EventBusSubscriber(modid = Minetendo.MOD_ID, value = Dist.CLIENT)
public final class ClientTicker {
    private ClientTicker() {}
    @SubscribeEvent
    public static void onInteractionKey(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        GamepadMode.onInteractionKey(event);
    }

    @SubscribeEvent
    public static void onMouse(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        GamepadMode.onMouseButton(event);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        InWorldSession.tick();
        // 联机电视流：宿主推流 / 观战端解码上传 / 超时清理
        TvStreamPublisher.tick();
        TvStreamClient.pump();
        TvStreamClient.keepAlive();
        TvSeats.tick();
        // 手柄操作模式：切物品/开界面自动断开；并清空原版按键队列（保证我们的优先级）
        GamepadMode.tick();
        GamepadMode.drainVanillaKeys();
        if (net.minecraft.client.Minecraft.getInstance().level == null) TvSeats.clear();
        if (net.minecraft.client.Minecraft.getInstance().level == null) {
            TvStreamClient.clear();
        } else if ((System.currentTimeMillis() / 1000) % 5 == 0) {
            TvStreamClient.prune();
        }
    }
}