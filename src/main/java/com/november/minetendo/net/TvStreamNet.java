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
 * <p>为什么走服务器中转而不是 P2P/HTTP：
 * <ul>
 *   <li>玩家之间通常互不可达（NAT），直连要打洞或开端口；</li>
 *   <li>服务器天然知道"谁在电视附近"，可以只发给需要的人，避免广播浪费；</li>
 *   <li>权限也简单：服务器可以按距离/人数限流。</li>
 * </ul>
 *
 * <p>带宽：一帧 JPEG 320×240 质量 0.5 约 10~15KB，默认 12fps ≈ 150KB/s/观众。
 * 只发给 {@value #RADIUS} 格内的玩家。
 */
public final class TvStreamNet {

    /** 只转发给电视这个半径内的玩家（格） */
    public static final double RADIUS = 64.0;

    /** 每个玩家每秒最多接受的字节（软限流，防止刷爆） */
    private static final int MAX_BYTES_PER_SEC = 2 * 1024 * 1024;

    /** 单人调试回环（-Dminetendo.tvLoopback=true） */
    private static final boolean LOOPBACK = Boolean.getBoolean("minetendo.tvLoopback");

    private TvStreamNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playToServer(TvStreamStatePayload.TYPE, TvStreamStatePayload.STREAM_CODEC, TvStreamNet::onStateFromHost);
        reg.playToServer(TvFramePayload.TYPE, TvFramePayload.STREAM_CODEC, TvStreamNet::onFrameFromHost);
        reg.playToClient(TvStreamStatePayload.TYPE, TvStreamStatePayload.STREAM_CODEC, TvStreamNet::onStateToViewer);
        reg.playToClient(TvFramePayload.TYPE, TvFramePayload.STREAM_CODEC, TvStreamNet::onFrameToViewer);
    }

    // ── 服务器侧：收到宿主的包，转发给附近玩家 ──

    private static void onStateFromHost(TvStreamStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer from)) return;
            if (!(from.level() instanceof ServerLevel level)) return;
            relay(level, from, payload.pos(), payload);
        });
    }

    private static void onFrameFromHost(TvFramePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer from)) return;
            if (!(from.level() instanceof ServerLevel level)) return;
            relay(level, from, payload.pos(), payload);
        });
    }

    private static void relay(ServerLevel level, ServerPlayer from, BlockPos pos, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersNear(level, from, pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                RADIUS, payload);
        // 调试：-Dminetendo.tvLoopback=true 时把包也回给宿主，单人世界即可验证
        // 编码 / 分片 / 解码 / 上传 整条链路（正式多人时不要开）。
        if (LOOPBACK) {
            PacketDistributor.sendToPlayer(from, payload);
        }
    }

    // ── 客户端侧：宿主自己也会收到中继回来的包，忽略即可 ──

    private static void onStateToViewer(TvStreamStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> TvStreamClient.onState(payload));
    }

    private static void onFrameToViewer(TvFramePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> TvStreamClient.onFrame(payload));
    }
}
