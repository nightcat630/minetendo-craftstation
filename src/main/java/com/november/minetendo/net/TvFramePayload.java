// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 一帧画面（JPEG）的分片。
 *
 * <p>{@code lease} 必须和当前会话一致，否则丢弃（防止重开一局后旧帧串进新画面）。
 * MC 自定义包上限约 1MB，所以一帧要切片：{@code chunkIndex} / {@code chunkCount}。
 */
public record TvFramePayload(BlockPos pos, UUID lease, int seq, int chunkIndex, int chunkCount, byte[] data)
        implements CustomPacketPayload {

    /** 单片最大字节数（留足余量给包头） */
    public static final int MAX_CHUNK = 24 * 1024;

    public static final Type<TvFramePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_frame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvFramePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeUUID(p.lease());
                buf.writeVarInt(p.seq());
                buf.writeVarInt(p.chunkIndex());
                buf.writeVarInt(p.chunkCount());
                buf.writeVarInt(p.data().length);
                buf.writeBytes(p.data());
            },
            buf -> {
                BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                UUID lease = buf.readUUID();
                int seq = buf.readVarInt();
                int idx = buf.readVarInt();
                int cnt = buf.readVarInt();
                int len = buf.readVarInt();
                byte[] data = new byte[len];
                buf.readBytes(data);
                return new TvFramePayload(pos, lease, seq, idx, cnt, data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}