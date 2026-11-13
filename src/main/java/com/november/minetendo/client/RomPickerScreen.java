package com.november.minetendo.client;

import com.november.minetendo.SessionHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 选游戏界面（按机种参数化；inWorld=true 时选中后在世界内运行，画面贴到电视上）。 */
public class RomPickerScreen extends Screen {

    private final Core core;
    private final Screen parent;
    private final boolean inWorld;

    private List<Path> games = List.of();
    private int selected;
    private String status = "";
    // 3DS：解密跑完后自动刷新列表（用户不用点"刷新"）
    private boolean decRunning;
    private int dirSignature = -1;
    private int tickCount;

    public static String notice = "";

    public RomPickerScreen(Core core, Screen parent) { this(core, parent, false); }

    public RomPickerScreen(Core core, Screen parent, boolean inWorld) {
        super(Component.literal("选择" + core.label + "游戏"));
        this.core = core;
        this.parent = parent;
        this.inWorld = inWorld;
    }

    private static Path romsDir(Core core) {
        return FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(core.dir).resolve("roms");
    }

    private static List<Path> scan(Core core) {
        Path dir = romsDir(core);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> core.accepts(p.getFileName().toString()))
                    .sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    protected void init() {
        // 3DS：先把 roms 里的 .cia 自动解密成 .app（后台线程，完成后刷新列表）
        if (core == Core.THREE_DS) {
            CiaDecryptor.decryptAllAsync(romsDir(core), () ->
                    net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        games = scan(core);
                        if (selected >= games.size()) selected = 0;
                    }));
        }
        games = scan(core);
        selected = 0;
        if (notice != null && !notice.isEmpty()) { status = notice; notice = ""; }

        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("开始"), b -> start())
                .bounds(cx - 150, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> {
            games = scan(core);
            if (selected >= games.size()) selected = 0;
        }).bounds(cx - 48, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(cx + 54, height - 52, 96, 20).build());

        if (games.isEmpty()) {
            Path corePath = FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(core.dir)
                    .resolve("core").resolve(core.coreDll);
            status = Files.isRegularFile(corePath)
                    ? "把游戏放进 " + romsDir(core)
                    : "缺少核心：" + corePath;
        }
    }

    private void start() {
        if (games.isEmpty()) return;
        Path game = games.get(Math.max(0, Math.min(selected, games.size() - 1)));
        if (!SessionHost.load()) { status = "原生库加载失败"; return; }
        if (inWorld && core != Core.NDS) {
            if (InWorldSession.start(core, game)) Minecraft.getInstance().setScreen(null);
            else status = InWorldSession.error();
            return;
        }
        if (core == Core.NDS) {
            NdsScreen.open(game);
            return;
        }
        Minecraft.getInstance().setScreen(new EmulatorScreen(core, game));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        int panelTop = 44;
        int panelBottom = Math.max(panelTop + 40, height - 68);
        g.fill(width / 2 - 170, panelTop, width / 2 + 170, panelBottom, 0xF0101010);
        super.render(g, mouseX, mouseY, partialTick);

        g.drawCenteredString(font, "选择" + core.label + "游戏", width / 2, 26, 0xFFFFFF);
        int y = 56;
        for (int i = 0; i < games.size() && y < panelBottom - 8; i++) {
            boolean sel = i == selected;
            if (sel) g.fill(width / 2 - 164, y - 3, width / 2 + 164, y + 13, 0x60FFFFFF);
            g.drawString(font, (i + 1) + ". " + games.get(i).getFileName(),
                    width / 2 - 156, y, sel ? 0xFFFF80 : 0xDDDDDD, true);
            y += 20;
        }
        if (core == Core.THREE_DS && (CiaDecryptor.running() || !CiaDecryptor.message().isEmpty())) {
            String label = CiaDecryptor.running()
                    ? ("解密 " + CiaDecryptor.fileIndex() + "/" + CiaDecryptor.fileCount()
                        + "  " + CiaDecryptor.currentFile()
                        + "  " + (int) (CiaDecryptor.progress() * 100) + "%")
                    : CiaDecryptor.message();
            g.drawCenteredString(font, label, width / 2, 30, 0x66CCFF);
            // 真实进度条：宽度按实际字节比例填充
            int barW = 320, barH = 8;
            int bx = width / 2 - barW / 2, by = 42;
            g.fill(bx, by, bx + barW, by + barH, 0xFF202830);
            int fill = (int) (barW * CiaDecryptor.progress());
            if (fill > 0) g.fill(bx, by, bx + fill, by + barH, 0xFF3AA0FF);
            g.fill(bx, by, bx + barW, by + 1, 0xFF44566A);
            g.fill(bx, by + barH - 1, bx + barW, by + barH, 0xFF44566A);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_S) {
            if (!games.isEmpty()) selected = (selected + 1) % games.size();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_W) {
            if (!games.isEmpty()) selected = (selected - 1 + games.size()) % games.size();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { start(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 每 tick 检查：解密是否刚结束 / 目录内容是否变化 → 自动重新扫描。 */
    @Override
    public void tick() {
        super.tick();
        if (core != Core.THREE_DS) return;
        tickCount++;

        boolean nowRunning = CiaDecryptor.running();
        if (decRunning && !nowRunning) {        // 解密刚结束
            rescan();
        } else if (tickCount % 20 == 0) {        // 兜底：每 20 tick 比一次目录指纹
            int sig = signature();
            if (sig != dirSignature) rescan();
        }
        decRunning = nowRunning;
    }

    /** 目录指纹：文件数 + 名字长度和，够用且便宜。 */
    private int signature() {
        Path dir = romsDir(core);
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            int[] acc = {17};
            s.forEach(p -> acc[0] = acc[0] * 31 + p.getFileName().toString().hashCode());
            return acc[0];
        } catch (Exception e) {
            return 0;
        }
    }

    private void rescan() {
        games = scan(core);
        if (selected >= games.size()) selected = 0;
        dirSignature = signature();
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}