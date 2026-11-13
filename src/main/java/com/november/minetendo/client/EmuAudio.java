// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * 模拟器音频输出（Java Sound + 环形缓冲）。
 *
 * <p>不用 OpenAL：MC 自己的声音引擎占着 OpenAL 上下文，再去切换会把 MC 挤掉
 * （日志为 {@code OpenAlUtil: Allocate new source: Invalid name parameter}）。
 *
 * <p>线程模型（之前写错过一次，这里写清楚）：
 * <ul>
 *   <li>生产者：MC 渲染线程，调用 {@link #pump}，只往环形缓冲追加；</li>
 *   <li>消费者：本类的写线程，从环形缓冲取固定块写声卡；</li>
 *   <li>两者都用 {@code synchronized (this)} + {@code wait/notifyAll} 协调。</li>
 * </ul>
 * 早期版本用普通 int 计数、写线程自旋读，JIT 会把读取提到循环外，
 * 写线程永远看到"没有数据"——表现就是**完全没声音**。
 */
public final class EmuAudio {

    private static final int BLOCK_FRAMES = 512;   // 每次写声卡的块（帧）

    private SourceDataLine line;
    private Thread writer;
    private volatile boolean running;

    private short[] ring;
    private int ringSize;                 // short 个数
    private int readPos, writePos, count; // 均在 synchronized(this) 内访问
    /** 音量 0..1（加减号调整） */
    private volatile float gain = 1.0f;

    /** 设置音量（0..1）。优先用声卡的 MASTER_GAIN，拿不到就软件缩放。 */
    public void setVolume(float v) {
        gain = Math.max(0f, Math.min(1f, v));
        try {
            if (line != null && line.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                var c = (javax.sound.sampled.FloatControl) line.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                float dB = (float) (gain <= 0.0001f ? -80.0 : 20.0 * Math.log10(gain));
                c.setValue(Math.max(c.getMinimum(), Math.min(c.getMaximum(), dB)));
            }
        } catch (Throwable ignored) {
        }
    }

    public float volume() { return gain; }

    /** 左右声道偏移：-1 全左，0 居中，+1 全右（电视在玩家左侧/右侧时用）。 */
    private volatile float pan = 0f;
    public void setPan(float p) { pan = Math.max(-1f, Math.min(1f, p)); }
    public float pan() { return pan; }

    private long pumpedFrames;            // 累计送入（诊断用）
    private int peak;                     // 最近一秒内采样峰值的绝对值（0 = 全静音）
    private long writtenFrames;           // 累计写出（诊断用）

    public boolean start(int sampleRate) {
        if (running) return true;
        int rate = sampleRate > 1000 ? sampleRate : 32730;
        try {
            AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 2, 4, rate, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, rate * 4 / 2);   // 约 0.5 秒设备缓冲
            line.start();

            synchronized (this) {
                ringSize = rate * 2;        // 1 秒容量
                ring = new short[ringSize];
                readPos = writePos = count = 0;
                pumpedFrames = writtenFrames = 0;
                running = true;
            }
            writer = new Thread(this::writeLoop, "minetendo-audio");
            writer.setDaemon(true);
            writer.start();
            return true;
        } catch (Throwable t) {
            System.out.println("[Minetendo] 音频启动失败: " + t);
            running = false;
            return false;
        }
    }

    private void writeLoop() {
        byte[] block = new byte[BLOCK_FRAMES * 4];
        while (running) {
            int avail;
            synchronized (this) {
                while (running && count < BLOCK_FRAMES * 2) {
                    try {
                        wait(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) return;
                avail = count;
            }
            int shorts = Math.min(avail, BLOCK_FRAMES * 2) & ~1;
            if (shorts <= 0) continue;

            synchronized (this) {
                for (int i = 0; i < shorts; i++) {
                    short v = ring[readPos];
                    readPos = (readPos + 1) % ringSize;
                    block[i * 2] = (byte) (v & 0xFF);
                    block[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                }
                count -= shorts;
                writtenFrames += shorts / 2;
            }
            try {
                line.write(block, 0, shorts * 2);
            } catch (Throwable t) {
                return;
            }
        }
    }

    /** 追加一帧 PCM（渲染线程调用，绝不阻塞）。 */
    public void pump(java.nio.ShortBuffer data, int shorts) {
        if (!running || shorts <= 0) return;
        int n = shorts & ~1;
        if (n == 0) return;
        data.position(0).limit(n);
        synchronized (this) {
            for (int i = 0; i < n; i++) {
                if (count >= ringSize) {          // 溢出：丢最旧的，控制延迟
                    readPos = (readPos + 1) % ringSize;
                    count--;
                }
                short s = data.get(i);
                if (gain < 0.999f) s = (short) (s * gain);
                ring[writePos] = s;
                writePos = (writePos + 1) % ringSize;
                count++;
            }
            pumpedFrames += n / 2;
            for (int i = 0; i < n; i++) {
                int v = Math.abs(data.get(i));
                if (v > peak) peak = v;
            }
            notifyAll();
        }
    }

    /** 当前缓冲里还有多少采样帧（左右算一帧）。 */
    public synchronized int levelFrames() {
        return count / 2;
    }

    /** 诊断：累计送入/写出的采样帧数。 */
    public synchronized String stats() {
        String out = "送入=" + pumpedFrames + " 写出=" + writtenFrames + " 缓冲=" + (count / 2) + " 峰值=" + peak;
        peak = 0;
        return out;
    }

    public void stop() {
        running = false;
        synchronized (this) {
            notifyAll();
        }
        if (writer != null) {
            try { writer.join(300); } catch (InterruptedException ignored) { }
            writer = null;
        }
        try {
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
            }
        } catch (Throwable ignored) {
        }
        line = null;
    }
}
