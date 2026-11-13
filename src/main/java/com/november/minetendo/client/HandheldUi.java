// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 掌机界面的"外观层"：配色、面板、提示文字。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>背景透明</b>：不再铺纯黑，世界照常透出来；只在屏幕周围画半透明机身面板；</li>
 *   <li><b>每个机种一套配色与布局</b>：GBA 靛蓝、3DS 青蓝、NDS 银灰；</li>
 *   <li>双屏机型（3DS/NDS）上下两块面板中间留铰链缝。</li>
 * </ul>
 */
public final class HandheldUi {

    private HandheldUi() {}

    // ── 各机种配色（ARGB）──
    public static final int GBA_ACCENT = 0xFF6A5ACD;   // 靛蓝
    public static final int THREE_DS_ACCENT = 0xFF2E9BB5; // 青蓝
    public static final int NDS_ACCENT = 0xFF9AA4AD;   // 银灰

    public static int accent(Core core) {
        return switch (core) {
            case GBA -> GBA_ACCENT;
            case THREE_DS -> THREE_DS_ACCENT;
            case NDS -> NDS_ACCENT;
            case SFC -> 0xFFB03030;
            case PS2 -> 0xFF1F5FBF;   // PlayStation 蓝
            case PSP -> 0xFF2B2B33;   // PSP 黑
        };
    }

    public static String model(Core core) {
        return switch (core) {
            case GBA -> "GAME BOY ADVANCE";
            case THREE_DS -> "NINTENDO 3DS";
            case NDS -> "NINTENDO DS";
            case SFC -> "SUPER FAMICOM";
            case PS2 -> "PLAYSTATION 2";
            case PSP -> "PLAYSTATION PORTABLE";
        };
    }

    /** 半透明机身板（0x66 alpha，世界可见）。 */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int accent) {
        int body = 0x66101418;
        g.fill(x, y, x + w, y + h, body);
        // 顶部一条机型色带
        g.fill(x, y, x + w, y + 2, (accent & 0x00FFFFFF) | 0x88000000);
        // 四边描边
        int edge = (accent & 0x00FFFFFF) | 0x66000000;
        g.fill(x, y, x + w, y + 1, edge);
        g.fill(x, y + h - 1, x + w, y + h, edge);
        g.fill(x, y, x + 1, y + h, edge);
        g.fill(x + w - 1, y, x + w, y + h, edge);
    }

    /** 屏幕凹槽（比机身更深一点，模拟玻璃框）。 */
    public static void screenWell(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x55000000);
    }

    /** 底部按键提示条。 */
    public static void legend(GuiGraphics g, net.minecraft.client.gui.Font font, int width, int height,
                              Core core, float volume, int extraInfo) {
        String keys = switch (core) {
            case GBA -> "方向键=十字键  K=A  J=B  I=X  L=Y  Enter=START  Tab=SELECT  Q/E=L/R";
            case THREE_DS, NDS -> "方向键/WASD=十字键  K=A  J=B  I=X  L=Y  Enter=START  Tab=SELECT  下屏=触摸";
            case SFC -> "方向键/WASD=十字键  K=A  J=B  I=X  L=Y  Enter=START  Tab=SELECT";
            case PS2 -> "方向键/WASD=十字键  K=×  J=○  I=□  L=△  Enter=START  Tab=SELECT  Q/E=L1/R1";
            case PSP -> "方向键=十字键  K=×  J=○  I=□  L=△  Enter=START  Tab=SELECT  Q/E=L/R";
        };
        String line = "ESC=存档退出    退格=删档回选单    +/-=音量 " + (int) (volume * 100) + "%"
                + (extraInfo > 0 ? "    帧 " + extraInfo : "");
        int y = height - 26;
        g.fill(0, y - 4, width, height, 0x55000000);
        g.drawString(font, keys, 6, y, 0xCCDDDDDD, true);
        g.drawString(font, line, 6, y + 11, 0x99AAAAAA, true);
    }

    /** 顶部机型标签。 */
    public static void title(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, Core core) {
        g.drawString(font, model(core), x, y, (accent(core) & 0x00FFFFFF) | 0xCC000000, true);
    }
}
