// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 一帧画面（JPEG）的分片。
 *
 * <p>MC 的自定义包上限约 1MB，所以一帧要切片：{@code chunkIndex} / {@code chunkCount}。
 * 观战端收到全部切片后拼起来解码；{@code seq} 变化说明上一帧没凑齐，直接丢弃。
 */
public record TvFramePayload(BlockPos pos, int seq, int chunkIndex, int chunkCount, byte[] data)
        implements CustomPacketPayload {

    /** 单片最大字节数（留足余量给包头） */
    public static final int MAX_CHUNK = 24 * 1024;

    public static final Type<TvFramePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_frame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvFramePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TvFramePayload::pos,
            ByteBufCodecs.VAR_INT, TvFramePayload::seq,
            ByteBufCodecs.VAR_INT, TvFramePayload::chunkIndex,
            ByteBufCodecs.VAR_INT, TvFramePayload::chunkCount,
            ByteBufCodecs.byteArray(MAX_CHUNK + 1024), TvFramePayload::data,
            TvFramePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
