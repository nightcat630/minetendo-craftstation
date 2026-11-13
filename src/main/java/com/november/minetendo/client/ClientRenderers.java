// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.Minetendo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 客户端渲染注册：CRT 电视的 BER。 */
@EventBusSubscriber(modid = Minetendo.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientRenderers {
    private ClientRenderers() {}
    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        // 电视屏幕改由 TvScreenLayer（RenderLevelStageEvent）绘制，与参考项目一致
    }
}