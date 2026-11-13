// SPDX-License-Identifier: GPL-3.0-or-later
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

/**
 * 选游戏界面（重做版）。
 *
 * <p>布局分四块，不再把东西堆在一起：
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │ 标题                                    [来源: 本机 ▾] [刷新] │
 * ├───────────────────────────────┬──────────────────────────┤
 * │ 列表（可滚动，带滚动条）        │ 详情卡：文件名 / 大小      │
 * │  1. xxx.sfc        2.1 MB 本机 │        来源 / 状态 / 路径  │
 * │  2. yyy.sfc       16.0 MB 未下载│        [开始] [下载] [详情]│
 * │  ...                          │ 下载进度条（下载时显示）    │
 * ├───────────────────────────────┴──────────────────────────┤
 * │ 状态行                                    [开始] [关闭]     │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>来源下拉切换"本机 roms"和"服务端 server-roms"；服务端条目可直接下载（分片传输）。
 */
public class RomPickerScreen extends Screen {

    private static final int PAD = 16;
    private static final int ROW_H = 20;
    private static final int HEADER_H = 26;
    private static final int FOOTER_H = 32;

    private final Core core;
    private final Screen parent;
    private final boolean inWorld;

    private RomLibrary.Source source = RomLibrary.Source.LOCAL;
    private int selected;
    private int scroll;
    private String status = "";
    /** 详情子页：null = 未打开 */
    private RomLibrary.Entry detail;

    /** 下拉是否展开 */
    private boolean dropdownOpen;

    private int listX, listY, listW, listH;
    private int detailX, detailY, detailW, detailH;

    private Button btnStart, btnDownload, btnRefresh, btnClose, btnDetail;
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

    private List<RomLibrary.Entry> entries() {
        return RomLibrary.entries(source);
    }

    @Override
    protected void init() {
        RomLibrary.refreshLocal(core);
        if (core == Core.THREE_DS) {
            CiaDecryptor.decryptAllAsync(romsDir(core), () ->
                    Minecraft.getInstance().execute(this::reload));
        }
        if (notice != null && !notice.isEmpty()) { status = notice; notice = ""; }

        layout();
        buildWidgets();
        reload();
    }

    /** 计算四块区域。 */
    private void layout() {
        int top = PAD + HEADER_H;
        int bottom = height - 34;   // 底部按钮条上方
        int totalW = width - PAD * 2;
        int gap = 10;
        listW = (int) (totalW * 0.60) - gap / 2;
        detailW = totalW - listW - gap;
        listX = PAD;
        listY = top;
        listH = bottom - top;
        detailX = PAD + listW + gap;
        detailY = top;
        detailH = listH;
    }

    private void buildWidgets() {
        clearWidgets();
        int y = height - 27;
        btnStart = addRenderableWidget(Button.builder(Component.literal("开始"), b -> primaryAction())
                .bounds(width / 2 - 150, y, 90, 20).build());
        btnDownload = addRenderableWidget(Button.builder(Component.literal("下载到本机"), b -> downloadSelected())
                .bounds(width / 2 - 54, y, 100, 20).build());
        btnRefresh = addRenderableWidget(Button.builder(Component.literal("刷新"), b -> reload())
                .bounds(width / 2 + 52, y, 60, 20).build());
        btnClose = addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(width / 2 + 118, y, 60, 20).build());
        btnDetail = addRenderableWidget(Button.builder(Component.literal("详情…"), b -> openDetail())
                .bounds(detailX + detailW - 70, detailY + detailH - 26, 62, 18).build());
        updateButtons();
    }

    private void updateButtons() {
        boolean hasSel = selected >= 0 && selected < entries().size();
        RomLibrary.Entry e = hasSel ? entries().get(selected) : null;
        boolean downloading = RomLibrary.downloadingName() != null;
        if (btnStart != null) btnStart.active = hasSel && !downloading
                && (source == RomLibrary.Source.LOCAL || (e != null && e.availableLocal));
        if (btnDownload != null) btnDownload.active = hasSel && !downloading
                && source == RomLibrary.Source.SERVER && e != null && !e.availableLocal;
        if (btnDetail != null) btnDetail.active = hasSel;
    }

    private void reload() {
        RomLibrary.refreshLocal(core);
        if (source == RomLibrary.Source.SERVER && Minecraft.getInstance().getConnection() != null) {
            RomLibrary.requestServer(core);
        }
        if (selected >= entries().size()) selected = Math.max(0, entries().size() - 1);
        dirSignature = signature();
        updateButtons();
    }

    // ── 交互 ──

    private void primaryAction() {
        List<RomLibrary.Entry> list = entries();
        if (list.isEmpty()) return;
        RomLibrary.Entry e = list.get(Math.max(0, Math.min(selected, list.size() - 1)));
        if (source == RomLibrary.Source.SERVER && !e.local) {
            if (!e.availableLocal) { RomLibrary.download(core, e); status = "开始下载 " + e.name; return; }
            // 已在本机 → 切回本机来源并用同名文件启动
            source = RomLibrary.Source.LOCAL;
            RomLibrary.refreshLocal(core);
            for (int i = 0; i < entries().size(); i++) {
                if (entries().get(i).name.equals(e.name)) { selected = i; break; }
            }
        }
        startLocal();
    }

    private void startLocal() {
        List<RomLibrary.Entry> list = entries();
        if (list.isEmpty()) return;
        RomLibrary.Entry e = list.get(Math.max(0, Math.min(selected, list.size() - 1)));
        Path game = romsDir(core).resolve(e.name);
        if (!Files.isRegularFile(game)) { status = "文件不存在: " + e.name; return; }
        if (!SessionHost.load()) { status = "原生库加载失败"; return; }

        if (inWorld && core != Core.NDS) {
            if (InWorldSession.start(core, game)) Minecraft.getInstance().setScreen(null);
            else status = InWorldSession.error();
            return;
        }
        if (core == Core.NDS) { NdsScreen.open(game); return; }
        Minecraft.getInstance().setScreen(new EmulatorScreen(core, game));
    }

    private void downloadSelected() {
        List<RomLibrary.Entry> list = entries();
        if (list.isEmpty()) return;
        RomLibrary.Entry e = list.get(Math.max(0, Math.min(selected, list.size() - 1)));
        RomLibrary.download(core, e);
        status = "开始下载 " + e.name;
    }

    private void openDetail() {
        List<RomLibrary.Entry> list = entries();
        if (list.isEmpty()) return;
        detail = list.get(Math.max(0, Math.min(selected, list.size() - 1)));
    }

    @Override
    public void tick() {
        super.tick();
        if (core != Core.THREE_DS) return;
        tickCount++;
        boolean nowRunning = CiaDecryptor.running();
        if (decRunning && !nowRunning) reload();
        else if (tickCount % 20 == 0) {
            int sig = signature();
            if (sig != dirSignature) { RomLibrary.refreshLocal(core); dirSignature = sig; updateButtons(); }
        }
        decRunning = nowRunning;
        updateButtons();
    }

    private int signature() {
        Path dir = romsDir(core);
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            int[] acc = {17};
            s.forEach(p -> acc[0] = acc[0] * 31 + p.getFileName().toString().hashCode());
            return acc[0];
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            int rows = visibleRows();
            int max = Math.max(0, entries().size() - rows);
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 下拉优先
        if (dropdownOpen) {
            int dy = listY - 4;
            for (RomLibrary.Source s : RomLibrary.Source.values()) {
                if (inBox(mouseX, mouseY, width - 250, dy, 120, 18)) {
                    source = s;
                    selected = 0;
                    scroll = 0;
                    dropdownOpen = false;
                    reload();
                    return true;
                }
                dy += 20;
            }
            dropdownOpen = false;
            return true;
        }
        // 详情子页
        if (detail != null) {
            detail = null;
            return true;
        }
        // 来源下拉按钮
        if (inBox(mouseX, mouseY, width - 250, PAD, 120, 18)) {
            dropdownOpen = true;
            return true;
        }
        // 列表行
        if (inBox(mouseX, mouseY, listX, listY + 18, listW, listH - 18)) {
            int row = (int) ((mouseY - (listY + 18)) / ROW_H) + scroll;
            if (row >= 0 && row < entries().size()) {
                selected = row;
                updateButtons();
                if (button == 1) openDetail();
                if (button == 0) downloadSelectedIfNeeded();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void downloadSelectedIfNeeded() {
        List<RomLibrary.Entry> list = entries();
        if (list.isEmpty()) return;
        RomLibrary.Entry e = list.get(selected);
        if (source == RomLibrary.Source.SERVER && !e.availableLocal && RomLibrary.downloadingName() == null) {
            // 单击不自动下载，避免误触；双击才下
            long now = System.currentTimeMillis();
            if (now - lastClickMs < 300) downloadSelected();
            lastClickMs = now;
        }
    }

    private long lastClickMs;

    private static boolean inBox(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private int visibleRows() {
        return Math.max(1, (listH - 18) / ROW_H);
    }

    // ── 绘制 ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不要用 renderBackground：1.21 在 world 里会走模糊后处理，把自绘内容一起糊掉
        g.fill(0, 0, width, height, 0xE60C0F13);

        // 顶部标题条
        g.fill(PAD, PAD, width - PAD, PAD + HEADER_H - 6, 0xFF181C22);
        g.fill(PAD, PAD, width - PAD, PAD + 1, 0xFF39485C);
        g.drawString(font, "选择 " + core.label + " 游戏", PAD + 8, PAD + 7, 0xFFFFFF, true);

        // 来源下拉按钮
        int ddX = width - 250, ddY = PAD;
        boolean hoverDD = inBox(mouseX, mouseY, ddX, ddY, 120, 18);
        g.fill(ddX, ddY, ddX + 120, ddY + 18, hoverDD ? 0xFF2C3440 : 0xFF232A33);
        g.fill(ddX, ddY, ddX + 120, ddY + 1, 0xFF48566B);
        g.drawString(font, "来源: " + (source == RomLibrary.Source.LOCAL ? "本机" : "服务器"), ddX + 6, ddY + 5, 0xE8EEF6, false);
        g.drawString(font, "▾", ddX + 106, ddY + 5, 0x9FB0C4, false);

        // 列表面板
        g.fill(listX, listY, listX + listW, listY + listH, 0xFF10141A);
        g.fill(listX, listY, listX + listW, listY + 1, 0xFF39485C);
        g.fill(listX, listY + listH - 1, listX + listW, listY + listH, 0xFF39485C);
        g.fill(listX, listY + 1, listX + listW, listY + 16, 0xFF1E2731);
        g.drawString(font, source == RomLibrary.Source.LOCAL ? "本机 ROM" : "服务端 ROM",
                listX + 6, listY + 4, 0xBFD2E6, false);
        String count = entries().size() + " 项";
        g.drawString(font, count, listX + listW - 6 - font.width(count), listY + 4, 0x8FA3B8, false);

        drawList(g, mouseX, mouseY);

        // 详情面板
        drawDetail(g);

        // 解密状态（3DS）
        if (core == Core.THREE_DS && (CiaDecryptor.running() || !CiaDecryptor.message().isEmpty())) {
            String label = CiaDecryptor.running()
                    ? ("解密 " + CiaDecryptor.fileIndex() + "/" + CiaDecryptor.fileCount()
                        + "  " + CiaDecryptor.currentFile() + "  " + (int) (CiaDecryptor.progress() * 100) + "%")
                    : CiaDecryptor.message();
            g.drawString(font, label, listX + 6, listY + listH + 3, 0x66CCFF, false);
        }

        // 底部按钮条（先铺底，控件后画，永远在最上层）
        int barTop = height - 30;
        g.fill(0, barTop, width, height, 0xFF0B0E12);
        g.fill(0, barTop, width, barTop + 1, 0xFF39485C);

        // 状态行
        String st = status;
        if (RomLibrary.downloadingName() != null) {
            st = "下载 " + RomLibrary.downloadingName() + "  " + RomLibrary.downloadText();
        } else if (RomLibrary.listPending()) {
            st = "正在向服务器请求清单…";
        } else if (!RomLibrary.serverError().isEmpty() && source == RomLibrary.Source.SERVER) {
            st = RomLibrary.serverError();
        }
        if (st != null && !st.isEmpty()) {
            g.drawString(font, st, PAD, height - PAD - FOOTER_H + 4, 0x9FB0C4, false);
        }

        // 关键：不能调 super.render() —— Screen.render() 会调用 renderBackground()，
        // 在 1.21 里那是**模糊后处理**，会把我们刚画的内容整块糊掉。这里只画控件。
        for (var r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }

        // 详情覆盖层 / 下拉层（画在最上层）
        renderDetailOverlay(g);
        if (dropdownOpen) {
            int dy = listY - 4;
            for (RomLibrary.Source s : RomLibrary.Source.values()) {
                boolean sel = s == source;
                g.fill(width - 250, dy, width - 130, dy + 18, sel ? 0xFF35506E : 0xFF232A33);
                g.drawString(font, s == RomLibrary.Source.LOCAL ? "本机 roms" : "服务端 server-roms",
                        width - 244, dy + 5, sel ? 0xFFFFFF : 0xCFDAE8, false);
                dy += 20;
            }
        }
    }

    private void drawList(GuiGraphics g, int mouseX, int mouseY) {
        List<RomLibrary.Entry> list = entries();
        int rows = visibleRows();
        int y0 = listY + 18;
        for (int i = 0; i < rows; i++) {
            int idx = scroll + i;
            if (idx >= list.size()) break;
            RomLibrary.Entry e = list.get(idx);
            int ry = y0 + i * ROW_H;
            boolean sel = idx == selected;
            boolean hover = inBox(mouseX, mouseY, listX + 1, ry, listW - 2, ROW_H - 2);
            if (sel) g.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H - 2, 0x503A6A9A);
            else if (hover) g.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H - 2, 0x302A3644);

            String name = e.name;
            int maxNameW = listW - 150;
            while (font.width(name) > maxNameW && name.length() > 4) name = name.substring(0, name.length() - 2);
            if (!name.equals(e.name)) name = name + "…";
            g.drawString(font, (idx + 1) + ". " + name, listX + 6, ry + 5, sel ? 0xFFFFFF : 0xD3DDE8, false);

            String size = e.sizeText();
            g.drawString(font, size, listX + listW - 96, ry + 5, 0x93A6BB, false);

            String badge = e.statusText(source);
            int color = switch (badge) {
                case "本机" -> 0x7FD18B;
                case "已在本机" -> 0x7FD18B;
                case "下载中" -> 0x66CCFF;
                default -> 0xE0A85C;
            };
            g.drawString(font, badge, listX + listW - 6 - font.width(badge), ry + 5, color, false);
        }

        // 滚动条
        int total = list.size();
        if (total > rows) {
            int trackX = listX + listW - 4;
            int trackH = listH - 20;
            g.fill(trackX, y0, trackX + 3, y0 + trackH, 0x40202A36);
            int barH = Math.max(14, trackH * rows / total);
            int maxScroll = Math.max(1, total - rows);
            int barY = y0 + (trackH - barH) * scroll / maxScroll;
            g.fill(trackX, barY, trackX + 3, barY + barH, 0xFF6E8CB4);
        }

        if (list.isEmpty()) {
            String msg = source == RomLibrary.Source.LOCAL
                    ? "本机 roms 目录为空"
                    : (RomLibrary.listPending() ? "加载中…" : "服务端没有该机种的 ROM");
            g.drawString(font, msg, listX + 10, y0 + 6, 0x8899AA, false);
        }
    }

    private void drawDetail(GuiGraphics g) {
        g.fill(detailX, detailY, detailX + detailW, detailY + detailH, 0xFF10141A);
        g.fill(detailX, detailY, detailX + detailW, detailY + 1, 0xFF39485C);
        g.fill(detailX, detailY + detailH - 1, detailX + detailW, detailY + detailH, 0xFF39485C);
        g.fill(detailX, detailY + 1, detailX + detailW, detailY + 16, 0xFF1E2731);
        g.drawString(font, "详情", detailX + 6, detailY + 4, 0xBFD2E6, false);

        List<RomLibrary.Entry> list = entries();
        if (selected < 0 || selected >= list.size()) {
            g.drawString(font, "未选择", detailX + 8, detailY + 26, 0x7C8C9C, false);
            return;
        }
        RomLibrary.Entry e = list.get(selected);
        int x = detailX + 8, y = detailY + 26;

        // 文件名自动换行
        for (String line : wrap(e.name, detailW - 18)) {
            g.drawString(font, line, x, y, 0xFFFFFF, false);
            y += 11;
        }
        y += 4;
        g.drawString(font, "大小: " + e.sizeText(), x, y, 0xC2CFDE, false); y += 12;
        g.drawString(font, "来源: " + (source == RomLibrary.Source.LOCAL ? "本机 roms" : "服务端 server-roms"),
                x, y, 0xC2CFDE, false); y += 12;
        g.drawString(font, "状态: " + e.statusText(source), x, y, 0x9FD3A9, false); y += 12;
        String dir = source == RomLibrary.Source.LOCAL ? "minetendo/" + core.dir + "/roms/" : "minetendo/" + core.dir + "/server-roms/";
        g.drawString(font, "目录: " + dir, x, y, 0x8FA3B8, false); y += 14;

        // 下载进度条
        if (RomLibrary.downloadingName() != null && RomLibrary.downloadingName().equals(e.name)) {
            int barW = detailW - 16, barH = 8;
            int bx = detailX + 8, by = detailY + detailH - 44;
            g.fill(bx, by, bx + barW, by + barH, 0xFF202830);
            int fill = (int) (barW * RomLibrary.downloadProgress());
            if (fill > 0) g.fill(bx, by, bx + fill, by + barH, 0xFF3AA0FF);
            g.drawString(font, RomLibrary.downloadText(), bx, by - 11, 0x9FD3FF, false);
        }

        // 说明
        g.drawString(font, source == RomLibrary.Source.LOCAL ? "Enter 开始游戏" : "双击下载 / 下载后开始",
                detailX + 8, detailY + detailH - 12, 0x7C8C9C, false);
    }

    private List<String> wrap(String text, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(line.toString() + c) > maxW) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ── 详情子页（覆盖层）──
    private void renderDetailOverlay(GuiGraphics g) {
        if (detail == null) return;
        int w = Math.min(420, width - 60), h = 180;
        int x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF000000);
        g.fill(x, y, x + w, y + h, 0xF0181E26);
        g.drawString(font, "ROM 详情", x + 10, y + 8, 0xFFFFFF, true);
        int yy = y + 30;
        for (String line : wrap(detail.name, w - 24)) {
            g.drawString(font, line, x + 10, yy, 0xE6EEF8, false);
            yy += 11;
        }
        yy += 6;
        g.drawString(font, "大小 " + detail.sizeText(), x + 10, yy, 0xB8C6D6, false); yy += 12;
        g.drawString(font, "来源 " + (source == RomLibrary.Source.LOCAL ? "本机" : "服务端"), x + 10, yy, 0xB8C6D6, false); yy += 12;
        g.drawString(font, "状态 " + detail.statusText(source), x + 10, yy, 0x9FD3A9, false); yy += 16;
        g.drawString(font, "点击任意处关闭", x + 10, y + h - 16, 0x7C8C9C, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (dropdownOpen) { dropdownOpen = false; return true; }
        if (detail != null) { detail = null; return true; }
        if (keyCode == GLFW.GLFW_KEY_DOWN) { move(1); return true; }
        if (keyCode == GLFW.GLFW_KEY_UP) { move(-1); return true; }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) { move(visibleRows()); return true; }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) { move(-visibleRows()); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { primaryAction(); return true; }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            source = source == RomLibrary.Source.LOCAL ? RomLibrary.Source.SERVER : RomLibrary.Source.LOCAL;
            selected = 0; scroll = 0; reload(); return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void move(int delta) {
        List<RomLibrary.Entry> list = entries();
        if (list.isEmpty()) return;
        selected = Math.max(0, Math.min(list.size() - 1, selected + delta));
        int rows = visibleRows();
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + rows) scroll = selected - rows + 1;
        updateButtons();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
