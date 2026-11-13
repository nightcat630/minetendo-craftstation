// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minetendo —— 1.21.1 / NeoForge 入口（重写版）。
 *
 * <p>这一版是"清干净重来"：只有 3DS 一台掌机，注册表、分类页各只有一份，
 * 不再有历史遗留的双重注册。
 *
 * <p>原生层是会话化的（{@code src/native/minetendo_host.cpp}）：
 * 每个模拟器会话自带独立 GL 上下文与 FBO，Java 侧通过 {@link SessionHost} 以 token 驱动。
 */
@Mod(Minetendo.MOD_ID)
public final class Minetendo {

    public static final String MOD_ID = "minetendo";
    public static final Logger LOGGER = LoggerFactory.getLogger("Minetendo");

    /** 唯一的创造模式分类页。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.minetendo"))
                    .icon(() -> com.november.minetendo.client.CrtTvRegistry.NDS3_CONSOLE_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(com.november.minetendo.client.CrtTvRegistry.SFC_CONSOLE_ITEM.get());
                        output.accept(com.november.minetendo.client.CrtTvRegistry.GBA_CONSOLE_ITEM.get());
                        output.accept(com.november.minetendo.client.CrtTvRegistry.NDS_CONSOLE_ITEM.get());
                        output.accept(com.november.minetendo.client.CrtTvRegistry.NDS3_CONSOLE_ITEM.get());
                        output.accept(com.november.minetendo.client.CrtTvRegistry.PS2_CONSOLE_ITEM.get());
                        output.accept(com.november.minetendo.client.CrtTvRegistry.PSP_CONSOLE_ITEM.get());
                        output.accept(com.november.minetendo.client.CrtTvRegistry.TV_ITEM.get());
                    })
                    .build());

    public Minetendo(ModContainer container, IEventBus modBus) {
        // 首次启动自解包：核心 / BIOS / PPSSPP 资源 / 目录骨架（让 jar 丢进 mods 即可用）
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.november.minetendo.client.RuntimeBootstrap.install();
            com.november.minetendo.client.RomLibrary.init();
            com.november.minetendo.net.TvStreamNet.setClientSink(p -> {
                if (p instanceof com.november.minetendo.net.TvStreamStatePayload s) com.november.minetendo.client.TvStreamClient.onState(s);
                else if (p instanceof com.november.minetendo.net.TvFramePayload f) com.november.minetendo.client.TvStreamClient.onFrame(f);
                else if (p instanceof com.november.minetendo.net.TvHostDemandPayload d) com.november.minetendo.client.TvStreamPublisher.onDemand(d);
                else if (p instanceof com.november.minetendo.net.TvSeatStatePayload s) com.november.minetendo.client.TvSeats.onState(s);
            });
            com.november.minetendo.net.TvSeatNet.setClientSink(p -> {
                if (p instanceof com.november.minetendo.net.TvSeatStatePayload s) com.november.minetendo.client.TvSeats.onState(s);
            });
        }
        modBus.addListener(com.november.minetendo.net.TvSeatNet::register);
        modBus.addListener(com.november.minetendo.net.RomServerNet::register);
        modBus.addListener(com.november.minetendo.net.TvStreamNet::register);
        MinetendoItems.register(modBus);
        com.november.minetendo.client.CrtTvRegistry.register(modBus);
        // 服务端 tick：清理过期操作席（与客户端无关，所有环境都挂）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.november.minetendo.net.SeatServerTicker::onTick);
        // 电视投屏：手动挂到游戏事件总线（RenderLevelStageEvent 属于游戏总线）
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.november.minetendo.client.TvScreenLayer::onRender);
        }
        TABS.register(modBus);
        LOGGER.info("[Minetendo] v{} 就绪（3DS，会话化原生层）",
                container.getModInfo().getVersion());
    }
}
