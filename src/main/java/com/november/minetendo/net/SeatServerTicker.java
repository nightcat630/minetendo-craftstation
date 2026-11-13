// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端 tick：定期清理过期的操作席（持有者超时/走远/下线）。
 *
 * <p>为什么不在每次请求时才清理：如果持有者只是站着不动然后退出，没人再发请求，
 * 席位就永远不释放 —— 必须有独立的定时清理。
 */
public final class SeatServerTicker {

    private static int counter;

    private SeatServerTicker() {}

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (++counter % 20 != 0) return;                 // 每秒一次
        for (ServerLevel level : event.getServer().getAllLevels()) {
            TvSeatNet.tick(level);
        }
    }
}