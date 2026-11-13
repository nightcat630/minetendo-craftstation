// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 服务端 ROM 库：让玩家在选游戏界面直接下载服务器上放好的 ROM。
 *
 * <p>服务端存放位置（与客户端目录对称，但独立，避免互相污染）：
 * <pre>
 *   &lt;服务端实例目录&gt;/minetendo/&lt;机种&gt;/server-roms/
 *   例：minetendo/sfc/server-roms/sanguo.sfc
 * </pre>
 *
 * <p>安全约束：
 * <ul>
 *   <li>只认文件名，拒绝任何路径分隔符与 {@code ..}（防目录穿越）</li>
 *   <li>只服务该机种允许的扩展名</li>
 *   <li>单文件上限 {@value #MAX_FILE_MB} MB</li>
 *   <li>只读，不提供上传/删除</li>
 * </ul>
 */
public final class RomServerNet {

    public static final long MAX_FILE_MB = 4096;

    /** 客户端侧的接收器（只在客户端赋值；服务端保持 null） */
    private static volatile Consumer<CustomPacketPayload> clientSink;

    public static void setClientSink(Consumer<CustomPacketPayload> sink) {
        clientSink = sink;
    }

    private RomServerNet() {}

    /** 服务端某个机种的 ROM 目录。 */
    public static Path serverRoms(String core) {
        return FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(sanitizeCore(core)).resolve("server-roms");
    }

    /** 机种 id 只允许小写字母数字，防止拼到路径里出花样。 */
    public static String sanitizeCore(String core) {
        if (core == null) return "";
        String c = core.toLowerCase(Locale.ROOT);
        return c.matches("[a-z0-9]{1,16}") ? c : "";
    }

    /** 文件名只允许纯文件名（无路径分隔符、无 ..）。 */
    public static boolean safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 200) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        return !name.equals(".") && !name.equals("..");
    }

    /** 各机种允许的扩展名（和客户端 Core.accepts 保持一致）。 */
    public static boolean acceptsExtension(String core, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (sanitizeCore(core)) {
            case "3ds" -> n.endsWith(".cia") || n.endsWith(".app") || n.endsWith(".3ds");
            case "nds" -> n.endsWith(".nds") || n.endsWith(".zip");
            case "gba" -> n.endsWith(".gba") || n.endsWith(".zip");
            case "sfc" -> n.endsWith(".sfc") || n.endsWith(".smc") || n.endsWith(".zip");
            case "ps2" -> n.endsWith(".iso") || n.endsWith(".chd") || n.endsWith(".bin");
            case "psp" -> n.endsWith(".iso") || n.endsWith(".cso") || n.endsWith(".pbp");
            default -> false;
        };
    }

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playToServer(RomListRequestPayload.TYPE, RomListRequestPayload.STREAM_CODEC, RomServerNet::onListRequest);
        reg.playToServer(RomDownloadRequestPayload.TYPE, RomDownloadRequestPayload.STREAM_CODEC, RomServerNet::onDownloadRequest);
        reg.playToClient(RomListResponsePayload.TYPE, RomListResponsePayload.STREAM_CODEC, RomServerNet::toClient);
        reg.playToClient(RomChunkPayload.TYPE, RomChunkPayload.STREAM_CODEC, RomServerNet::toClient);
    }

    private static void toClient(CustomPacketPayload payload, IPayloadContext context) {
        Consumer<CustomPacketPayload> sink = clientSink;
        if (sink == null) return;                       // 专用服务端上不会走到这里
        context.enqueueWork(() -> sink.accept(payload));
    }

    // ── 服务端：列目录 ──

    private static void onListRequest(RomListRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            String core = sanitizeCore(payload.core());
            List<String> names = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            String error = "";

            if (core.isEmpty()) {
                error = "机种无效";
            } else {
                Path dir = serverRoms(core);
                if (!Files.isDirectory(dir)) {
                    error = "服务端未放置该机种的 ROM（" + dir + "）";
                } else {
                    try (Stream<Path> s = Files.list(dir)) {
                        s.filter(Files::isRegularFile)
                                .filter(p -> acceptsExtension(core, p.getFileName().toString()))
                                .sorted()
                                .limit(RomListResponsePayload.MAX_ENTRIES)
                                .forEach(p -> {
                                    try {
                                        long size = Files.size(p);
                                        if (size > 0 && size <= MAX_FILE_MB * 1024L * 1024L) {
                                            names.add(p.getFileName().toString());
                                            sizes.add(size);
                                        }
                                    } catch (Exception ignored) {
                                    }
                                });
                    } catch (Exception e) {
                        error = "读取目录失败: " + e.getMessage();
                    }
                }
            }
            PacketDistributor.sendToPlayer(player, new RomListResponsePayload(core, names, sizes, error));
        });
    }

    // ── 服务端：发文件（分片）──

    private static void onDownloadRequest(RomDownloadRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            String core = sanitizeCore(payload.core());
            String name = payload.name();
            if (core.isEmpty() || !safeName(name) || !acceptsExtension(core, name)) {
                PacketDistributor.sendToPlayer(player,
                        new RomListResponsePayload(core, List.of(), List.of(), "请求无效: " + name));
                return;
            }
            Path file = serverRoms(core).resolve(name);
            if (!Files.isRegularFile(file)) {
                PacketDistributor.sendToPlayer(player,
                        new RomListResponsePayload(core, List.of(), List.of(), "文件不存在: " + name));
                return;
            }
            long size;
            try {
                size = Files.size(file);
            } catch (Exception e) {
                return;
            }
            if (size <= 0 || size > MAX_FILE_MB * 1024L * 1024L) {
                PacketDistributor.sendToPlayer(player,
                        new RomListResponsePayload(core, List.of(), List.of(), "文件过大或为空: " + name));
                return;
            }
            int total = (int) ((size + RomChunkPayload.MAX_CHUNK - 1) / RomChunkPayload.MAX_CHUNK);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[RomChunkPayload.MAX_CHUNK];
                int index = 0;
                while (true) {
                    int read = in.readNBytes(buf, 0, buf.length);
                    if (read <= 0) break;
                    byte[] part = new byte[read];
                    System.arraycopy(buf, 0, part, 0, read);
                    PacketDistributor.sendToPlayer(player,
                            new RomChunkPayload(core, name, index, total, size, part));
                    index++;
                }
                System.out.println("[Minetendo] 已发送 ROM: " + name + " (" + (size / 1024 / 1024) + "MB, "
                        + index + " 片) → " + player.getName().getString());
            } catch (Exception e) {
                System.out.println("[Minetendo] 发送 ROM 失败: " + name + " : " + e);
            }
        });
    }
}
