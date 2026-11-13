// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.SessionHost;
import com.november.minetendo.net.TvFramePayload;
import com.november.minetendo.net.TvStreamStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 宿主端：把正在电视上播的画面压成 JPEG 推给服务器。
 *
 * <p>要点：
 * <ul>
 *   <li>降采样到宽 ≤ {@value #TARGET_W}，画质 0.5 —— 一帧 10~15KB；</li>
 *   <li>默认 {@value #FPS} fps，和模拟器帧率解耦，模拟器跑 60fps 也只推这么点；</li>
 *   <li>编码在后台线程做，绝不占渲染线程；</li>
 *   <li>只有"本地正在投屏的那台电视"才推流，且必须有非专用服务器的连接。</li>
 * </ul>
 */
public final class TvStreamPublisher {

    private static final int TARGET_W = 320;
    private static final int FPS = 12;
    private static final long INTERVAL_MS = 1000L / FPS;

    private static BlockPos streamingPos;
    private static boolean streaming;
    private static int seq;
    private static long lastSendMs;
    private static volatile boolean encoding;
    private static byte[] reusable;

    private TvStreamPublisher() {}

    /** 客户端每 tick 调用。 */
    public static void tick() {
        SessionHost session = InWorldSession.session();
        BlockPos tv = InWorldSession.activeTv();
        boolean want = session != null && session.ready() && tv != null && !session.failed();

        if (!want) {
            if (streaming) stop();
            return;
        }
        // 电视换了 → 先停旧的
        if (streaming && !tv.equals(streamingPos)) stop();
        if (!streaming) start(tv, session);

        long now = System.currentTimeMillis();
        if (now - lastSendMs < INTERVAL_MS || encoding) return;
        lastSendMs = now;
        grabAndSend(session, tv);
    }

    private static void start(BlockPos tv, SessionHost session) {
        streaming = true;
        streamingPos = tv;
        seq = 0;
        int w = Math.max(1, session.width());
        int h = Math.max(1, session.height());
        send(new TvStreamStatePayload(tv, true, scaledW(w, h), scaledH(w, h)));
        System.out.println("[Minetendo] 开始推流: " + tv.toShortString() + " 源尺寸 " + w + "x" + h);
    }

    private static void stop() {
        if (streamingPos != null) {
            send(new TvStreamStatePayload(streamingPos, false, 0, 0));
            System.out.println("[Minetendo] 停止推流: " + streamingPos.toShortString());
        }
        streaming = false;
        streamingPos = null;
    }

    private static int scaledW(int w, int h) {
        if (w <= TARGET_W) return w;
        return TARGET_W;
    }

    private static int scaledH(int w, int h) {
        int outW = scaledW(w, h);
        return Math.max(1, (int) Math.round(h * (outW / (double) w)));
    }

    private static void grabAndSend(SessionHost session, BlockPos tv) {
        int srcW = Math.max(1, session.width());
        int srcH = Math.max(1, session.height());
        int outW = scaledW(srcW, srcH);
        int outH = scaledH(srcW, srcH);

        int need = srcW * srcH * 4;
        if (reusable == null || reusable.length < need) reusable = new byte[need];
        ByteBuffer dst = ByteBuffer.wrap(reusable);
        try {
            session.copyFrameTo(dst, srcW, srcH);
        } catch (Throwable t) {
            return;                       // 帧还没就绪，下一拍再来
        }

        byte[] src = reusable;
        final int fw = srcW, fh = srcH, ow = outW, oh = outH;
        encoding = true;
        Thread t = new Thread(() -> {
            try {
                byte[] jpeg = encodeJpeg(src, fw, fh, ow, oh);
                if (jpeg != null) sendFrame(tv, jpeg);
            } catch (Throwable ignored) {
            } finally {
                encoding = false;
            }
        }, "minetendo-tv-encode");
        t.setDaemon(true);
        t.start();
    }

    /** 最近邻降采样 + JPEG 编码。 */
    private static byte[] encodeJpeg(byte[] rgba, int sw, int sh, int dw, int dh) {
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, y * sh / dh);
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, x * sw / dw);
                int i = (sy * sw + sx) * 4;
                int r = rgba[i] & 0xFF, g = rgba[i + 1] & 0xFF, b = rgba[i + 2] & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024)) {
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            javax.imageio.plugins.jpeg.JPEGImageWriteParam param =
                    new javax.imageio.plugins.jpeg.JPEGImageWriteParam(null);
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.5f);
            try (javax.imageio.stream.MemoryCacheImageOutputStream ios =
                         new javax.imageio.stream.MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendFrame(BlockPos tv, byte[] jpeg) {
        seq++;
        int chunks = (jpeg.length + TvFramePayload.MAX_CHUNK - 1) / TvFramePayload.MAX_CHUNK;
        List<TvFramePayload> list = new ArrayList<>(chunks);
        for (int i = 0; i < chunks; i++) {
            int from = i * TvFramePayload.MAX_CHUNK;
            int len = Math.min(TvFramePayload.MAX_CHUNK, jpeg.length - from);
            byte[] part = new byte[len];
            System.arraycopy(jpeg, from, part, 0, len);
            list.add(new TvFramePayload(tv, seq, i, chunks, part));
        }
        for (TvFramePayload p : list) send(p);
    }

    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        try {
            PacketDistributor.sendToServer(payload);
        } catch (Throwable ignored) {
        }
    }
}
