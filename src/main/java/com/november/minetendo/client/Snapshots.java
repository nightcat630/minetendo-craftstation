package com.november.minetendo.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 即时存档（快照）管理。
 *
 * <p>规则：
 * <ul>
 *   <li>ESC 退出 → 保留快照，下次打开同一 ROM 接着玩；</li>
 *   <li>退格退出 → 删掉该 ROM 的快照，下次从头开始；</li>
 *   <li><b>关闭 Minecraft → 清掉所有快照</b>：快照与核心版本/内存布局强相关，
 *       跨进程残留很容易在下次读档时把核心打崩（实测"逆转裁判2"闪退）。</li>
 * </ul>
 */
public final class Snapshots {

    private static final Logger LOG = LoggerFactory.getLogger("Minetendo/Snapshots");
    private static boolean hookInstalled;

    private Snapshots() {}

    /** 安装 JVM 关闭钩子：清空 minetendo/<机种>/saves/*.snap。 */
    public static synchronized void installShutdownHook() {
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(Snapshots::clearAll, "Minetendo-SnapshotCleanup"));
    }

    /** 删除所有快照（保留 .sav 存档）。 */
    public static void clearAll() {
        try {
            Path root = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("minetendo");
            if (!Files.isDirectory(root)) return;
            int removed = 0;
            try (Stream<Path> s = Files.walk(root, 3)) {
                for (Path p : s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".snap")).toList()) {
                    try { Files.deleteIfExists(p); removed++; } catch (Exception ignored) {}
                }
            }
            if (removed > 0) LOG.info("[Minetendo] 已清理 {} 个即时存档（关闭游戏）", removed);
        } catch (Throwable t) {
            LOG.warn("[Minetendo] 清理快照失败: {}", t.toString());
        }
    }

    /** 玩家退出世界时也清一次：避免下次开世界残留旧快照。 */
    @EventBusSubscriber(modid = com.november.minetendo.Minetendo.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            clearAll();
        }
    }
}
