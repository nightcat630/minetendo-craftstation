// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.net.TvSeatRequestPayload;
import com.november.minetendo.net.TvSeatStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端的席位视图：谁在操作、我能不能操作。
 *
 * <p>交互流程：
 * <pre>
 *   右键机器 → requestControl(pos, 回调)
 *        ├─ 服务器回 youHold=true  → 执行回调（打开选游戏界面 / 启动会话）
 *        └─ 服务器回 youHold=false → 提示"X 正在操作这台机器"
 * </pre>
 *
 * <p>持有操作席期间每 3 秒自动续期（服务器 8 秒没收到就收回去）。
 */
public final class TvSeats {

    private static final class State {
        boolean youHold;
        String holderName = "";
        int watchers;
        int revision = -1;
        long lastMs;
    }

    private static final Map<BlockPos, State> STATES = new HashMap<>();
    /** pos → 申请成功后要做的事 */
    private static final Map<BlockPos, Runnable> PENDING = new HashMap<>();

    private static BlockPos heldPos;
    private static long lastRenewMs;

    private TvSeats() {}

    public static void onState(TvSeatStatePayload p) {
        State s = STATES.computeIfAbsent(p.pos(), k -> new State());
        s.youHold = p.youHold();
        s.holderName = p.holderName();
        s.watchers = p.watchers();
        s.revision = p.revision();
        s.lastMs = System.currentTimeMillis();

        if (p.youHold()) {
            heldPos = p.pos();
            Runnable r = PENDING.remove(p.pos());
            if (r != null) r.run();
        } else {
            if (p.pos().equals(heldPos)) heldPos = null;
            Runnable r = PENDING.remove(p.pos());
            if (r != null) {
                String who = p.holderName().isEmpty() ? "别人" : p.holderName();
                message("§c" + who + " 正在操作这台机器");
            }
        }
    }

    /** 请求操作席；拿到了就执行 onGranted，被拒会提示。 */
    public static void requestControl(BlockPos pos, Runnable onGranted) {
        PENDING.put(pos, onGranted);
        send(pos, TvSeatRequestPayload.TAKE);
    }

    public static void release(BlockPos pos) {
        PENDING.remove(pos);
        if (pos != null) send(pos, TvSeatRequestPayload.RELEASE);
        if (pos != null && pos.equals(heldPos)) heldPos = null;
    }

    public static boolean iHold(BlockPos pos) {
        State s = STATES.get(pos);
        return s != null && s.youHold;
    }

    public static String holderName(BlockPos pos) {
        State s = STATES.get(pos);
        return s == null ? "" : s.holderName;
    }

    public static int watchers(BlockPos pos) {
        State s = STATES.get(pos);
        return s == null ? 0 : s.watchers;
    }

    /** 每 tick：续期 + 清理过期缓存。 */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (heldPos != null && now - lastRenewMs >= 3000) {
            lastRenewMs = now;
            send(heldPos, TvSeatRequestPayload.TAKE);   // 续期
        }
        if (now % 1000 < 60) {
            STATES.entrySet().removeIf(e -> now - e.getValue().lastMs > 20_000);
        }
    }

    public static void clear() {
        STATES.clear();
        PENDING.clear();
        heldPos = null;
    }

    private static void send(BlockPos pos, int action) {
        if (Minecraft.getInstance().getConnection() == null) return;
        try {
            PacketDistributor.sendToServer(new TvSeatRequestPayload(pos, action));
        } catch (Throwable ignored) {
        }
    }

    private static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), true);
    }
}
