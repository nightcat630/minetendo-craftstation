// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 电视流的状态包（宿主 → 服务器 → 附近观众）。
 *
 * <p>{@code lease} 是这一路流的"租约"：宿主每次开播换一个新 UUID，观众只认当前 lease 的帧，
 * 旧会话残留的包会被直接丢弃 —— 这是"不串台"的根本手段（没有它，重开一局后旧帧会混进新画面）。
 *
 * <p>{@code phase}：
 * <ul>
 *   <li>{@link #ANNOUNCE} 宿主宣告"我这台电视有画面"，观众收到后回 {@code TvWatchInterest}</li>
 *   <li>{@link #STREAMING} 宿主开始推帧（只有服务器告诉它有人看才会进这个阶段）</li>
 *   <li>{@link #STOP} 结束，观众释放纹理</li>
 * </ul>
 */
public record TvStreamStatePayload(BlockPos pos, UUID lease, int phase, int width, int height)
        implements CustomPacketPayload {

    public static final int ANNOUNCE = 0;
    public static final int STREAMING = 1;
    public static final int STOP = 2;

    public static final Type<TvStreamStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_stream_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvStreamStatePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeUUID(p.lease());
                buf.writeVarInt(p.phase());
                buf.writeVarInt(p.width());
                buf.writeVarInt(p.height());
            },
            buf -> new TvStreamStatePayload(BlockPos.STREAM_CODEC.decode(buf), buf.readUUID(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
