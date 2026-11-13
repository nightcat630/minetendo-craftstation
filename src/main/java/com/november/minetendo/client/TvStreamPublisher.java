// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.SessionHost;
import com.november.minetendo.net.TvFramePayload;
import com.november.minetendo.net.TvHostDemandPayload;
import com.november.minetendo.net.TvStreamStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 宿主端：把正在电视上播的画面压成 JPEG 推给服务器。
 *
 * <p>带 <b>需求门控</b>：先发 ANNOUNCE（只宣告，不推帧），等服务器回
 * {@link TvHostDemandPayload} 说有人看（watchers &gt; 0）才开始推帧；人走光就自动停帧。
 * 没人看时不编码、不发送 —— 这是省带宽最直接的一刀。
 *
 * <p>带 <b>租约</b>：每次开播生成新 UUID，帧包带它，观战端只认这个 lease。
 */
public final class TvStreamPublisher {

    private static final int TARGET_W = 320;
    private static final int FPS = 12;
    private static final long INTERVAL_MS = 1000L / FPS;

    private static BlockPos pos;
    private static UUID lease;
    private static boolean announced;
    private static boolean streaming;
    private static int watchers;
    private static long lastDemandMs;
    private static int seq;
    private static long lastSendMs;
    private static volatile boolean encoding;
    private static byte[] reusable;

    private TvStreamPublisher() {}

    /** 客户端每 tick 调用。 */
    public static void tick() {
        SessionHost session = InWorldSession.session();
        BlockPos tv = InWorldSession.activeTv();
        boolean want = session != null && session.ready() && tv != null && !session.failed()
                && Minecraft.getInstance().getConnection() != null;

        if (!want) {
            if (announced) stop();
            return;
        }
        if (announced && !tv.equals(pos)) stop();
        if (!announced) announce(tv, session);

        // 服务器超过 6 秒没回需求 → 视为无人观看，暂停推帧（保留宣告）
        if (System.currentTimeMillis() - lastDemandMs > 6000) watchers = 0;

        if (!streaming) {
            if (watchers > 0) startStreaming();
            else return;
        }
        if (watchers <= 0) return;                       // 没人看：不抓帧不编码

        long now = System.currentTimeMillis();
        if (now - lastSendMs < INTERVAL_MS || encoding) return;
        lastSendMs = now;
        grabAndSend(session);
    }

    /** 服务器告诉宿主：当前多少人在看。 */
    public static void onDemand(TvHostDemandPayload p) {
        if (!announced || !p.pos().equals(pos) || lease == null || !lease.equals(p.lease())) return;
        watchers = p.watchers();
        lastDemandMs = System.currentTimeMillis();
        if (watchers <= 0 && streaming) {
            streaming = false;
            send(new TvStreamStatePayload(pos, lease, TvStreamStatePayload.ANNOUNCE, 0, 0));
            System.out.println("[Minetendo] 无人观看，暂停推流: " + pos.toShortString());
        }
    }

    private static void announce(BlockPos tv, SessionHost session) {
        pos = tv;
        lease = UUID.randomUUID();
        announced = true;
        streaming = false;
        watchers = 0;
        lastDemandMs = System.currentTimeMillis();
        seq = 0;
        int w = Math.max(1, session.width()), h = Math.max(1, session.height());
        send(new TvStreamStatePayload(pos, lease, TvStreamStatePayload.ANNOUNCE, scaledW(w, h), scaledH(w, h)));
        System.out.println("[Minetendo] 宣告电视流: " + tv.toShortString() + "  " + w + "x" + h);
    }

    private static void startStreaming() {
        streaming = true;
        send(new TvStreamStatePayload(pos, lease, TvStreamStatePayload.STREAMING,
                scaledW(lastW, lastH), scaledH(lastW, lastH)));
        System.out.println("[Minetendo] 开始推流（观众 " + watchers + "）: " + pos.toShortString());
    }

    private static void stop() {
        if (pos != null && lease != null) {
            send(new TvStreamStatePayload(pos, lease, TvStreamStatePayload.STOP, 0, 0));
            System.out.println("[Minetendo] 停止推流: " + pos.toShortString());
        }
        announced = false;
        streaming = false;
        pos = null;
        lease = null;
        watchers = 0;
    }

    private static int lastW = 400, lastH = 480;

    private static int scaledW(int w, int h) {
        return w <= TARGET_W ? w : TARGET_W;
    }

    private static int scaledH(int w, int h) {
        int outW = scaledW(w, h);
        return Math.max(1, (int) Math.round(h * (outW / (double) w)));
    }

    private static void grabAndSend(SessionHost session) {
        int srcW = Math.max(1, session.width());
        int srcH = Math.max(1, session.height());
        lastW = srcW;
        lastH = srcH;
        int outW = scaledW(srcW, srcH);
        int outH = scaledH(srcW, srcH);

        int need = srcW * srcH * 4;
        if (reusable == null || reusable.length < need) reusable = new byte[need];
        try {
            session.copyFrameTo(ByteBuffer.wrap(reusable), srcW, srcH);
        } catch (Throwable t) {
            return;
        }
        byte[] src = reusable;
        final int fw = srcW, fh = srcH, ow = outW, oh = outH;
        final UUID useLease = lease;
        final BlockPos usePos = pos;
        encoding = true;
        Thread t = new Thread(() -> {
            try {
                byte[] jpeg = encodeJpeg(src, fw, fh, ow, oh);
                if (jpeg != null && useLease != null) sendFrame(usePos, useLease, jpeg);
            } catch (Throwable ignored) {
            } finally {
                encoding = false;
            }
        }, "minetendo-tv-encode");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] encodeJpeg(byte[] rgba, int sw, int sh, int dw, int dh) {
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, y * sh / dh);
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, x * sw / dw);
                int i = (sy * sw + sx) * 4;
                img.setRGB(x, y, ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            JPEGImageWriteParam param = new JPEGImageWriteParam(null);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.5f);
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendFrame(BlockPos tv, UUID useLease, byte[] jpeg) {
        seq++;
        int chunks = (jpeg.length + TvFramePayload.MAX_CHUNK - 1) / TvFramePayload.MAX_CHUNK;
        List<TvFramePayload> list = new ArrayList<>(chunks);
        for (int i = 0; i < chunks; i++) {
            int from = i * TvFramePayload.MAX_CHUNK;
            int len = Math.min(TvFramePayload.MAX_CHUNK, jpeg.length - from);
            byte[] part = new byte[len];
            System.arraycopy(jpeg, from, part, 0, len);
            list.add(new TvFramePayload(tv, useLease, seq, i, chunks, part));
        }
        for (TvFramePayload p : list) send(p);
    }

    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (Minecraft.getInstance().getConnection() == null) return;
        try {
            PacketDistributor.sendToServer(payload);
        } catch (Throwable ignored) {
        }
    }
}
