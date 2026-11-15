// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.common;

import java.util.List;

/** 支持的机种：只是"目录 + 核心 DLL + 键位 + 是否有触摸"的组合。 */
public enum Core {

    THREE_DS("3ds", "azahar_libretro", "3DS", true, List.of(".app", ".3ds", ".cci", ".cia")),
    NDS("nds", "melonds_libretro", "NDS", true, List.of(".nds", ".zip")),
    GBA("gba", "mgba_libretro", "GBA", false, List.of(".gba", ".zip")),
    SFC("sfc", "snes9x_libretro", "SFC", false, List.of(".sfc", ".smc", ".zip")),
      PS2("ps2", "pcsx2_libretro", "PS2", false, List.of(".iso", ".chd", ".bin", ".elf", ".gz")),
      PSP("psp", "ppsspp_libretro", "PSP", false, List.of(".iso", ".cso", ".pbp", ".elf")),
        // RPG Maker 2000/2003：EasyRPG Player。内容是游戏包（zip）或 .ldb，原生 320x240
        RPG("rpg", "easyrpg_libretro", "RPG Maker", false,
            List.of(".zip", ".ldb", ".lzh", ".easyrpg"));

    public final String dir;
    public final String coreBase;
    public final String label;
    public final boolean touch;
    public final List<String> extensions;

    Core(String dir, String coreBase, String label, boolean touch, List<String> extensions) {
        this.dir = dir;
        this.coreBase = coreBase;
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
            case RPG -> 320;
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
            case RPG -> 240;
        };
    }

    public boolean accepts(String fileName) {
        String n = fileName.toLowerCase();
        for (String e : extensions) if (n.endsWith(e)) return true;
        return false;
    }

    /** 当前平台下的核心文件名（Windows .dll / Linux .so） */
    public String fileName() {
        return Platform.coreFileName(coreBase);
    }
}
