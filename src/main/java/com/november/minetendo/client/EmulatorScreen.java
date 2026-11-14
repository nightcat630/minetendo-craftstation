// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.november.minetendo.SessionHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 全屏模拟器界面（所有机种共用）。 */
public class EmulatorScreen extends Screen {

    private static final int BTN_B = 0, BTN_Y = 1, BTN_SELECT = 2, BTN_START = 3;
    private static final int BTN_UP = 4, BTN_DOWN = 5, BTN_LEFT = 6, BTN_RIGHT = 7;
    private static final int BTN_A = 8, BTN_X = 9, BTN_L = 10, BTN_R = 11;

    private final Core core;
    private final Path game;

    private SessionHost session;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private int texW = 400, texH = 480;
    private String error;
    private int frames;

    private final Set<Integer> held = new HashSet<>();
    private final EmuAudio audio = new EmuAudio();
    private boolean audioStarted;
    private int lastAudioRead;

    private boolean touching;
    private long openedAtMs;
    private int lastSeq;
    private double uploadMs;

    private int fpsShown, fpsFrames;
    private long fpsAtMs = System.currentTimeMillis();
    private double frameGapAvg, frameGapMax;
    private long lastUploadAtNs, gapSumNs, gapMaxNs;
    private int gapCount;
    /** 即时存档文件：minetendo/<机种>/saves/<rom>.snap */
    /** 待恢复的快照：会话就绪后再读（open 是异步的，init 时 token 还是 0） */
    private Path pendingSnapshot;
    private int snapshotAttempts;
    private int snapshotNextTryFrame;

    private Path snapshotPath() {
        return FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(core.dir).resolve("saves")
                .resolve(game.getFileName().toString().replaceAll("\\.[^.]+$", ".snap"));
    }

    public EmulatorScreen(Core core, Path game) {
        super(Component.literal("Minetendo " + core.label));
        this.core = core;
        this.game = game;
    }

    @Override
    protected void init() {
        super.init();
        if (!SessionHost.load()) {
            error = "原生库加载失败（只支持 Windows x64）";
            return;
        }
        Path base = FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(core.dir);
        Path corePath = base.resolve("core").resolve(core.coreDll);
        Path sys = base.resolve("sysdata");
        if (!Files.isRegularFile(corePath)) {
            error = "缺少核心：" + corePath;
            return;
        }
        if (game == null || !Files.isRegularFile(game)) {
            error = "游戏文件不存在：" + game;
            return;
        }

        texture = new DynamicTexture(texW, texH, false);
        textureId = ResourceLocation.fromNamespaceAndPath("minetendo", "emulator_screen");
        Minecraft.getInstance().getTextureManager().register(textureId, texture);

        SessionHost.captureContext();
        pendingSnapshot = Files.isRegularFile(snapshotPath()) ? snapshotPath() : null;
        openedAtMs = System.currentTimeMillis();
        session = SessionHost.open(corePath, game, sys);
        if (session == null) {
            error = "会话启动失败：" + SessionHost.lastError();
            return;
        }
        // 注意：SessionHost.open() 是异步的，这里 token 还是 0，不能立刻读档。
        // 改为在 render() 里等会话就绪后读一次（见 pendingSnapshot）。
        lockWindow(false);
        // 音频必须等会话就绪后再开：open() 是异步的，此时 sampleRate 还是 0，
        // 会退回兜底 32730，导致 44100 的音频被放慢（女声变男声）。
    }

    /** 单屏机型（GBA / SFC）：一块屏幕居中，外面套半透明机身。 */
    /** 单屏机型（GBA / SFC）：按实物机身布局绘制。 */
    private void drawSingleScreen(GuiGraphics g, int texW, int texH, int mouseX, int mouseY) {
        // PSP：专属机身（金属边、左侧十字键+摇杆、右侧 △○×□、底部功能条）
        if (core == Core.PSP) {
            var pspLayout = HandheldShell.psp(width, height);
            HandheldShell.drawPsp(g, font, pspLayout, held, axisX(), axisY(), axisX2(), axisY2());
            var ps = pspLayout.screenTop();
            g.blit(textureId, ps.x(), ps.y(), ps.w(), ps.h(), 0f, 0f, texW, texH, texW, texH);
            return;
        }
        var layout = HandheldShell.single(core, width, height);
        if (core == Core.GBA) HandheldShell.drawGba(g, font, layout, held);
        else HandheldUi.panel(g, layout.body().x(), layout.body().y(), layout.body().w(), layout.body().h(),
                HandheldUi.accent(core));
        var s = layout.screenTop();
        if (core.touch) pushTouch(mouseX, mouseY, s.x(), s.y(), s.w(), s.h(), 0, 0, texW, texH, texW, texH);
        g.blit(textureId, s.x(), s.y(), s.w(), s.h(), 0f, 0f, texW, texH, texW, texH);
    }

    /** 双屏机型（3DS）：上盖黑边框屏幕 + 下半按键区。 */
    private void drawDualScreen(GuiGraphics g, int texW, int texH, int mouseX, int mouseY) {
        var layout = HandheldShell.dual(core, width, height);
        HandheldShell.draw3ds(g, font, layout, held, axisX(), axisY());
        var top = layout.screenTop();
        var bot = layout.screenBottom();
        g.blit(textureId, top.x(), top.y(), top.w(), top.h(), 0f, 0f, 400, 240, texW, texH);
        g.blit(textureId, bot.x(), bot.y(), bot.w(), bot.h(), 40f, 240, 320, 240, texW, texH);
        if (core.touch) pushTouch(mouseX, mouseY, bot.x(), bot.y(), bot.w(), bot.h(),
                40, 240, 320, 240, 400, 480);   // 3DS 下屏在整帧中的位置
    }
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 故意留空
    }

    private void ensureTextureSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (texture != null && w == texW && h == texH) return;
        texW = w;
        texH = h;
        texture = new DynamicTexture(w, h, false);
        textureId = ResourceLocation.fromNamespaceAndPath("minetendo", "emulator_screen");
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        // 背景：不铺纯黑（世界可见）；只在屏幕外框画半透明机身

        if (error != null) {
            g.drawCenteredString(font, error, width / 2, height / 2 - 10, 0xFF5555);
            g.drawCenteredString(font, "ESC 关闭", width / 2, height / 2 + 10, 0xAAAAAA);
            return;
        }
        if (session == null) return;
        if (session.failed()) { error = "会话异常：" + session.failure(); return; }
        if (!session.ready()) {
            g.drawCenteredString(font, "正在启动 " + core.label + "…", width / 2, height / 2 - 20, 0xFFFFFF);
            g.drawCenteredString(font, "已跑 " + session.frames() + " 帧", width / 2, height / 2, 0xAAAAAA);
            return;
        }

        // 读档：PPSSPP/PCSX2 启动要编译 JIT、载入镜像，60 帧时常常还没就绪 →
        // 失败就隔 60 帧再试，最多 8 次；**绝不删除快照**（删了就永远丢档）
        if (pendingSnapshot != null && session.frames() > 60 && snapshotAttempts < 8) {
            if (session.frames() >= snapshotNextTryFrame) {
                snapshotAttempts++;
                snapshotNextTryFrame = session.frames() + 60;
                try {
                    boolean ok = session.loadState(pendingSnapshot);
                    System.out.println("[Minetendo] 读档第 " + snapshotAttempts + " 次 "
                            + (ok ? "成功: " : "失败: ") + pendingSnapshot);
                    if (ok) pendingSnapshot = null;
                } catch (Throwable t) {
                    System.out.println("[Minetendo] 读档异常（保留快照）: " + t);
                }
            }
        }
        if (!audioStarted && session.ready() && session.sampleRate() > 1000) {
            audioStarted = audio.start(session.sampleRate());
            System.out.println("[Minetendo] 音频就绪 @" + session.sampleRate() + "Hz");
        }
        frames++;
        ensureTextureSize(session.width(), session.height());
        int seq = session.frameSeq();
        if (seq != lastSeq) {
            lastSeq = seq;
            long up0 = System.nanoTime();
            // 核心跟不上时，隔帧跳过上传（画面直接复用上一帧，省掉一次 GL 搬运）
            if (!session.skipUpload()) session.uploadTo(texture.getId());
            uploadMs = (System.nanoTime() - up0) / 1_000_000.0;
            long nowNs = System.nanoTime();
            if (lastUploadAtNs != 0) {
                long gap = nowNs - lastUploadAtNs;
                gapSumNs += gap; gapCount++;
                if (gap > gapMaxNs) gapMaxNs = gap;
            }
            lastUploadAtNs = nowNs;
        }

        if (audioStarted) {
            ByteBuffer ab = session.audioBuffer();
            ab.clear();
            int af = session.readAudio(ab, 4096);
            lastAudioRead = af;
            if (af > 0) audio.pump(ab.asShortBuffer(), af * 2);
        }

        session.setInput(buttons(), axisX(), axisY(), axisX2(), axisY2());

        // ── 按机种布局 ──
        switch (core) {
            case THREE_DS -> drawDualScreen(g, texW, texH, mouseX, mouseY);
            default -> drawSingleScreen(g, texW, texH, mouseX, mouseY);
        }

        int coreFrames = session.frames();
        long nowMs = System.currentTimeMillis();
        if (nowMs - fpsAtMs >= 1000) {
            fpsShown = (int) ((coreFrames - fpsFrames) * 1000L / Math.max(1, nowMs - fpsAtMs));
            fpsFrames = coreFrames;
            fpsAtMs = nowMs;
            if (gapCount > 0) {
                frameGapAvg = gapSumNs / (double) gapCount / 1_000_000.0;
                frameGapMax = gapMaxNs / 1_000_000.0;
            }
            gapSumNs = 0; gapCount = 0; gapMaxNs = 0;
        }

        HandheldShell.sidebar(g, font, 8, 8, core, audio.volume(), fpsShown, texW, texH);
    }

    /**
     * 触摸：把鼠标位置换算到**整帧**归一化坐标（libretro 指针是相对整个帧的）。
     *
     * <p>3DS 帧是 400×480（上屏在上、下屏在下且左右各留 40 像素），因此不能只把下屏
     * 当满幅 —— 那样会整体缩小并偏移。
     *
     * @param sx,sy,sw,sh 该屏幕在界面上的矩形
     * @param fx,fy,fw,fh 该屏幕在帧缓冲里的源矩形
     */
    private void pushTouch(int mouseX, int mouseY, int sx, int sy, int sw, int sh,
                           int fx, int fy, int fw, int fh, int frameW, int frameH) {
        boolean inside = mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + sh;
        if (!inside || !touching) { session.setPointer(0, 0, false); return; }
        double u = (mouseX - sx) / (double) sw;          // 0..1 在屏幕内
        double v = (mouseY - sy) / (double) sh;
        double frameX = (fx + u * fw) / frameW;          // 换算到整帧 0..1
        double frameY = (fy + v * fh) / frameH;
        session.setPointer((int) Math.round((frameX * 2 - 1) * 32767),
                (int) Math.round((frameY * 2 - 1) * 32767), true);
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) { touching = true; return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) { touching = false; return true; }
        return super.mouseReleased(mx, my, button);
    }

    private int buttons() {
        int b = 0;
        for (int key : held) {
            switch (key) {
                case GLFW.GLFW_KEY_UP -> b |= 1 << BTN_UP;   // 十字键只认方向键
                case GLFW.GLFW_KEY_DOWN -> b |= 1 << BTN_DOWN;
                case GLFW.GLFW_KEY_LEFT -> b |= 1 << BTN_LEFT;
                case GLFW.GLFW_KEY_RIGHT -> b |= 1 << BTN_RIGHT;
                // PSP：△=G □=H ×=T ○=Y；其他机种保持 K=A J=B I=X L=Y
                // PSP 面键沿用之前那版：K=× J=○ I=□ L=△（避免和聊天键 T 冲突）
                case GLFW.GLFW_KEY_K -> b |= 1 << (core == Core.PS2 ? BTN_B : BTN_A);   // PSP ×
                case GLFW.GLFW_KEY_J -> b |= 1 << (core == Core.PS2 ? BTN_A : BTN_B);   // PSP ○
                case GLFW.GLFW_KEY_I -> b |= 1 << BTN_X;                                  // PSP □
                case GLFW.GLFW_KEY_L -> b |= 1 << BTN_Y;                                  // PSP △
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> b |= 1 << BTN_START;
                case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_RIGHT_SHIFT -> b |= 1 << BTN_SELECT;
                case GLFW.GLFW_KEY_Q -> b |= 1 << BTN_L;
                case GLFW.GLFW_KEY_E -> b |= 1 << BTN_R;
                default -> { }
            }
        }
        return b;
    }

    /** 右摇杆：小键盘 4=左 6=右 8=上 2=下（面键已占用 K/J/I/L） */
    private int axisX2() {
        int v = 0;
        if (held.contains(GLFW.GLFW_KEY_KP_4)) v -= 32767;
        if (held.contains(GLFW.GLFW_KEY_KP_6)) v += 32767;
        return v;
    }

    private int axisY2() {
        int v = 0;
        if (held.contains(GLFW.GLFW_KEY_KP_8)) v -= 32767;
        if (held.contains(GLFW.GLFW_KEY_KP_2)) v += 32767;
        return v;
    }

    private int axisX() {
        int v = 0;
        if (held.contains(GLFW.GLFW_KEY_A)) v -= 32767;   // 摇杆 = WASD
        if (held.contains(GLFW.GLFW_KEY_D)) v += 32767;
        return v;
    }

    private int axisY() {
        int v = 0;
        if (held.contains(GLFW.GLFW_KEY_W)) v -= 32767;
        if (held.contains(GLFW.GLFW_KEY_S)) v += 32767;
        return v;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC：写快照后退出（下次打开自动接着玩）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            try {
                Path snap = snapshotPath();
                Files.createDirectories(snap.getParent());
                boolean ok = session.saveState(snap);
                System.out.println("[Minetendo] 退出快照 " + (ok ? "已保存: " : "保存失败: ") + snap);
            } catch (Throwable t) {
                System.out.println("[Minetendo] 快照异常: " + t);
            }
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        // 退格：彻底退出 —— 不写快照，并且**删掉已有快照**，下次从头开始
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            try {
                Path snap = snapshotPath();
                boolean gone = Files.deleteIfExists(snap);
                System.out.println("[Minetendo] 退格彻底退出，快照" + (gone ? "已删除: " : "不存在: ") + snap);
            } catch (Throwable t) {
                System.out.println("[Minetendo] 删除快照失败: " + t);
            }
            Minecraft.getInstance().setScreen(new RomPickerScreen(core, null));
            return true;
        }
        // +/- 调整音量
        if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
            audio.setVolume(audio.volume() + 0.1f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            audio.setVolume(audio.volume() - 0.1f);
            return true;
        }
        held.add(keyCode);
        return true;
    }

    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        held.remove(keyCode);
        return true;
    }

    @Override
    public void removed() {
        held.clear();
        lockWindow(true);
        if (session != null) { session.close(); session = null; }
        if (audioStarted) { audio.stop(); audioStarted = false; }
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            textureId = null;
        }
        texture = null;
        GlStateReset.afterEmulatorCall();
        super.removed();
    }

    private static void lockWindow(boolean resizable) {
        try {
            long handle = Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_RESIZABLE,
                    resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        } catch (Throwable ignored) {
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}



