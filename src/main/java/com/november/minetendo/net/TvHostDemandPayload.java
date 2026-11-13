// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** 服务器 → 宿主：当前有多少人在看这台电视（0 = 可以停止推流）。 */
public record TvHostDemandPayload(BlockPos pos, UUID lease, int watchers) implements CustomPacketPayload {

    public static final Type<TvHostDemandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_host_demand"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvHostDemandPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeUUID(p.lease());
                buf.writeVarInt(p.watchers());
            },
            buf -> new TvHostDemandPayload(BlockPos.STREAM_CODEC.decode(buf), buf.readUUID(), buf.readVarInt()));

    public boolean needed() { return watchers > 0; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}