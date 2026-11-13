// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 电视流的"状态包"：某个电视开始/停止串流，以及画面尺寸。
 *
 * <p>联机时宿主客户端跑模拟器，别的玩家没有 ROM；宿主把画面压成 JPEG 流经服务器中继，
 * 观战端只要收到这个状态包就知道该给哪台电视建纹理、建多大。
 */
public record TvStreamStatePayload(BlockPos pos, boolean active, int width, int height) implements CustomPacketPayload {

    public static final Type<TvStreamStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_stream_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvStreamStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TvStreamStatePayload::pos,
            ByteBufCodecs.BOOL, TvStreamStatePayload::active,
            ByteBufCodecs.VAR_INT, TvStreamStatePayload::width,
            ByteBufCodecs.VAR_INT, TvStreamStatePayload::height,
            TvStreamStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
