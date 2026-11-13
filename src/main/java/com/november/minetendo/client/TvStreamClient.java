// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.minetendo.net.TvFramePayload;
import com.november.minetendo.net.TvHostDemandPayload;
import com.november.minetendo.net.TvStreamStatePayload;
import com.november.minetendo.net.TvWatchInterestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 观战端：接收宿主推来的 JPEG 流，解码成纹理，供电视渲染用。
 *
 * <p>带 <b>租约</b>：只接受当前 lease 的帧，宿主重开一局（换 lease）时旧帧自动作废。
 * 带 <b>兴趣上报</b>：看到 ANNOUNCE 就回 {@link TvWatchInterestPayload}，服务器才知道有人要看；
 * 走远或窗口关闭时上报 watching=false。
 */
public final class TvStreamClient {

    private static final class Stream {
        final BlockPos pos;
        int width, height;
        UUID lease;
        int phase;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int seq = -1;
        int got, expect = 1;
        volatile byte[] pendingRgb;
        int pendingW, pendingH;
        DynamicTexture texture;
        ResourceLocation id;
        long lastPacketMs;
        long lastInterestMs;
        boolean announced;

        Stream(BlockPos pos) { this.pos = pos; }
    }

    private static final Map<BlockPos, Stream> STREAMS = new ConcurrentHashMap<>();

    private TvStreamClient() {}

    // ── 渲染侧查询 ──

    public static boolean has(BlockPos pos) {
        Stream s = STREAMS.get(pos);
        return s != null && s.id != null;
    }

    public static ResourceLocation texture(BlockPos pos) {
        Stream s = STREAMS.get(pos);
        return s != null ? s.id : null;
    }

    public static int width(BlockPos pos) {
        Stream s = STREAMS.get(pos);
        return s != null ? s.width : 0;
    }

    public static int height(BlockPos pos) {
        Stream s = STREAMS.get(pos);
        return s != null ? s.height : 0;
    }

    /** 是否已宣告但还没画面（渲染层可以画个"连接中"）。 */
    public static boolean announcedOnly(BlockPos pos) {
        Stream s = STREAMS.get(pos);
        return s != null && s.announced && s.id == null;
    }

    // ── 收包 ──

    public static void onState(TvStreamStatePayload p) {
        switch (p.phase()) {
            case TvStreamStatePayload.STOP -> {
                drop(p.pos());
            }
            case TvStreamStatePayload.ANNOUNCE, TvStreamStatePayload.STREAMING -> {
                Stream s = STREAMS.get(p.pos());
                if (s == null || !p.lease().equals(s.lease)) {
                    drop(p.pos());
                    s = new Stream(p.pos());
                    s.lease = p.lease();
                    STREAMS.put(p.pos(), s);
                    System.out.println("[Minetendo] 发现电视流: " + p.pos().toShortString()
                            + "  " + p.width() + "x" + p.height());
                }
                s.width = p.width();
                s.height = p.height();
                s.phase = p.phase();
                s.announced = true;
                s.lastPacketMs = System.currentTimeMillis();
                sendInterest(p.pos(), true, s);
            }
            default -> {
            }
        }
    }

    public static void onFrame(TvFramePayload p) {
        Stream s = STREAMS.get(p.pos());
        if (s == null || s.lease == null || !s.lease.equals(p.lease())) return;   // 旧租约的帧直接丢
        s.lastPacketMs = System.currentTimeMillis();
        synchronized (s) {
            if (p.seq() != s.seq) {
                s.seq = p.seq();
                s.buffer.reset();
                s.got = 0;
                s.expect = Math.max(1, p.chunkCount());
            }
            s.buffer.write(p.data(), 0, p.data().length);
            s.got++;
            if (s.got >= s.expect) {
                byte[] jpeg = s.buffer.toByteArray();
                s.buffer.reset();
                s.got = 0;
                decodeAsync(s, jpeg);
            }
        }
    }

    public static void onDemand(TvHostDemandPayload p) {
        // 观战端不需要处理；宿主端由 TvStreamPublisher 处理
        TvStreamPublisher.onDemand(p);
    }

    // ── 兴趣上报 ──

    private static void sendInterest(BlockPos pos, boolean watching, Stream s) {
        long now = System.currentTimeMillis();
        if (s != null) {
            if (watching && now - s.lastInterestMs < 1800) return;   // 2 秒一次心跳
            s.lastInterestMs = now;
        }
        try {
            PacketDistributor.sendToServer(new TvWatchInterestPayload(pos, watching));
        } catch (Throwable ignored) {
        }
    }

    /** 每 tick：对"已宣告但还没心跳"的流补发兴趣；对看不见的流上报离开。 */
    public static void keepAlive() {
        if (STREAMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { clear(); return; }
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, Stream> e : STREAMS.entrySet()) {
            Stream s = e.getValue();
            if (now - s.lastInterestMs >= 2000 && now - s.lastPacketMs < 12_000) {
                sendInterest(e.getKey(), true, s);
            }
        }
    }

    // ── 解码 ──

    private static void decodeAsync(Stream s, byte[] jpeg) {
        Thread t = new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                if (img == null) return;
                int w = img.getWidth(), h = img.getHeight();
                int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
                byte[] rgba = new byte[w * h * 4];
                for (int i = 0; i < argb.length; i++) {
                    int c = argb[i];
                    rgba[i * 4] = (byte) ((c >> 16) & 0xFF);
                    rgba[i * 4 + 1] = (byte) ((c >> 8) & 0xFF);
                    rgba[i * 4 + 2] = (byte) (c & 0xFF);
                    rgba[i * 4 + 3] = (byte) 0xFF;
                }
                s.pendingRgb = rgba;
                s.pendingW = w;
                s.pendingH = h;
            } catch (Throwable ignored) {
            }
        }, "minetendo-tv-decode");
        t.setDaemon(true);
        t.start();
    }

    public static void pump() {
        if (STREAMS.isEmpty()) return;
        for (Stream s : STREAMS.values()) {
            byte[] rgb = s.pendingRgb;
            if (rgb == null) continue;
            s.pendingRgb = null;
            upload(s, rgb, s.pendingW, s.pendingH);
        }
    }

    private static void upload(Stream s, byte[] rgba, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (s.texture == null) {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                writePixels(img, rgba, w, h);
                s.texture = new DynamicTexture(img);
                s.id = mc.getTextureManager().register("minetendo_tv_stream_" + s.pos.toShortString(), s.texture);
            } else {
                NativeImage img = s.texture.getPixels();
                if (img != null && img.getWidth() == w && img.getHeight() == h) {
                    writePixels(img, rgba, w, h);
                }
                s.texture.upload();
            }
        } catch (Throwable t) {
            System.out.println("[Minetendo] 远端画面上传失败: " + t);
        }
    }

    private static void writePixels(NativeImage img, byte[] rgba, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int r = rgba[i] & 0xFF, g = rgba[i + 1] & 0xFF, b = rgba[i + 2] & 0xFF, a = rgba[i + 3] & 0xFF;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    public static void drop(BlockPos pos) {
        Stream s = STREAMS.remove(pos);
        if (s == null) return;
        sendInterest(pos, false, null);
        Minecraft mc = Minecraft.getInstance();
        if (s.id != null) {
            try {
                mc.getTextureManager().release(s.id);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clear() {
        for (BlockPos p : new HashMap<>(STREAMS).keySet()) drop(p);
    }

    public static void prune() {
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, Stream> e : STREAMS.entrySet()) {
            if (now - e.getValue().lastPacketMs > 10_000L) drop(e.getKey());
        }
    }
}
