// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 服务端返回某个机种的 ROM 清单（文件名 + 字节数）。 */
public record RomListResponsePayload(String core, List<String> names, List<Long> sizes, String error)
        implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 512;
    public static final int MAX_NAME = 200;

    public static final Type<RomListResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "rom_list_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomListResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.core, 16);
                        buf.writeUtf(p.error == null ? "" : p.error, 200);
                        int n = Math.min(p.names.size(), MAX_ENTRIES);
                        buf.writeVarInt(n);
                        for (int i = 0; i < n; i++) {
                            buf.writeUtf(p.names.get(i), MAX_NAME);
                            buf.writeVarLong(p.sizes.get(i));
                        }
                    },
                    buf -> {
                        String core = buf.readUtf(16);
                        String error = buf.readUtf(200);
                        int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
                        List<String> names = new ArrayList<>(n);
                        List<Long> sizes = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            names.add(buf.readUtf(MAX_NAME));
                            sizes.add(buf.readVarLong());
                        }
                        return new RomListResponsePayload(core, names, sizes, error);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
