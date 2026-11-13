// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.minetendo.net.TvFramePayload;
import com.november.minetendo.net.TvStreamStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 观战端：接收宿主推来的 JPEG 流，解码成纹理，供电视渲染用。
 *
 * <p>宿主自己的客户端也会收到中继包（服务器不区分），所以这里必须跳过"本地正在播的那台电视"，
 * 否则本地画面会被低帧率的网络流覆盖。
 */
public final class TvStreamClient {

    /** 一台电视的远端流状态 */
    private static final class Stream {
        final BlockPos pos;
        final int width, height;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int seq = -1;
        int receivedChunks;
        int expectedChunks = 1;
        /** 解码完成、待上传到 GL 的像素 */
        volatile byte[] pendingRgb;
        int pendingW, pendingH;
        DynamicTexture texture;
        ResourceLocation id;
        long lastPacketMs;

        Stream(BlockPos pos, int width, int height) {
            this.pos = pos;
            this.width = width;
            this.height = height;
        }
    }

    private static final Map<BlockPos, Stream> STREAMS = new ConcurrentHashMap<>();

    private TvStreamClient() {}

    /** 远端流是否可用（供渲染判定）。 */
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

    /** 状态包：开/停流。 */
    public static void onState(TvStreamStatePayload p) {
        if (!p.active()) {
            drop(p.pos());
            return;
        }
        Stream s = STREAMS.get(p.pos());
        if (s == null || s.width != p.width() || s.height != p.height()) {
            drop(p.pos());
            STREAMS.put(p.pos(), new Stream(p.pos(), p.width(), p.height()));
            System.out.println("[Minetendo] 远端电视流建立: " + p.pos().toShortString()
                    + "  " + p.width() + "x" + p.height());
        }
    }

    /** 帧分片：凑齐后解码。 */
    public static void onFrame(TvFramePayload p) {
        Stream s = STREAMS.get(p.pos());
        if (s == null) {
            // 没收到状态包就先按 400×480 建一个，后面状态包会纠正
            s = new Stream(p.pos(), 400, 480);
            STREAMS.put(p.pos(), s);
        }
        synchronized (s) {
            if (p.seq() != s.seq) {          // 新的一帧
                s.seq = p.seq();
                s.buffer.reset();
                s.receivedChunks = 0;
                s.expectedChunks = Math.max(1, p.chunkCount());
            }
            s.buffer.write(p.data(), 0, p.data().length);
            s.receivedChunks++;
            s.lastPacketMs = System.currentTimeMillis();
            if (s.receivedChunks >= s.expectedChunks) {
                byte[] jpeg = s.buffer.toByteArray();
                s.buffer.reset();
                s.receivedChunks = 0;
                decodeAsync(s, jpeg);
            }
        }
    }

    /** JPEG → RGB 字节（后台线程，避免卡渲染）。 */
    private static void decodeAsync(Stream s, byte[] jpeg) {
        Thread t = new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                if (img == null) return;
                int w = img.getWidth(), h = img.getHeight();
                int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
                byte[] rgb = new byte[w * h * 4];
                for (int i = 0; i < argb.length; i++) {
                    int c = argb[i];
                    rgb[i * 4] = (byte) ((c >> 16) & 0xFF);
                    rgb[i * 4 + 1] = (byte) ((c >> 8) & 0xFF);
                    rgb[i * 4 + 2] = (byte) (c & 0xFF);
                    rgb[i * 4 + 3] = (byte) 0xFF;
                }
                s.pendingRgb = rgb;
                s.pendingW = w;
                s.pendingH = h;
            } catch (Throwable ignored) {
            }
        }, "minetendo-tv-decode");
        t.setDaemon(true);
        t.start();
    }

    /** 渲染线程每帧调用：把解好的像素上传到 GL。 */
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
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 4;
                        int r = rgba[i] & 0xFF, g = rgba[i + 1] & 0xFF, b = rgba[i + 2] & 0xFF, a = rgba[i + 3] & 0xFF;
                        img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
                s.texture = new DynamicTexture(img);
                s.id = mc.getTextureManager().register("minetendo_tv_stream_" + s.pos.toShortString(), s.texture);
            } else {
                NativeImage img = s.texture.getPixels();
                if (img != null && img.getWidth() == w && img.getHeight() == h) {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int i = (y * w + x) * 4;
                            int r = rgba[i] & 0xFF, g = rgba[i + 1] & 0xFF, b = rgba[i + 2] & 0xFF, a = rgba[i + 3] & 0xFF;
                            img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                        }
                    }
                }
                s.texture.upload();
            }
        } catch (Throwable t) {
            System.out.println("[Minetendo] 远端画面上传失败: " + t);
        }
    }

    /** 停止并释放一台电视的流。 */
    public static void drop(BlockPos pos) {
        Stream s = STREAMS.remove(pos);
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (s.id != null) {
            try {
                mc.getTextureManager().release(s.id);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 断线时清空。 */
    public static void clear() {
        for (BlockPos p : new HashMap<>(STREAMS).keySet()) drop(p);
    }

    /** 清理超时未更新的流（宿主掉线/走远时兜底）。 */
    public static void prune() {
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, Stream> e : STREAMS.entrySet()) {
            if (now - e.getValue().lastPacketMs > 10_000L) drop(e.getKey());
        }
    }
}
