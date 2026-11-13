// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端请求服务端某个机种的 ROM 清单。 */
public record RomListRequestPayload(String core) implements CustomPacketPayload {

    public static final Type<RomListRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "rom_list_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomListRequestPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeUtf(p.core, 16),
                    buf -> new RomListRequestPayload(buf.readUtf(16)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
