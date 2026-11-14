// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Set;

/**
 * 掌机机身外观（**仅全屏界面的 GUI**，与方块模型无关）。
 *
 * <p>按实物照片还原布局：
 * <ul>
 *   <li><b>GBA</b>：横向银色机身，左侧十字键 + START/SELECT，右侧 A/B，屏幕居中偏上；</li>
 *   <li><b>3DS</b>：上盖（黑边框屏幕 + 摄像头点），下半白色：左摇杆 + 十字键、右 X/Y/A/B、
 *       屏幕下方一排 SELECT / HOME / START；</li>
 *   <li><b>NDS</b>：同上但无摇杆，右侧 X/Y/A/B，机身更方，左下 POWER。</li>
 * </ul>
 *
 * <p>交互反馈：按键被按下时颜色变深；摇杆随模拟量偏移。
 */
public final class HandheldShell {

    public record Rect(int x, int y, int w, int h) {
        public int cx() { return x + w / 2; }
        public int cy() { return y + h / 2; }
        public boolean inside(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** 一次布局算出的所有矩形（屏幕 + 机身），供调用方 blit 与绘制。 */
    public record Layout(Rect body, Rect screenTop, Rect screenBottom, int sidebarX) {}

    private static boolean pspLayoutChecked;

    /** 从 PSP 3DS 模型前视图投影出的机身剪影贴图（1000×427） */
    private static final net.minecraft.resources.ResourceLocation PSP_SHELL_TEX =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minetendo", "textures/gui/psp_body.png");
    private static final int SHELL_W = 1000, SHELL_H = 427;

    // ── 从实物正面图逐行提取的真实剪影（48 行，归一化到机身包围盒）──
    private static final float[] PSP_OUTLINE_L = {
            0.2131f, 0.1375f, 0.1282f, 0.1181f, 0.1070f, 0.0987f, 0.0923f, 0.0867f,
            0.0812f, 0.0766f, 0.0729f, 0.0664f, 0.0627f, 0.0590f, 0.0554f, 0.0507f,
            0.0489f, 0.0443f, 0.0397f, 0.0341f, 0.0360f, 0.0304f, 0.0277f, 0.0332f,
            0.0277f, 0.0295f, 0.0286f, 0.0295f, 0.0332f, 0.0258f, 0.0332f, 0.0323f,
            0.0360f, 0.0415f, 0.0443f, 0.0470f, 0.0535f, 0.0637f, 0.0683f, 0.0738f,
            0.0867f, 0.0959f, 0.1033f, 0.1208f, 0.1458f, 0.1863f, 0.2408f, 0.5212f};
    private static final float[] PSP_OUTLINE_R = {
            0.8127f, 0.8856f, 0.8958f, 0.9059f, 0.9179f, 0.9262f, 0.9336f, 0.9382f,
            0.9511f, 0.9622f, 0.9622f, 0.9622f, 0.9686f, 0.9779f, 0.9797f, 0.9815f,
            0.9935f, 0.9945f, 0.9972f, 0.9991f, 0.9991f, 0.9991f, 0.9991f, 0.9991f,
            0.9991f, 0.9991f, 0.9991f, 0.9991f, 0.9991f, 0.9991f, 0.9991f, 0.9991f,
            0.9991f, 0.9991f, 0.9991f, 0.9963f, 0.9935f, 0.9806f, 0.9788f, 0.9677f,
            0.9520f, 0.9428f, 0.9290f, 0.9151f, 0.8524f, 0.8201f, 0.5378f, 0.5212f};
    // 屏幕在原图中的位置（实测：x 310..866, y 139..458，机身 bbox 27,115,1084x498）
    // 由 3DS 模型量得：Pantalla 对象 X 226.87..677.81、Z 64.00..323.22（前视包围盒 805.7×343.8）
    private static final float[] PSP_SCREEN = {0.2221f, 0.0960f, 0.5596f, 0.7540f};
    /** 左侧十字键中心（由 st_* 部件量得） */
    private static final float PSP_DPAD_X = 0.1118f, PSP_DPAD_Y = 0.5649f;
    /** 右侧 △○×□ 中心（由 s_1..s_4 部件量得） */
    private static final float PSP_FACE_X = 0.8863f, PSP_FACE_Y = 0.4602f;
    private HandheldShell() {}

    // ── 配色 ──
    private static final int GBA_BODY = 0xE6C8CCD2;      // 银
    private static final int GBA_BODY_DARK = 0xE6A8ADB5;
    private static final int GBA_SCREEN_FRAME = 0xF0202428;
    private static final int GBA_BTN_BLUE = 0xFF2C5FA8;
    private static final int GBA_BTN_BLUE_DOWN = 0xFF1B3E70;

    private static final int DS_WHITE = 0xF2F2F2F4;
    private static final int DS_WHITE_DARK = 0xE0D6D6DA;
    private static final int DS_BLACK = 0xF0181A1E;
    private static final int DS_BTN = 0xE8E8EAEE;
    private static final int DS_BTN_DOWN = 0xCCB8BCC4;
    private static final int DS_ACCENT = 0xFF2E9BB5;

    // ── 布局 ──────────────────────────────────────────────

    /** 单屏（GBA / SFC）：机身横向，屏幕尽量大，左侧留出提示区。 */
    public static Layout single(Core core, int width, int height) {
        int sideW = 118;
        int availW = width - sideW - 24;
        int availH = height - 32;
        // GBA 机身比例约 1.51:1；屏幕占机身宽度 46%
        float bodyRatio = 1.51f;
        int bodyW = Math.min(availW, (int) (availH * bodyRatio));
        int bodyH = (int) (bodyW / bodyRatio);
        int bodyX = (width - bodyW) / 2;   // 居中到整个屏幕
        int bodyY = (height - bodyH) / 2;

        int screenW = (int) (bodyW * 0.50f);
        int screenH = (int) (screenW * (core == Core.SFC ? 224f / 256f : 160f / 240f));
        if (screenH > bodyH * 0.74f) {
            screenH = (int) (bodyH * 0.74f);
            screenW = (int) (screenH * (core == Core.SFC ? 256f / 224f : 240f / 160f));
        }
        int screenX = bodyX + (bodyW - screenW) / 2;
        int screenY = bodyY + (int) (bodyH * 0.20f);

        return new Layout(new Rect(bodyX, bodyY, bodyW, bodyH),
                new Rect(screenX, screenY, screenW, screenH), null, 10);
    }

    /** 双屏（3DS / NDS）：上盖 + 下半，屏幕最大化，机身顶满上下。 */
    public static Layout dual(Core core, int width, int height) {
        int sideW = 118;
        int availW = width - sideW - 24;
        int availH = height - 16;
        boolean is3ds = core == Core.THREE_DS;

        float topAspect = is3ds ? 400f / 240f : 256f / 192f;
        float botAspect = is3ds ? 320f / 240f : 256f / 192f;

        // 由高度反推：上屏占可用高的 44%，机身宽 = 上屏宽 / 0.82（留边框与摄像头区）
        int topScreenH = (int) (availH * 0.44f);
        int topScreenW = (int) (topScreenH * topAspect);
        int bodyW = (int) (topScreenW / 0.82f);
        if (bodyW > availW) {
            bodyW = availW;
            topScreenW = (int) (bodyW * 0.82f);
            topScreenH = (int) (topScreenW / topAspect);
        }
        int topPanelH = (int) (topScreenH * 1.14f);          // 上盖（含黑边）
        int bodyH = availH;                                   // 机身顶满
        int botPanelH = bodyH - topPanelH;
        if (botPanelH < topScreenH * 0.9f) {                  // 下半天太小 → 压上屏
            topScreenH = (int) (availH * 0.38f);
            topScreenW = (int) (topScreenH * topAspect);
            bodyW = Math.min(availW, (int) (topScreenW / 0.82f));
            topScreenW = (int) (bodyW * 0.82f);
            topScreenH = (int) (topScreenW / topAspect);
            topPanelH = (int) (topScreenH * 1.14f);
            botPanelH = bodyH - topPanelH;
        }

        int bodyX = (width - bodyW) / 2;   // 居中到整个屏幕
        int bodyY = 6;

        int topScreenX = bodyX + (bodyW - topScreenW) / 2;
        int topScreenY = bodyY + (topPanelH - topScreenH) / 2;

        // 下屏：高度取下半的 72%，宽度不超过机身的 52%（两侧留给摇杆/按键）
        int botScreenH = (int) (botPanelH * 0.72f);
        int botScreenW = (int) (botScreenH * botAspect);
        int maxBotW = (int) (bodyW * 0.52f);
        if (botScreenW > maxBotW) {
            botScreenW = maxBotW;
            botScreenH = (int) (botScreenW / botAspect);
        }
        int botScreenX = bodyX + (bodyW - botScreenW) / 2;
        int botScreenY = bodyY + topPanelH + (int) (botPanelH * 0.05f);

        return new Layout(new Rect(bodyX, bodyY, bodyW, bodyH),
                new Rect(topScreenX, topScreenY, topScreenW, topScreenH),
                new Rect(botScreenX, botScreenY, botScreenW, botScreenH), 10);
    }

    // ── 绘制 ──────────────────────────────────────────────

    public static void drawGba(GuiGraphics g, Font font, Layout l, Set<Integer> held) {
        Rect b = l.body();
        // 机身：银灰 + 上下略暗，模拟塑感
        roundRect(g, b.x() - 6, b.y() - 6, b.w() + 12, b.h() + 12, GBA_BODY_DARK);
        roundRect(g, b.x(), b.y(), b.w(), b.h(), GBA_BODY);

        // 屏幕黑框 + 蓝边
        Rect s = l.screenTop();
        g.fill(s.x() - 8, s.y() - 8, s.x() + s.w() + 8, s.y() + s.h() + 8, GBA_BTN_BLUE);
        g.fill(s.x() - 5, s.y() - 5, s.x() + s.w() + 5, s.y() + s.h() + 5, GBA_SCREEN_FRAME);

        // 左侧十字键
        int dx = b.x() + (int) (b.w() * 0.13f);
        int dy = b.y() + (int) (b.h() * 0.52f);
        int arm = Math.max(8, (int) (b.w() * 0.035f));
        int thick = Math.max(6, (int) (b.w() * 0.028f));
        boolean up = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_UP);
        boolean down = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN);
        boolean left = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT);
        boolean right = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT);
        dpad(g, dx, dy, arm, thick, up, down, left, right);

        // 右侧 A / B
        int bxA = b.x() + (int) (b.w() * 0.84f);
        int byA = b.y() + (int) (b.h() * 0.46f);
        int r = Math.max(7, (int) (b.w() * 0.030f));
        boolean aDown = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_K);
        boolean bDown = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_J);
        circle(g, bxA + r + 4, byA, r, aDown ? GBA_BTN_BLUE_DOWN : GBA_BTN_BLUE);
        circle(g, bxA - r - 4, byA + r + 2, r, bDown ? GBA_BTN_BLUE_DOWN : GBA_BTN_BLUE);
        // 字母居中到圆心（之前偏移量写错，B 跑到圆外）
        int acx = bxA + r + 4, acy = byA;
        int bcx = bxA - r - 4, bcy = byA + r + 2;
        g.drawString(font, "A", acx - font.width("A") / 2, acy - 4, 0xDDEEFFEE, true);
        g.drawString(font, "B", bcx - font.width("B") / 2, bcy - 4, 0xDDEEFFEE, true);

        // 左下 START / SELECT
        int sy = b.y() + (int) (b.h() * 0.78f);
        pill(g, b.x() + (int) (b.w() * 0.10f), sy, 34, 9, GBA_BODY_DARK, "START", font);
        pill(g, b.x() + (int) (b.w() * 0.10f), sy + 13, 34, 9, GBA_BODY_DARK, "SELECT", font);

        // 屏幕下缘 GAME BOY ADVANCE 字样
        String label = "GAME BOY ADVANCE";
        int lw = font.width(label);
        g.drawString(font, label, s.cx() - lw / 2, s.y() + s.h() + 12, 0xFF2C3E70, true);
    }

    public static void draw3ds(GuiGraphics g, Font font, Layout l, Set<Integer> held, int axisX, int axisY) {
        Rect b = l.body();
        Rect top = l.screenTop();
        Rect bot = l.screenBottom();

        // 上盖：黑色整块（含摄像头点）
        int topPanelH = top.y() + top.h() + (int) (b.h() * 0.05f);
        roundRect(g, b.x(), b.y(), b.w(), topPanelH - b.y() + topPanelH, DS_BLACK);
        roundRect(g, b.x(), b.y(), b.w(), top.y() + top.h() + 14 - b.y(), DS_BLACK);
        circle(g, b.cx(), b.y() + 8, 3, 0xFF3A3D42);

        // 下半白色
        int lowerY = top.y() + top.h() + 16;
        roundRect(g, b.x(), lowerY, b.w(), b.y() + b.h() - lowerY, DS_WHITE);
        // 铰链
        g.fill(b.x() + 8, lowerY - 6, b.x() + b.w() - 8, lowerY + 2, DS_WHITE_DARK);

        // 摇杆（左）
        int padX = b.x() + (int) (b.w() * 0.115f);
        int padY = bot.y() + (int) (bot.h() * 0.22f);
        int padR = Math.max(8, (int) (b.w() * 0.030f));
        circle(g, padX, padY, padR + 3, DS_WHITE_DARK);
        circle(g, padX + axisX / 32767 * 3, padY + axisY / 32767 * 3, padR, 0xFFBFC4CC);
        circle(g, padX + axisX / 32767 * 3, padY + axisY / 32767 * 3, padR - 3, 0xFFE4E7EC);

        // 十字键（左下）
        int dx = b.x() + (int) (b.w() * 0.075f);   // 左侧 15% 区域中心
        int dy = bot.y() + (int) (bot.h() * 0.68f);
        int arm = Math.max(7, (int) (b.w() * 0.024f));
        int thick = Math.max(4, (int) (b.w() * 0.017f));
        dpad(g, dx, dy, arm, thick,
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_UP),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT));

        // 右侧四键菱形 X / Y / A / B
        int fx = bot.x() + bot.w() + (int) (b.w() * 0.085f);   // 下屏右缘外，避免压屏
        int fy = bot.y() + (int) (bot.h() * 0.50f);
        int r = Math.max(6, (int) (b.w() * 0.019f));
        faceButtons(g, font, fx, fy, r, held);

        // SELECT / HOME / START
        int rowY = bot.y() + bot.h() + 8;
        int cx = bot.cx();
        pill(g, cx - 72, rowY, 44, 9, DS_WHITE_DARK, "SELECT", font);
        pill(g, cx - 18, rowY, 36, 9, DS_WHITE_DARK, "HOME", font);
        pill(g, cx + 26, rowY, 44, 9, DS_WHITE_DARK, "START", font);
    }

    public static void drawNds(GuiGraphics g, Font font, Layout l, Set<Integer> held) {
        Rect b = l.body();
        Rect top = l.screenTop();
        Rect bot = l.screenBottom();

        roundRect(g, b.x(), b.y(), b.w(), top.y() + top.h() + 14 - b.y(), DS_WHITE);
        g.fill(top.x() - 6, top.y() - 6, top.x() + top.w() + 6, top.y() + top.h() + 6, DS_BLACK);

        int lowerY = top.y() + top.h() + 16;
        roundRect(g, b.x(), lowerY, b.w(), b.y() + b.h() - lowerY, DS_WHITE);
        g.fill(b.x() + 8, lowerY - 6, b.x() + b.w() - 8, lowerY + 2, DS_WHITE_DARK);

        // 左下十字键
        int dx = b.x() + (int) (b.w() * 0.15f);
        int dy = bot.y() + (int) (bot.h() * 0.55f);
        int arm = Math.max(7, (int) (b.w() * 0.024f));
        int thick = Math.max(4, (int) (b.w() * 0.017f));
        dpad(g, dx, dy, arm, thick,
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_UP),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT));

        // 右侧四键
        int fx = bot.x() + bot.w() + (int) (b.w() * 0.085f);
        int fy = bot.y() + (int) (bot.h() * 0.42f);
        int r = Math.max(6, (int) (b.w() * 0.019f));
        faceButtons(g, font, fx, fy, r, held);

        // START / SELECT
        int rowY = bot.y() + bot.h() + 8;
        pill(g, b.cx() + 10, rowY, 44, 9, DS_WHITE_DARK, "START", font);
        pill(g, b.cx() + 10, rowY + 12, 44, 9, DS_WHITE_DARK, "SELECT", font);
        // POWER
        circle(g, b.x() + 18, b.y() + b.h() - 16, 5, 0xFF9AA4AD);
        g.drawString(font, "POWER", b.x() + 26, b.y() + b.h() - 20, 0xFF8A9098, true);
    }

    /** 侧边说明（放在屏幕左侧，不再压底部）。 */
    public static void sidebar(GuiGraphics g, Font font, int x, int y, Core core, float volume, int fps, int texW, int texH) {
        int accent = HandheldUi.accent(core);
        g.drawString(font, HandheldUi.model(core), x, y, (accent & 0x00FFFFFF) | 0xDD000000, true);
        int yy = y + 14;
        String[] lines = switch (core) {
            case GBA -> new String[]{
                    "方向键 / WASD = 十字键",
                    "K = A    J = B",
                    "I = X    L = Y",
                    "Enter = START",
                    "Tab = SELECT",
                    "Q / E = L / R",
                    "小键盘 2/4/6/8 = 右摇杆",
                    "",
                    "ESC = 存档退出",
                    "退格 = 删档回选单",
                    "+ / - = 音量 " + (int) (volume * 100) + "%",
                    "",
                    fps + " FPS   " + texW + "x" + texH};
            default -> new String[]{
                    "方向键 / WASD = 十字键",
                    "K = A    J = B",
                    "I = X    L = Y",
                    "Enter = START",
                    "Tab = SELECT",
                    "下屏 = 鼠标触摸",
                    "",
                    "ESC = 存档退出",
                    "退格 = 删档回选单",
                    "+ / - = 音量 " + (int) (volume * 100) + "%",
                    "",
                    fps + " FPS   " + texW + "x" + texH};
        };
        for (String s : lines) {
            g.drawString(font, s, x, yy, 0xCCDDDDDD, true);
            yy += 11;
        }
    }

    // ── 小工具 ────────────────────────────────────────────

    private static void faceButtons(GuiGraphics g, Font font, int cx, int cy, int r, Set<Integer> held) {
        boolean x = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_I);
        boolean y = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_L);
        boolean a = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_K);
        boolean b = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_J);
        int d = (int) Math.round(r * 1.75);      // 簇内间距（比原来 2r+2 更紧）
        circle(g, cx, cy - d, r, x ? DS_BTN_DOWN : DS_BTN);
        circle(g, cx - d, cy, r, y ? DS_BTN_DOWN : DS_BTN);
        circle(g, cx + d, cy, r, a ? DS_BTN_DOWN : DS_BTN);
        circle(g, cx, cy + d, r, b ? DS_BTN_DOWN : DS_BTN);
        int lh = 4;
        g.drawString(font, "X", cx - font.width("X") / 2, cy - d - lh, 0xFF3A3D42, true);
        g.drawString(font, "Y", cx - d - font.width("Y") / 2, cy - lh, 0xFF3A3D42, true);
        g.drawString(font, "A", cx + d - font.width("A") / 2, cy - lh, 0xFF3A3D42, true);
        g.drawString(font, "B", cx - font.width("B") / 2, cy + d - lh, 0xFF3A3D42, true);
    }

    private static void dpad(GuiGraphics g, int cx, int cy, int arm, int thick,
                             boolean up, boolean down, boolean left, boolean right) {
        int base = 0xFF3A3D42;
        int press = 0xFF1B1D21;
        g.fill(cx - thick / 2, cy - arm, cx + thick / 2, cy + arm, base);
        g.fill(cx - arm, cy - thick / 2, cx + arm, cy + thick / 2, base);
        if (up) g.fill(cx - thick / 2, cy - arm, cx + thick / 2, cy - thick / 2, press);
        if (down) g.fill(cx - thick / 2, cy + thick / 2, cx + thick / 2, cy + arm, press);
        if (left) g.fill(cx - arm, cy - thick / 2, cx - thick / 2, cy + thick / 2, press);
        if (right) g.fill(cx + thick / 2, cy - thick / 2, cx + arm, cy + thick / 2, press);
        // 中心
        g.fill(cx - thick / 4, cy - thick / 4, cx + thick / 4, cy + thick / 4, 0xFF2A2C31);
    }

    private static void pill(GuiGraphics g, int x, int y, int w, int h, int color, String label, Font font) {
        roundRect(g, x, y, w, h, color);
        g.drawString(font, label, x + 2, y + 1, 0xFF5A6068, false);
    }

    private static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    /** 大圆角机身（PSP 那种两端半圆）：中间矩形 + 两端半圆。 */
    private static void stadium(GuiGraphics g, int x, int y, int w, int h, int color) {
        int r = Math.min(h / 2, w / 2);
        g.fill(x + r, y, x + w - r, y + h, color);
        circle(g, x + r, y + r, r, color);
        circle(g, x + r, y + h - r - 1, r, color);
        circle(g, x + w - r, y + r, r, color);
        circle(g, x + w - r, y + h - r - 1, r, color);
    }

    private static void roundRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
    }
    // ── PSP（按实物正面图：宽机身、左右金属边、底部功能键条）────────────

    /** PSP 布局：屏幕尽量大，左右留出十字键/摇杆与 △○×□ 的空间。 */
    /**
     * PSP 布局（按实物 169.4 × 71.4 mm，比例 2.37:1）。
     *
     * <p>屏幕只占机身宽约 40%（实物 480×272 面板 ≈ 66mm 宽），两侧各留约 30% 给按键；
     * 机身上下是**大圆角**（画的时候用 stadium 形状），不是方块。
     */
    /**
     * PSP 布局：**画面优先**。
     *
     * <p>屏幕取窗口能容纳的最大 16:9 区域（高度 86%，宽度 84%），
     * 机身只是屏幕外的一圈壳（左右各留 ~11% 给十字键/摇杆与 △○×□），
     * 这样画面够大、按键也不会压到画面。
     */
    /**
     * PSP 布局 —— **画面优先**：屏幕占满窗口（16:9 最大内接），机身只留一圈细边，
     * 十字键 / 摇杆 / △○×□ 以半透明形式叠在画面四角（像手机模拟器的手柄层），
     * 这样画面最大，按键也不挡中间的游戏内容。
     */
    /**
     * PSP 布局：**机身填满可用高度**，屏幕取机身内能容下的最大 16:9，
     * 两侧只留刚好放得下十字键/摇杆与 △○×□ 的宽度。
     */
    public static Layout psp(int width, int height) {
        int pad = 10;
        int availW = width - pad * 2;
        int availH = height - pad * 2;

        // 机身：用满可用宽高（剪影本身近似 2.18:1，窄窗口时退化为更方一点）
        int bodyW = availW;
        int bodyH = (int) Math.round(bodyW / 2.344f);
        if (bodyH > availH) {
            bodyH = availH;
            bodyW = (int) Math.round(bodyH * 2.344f);
        }
        int bodyX = (width - bodyW) / 2;
        int bodyY = (height - bodyH) / 2;

        // 屏幕 = 机身 × 实测比例（占机身宽 51.3%、高 64.1%）
        int screenX = bodyX + (int) (bodyW * PSP_SCREEN[0]);
        int screenY = bodyY + (int) (bodyH * PSP_SCREEN[1]);
        int screenW = (int) (bodyW * PSP_SCREEN[2]);
        int screenH = (int) (bodyH * PSP_SCREEN[3]);
        return new Layout(new Rect(bodyX, bodyY, bodyW, bodyH),
                new Rect(screenX, screenY, screenW, screenH), null, 8);
    }






    /** 画 PSP 机身（金属边 + 黑色中段 + 屏幕框 + 按键 + 底部功能条），并自检是否压住屏幕。 */
    public static void drawPsp(GuiGraphics g, Font font, Layout l, Set<Integer> held, int axisX, int axisY, int axisX2, int axisY2) {
        Rect b = l.body();
        Rect s = l.screenTop();

        // ① 机身：模型前视图投影出的剪影贴图（带透明通道），直接拉伸到机身矩形
        g.blit(PSP_SHELL_TEX, b.x(), b.y(), b.w(), b.h(), 0f, 0f, SHELL_W, SHELL_H, SHELL_W, SHELL_H);

        // ② 屏幕：位置/大小由模型 Pantalla 部件量得，贴黑底后由外部画游戏画面
        g.fill(s.x() - 3, s.y() - 3, s.x() + s.w() + 3, s.y() + s.h() + 3, 0xFF0A0B0D);
        g.fill(s.x(), s.y(), s.x() + s.w(), s.y() + s.h(), 0xFF000000);

        // ③ 十字键 / 摇杆 / △○×□：位置来自模型部件（st_* / s_1..s_4）中心
        int dpadCx = b.x() + (int) (b.w() * PSP_DPAD_X);
        int dpadCy = b.y() + (int) (b.h() * PSP_DPAD_Y);
        int arm = Math.max(9, (int) (b.h() * 0.085f));
        int thick = Math.max(7, (int) (arm * 0.62f));
        dpad(g, dpadCx, dpadCy, arm, thick,
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_UP),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT),
                held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT));

        int nubR = Math.max(10, (int) (b.h() * 0.075f));
        int nubCy = dpadCy + arm + nubR + 8;
        int ndx = axisX / 32767 * Math.max(1, nubR / 3);
        int ndy = axisY / 32767 * Math.max(1, nubR / 3);
        circle(g, dpadCx, nubCy, nubR + 3, 0xFF202329);
        circle(g, dpadCx + ndx, nubCy + ndy, nubR, 0xFF4A4E55);
        circle(g, dpadCx + ndx, nubCy + ndy, Math.max(3, nubR - 4), 0xFF7A7F86);

        int faceCx = b.x() + (int) (b.w() * PSP_FACE_X);
        int faceCy = b.y() + (int) (b.h() * PSP_FACE_Y);
        int r = Math.max(9, (int) (b.h() * 0.062f));
        int d = (int) Math.round(r * 1.65);
        boolean tri = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_L);   // △ = L
        boolean cir = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_J);   // ○ = J
        boolean cro = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_K);   // × = K
        boolean squ = held.contains(org.lwjgl.glfw.GLFW.GLFW_KEY_I);   // □ = I
        circle(g, faceCx, faceCy - d, r, tri ? 0xFF4A4E55 : 0xFF2C3036);
        circle(g, faceCx + d, faceCy, r, cir ? 0xFF4A4E55 : 0xFF2C3036);
        circle(g, faceCx, faceCy + d, r, cro ? 0xFF4A4E55 : 0xFF2C3036);
        circle(g, faceCx - d, faceCy, r, squ ? 0xFF4A4E55 : 0xFF2C3036);
        g.drawString(font, "△", faceCx - font.width("△") / 2, faceCy - d - 4, 0xFFE8EAEE, true);
        g.drawString(font, "○", faceCx + d - font.width("○") / 2, faceCy - 4, 0xFFE8EAEE, true);
        g.drawString(font, "×", faceCx - font.width("×") / 2, faceCy + d - 4, 0xFFE8EAEE, true);
        g.drawString(font, "□", faceCx - d - font.width("□") / 2, faceCy - 4, 0xFFE8EAEE, true);

        // 右摇杆（面键簇下方，随 J/K/L/I 偏移）
        int nub2R = nubR;
        int nub2Cy = faceCy + d + r + nub2R + 12;
        int ndx2 = axisX2 / 32767 * Math.max(1, nub2R / 3);
        int ndy2 = axisY2 / 32767 * Math.max(1, nub2R / 3);
        circle(g, faceCx, nub2Cy, nub2R + 3, 0xFF202329);
        circle(g, faceCx + ndx2, nub2Cy + ndy2, nub2R, 0xFF4A4E55);
        circle(g, faceCx + ndx2, nub2Cy + ndy2, Math.max(3, nub2R - 4), 0xFF7A7F86);

        if (!pspLayoutChecked) {
            pspLayoutChecked = true;
            System.out.println("[Minetendo] PSP 模型布局: body=" + b.w() + "x" + b.h()
                    + " screen=" + s.w() + "x" + s.h()
                    + " 屏幕占机身宽 " + String.format("%.0f%%", s.w() * 100f / b.w())
                    + " 高 " + String.format("%.0f%%", s.h() * 100f / b.h())
                    + " dpad=(" + dpadCx + "," + dpadCy + ") face=(" + faceCx + "," + faceCy + ")"
                    + " arm=" + arm + " nubR=" + nubR + " r=" + r);
        }
    }
}
