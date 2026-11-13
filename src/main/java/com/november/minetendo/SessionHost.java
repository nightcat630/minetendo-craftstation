// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.opengl.GL11;

/**
 * 会话化原生层的 Java 侧封装（工作线程版）。
 *
 * <p>原生实现见 {@code src/native/minetendo_host.cpp}：每个会话自带隐藏窗口、
 * GL 上下文、FBO 与核心模块；换系统 = 新建会话，旧核心**永不卸载**。
 *
 * <p><b>为什么用工作线程</b>：核心初始化（编译着色器）和每帧 {@code retro_run}
 * 都是重活，放渲染线程会卡住整个游戏。会话有自己的 GL 上下文，所以可以独立跑；
 * 渲染线程只负责把最新一帧上传成纹理。
 *
 * <p><b>窗口尺寸</b>：内部渲染分辨率固定为核心输出尺寸（400×480），与窗口无关；
 * 窗口只负责缩放显示。窗口尺寸变化时界面侧不打断会话（核心在那种路径上会崩）。
 */
public final class SessionHost {

    private static final String RESOURCE = "/minetendo/native/windows-x86_64/minetendo.dll";
    /** 目标帧率：3DS 原生 60，但宿主呈现链路（回读+上传）撑不住，先锁 30 */
    private static final int TARGET_FPS = 60;
    public static final int MAX_W = 1024;
    public static final int MAX_H = 768;

    private static boolean loaded;

    private volatile long token;
    private final ByteBuffer back;      // 工作线程写
    private final ByteBuffer front;     // 渲染线程读
    private final ByteBuffer audioBuf;
    private final Object frameLock = new Object();
    private final int[] meta = new int[5];   // w, h, rate, audioFrames, programs
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile int frameW = 400, frameH = 480, rate = 32730;
    private volatile int frames;
    /** 每产出一帧 +1：渲染线程据此判断"有没有新帧"，避免重复上传同一帧 */
    private volatile int frameSeq;
    private volatile boolean ready;
    private volatile boolean failed;
    private volatile String failure = "";

    private int expectedPrograms;
    private String sysPathForProgress;
    private Thread worker;
    /** 音频水位（采样帧）：由界面每帧更新，工作线程据此调节速度 */
    private volatile int audioLevelFrames = 0;
    private volatile int audioRate = 32730;

    // 输入只存字段，由工作线程在每帧前统一下发（避免跨线程抢槽位锁）
    private volatile int inButtons, inAxisX, inAxisY, inAxisX2, inAxisY2;
    private volatile int inPointerX, inPointerY;
    private volatile boolean inPointerDown;

    private SessionHost(ByteBuffer back, ByteBuffer front, ByteBuffer audio) {
        this.back = back;
        this.front = front;
        this.audioBuf = audio;
    }

    public static synchronized boolean load() {
        if (loaded) return true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try (InputStream in = SessionHost.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return false;
            byte[] bytes = in.readAllBytes();
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "minetendo");
            Files.createDirectories(dir);
            // 按内容哈希命名，避免长度相同的旧版本被复用
            String hash;
            try {
                byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
                hash = sb.toString();
            } catch (Throwable t) {
                hash = Integer.toString(bytes.length);
            }
            Path dll = dir.resolve("minetendo_host_" + hash + ".dll");
            if (!Files.isRegularFile(dll)) {
                Files.copy(new java.io.ByteArrayInputStream(bytes), dll, StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(dll.toAbsolutePath().toString());
            loaded = true;
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    public static boolean available() {
        return loaded;
    }

    /** 打开会话：立刻返回，后台线程负责创建与逐帧推进。 */
    public static SessionHost open(Path core, Path game, Path sysDir) {
        if (!load()) return null;
        ByteBuffer back = ByteBuffer.allocateDirect(MAX_W * MAX_H * 4).order(ByteOrder.nativeOrder());
        ByteBuffer front = ByteBuffer.allocateDirect(MAX_W * MAX_H * 4).order(ByteOrder.nativeOrder());
        ByteBuffer audio = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());

        SessionHost host = new SessionHost(back, front, audio);
        host.sysPathForProgress = sysDir.toAbsolutePath().toString();
        host.expectedPrograms = readExpected(sysDir.toAbsolutePath().toString());

        final String corePath = core.toAbsolutePath().toString();
        final String gamePath = game.toAbsolutePath().toString();
        final String sysPath = sysDir.toAbsolutePath().toString();

        host.worker = new Thread(() -> host.loop(corePath, gamePath, sysPath), "minetendo-session");
        host.worker.setDaemon(true);
        host.worker.start();
        return host;
    }

    private void loop(String corePath, String gamePath, String sysPath) {
        long tk = 0L;
        try {
            tk = create(corePath, gamePath, sysPath, back, audioBuf);
            if (tk == 0L) {
                failed = true;
                failure = lastError();
                return;
            }
            token = tk;
            // 回读+上传模式（共享上下文那版会黑屏，已回退）

            final long frameNanos = 1_000_000_000L / TARGET_FPS;
            long startNs = System.nanoTime();
            long samplesFed = 0;
            while (running.get()) {
                long t0 = System.nanoTime();
                setInput(tk, inButtons, inAxisX, inAxisY, inAxisX2, inAxisY2);
                setPointer(tk, inPointerX, inPointerY, inPointerDown);
                if (!nativeStep(tk, meta)) {
                    failed = true;
                    failure = lastError();
                    return;
                }
                stepMillis = (System.nanoTime() - t0) / 1_000_000.0;
                if (meta[0] > 0) frameW = meta[0];
                if (meta[1] > 0) frameH = meta[1];
                if (meta[2] > 0) rate = meta[2];
                // 掉帧时的宿主侧跳帧：核心已经跟不上，就别再让像素搬运继续拖累它
                skipUpload = stepMillis > 21.0 && (frames & 1) == 0;
                swapBuffers();
                frames++;
                frameSeq++;
                if (!ready) {
                    ready = true;
                    saveExpected();
                }
                // 节拍按**累计音频时钟**推进：核心产出多少采样，就给它多少真实时间。
                // 这是唯一能同时保证"不加速、不欠载"的方式：
                //   固定帧率 → 核心 76fps 时游戏被加速、音频溢出丢块（电流声）
                //   水位反馈 → 核心全速跑，加速更明显
                samplesFed += meta[3];
                long target = startNs + samplesFed * 1_000_000_000L / Math.max(1, rate);
                long remain = target - System.nanoTime();
                if (remain > 2_000_000L) {
                    Thread.sleep((remain - 2_000_000L) / 1_000_000L);
                }
                while (System.nanoTime() < target) {
                    Thread.onSpinWait();
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            failed = true;
            failure = String.valueOf(t);
        } finally {
            if (tk != 0L) {
                try {
                    destroy(tk);      // 在会话所属线程上销毁
                } catch (Throwable ignored) {
                }
                token = 0L;
            }
        }
    }

    private void swapBuffers() {
        synchronized (frameLock) {
            int w = frameW, h = frameH;
            if (w <= 0 || h <= 0) return;
            int bytes = Math.min(w * h * 4, back.capacity());
            back.position(0).limit(bytes);
            front.position(0).limit(bytes);
            front.put(back);
            front.position(0);
        }
    }

    public boolean ready() { return ready; }
    public boolean failed() { return failed; }
    public String failure() { return failure; }
    public int frames() { return frames; }
    public int frameSeq() { return frameSeq; }
    public int width() { return frameW > 0 ? frameW : 400; }
    public int height() { return frameH > 0 ? frameH : 480; }
    public int sampleRate() { return rate; }
    public int programs() { return meta[4]; }
    /** 本帧产出的音频采样帧数（左右各一个 short） */
    public int audioFrames() { return meta[3]; }
    public int expectedPrograms() { return expectedPrograms; }
    public ByteBuffer audioBuffer() { return audioBuf; }

    /** 把最新一帧上传成纹理（渲染线程调用，一次 glTexSubImage2D）。 */
    public void uploadTo(int textureId) {
        int w = width(), h = height();
        if (textureId <= 0) return;
        long tk = token;
        if (tk != 0L) {
            // 共享上下文 + GPU 内 blit：零回读（这是"手机都能跑、PC 却慢"的根因所在）
            try {
                if (copyTo(tk, textureId, w, h)) return;
            } catch (Throwable ignored) {
            }
        }

        synchronized (frameLock) {
            if (front.limit() < w * h * 4) return;
            front.position(0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, front);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
        }
    }

    /** 界面把当前音频缓冲水位告诉会话：低水位时核心全速跑，避免音频欠载（电流声）。 */
    public void setAudioLevel(int frames, int rate) {
        audioLevelFrames = frames;
        if (rate > 1000) audioRate = rate;
    }

    /**
     * 从会话的音频环形缓冲里取数据（原生侧加锁，不会读到半截数据）。
     *
     * @return 取到的采样帧数（左右各一个 short）
     */
    public int readAudio(java.nio.ByteBuffer dst, int maxFrames) {
        long tk = token;
        if (tk == 0L) return 0;
        try {
            return nativeReadAudio(tk, dst, maxFrames);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 按纹理实际尺寸裁切上传（纯 Java 实现，与 {@link #uploadTo} 同一条链路）。
     *
     * <p>核心在游戏内会切到 239 行，而电视纹理固定 224 行；整块上传会写错区域，
     * 表现为画面整体跳动。这里只上传 min(核心, 纹理)。
     */
    /** 把最新一帧拷进目标缓冲（参考项目的做法：写 NativeImage.pixels 再 texture.upload()）。 */
    public void copyFrameTo(java.nio.ByteBuffer dst, int texW, int texH) {
        synchronized (frameLock) {
            int w = Math.min(width(), texW);
            int h = Math.min(height(), texH);
            if (w <= 0 || h <= 0 || dst.capacity() < w * h * 4) return;
            int bytes = w * h * 4;
            front.position(0).limit(bytes);
            dst.position(0).limit(bytes);
            dst.put(front);
            dst.position(0);
        }
    }

    public void uploadToSized(int textureId, int texW, int texH) {
        if (textureId <= 0) return;
        synchronized (frameLock) {
            int w = Math.min(width(), texW);
            int h = Math.min(height(), texH);
            if (w <= 0 || h <= 0) return;
            int need = w * h * 4;
            if (front.limit() < need) return;
            front.position(0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(textureId);
            // 显式设置行距：GL_UNPACK_ROW_LENGTH 是全局状态，别的模组可能残留非 0，
            // 会让驱动按错误行距读我们的缓冲（画面横向剪切）。
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, w);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, front);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
        }
    }

    /** 即时存档（核心支持 retro_serialize 时有效；3DS 的 Azahar 不支持）。 */
    public boolean saveState(java.nio.file.Path file) {
        long tk = token;
        if (tk == 0L) return false;
        try {
            boolean queued = nativeSaveState(tk, file.toAbsolutePath().toString());
            if (queued) nativeWaitSnapshot(tk, 15000);   // 等模拟线程真正写完
            return queued;
        } catch (Throwable t) { return false; }
    }

    /** 读即时存档。 */
    public boolean loadState(java.nio.file.Path file) {
        long tk = token;
        if (tk == 0L) return false;
        try {
            boolean queued = nativeLoadState(tk, file.toAbsolutePath().toString());
            if (queued) nativeWaitSnapshot(tk, 15000);
            return queued;
        } catch (Throwable t) { return false; }
    }

    public void setInput(int buttons, int axisX, int axisY) { setInput(buttons, axisX, axisY, 0, 0); }

    /** 双摇杆版本：axisX2/axisY2 是右摇杆（PSP 用）。 */
    /** 最近一帧核心耗时（毫秒），用于宿主侧跳帧判断。 */
    private volatile double stepMillis;
    /** 本帧是否跳过像素搬运（核心耗时 >21ms 时隔帧跳） */
    private volatile boolean skipUpload;
    public boolean skipUpload() { return skipUpload; }
    public double stepMillis() { return stepMillis; }

    public void setInput(int buttons, int axisX, int axisY, int axisX2, int axisY2) {
        inButtons = buttons;
        inAxisX = axisX;
        inAxisY = axisY;
        inAxisX2 = axisX2;
        inAxisY2 = axisY2;
    }

    public void setPointer(int x, int y, boolean down) {
        inPointerX = x;
        inPointerY = y;
        inPointerDown = down;
    }

    /** 停止会话：发信号给工作线程，由它自己销毁。 */
    public void close() {
        running.set(false);
        Thread w = worker;
        if (w != null) {
            try {
                w.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        ready = false;
    }

    private static Path progressFile(String sysDir) {
        return Path.of(sysDir).resolve("azahar-shaders-expected.txt");
    }

    private static int readExpected(String sysDir) {
        try {
            Path p = progressFile(sysDir);
            if (!Files.isRegularFile(p)) return 0;
            return Integer.parseInt(Files.readString(p).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private void saveExpected() {
        try {
            int done = meta[4];
            if (done <= 0 || sysPathForProgress == null) return;
            Files.writeString(progressFile(sysPathForProgress), Integer.toString(done));
            expectedPrograms = done;
        } catch (Throwable ignored) {
        }
    }

    /** 必须在 MC 渲染线程调用：让原生层记住 MC 的 GL 上下文，
     *  会话上下文据此共享纹理/FBO（共享后画面直接 GPU 内 blit，不再回读）。 */
    public static native void captureContext();

    public static native String lastError();

    private static native long create(String core, String game, String sysDir,
                                      ByteBuffer video, ByteBuffer audio);
    private static native boolean nativeStep(long token, int[] meta);
    private static native boolean nativeSaveState(long token, String path);
    private static native boolean nativeLoadState(long token, String path);
    private static native boolean nativeWaitSnapshot(long token, int timeoutMs);
    private static native int nativeReadAudio(long token, java.nio.ByteBuffer dst, int maxFrames);
    private static native void setGpuCopy(long token, boolean on);
    private static native int textureId(long token);
    private static native boolean copyTo(long token, int dstTex, int w, int h);
    private static native void destroy(long token);
    private static native void setInput(long token, int buttons, int axisX, int axisY, int axisX2, int axisY2);
    private static native void setPointer(long token, int x, int y, boolean down);
}
