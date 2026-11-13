package com.november.minetendo.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.november.mcphonends.core.NativeMelon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * NDS 全屏界面：上下两屏竖着拼成 256×384。
 *
 * <p>帧来路：模拟线程 {@code runFrame → copyFrames → 两块直接缓冲}，
 * 渲染线程只做两次 {@code glTexSubImage2D}（与 1.20 的 NdsFramebuffer 同样思路）。
 */
public class NdsScreen extends Screen {

    private static final int W = 256, H = 192, BYTES = W * H * 4;

    private final Path game;
    private long handle;
    private Thread worker;
    private volatile boolean running;

    private final ByteBuffer top = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer bottom = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());
    private final Object frameLock = new Object();
    private final short[] audioScratch = new short[4096 * 2];
    private EmuAudio audio;

    private DynamicTexture texture;
    private ResourceLocation textureId;
    private String error;
    private final Set<Integer> held = new HashSet<>();
    private boolean touching;
    private int frames;
    private float volume = 1.0f;
    /** 待恢复的快照：等引擎跑过若干帧再读，否则核心会崩 */
    private Path pendingSnapshot;
    private int bootFrames;

    private Path snapshotPath() {
        return FMLPaths.GAMEDIR.get().resolve("minetendo/nds/saves")
                .resolve(game.getFileName().toString().replaceAll("\\.[^.]+$", ".snap"));
    }

    public NdsScreen(Path game) {
        super(Component.literal("Minetendo NDS"));
        this.game = game;
    }

    @Override
    protected void init() {
        super.init();
        if (!NativeMelon.load()) { error = "NDS 原生库加载失败"; return; }
        Path base = FMLPaths.GAMEDIR.get().resolve("minetendo/nds");
        Path save = base.resolve("saves");
        try { Files.createDirectories(save); } catch (Exception ignored) {}

        handle = NativeMelon.create(game.toAbsolutePath().toString(),
                save.resolve(game.getFileName().toString().replaceAll("\\.[^.]+$", ".sav")).toString(),
                base.toString());
        if (handle == 0L) { error = "NDS 会话创建失败：" + NativeMelon.lastError(0L); return; }

        texture = new DynamicTexture(W, H * 2, false);
        textureId = ResourceLocation.fromNamespaceAndPath("minetendo", "nds_screen");
        Minecraft.getInstance().getTextureManager().register(textureId, texture);

        audio = new EmuAudio();
        audio.start(32768);

        // 读档必须等引擎真正跑起来（刚 create 就 loadState 会把这个核心打崩）。
        pendingSnapshot = Files.isRegularFile(snapshotPath()) ? snapshotPath() : null;
        running = true;
        worker = new Thread(this::loop, "Minetendo NDS");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running) {
            long t0 = System.nanoTime();
            try {
                NativeMelon.runFrame(handle);
                synchronized (frameLock) {
                    NativeMelon.copyFrames(handle, top, bottom);
                if (pendingSnapshot != null && ++bootFrames > 30) {
                    Path snap = pendingSnapshot;
                    pendingSnapshot = null;
                    try {
                        if (NativeMelon.loadState(handle, snap.toAbsolutePath().toString())) {
                            System.out.println("[Minetendo] NDS 已恢复快照：" + snap);
                        } else {
                            System.out.println("[Minetendo] NDS 快照读取失败，已删除：" + snap);
                            Files.deleteIfExists(snap);
                        }
                    } catch (Throwable t) {
                        System.out.println("[Minetendo] NDS 读档异常: " + t);
                    }
                }
                }
                int n = NativeMelon.readAudio(handle, audioScratch, 4096);
                if (n > 0 && audio != null) {
                    audio.pump(java.nio.ShortBuffer.wrap(audioScratch, 0, n * 2), n * 2);
                }
            } catch (Throwable t) {
                error = "NDS 运行异常：" + t;
                return;
            }
            long spent = System.nanoTime() - t0;
            long sleep = (16_666_667L - spent) / 1_000_000L;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        // 不再铺纯黑：世界透出来，只画半透明机身与两块屏幕凹槽
        if (error != null) {
            g.drawCenteredString(font, error, width / 2, height / 2, 0xFF5555);
            return;
        }
        if (texture == null) return;

        upload(texture.getId());
        frames++;

        // NDS：用 HandheldShell 的实物机身布局（与 3DS 同族但无摇杆）
        var layout = HandheldShell.dual(Core.NDS, width, height);
        HandheldShell.drawNds(g, font, layout, held);
        var top = layout.screenTop();
        var bot = layout.screenBottom();
        pushTouch(mouseX, mouseY, bot.x(), bot.y(), bot.w(), bot.h());   // NDS：下屏即整帧下半
        g.blit(textureId, top.x(), top.y(), top.w(), top.h(), 0f, 0f, W, H, W, H * 2);
        g.blit(textureId, bot.x(), bot.y(), bot.w(), bot.h(), 0f, H, W, H, W, H * 2);
        HandheldShell.sidebar(g, font, 8, 8, Core.NDS, volume, frames, W, H * 2);
    }

    private void upload(int texId) {
        synchronized (frameLock) {
            GlStateManager._bindTexture(texId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, W);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            top.position(0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, W, H, org.lwjgl.opengl.GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, top);
            bottom.position(0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, H, W, H, org.lwjgl.opengl.GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bottom);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._bindTexture(0);
        }
    }

    /** 下屏触摸：NDS 坐标 0..255 / 0..191。 */
    private void pushTouch(int mouseX, int mouseY, int x, int y, int w, int h) {
        boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;   // 整块下屏
        if (!inside || !touching) { NativeMelon.releaseTouch(handle); return; }
        int tx = (int) ((mouseX - x) / (float) w * W);
        int ty = (int) ((mouseY - y) / (float) h * H);
        NativeMelon.touch(handle, Math.max(0, Math.min(W - 1, tx)), Math.max(0, Math.min(H - 1, ty)));
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) { touching = true; return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) { touching = false; NativeMelon.releaseTouch(handle); return true; }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            try {
                Path snap = snapshotPath();
                Files.createDirectories(snap.getParent());
                boolean ok = NativeMelon.saveState(handle, snap.toAbsolutePath().toString());
                System.out.println("[Minetendo] NDS 退出快照 " + (ok ? "已保存: " : "保存失败: ") + snap);
            } catch (Throwable t) {
                System.out.println("[Minetendo] NDS 快照异常: " + t);
            }
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            try {
                Path snap = snapshotPath();
                boolean gone = Files.deleteIfExists(snap);
                System.out.println("[Minetendo] NDS 退格彻底退出，快照" + (gone ? "已删除: " : "不存在: ") + snap);
            } catch (Throwable t) {
                System.out.println("[Minetendo] NDS 删除快照失败: " + t);
            }
            Minecraft.getInstance().setScreen(new RomPickerScreen(Core.NDS, null));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
            volume = Math.min(1f, volume + 0.1f);
            if (audio != null) audio.setVolume(volume);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            volume = Math.max(0f, volume - 0.1f);
            if (audio != null) audio.setVolume(volume);
            return true;
        }
        held.add(keyCode);
        pushKeys();
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        held.remove(keyCode);
        pushKeys();
        return true;
    }

    /** 键位与 melonDS 的位顺序：A B Select Start Right Left Up Down R L X Y。 */
    private void pushKeys() {
        if (handle == 0L) return;
        int m = 0xFFF;
        if (held.contains(GLFW.GLFW_KEY_K)) m &= ~(1 << 0);
        if (held.contains(GLFW.GLFW_KEY_J)) m &= ~(1 << 1);
        if (held.contains(GLFW.GLFW_KEY_TAB) || held.contains(GLFW.GLFW_KEY_RIGHT_SHIFT)) m &= ~(1 << 2);
        if (held.contains(GLFW.GLFW_KEY_ENTER) || held.contains(GLFW.GLFW_KEY_KP_ENTER)) m &= ~(1 << 3);
        if (held.contains(GLFW.GLFW_KEY_RIGHT) || held.contains(GLFW.GLFW_KEY_D)) m &= ~(1 << 4);
        if (held.contains(GLFW.GLFW_KEY_LEFT) || held.contains(GLFW.GLFW_KEY_A)) m &= ~(1 << 5);
        if (held.contains(GLFW.GLFW_KEY_UP) || held.contains(GLFW.GLFW_KEY_W)) m &= ~(1 << 6);
        if (held.contains(GLFW.GLFW_KEY_DOWN) || held.contains(GLFW.GLFW_KEY_S)) m &= ~(1 << 7);
        if (held.contains(GLFW.GLFW_KEY_E)) m &= ~(1 << 8);
        if (held.contains(GLFW.GLFW_KEY_Q)) m &= ~(1 << 9);
        if (held.contains(GLFW.GLFW_KEY_I)) m &= ~(1 << 10);
        if (held.contains(GLFW.GLFW_KEY_L)) m &= ~(1 << 11);
        NativeMelon.setKeys(handle, m);
    }

    @Override
    public void removed() {
        running = false;
        try { if (worker != null) worker.join(500); } catch (InterruptedException ignored) { }
        worker = null;
        if (handle != 0L) {
            try { NativeMelon.flushSave(handle); } catch (Throwable ignored) { }
            NativeMelon.destroy(handle);
            handle = 0L;
        }
        if (audio != null) { audio.stop(); audio = null; }
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            textureId = null;
        }
        texture = null;
        super.removed();
    }

    /** 透明背景：不模糊、不压暗。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override public boolean isPauseScreen() { return false; }

    /** 供选游戏界面调用：NDS 有自己的界面。 */
    public static void open(Path rom) {
        Minecraft.getInstance().setScreen(new NdsScreen(rom));
    }
}
