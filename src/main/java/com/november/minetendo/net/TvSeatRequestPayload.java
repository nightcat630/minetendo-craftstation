// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 席位请求：玩家请求"操作席"或"观看席"。
 *
 * <p>席位模型（借鉴 PIQ 的 room → seat → lease 思路）：
 * <ul>
 *   <li><b>操作席</b>（CONTROL）：一台机器同时只允许一个人操作，拿席的人才能启动会话、
 *       才能把自己的键盘输入送进核心。别人拿不到，只能看。</li>
 *   <li><b>观看席</b>（WATCH）：只读，能收画面，不能操作、不需要 ROM。</li>
 * </ul>
 *
 * <p>{@code action}：
 * {@link #TAKE} 申请/续期操作席、{@link #RELEASE} 释放、{@link #QUERY} 只查询状态。
 */
public record TvSeatRequestPayload(BlockPos pos, int action) implements CustomPacketPayload {

    public static final int TAKE = 0;
    public static final int RELEASE = 1;
    public static final int QUERY = 2;

    public static final Type<TvSeatRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_seat_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvSeatRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeVarInt(p.action());
            },
            buf -> new TvSeatRequestPayload(BlockPos.STREAM_CODEC.decode(buf), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
