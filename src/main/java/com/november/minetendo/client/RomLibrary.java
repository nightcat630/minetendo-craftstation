// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.net.RomChunkPayload;
import com.november.minetendo.net.RomDownloadRequestPayload;
import com.november.minetendo.net.RomListRequestPayload;
import com.november.minetendo.net.RomListResponsePayload;
import com.november.minetendo.net.RomServerNet;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 客户端 ROM 库：本机目录扫描 + 服务端清单元数据 + 下载组装。
 *
 * <p>选游戏界面的"来源"下拉就切换这里的两套数据：
 * <ul>
 *   <li>{@link Source#LOCAL} —— 直接扫 {@code <gamedir>/minetendo/<机种>/roms/}</li>
 *   <li>{@link Source#SERVER} —— 发 {@link RomListRequestPayload} 问服务器要清单</li>
 * </ul>
 *
 * <p>下载走 24KB 分片，先写 {@code .part} 再改名（避免半截文件被当成可用 ROM）。
 */
public final class RomLibrary {

    public enum Source { LOCAL, SERVER }

    /** 一条 ROM 记录 */
    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean local;
        public final boolean availableLocal;   // SERVER 条目：本机是否已有同名文件

        Entry(String name, long size, boolean local, boolean availableLocal) {
            this.name = name;
            this.size = size;
            this.local = local;
            this.availableLocal = availableLocal;
        }

        public String sizeText() { return bytes(size); }

        public String statusText(Source source) {
            if (source == Source.LOCAL) return "本机";
            if (downloadingName != null && downloadingName.equals(name)) return "下载中";
            return availableLocal ? "已在本机" : "未下载";
        }
    }

    private static final DecimalFormat SIZE_FMT = new DecimalFormat("0.0");

    // ── 状态（选游戏界面直接读）──
    private static final List<Entry> LOCAL_ENTRIES = new ArrayList<>();
    private static final List<Entry> SERVER_ENTRIES = new ArrayList<>();
    private static volatile String serverError = "";
    private static volatile boolean listPending;

    private static volatile String downloadingName;
    private static volatile long downloadedBytes;
    private static volatile long totalBytes;
    private static volatile ByteArrayOutputStream chunkBuffer;

    private RomLibrary() {}

    public static void init() {
        RomServerNet.setClientSink(payload -> {
            if (payload instanceof RomListResponsePayload list) onList(list);
            else if (payload instanceof RomChunkPayload chunk) onChunk(chunk);
        });
    }

    public static String serverError() { return serverError; }
    public static boolean listPending() { return listPending; }
    public static String downloadingName() { return downloadingName; }

    public static double downloadProgress() {
        return totalBytes <= 0 ? 0 : Math.min(1.0, downloadedBytes / (double) totalBytes);
    }

    public static String downloadText() {
        if (downloadingName == null) return "";
        return bytes(downloadedBytes) + " / " + bytes(totalBytes);
    }

    public static List<Entry> entries(Source source) {
        return source == Source.LOCAL ? LOCAL_ENTRIES : SERVER_ENTRIES;
    }

    /** 本机目录 */
    public static Path localRoms(Core core) {
        return FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(core.dir).resolve("roms");
    }

    /** 重新扫描本机 ROM 目录。 */
    public static void refreshLocal(Core core) {
        LOCAL_ENTRIES.clear();
        Path dir = localRoms(core);
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> core.accepts(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            LOCAL_ENTRIES.add(new Entry(p.getFileName().toString(), Files.size(p), true, true));
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    /** 向服务器要清单。 */
    public static void requestServer(Core core) {
        listPending = true;
        serverError = "";
        SERVER_ENTRIES.clear();
        try {
            PacketDistributor.sendToServer(new RomListRequestPayload(core.dir));
        } catch (Throwable t) {
            listPending = false;
            serverError = "未连接服务器";
        }
    }

    /** 请求下载某条服务器条目。 */
    public static void download(Core core, Entry entry) {
        if (entry == null || !entry.local) return;
        downloadingName = entry.name;
        downloadedBytes = 0;
        totalBytes = entry.size;
        chunkBuffer = new ByteArrayOutputStream((int) Math.min(entry.size, Integer.MAX_VALUE - 8));
        try {
            PacketDistributor.sendToServer(new RomDownloadRequestPayload(core.dir, entry.name));
        } catch (Throwable t) {
            finish(null, core, entry.name, "发送请求失败");
        }
    }

    // ── 收服务端数据 ──

    private static void onList(RomListResponsePayload p) {
        listPending = false;
        serverError = p.error() == null ? "" : p.error();
        SERVER_ENTRIES.clear();
        Core core = coreOf(p.core());
        if (core == null) return;
        refreshLocal(core);
        for (int i = 0; i < p.names().size(); i++) {
            String name = p.names().get(i);
            long size = p.sizes().get(i);
            boolean have = LOCAL_ENTRIES.stream().anyMatch(e -> e.name.equals(name));
            SERVER_ENTRIES.add(new Entry(name, size, false, have));
        }
        if (!serverError.isEmpty()) {
            System.out.println("[Minetendo] 服务端 ROM 清单问题: " + serverError);
        }
    }

    private static void onChunk(RomChunkPayload p) {
        if (downloadingName == null || !downloadingName.equals(p.name())) return;
        ByteArrayOutputStream buf = chunkBuffer;
        if (buf == null) return;
        try {
            buf.write(p.data(), 0, p.data().length);
            downloadedBytes = buf.size();
            totalBytes = p.size();
            if (p.index() + 1 >= p.total()) {
                byte[] all = buf.toByteArray();
                finish(all, coreOf(p.core()), p.name(), null);
            }
        } catch (Throwable t) {
            finish(null, coreOf(p.core()), p.name(), "写入失败: " + t.getMessage());
        }
    }

    private static void finish(byte[] data, Core core, String name, String error) {
        try {
            if (data != null && core != null && name != null) {
                Path dir = localRoms(core);
                Files.createDirectories(dir);
                Path part = dir.resolve(name + ".part");
                Path target = dir.resolve(name);
                Files.write(part, data);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Minetendo] ROM 下载完成: " + name + " (" + data.length / 1024 / 1024 + "MB)");
            } else if (error != null) {
                serverError = error;
                System.out.println("[Minetendo] ROM 下载失败: " + error);
            }
        } catch (Throwable t) {
            serverError = "保存失败: " + t.getMessage();
            System.out.println("[Minetendo] 保存 ROM 失败: " + t);
        } finally {
            downloadingName = null;
            chunkBuffer = null;
            downloadedBytes = 0;
            totalBytes = 0;
            if (core != null) {
                refreshLocal(core);
                // 更新"是否已在本机"标记
                for (int i = 0; i < SERVER_ENTRIES.size(); i++) {
                    Entry e = SERVER_ENTRIES.get(i);
                    boolean have = LOCAL_ENTRIES.stream().anyMatch(l -> l.name.equals(e.name));
                    if (have != e.availableLocal) {
                        SERVER_ENTRIES.set(i, new Entry(e.name, e.size, false, have));
                    }
                }
            }
            Minecraft.getInstance().execute(() -> { });
        }
    }

    private static Core coreOf(String id) {
        for (Core c : Core.values()) if (c.dir.equalsIgnoreCase(id)) return c;
        return null;
    }

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return SIZE_FMT.format(n / 1024.0) + " KB";
        if (n < 1024L * 1024 * 1024) return SIZE_FMT.format(n / (1024.0 * 1024)) + " MB";
        return SIZE_FMT.format(n / (1024.0 * 1024 * 1024)) + " GB";
    }
}
