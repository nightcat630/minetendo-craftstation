// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import com.november.minetendo.client.TvStreamClient;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 电视串流的网络层：宿主客户端 → 服务器 → 附近观战客户端。
 *
 * <p><b>注意</b>：同一个 payload 类型只能注册一次，不能既 {@code playToServer} 又
 * {@code playToClient} —— 那会抛 {@code Cannot register payload ... already registered}。
 * 双向包必须用 {@code playBidirectional}，在同一个处理函数里按"我是不是服务端"分流。
 *
 * <p>带宽：一帧 JPEG 320×240 质量 0.5 约 10~15KB，默认 12fps ≈ 150KB/s/观众，
 * 只发给 {@value #RADIUS} 格内的玩家。
 */
public final class TvStreamNet {

    /** 只转发给电视这个半径内的玩家（格） */
    public static final double RADIUS = 64.0;

    /** 单人调试回环（-Dminetendo.tvLoopback=true） */
    private static final boolean LOOPBACK = Boolean.getBoolean("minetendo.tvLoopback");

    private TvStreamNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playBidirectional(TvStreamStatePayload.TYPE, TvStreamStatePayload.STREAM_CODEC, TvStreamNet::onState);
        reg.playBidirectional(TvFramePayload.TYPE, TvFramePayload.STREAM_CODEC, TvStreamNet::onFrame);
    }

    // ── 同一个处理函数，一半是服务端中继、一半是客户端接收 ──

    private static void onState(TvStreamStatePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer from) {
            context.enqueueWork(() -> relay(from, payload.pos(), payload));
        } else {
            context.enqueueWork(() -> TvStreamClient.onState(payload));
        }
    }

    private static void onFrame(TvFramePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer from) {
            context.enqueueWork(() -> relay(from, payload.pos(), payload));
        } else {
            context.enqueueWork(() -> TvStreamClient.onFrame(payload));
        }
    }

    private static void relay(ServerPlayer from, BlockPos pos, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (!(from.level() instanceof ServerLevel level)) return;
        PacketDistributor.sendToPlayersNear(level, from, pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                RADIUS, payload);
        // 调试：-Dminetendo.tvLoopback=true 时把包也回给宿主，单人世界即可验证
        // 编码 / 分片 / 解码 / 上传 整条链路（正式多人时不要开）。
        if (LOOPBACK) {
            PacketDistributor.sendToPlayer(from, payload);
        }
    }
}
