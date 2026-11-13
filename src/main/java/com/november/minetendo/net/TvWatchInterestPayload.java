// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 观众告诉服务器"我在看/不看这台电视"。
 *
 * <p>服务器据此统计需求人数并转给宿主 —— 没人看时宿主就不推流，这是省带宽的关键。
 * 客户端每 2 秒重发一次（watching=true）当作心跳，服务器 6 秒没收到就算离开。
 */
public record TvWatchInterestPayload(BlockPos pos, boolean watching) implements CustomPacketPayload {

    public static final Type<TvWatchInterestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_watch_interest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvWatchInterestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeBoolean(p.watching());
            },
            buf -> new TvWatchInterestPayload(BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}