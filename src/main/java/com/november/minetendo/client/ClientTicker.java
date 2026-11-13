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
    public static void onTick(ClientTickEvent.Post event) {
        InWorldSession.tick();
    }
}