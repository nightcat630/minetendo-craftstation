// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import com.november.minetendo.client.TvStreamClient;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 电视串流的网络层：宿主 → 服务器 → 附近观众。
 *
 * <p>设计参照 PIQ 的 WatchService（GPL-3.0-or-later，结构可借鉴）：
 * <ul>
 *   <li><b>租约（lease）</b>：每次开播一个新的 UUID，帧包带 lease，观众只认当前 lease，
 *       旧会话的包直接丢 —— 解决"重开一局画面串台"</li>
 *   <li><b>需求（demand）</b>：观众发 {@link TvWatchInterestPayload}，服务器统计人数，
 *       只在人数变化或每 2 秒发一次 {@link TvHostDemandPayload} 给宿主；
 *       人数为 0 时宿主停止推帧（保留 announce）—— 没人看就不烧带宽</li>
 *   <li><b>预算（budget）</b>：每个观众每秒最多 {@value #BUDGET_BYTES_PER_SEC} 字节，
 *       超了直接丢帧而不是排队（宁可花屏也不要把服务器上行压爆）</li>
 * </ul>
 *
 * <p>带宽：一帧 JPEG 320×240 q0.5 约 10~15KB，12fps ≈ 150KB/s/观众，只发给 {@value #RADIUS} 格内的玩家。
 */
public final class TvStreamNet {

    public static final double RADIUS = 64.0;

    /** 每个观众每秒能收到的字节上限 */
    public static final int BUDGET_BYTES_PER_SEC = 512 * 1024;

    /** 观众心跳超时（毫秒）；客户端每 2 秒重发一次兴趣 */
    private static final long VIEWER_TIMEOUT_MS = 6000;

    /** 宿主 demand 重发间隔（毫秒） */
    private static final long DEMAND_REFRESH_MS = 2000;

    /** 单人调试回环（-Dminetendo.tvLoopback=true） */
    private static final boolean LOOPBACK = Boolean.getBoolean("minetendo.tvLoopback");

    /** 客户端接收器（只在客户端赋值） */
    private static volatile Consumer<net.minecraft.network.protocol.common.custom.CustomPacketPayload> clientSink;

    public static void setClientSink(Consumer<net.minecraft.network.protocol.common.custom.CustomPacketPayload> sink) {
        clientSink = sink;
    }

    // ── 服务端状态 ──

    private static final class Source {
        Source(BlockPos pos, UUID host) {
            this.pos = pos;
            this.host = host;
        }

        final BlockPos pos;
        final UUID host;
        UUID lease;
        int phase;
        int width, height;
        final Map<UUID, Long> viewers = new HashMap<>();   // 观众 → 最后心跳时间
        int lastDemand = -1;
        long lastDemandSent;
        long budgetWindowMs;
        int budgetUsed;
    }

    /** level.dimension() → pos → source */
    private static final Map<Level, Map<BlockPos, Source>> SOURCES = new HashMap<>();

    private TvStreamNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playBidirectional(TvStreamStatePayload.TYPE, TvStreamStatePayload.STREAM_CODEC, TvStreamNet::onState);
        reg.playBidirectional(TvFramePayload.TYPE, TvFramePayload.STREAM_CODEC, TvStreamNet::onFrame);
        reg.playToServer(TvWatchInterestPayload.TYPE, TvWatchInterestPayload.STREAM_CODEC, TvStreamNet::onInterest);
        reg.playToClient(TvHostDemandPayload.TYPE, TvHostDemandPayload.STREAM_CODEC, TvStreamNet::onDemand);
    }

    // ── 宿主 → 服务器 / 服务器 → 观众 ──

    private static void onState(TvStreamStatePayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer from) {
            ctx.enqueueWork(() -> hostState(from, p));
        } else {
            ctx.enqueueWork(() -> sink(p));
        }
    }

    private static void onFrame(TvFramePayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer from) {
            ctx.enqueueWork(() -> hostFrame(from, p));
        } else {
            ctx.enqueueWork(() -> sink(p));
        }
    }

    private static void onInterest(TvWatchInterestPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer from)) return;
        ctx.enqueueWork(() -> interest(from, p));
    }

    private static void onDemand(TvHostDemandPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> sink(p));
    }

    private static void sink(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Consumer<net.minecraft.network.protocol.common.custom.CustomPacketPayload> s = clientSink;
        if (s != null) s.accept(payload);
    }

    // ── 服务端逻辑 ──

    private static Map<BlockPos, Source> levelMap(Level level) {
        return SOURCES.computeIfAbsent(level, k -> new HashMap<>());
    }

    private static void hostState(ServerPlayer from, TvStreamStatePayload p) {
        if (!(from.level() instanceof ServerLevel level)) return;
        Map<BlockPos, Source> map = levelMap(level);

        if (p.phase() == TvStreamStatePayload.STOP) {
            Source s = map.get(p.pos());
            if (s != null && s.host.equals(from.getUUID()) && s.lease.equals(p.lease())) {
                map.remove(p.pos());
                relay(level, p.pos(), from, p);
                sendDemand(from, p.pos(), p.lease(), 0);
                System.out.println("[Minetendo] 电视流结束: " + p.pos().toShortString());
            }
            return;
        }

        Source s = map.get(p.pos());
        if (s == null || !s.host.equals(from.getUUID()) || !s.lease.equals(p.lease())) {
            s = new Source(p.pos(), from.getUUID());
            s.lease = p.lease();
            s.phase = p.phase();
            s.width = p.width();
            s.height = p.height();
            map.put(p.pos(), s);
            s.lastDemand = 0;
            if (p.phase() == TvStreamStatePayload.ANNOUNCE) {
                System.out.println("[Minetendo] 电视流宣告: " + p.pos().toShortString()
                        + "  " + p.width() + "x" + p.height());
            }
        }
        s.phase = p.phase();
        s.width = p.width();
        s.height = p.height();
        relay(level, p.pos(), from, p);
        // 有人看的话立刻回一个 demand，宿主不用等下一轮
        sendDemand(from, p.pos(), s.lease, countViewers(s));
    }

    private static void interest(ServerPlayer viewer, TvWatchInterestPayload p) {
        if (!(viewer.level() instanceof ServerLevel level)) return;
        Source s = levelMap(level).get(p.pos());
        if (s == null) return;
        if (s.host.equals(viewer.getUUID())) return;          // 宿主自己不算观众
        if (!withinRange(viewer, p.pos())) {                   // 太远直接忽略
            s.viewers.remove(viewer.getUUID());
            return;
        }
        if (p.watching()) {
            boolean isNew = !s.viewers.containsKey(viewer.getUUID());
            s.viewers.put(viewer.getUUID(), System.currentTimeMillis());
            if (isNew) demand(level, s);
        } else {
            if (s.viewers.remove(viewer.getUUID()) != null) demand(level, s);
        }
    }

    private static void hostFrame(ServerPlayer from, TvFramePayload p) {
        if (!(from.level() instanceof ServerLevel level)) return;
        Source s = levelMap(level).get(p.pos());
        if (s == null || !s.host.equals(from.getUUID()) || !s.lease.equals(p.lease())) return;
        if (s.phase != TvStreamStatePayload.STREAMING) return;

        pruneViewers(level, s);
        if (s.viewers.isEmpty()) return;                       // 没人看：丢帧，宿主该被 demand=0 叫停

        int bytes = p.data().length + 32;
        List<ServerPlayer> targets = new ArrayList<>();
        for (UUID id : s.viewers.keySet()) {
            ServerPlayer viewer = level.getServer().getPlayerList().getPlayer(id);
            if (viewer != null && withinRange(viewer, p.pos())) targets.add(viewer);
        }
        for (ServerPlayer viewer : targets) {
            if (charge(s, bytes)) {
                PacketDistributor.sendToPlayer(viewer, p);
            }
        }
        demand(level, s);
    }

    /** 每人每秒预算：超了这秒就丢，不排队。 */
    private static boolean charge(Source s, int bytes) {
        long now = System.currentTimeMillis();
        if (now - s.budgetWindowMs >= 1000) {
            s.budgetWindowMs = now;
            s.budgetUsed = 0;
        }
        if (s.budgetUsed + bytes > BUDGET_BYTES_PER_SEC) return false;
        s.budgetUsed += bytes;
        return true;
    }

    private static int countViewers(Source s) {
        return s.viewers.size();
    }

    private static void pruneViewers(ServerLevel level, Source s) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = s.viewers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null || now - e.getValue() > VIEWER_TIMEOUT_MS || !withinRange(p, s.pos)) {
                it.remove();
            }
        }
    }

    /** 人数变化或到刷新点时，通知宿主"当前 N 人在看"。 */
    private static void demand(ServerLevel level, Source s) {
        pruneViewers(level, s);
        int count = countViewers(s);
        long now = System.currentTimeMillis();
        boolean changed = count != s.lastDemand;
        if (changed || now - s.lastDemandSent >= DEMAND_REFRESH_MS) {
            s.lastDemand = count;
            s.lastDemandSent = now;
            ServerPlayer host = level.getServer().getPlayerList().getPlayer(s.host);
            if (host != null) sendDemand(host, s.pos, s.lease, count);
        }
    }

    private static void sendDemand(ServerPlayer host, BlockPos pos, UUID lease, int watchers) {
        PacketDistributor.sendToPlayer(host, new TvHostDemandPayload(pos, lease, watchers));
    }

    private static boolean withinRange(ServerPlayer p, BlockPos pos) {
        return p.blockPosition().distSqr(pos) <= RADIUS * RADIUS;
    }

    /** 广播给附近玩家（排除宿主），用于 ANNOUNCE/STREAMING/STOP。 */
    private static void relay(ServerLevel level, BlockPos pos, ServerPlayer from,
                              net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersNear(level, from, pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                RADIUS, payload);
        if (LOOPBACK) {
            PacketDistributor.sendToPlayer(from, payload);
        }
    }

    /** 服务器关闭/玩家退出时清理（由 ClientTicker 之外的服务端 tick 调用也可以，这里做兜底）。 */
    public static void forget(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Map<BlockPos, Source> map = SOURCES.get(level);
        if (map == null) return;
        for (Source s : map.values()) {
            s.viewers.remove(player.getUUID());
            if (s.host.equals(player.getUUID())) {
                s.phase = TvStreamStatePayload.STOP;
            }
        }
        map.entrySet().removeIf(e -> e.getValue().host.equals(player.getUUID()));
    }
}
