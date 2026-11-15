// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.common;

import java.util.Locale;

/**
 * 平台判定与原生库命名。
 *
 * <p>jar 里同时带 Windows 与 Linux 的原生资源：
 * {@code minetendo/native/windows-x86_64/*.dll} 与 {@code .../linux-x86_64/*.so}。
 * 所有"哪个目录、什么后缀"的判断都收在这里，别再散落到各处。
 */
public final class Platform {

    public enum Os { WINDOWS, LINUX, MACOS, OTHER }

    private static final Os OS = detect();

    private Platform() {}

    private static Os detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("linux")) return Os.LINUX;
        if (name.contains("mac") || name.contains("darwin")) return Os.MACOS;
        return Os.OTHER;
    }

    public static Os os() { return OS; }

    public static boolean isWindows() { return OS == Os.WINDOWS; }

    public static boolean isLinux() { return OS == Os.LINUX; }

    /** 资源子目录名，例如 {@code windows-x86_64} / {@code linux-x86_64} */
    public static String nativeDir() {
        return switch (OS) {
            case WINDOWS -> "windows-x86_64";
            case LINUX -> "linux-x86_64";
            default -> "unsupported";
        };
    }

    public static String libExt() {
        return switch (OS) {
            case WINDOWS -> ".dll";
            case LINUX -> ".so";
            default -> "";
        };
    }

    /** 宿主库文件名（JNI 那一层） */
    public static String hostLibName() {
        return switch (OS) {
            case WINDOWS -> "minetendo.dll";
            case LINUX -> "libminetendo.so";
            default -> "minetendo";
        };
    }

    /** libretro 核心文件名：{@code mgba_libretro} + 平台后缀 */
    public static String coreFileName(String base) {
        return base + libExt();
    }

    /** 当前平台是否支持跑模拟（Mac 未做原生宿主） */
    public static boolean supported() {
        return OS == Os.WINDOWS || OS == Os.LINUX;
    }

    public static String describe() {
        return OS.name().toLowerCase(Locale.ROOT) + "-" + System.getProperty("os.arch", "?");
    }
}
