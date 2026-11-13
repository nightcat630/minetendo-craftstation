// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import java.util.List;

/** 支持的机种：只是"目录 + 核心 DLL + 键位 + 是否有触摸"的组合。 */
public enum Core {

    THREE_DS("3ds", "azahar_libretro.dll", "3DS", true, List.of(".app", ".3ds", ".cci", ".cia")),
    NDS("nds", "melonds_libretro.dll", "NDS", true, List.of(".nds", ".zip")),
    GBA("gba", "mgba_libretro.dll", "GBA", false, List.of(".gba", ".zip")),
    SFC("sfc", "snes9x_libretro.dll", "SFC", false, List.of(".sfc", ".smc", ".zip")),
      PS2("ps2", "pcsx2_libretro.dll", "PS2", false, List.of(".iso", ".chd", ".bin", ".elf", ".gz")),
      PSP("psp", "ppsspp_libretro.dll", "PSP", false, List.of(".iso", ".cso", ".pbp", ".elf"));

    public final String dir;
    public final String coreDll;
    public final String label;
    public final boolean touch;
    public final List<String> extensions;

    Core(String dir, String coreDll, String label, boolean touch, List<String> extensions) {
        this.dir = dir;
        this.coreDll = coreDll;
        this.label = label;
        this.touch = touch;
        this.extensions = extensions;
    }

    /** 机种固定分辨率（用于投屏比例；与核心实际输出一致） */
    public int baseWidth() {
        return switch (this) {
            case GBA -> 240;
            case SFC -> 256;
            case NDS -> 256;
            case THREE_DS -> 400;
            case PS2 -> 640;
            case PSP -> 480;
        };
    }

    public int baseHeight() {
        return switch (this) {
            case GBA -> 160;
            case SFC -> 224;
            case NDS -> 384;
            case THREE_DS -> 480;
            case PS2 -> 448;
            case PSP -> 270;
        };
    }

    public boolean accepts(String fileName) {
        String n = fileName.toLowerCase();
        for (String e : extensions) if (n.endsWith(e)) return true;
        return false;
    }
}
