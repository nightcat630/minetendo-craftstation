// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 席位服务：谁在操作这台机器，谁只是观众。
 *
 * <p>为什么需要它：现在"谁右键谁开播"，两个人抢同一台电视时会出现两个会话互相顶掉、
 * 或者后开的那个把先开的挤下线。席位模型把这件事变成显式状态：
 *
 * <pre>
 *   Room(电视) ── CONTROL 席（唯一，可租用）── WATCH 席（多人，只读）
 * </pre>
 *
 * <p>规则：
 * <ul>
 *   <li>操作席同时只有一个人；申请成功返回 {@code youHold=true}</li>
 *   <li>持有者每 3 秒续期一次；8 秒没续期、或走远 16 格、或下线 → 自动释放</li>
 *   <li>别人持有期间申请 → 拒绝（回当前持有者名字），客户端提示"X 正在操作"</li>
 *   <li>座位变化广播给 64 格内玩家，客户端可显示"谁在玩 / 几人观看"</li>
 * </ul>
 */
public final class TvSeatNet {

    /** 操作席空闲超时（毫秒） */
    public static final long CONTROL_TIMEOUT_MS = 8000;
    /** 走多远自动放弃操作席 */
    public static final double CONTROL_RADIUS = 16.0;
    /** 状态广播半径 */
    private static final double BROADCAST_RADIUS = 64.0;

    private static final class Room {
        final BlockPos pos;
        UUID holder;
        String holderName = "";
        long lastSeen;
        final Map<UUID, Long> watchers = new HashMap<>();
        int revision;

        Room(BlockPos pos) { this.pos = pos; }
    }

    private static final Map<ServerLevel, Map<BlockPos, Room>> ROOMS = new HashMap<>();
    private static volatile Consumer<CustomPacketPayload> clientSink;

    public static void setClientSink(Consumer<CustomPacketPayload> sink) {
        clientSink = sink;
    }

    private TvSeatNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playToServer(TvSeatRequestPayload.TYPE, TvSeatRequestPayload.STREAM_CODEC, TvSeatNet::onRequest);
        reg.playToClient(TvSeatStatePayload.TYPE, TvSeatStatePayload.STREAM_CODEC, TvSeatNet::onStateClient);
    }

    private static void onStateClient(TvSeatStatePayload p, IPayloadContext ctx) {
        Consumer<CustomPacketPayload> sink = clientSink;
        if (sink != null) ctx.enqueueWork(() -> sink.accept(p));
    }

    // ── 服务端 ──

    private static Map<BlockPos, Room> map(ServerLevel level) {
        return ROOMS.computeIfAbsent(level, k -> new HashMap<>());
    }

    private static void onRequest(TvSeatRequestPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ctx.enqueueWork(() -> handle(player, p));
    }

    private static void handle(ServerPlayer player, TvSeatRequestPayload p) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Map<BlockPos, Room> rooms = map(level);
        Room room = rooms.computeIfAbsent(p.pos(), Room::new);
        long now = System.currentTimeMillis();
        expire(level, room, now);

        switch (p.action()) {
            case TvSeatRequestPayload.TAKE -> {
                boolean freeSeat = room.holder == null || room.holder.equals(player.getUUID());
                if (!freeSeat) {
                    // 被别人占着：只回状态，不改
                    send(level, player, room, player);
                    return;
                }
                room.holder = player.getUUID();
                room.holderName = player.getName().getString();
                room.lastSeen = now;
                room.revision++;
                broadcast(level, room);
            }
            case TvSeatRequestPayload.RELEASE -> {
                if (player.getUUID().equals(room.holder)) {
                    room.holder = null;
                    room.holderName = "";
                    room.revision++;
                    broadcast(level, room);
                } else {
                    send(level, player, room, player);
                }
            }
            default -> send(level, player, room, player);
        }
    }

    /** 超时/走远/下线 → 释放操作席。 */
    private static void expire(ServerLevel level, Room room, long now) {
        if (room.holder == null) return;
        ServerPlayer holder = level.getServer().getPlayerList().getPlayer(room.holder);
        boolean gone = holder == null
                || now - room.lastSeen > CONTROL_TIMEOUT_MS
                || holder.blockPosition().distSqr(room.pos) > CONTROL_RADIUS * CONTROL_RADIUS;
        if (gone) {
            room.holder = null;
            room.holderName = "";
            room.revision++;
            broadcast(level, room);
        }
        Iterator<Map.Entry<UUID, Long>> it = room.watchers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayer viewer = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (viewer == null || now - e.getValue() > TvStreamNet.VIEWER_TIMEOUT_MS
                    || viewer.blockPosition().distSqr(room.pos) > BROADCAST_RADIUS * BROADCAST_RADIUS) {
                it.remove();
            }
        }
    }

    /** 观众计数由 TvStreamNet 的兴趣包维护；这里只读它算 watchers。 */
    public static void noteWatcher(ServerLevel level, BlockPos pos, UUID viewer, boolean watching) {
        Map<BlockPos, Room> rooms = map(level);
        Room room = rooms.computeIfAbsent(pos, Room::new);
        if (watching) room.watchers.put(viewer, System.currentTimeMillis());
        else room.watchers.remove(viewer);
        broadcast(level, room);
    }

    private static void broadcast(ServerLevel level, Room room) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p.level() == level && p.blockPosition().distSqr(room.pos) <= BROADCAST_RADIUS * BROADCAST_RADIUS) {
                send(level, p, room, p);
            }
        }
    }

    private static void send(ServerLevel level, ServerPlayer to, Room room, ServerPlayer self) {
        TvSeatStatePayload payload = new TvSeatStatePayload(room.pos, room.holder, room.holderName,
                room.watchers.size(), self.getUUID().equals(room.holder), room.revision);
        PacketDistributor.sendToPlayer(to, payload);
    }

    /** 玩家下线/换维度时清理。 */
    public static void forget(ServerPlayer player) {
        for (Map<BlockPos, Room> rooms : ROOMS.values()) {
            for (Room room : rooms.values()) {
                if (player.getUUID().equals(room.holder)) {
                    room.holder = null;
                    room.holderName = "";
                    room.revision++;
                }
                room.watchers.remove(player.getUUID());
            }
        }
    }

    /** 服务端 tick 兜底：清理超时席位（由外部 tick 调用）。 */
    public static void tick(ServerLevel level) {
        long now = System.currentTimeMillis();
        Map<BlockPos, Room> rooms = ROOMS.get(level);
        if (rooms == null) return;
        for (Room room : rooms.values()) expire(level, room, now);
    }
}
