// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端的一帧 ROM 分片。
 *
 * <p>{@code index == 0} 表示这批发完还有 {@code total} 片；客户端据此显示进度。
 * 分片大小上限 {@value #MAX_CHUNK}，和 MC 自定义包上限留足余量。
 */
public record RomChunkPayload(String core, String name, int index, int total, long size, byte[] data)
        implements CustomPacketPayload {

    public static final int MAX_CHUNK = 24 * 1024;

    public static final Type<RomChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "rom_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomChunkPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.core, 16);
                        buf.writeUtf(p.name, 200);
                        buf.writeVarInt(p.index);
                        buf.writeVarInt(p.total);
                        buf.writeVarLong(p.size);
                        buf.writeVarInt(p.data.length);
                        buf.writeBytes(p.data);
                    },
                    buf -> {
                        String core = buf.readUtf(16);
                        String name = buf.readUtf(200);
                        int index = buf.readVarInt();
                        int total = buf.readVarInt();
                        long size = buf.readVarLong();
                        int len = buf.readVarInt();
                        byte[] data = new byte[len];
                        buf.readBytes(data);
                        return new RomChunkPayload(core, name, index, total, size, data);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
