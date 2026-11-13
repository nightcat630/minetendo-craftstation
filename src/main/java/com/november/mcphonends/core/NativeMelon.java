// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.mcphonends.core;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NDS 原生库桥接（包名/方法名必须与 DLL 里的 JNI 导出逐字一致：
 * {@code Java_com_november_mcphonends_core_NativeMelon_*}）。
 *
 * <p>原生实现是 melonDS 派生的自研库（{@code minetendo_nds.dll}），
 * 接口与 1.20 版本相同：create / runFrame / copyFrames / readAudio / setKeys / touch。
 */
public final class NativeMelon {

    private static final String RESOURCE = "/minetendo/native/windows-x86_64/minetendo_nds.dll";
    private static boolean loaded;

    private NativeMelon() {}

    public static synchronized boolean load() {
        if (loaded) return true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try (InputStream in = NativeMelon.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return false;
            byte[] bytes = in.readAllBytes();
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "minetendo");
            Files.createDirectories(dir);
            String hash;
            try {
                byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
                hash = sb.toString();
            } catch (Throwable t) {
                hash = Integer.toString(bytes.length);
            }
            Path dll = dir.resolve("minetendo_nds_" + hash + ".dll");
            if (!Files.isRegularFile(dll)) {
                Files.copy(new java.io.ByteArrayInputStream(bytes), dll, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(dll.toAbsolutePath().toString());
            loaded = true;
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    public static native long create(String romPath, String savePath, String biosDir);
    public static native void destroy(long handle);
    public static native void setKeys(long handle, int mask);
    public static native void touch(long handle, int x, int y);
    public static native void releaseTouch(long handle);
    public static native void runFrame(long handle);
    public static native void copyFrames(long handle, ByteBuffer dstTop, ByteBuffer dstBottom);
    public static native int readAudio(long handle, short[] out, int maxFrames);
    public static native void flushSave(long handle);
    public static native int state(long handle);
    public static native String lastError(long handle);
    public static native boolean saveState(long handle, String path);
    public static native boolean loadState(long handle, String path);
    public static native long frameCount(long handle);
}
