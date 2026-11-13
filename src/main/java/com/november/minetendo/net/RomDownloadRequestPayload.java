// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端请求下载服务端某个 ROM（按文件名）。 */
public record RomDownloadRequestPayload(String core, String name) implements CustomPacketPayload {

    public static final Type<RomDownloadRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "rom_download_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomDownloadRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(16), RomDownloadRequestPayload::core,
                    ByteBufCodecs.stringUtf8(200), RomDownloadRequestPayload::name,
                    RomDownloadRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
