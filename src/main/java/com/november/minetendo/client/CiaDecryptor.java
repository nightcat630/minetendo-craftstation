package com.november.minetendo.client;

import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 3DS 的 CIA 自动解密。
 *
 * <p>Azahar/Citra 只能跑解密后的 NCCH（也就是 {@code .app}），而网上下到的多是 {@code .cia}。
 * 这里用打包在 mod 里的工具链（decrypt.exe / ctrtool.exe / seeddb.bin）把 roms 目录里的
 * {@code .cia} 转成同名 {@code .app}，成功后删除原 {@code .cia}。
 *
 * <p>多个 CIA：**逐个串行**处理（每个用独立临时目录），全部完成后统一刷新列表。
 *
 * <p>进度：**真实字节进度** —— 读 CIA 头里的 content_size 当分母，轮询解密产物已写出的
 * 字节数当分子；不是计时估算。
 */
public final class CiaDecryptor {

    private static final String TOOL_RES = "/minetendo/tools/3ds/";
    private static final String[] TOOLS = {"decrypt.exe", "ctrtool.exe", "seeddb.bin"};

    // ── 进度状态（选游戏界面读取）──
    private static volatile String currentFile = "";
    private static volatile int fileIndex;          // 当前第几个（1 起）
    private static volatile int fileCount;
    private static volatile double progress;        // 0..1，全部文件的总进度
    private static volatile boolean running;
    private static volatile String message = "";

    private CiaDecryptor() {}

    public static String message() { return message; }
    public static String currentFile() { return currentFile; }
    public static int fileIndex() { return fileIndex; }
    public static int fileCount() { return fileCount; }
    public static double progress() { return Math.max(0, Math.min(1, progress)); }
    public static boolean running() { return running; }

    public static Path toolsDir() {
        return FMLPaths.GAMEDIR.get().resolve("minetendo").resolve("3ds").resolve("tools");
    }

    private static boolean ensureTools() {
        Path dir = toolsDir();
        try {
            Files.createDirectories(dir);
            for (String name : TOOLS) {
                Path out = dir.resolve(name);
                if (Files.isRegularFile(out)) continue;
                try (InputStream in = CiaDecryptor.class.getResourceAsStream(TOOL_RES + name)) {
                    if (in == null) {
                        message = "缺少解密工具资源: " + name;
                        return false;
                    }
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } catch (Throwable t) {
            message = "解包解密工具失败: " + t;
            return false;
        }
    }

    public static List<Path> findCia(Path romsDir) {
        if (!Files.isDirectory(romsDir)) return List.of();
        try (Stream<Path> s = Files.list(romsDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cia"))
                    .sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 后台把 roms 里所有 .cia 转成 .app；完成后 onDone 在后台线程被调用。 */
    public static boolean decryptAllAsync(Path romsDir, Runnable onDone) {
        List<Path> cias = findCia(romsDir);
        if (cias.isEmpty()) return true;
        if (running) return true;
        if (!ensureTools()) return false;

        running = true;
        progress = 0;
        fileCount = cias.size();
        fileIndex = 0;
        message = "准备解密 " + cias.size() + " 个 CIA";

        Thread t = new Thread(() -> {
            int ok = 0, fail = 0;
            long totalBytes = 0, doneBytes = 0;
            for (Path c : cias) totalBytes += safeSize(c);

            try {
                for (Path cia : cias) {
                    fileIndex++;
                    currentFile = cia.getFileName().toString();
                    boolean good = decryptOne(cia, doneBytes, totalBytes);
                    doneBytes += safeSizeOfSource(cia);
                    if (good) ok++; else fail++;
                    progress = totalBytes > 0 ? Math.min(1.0, doneBytes / (double) totalBytes) : 0;
                }
                message = "解密完成：成功 " + ok + (fail > 0 ? "，失败 " + fail : "");
                progress = 1.0;
            } catch (Throwable e) {
                message = "解密异常：" + e.getMessage();
                System.out.println("[Minetendo] CIA 解密异常: " + e);
            } finally {
                running = false;
                if (onDone != null) onDone.run();
            }
        }, "minetendo-cia");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * 解密单个 cia。
     *
     * <p><b>必须注意的两件事</b>：
     * <ol>
     *   <li>decrypt.exe 是 PyInstaller 单文件程序，要在**能创建/删除临时目录**的地方跑；
     *       gradle 的 run 目录带有拒绝删除的 ACL，所以一律复制到系统临时目录执行；</li>
     *   <li>它把结果写到**当前工作目录**，不是输入文件旁边 → 运行结束后从临时目录搬回 roms。</li>
     * </ol>
     *
     * <p>进度用"已写出的 ncch 字节 / CIA 头里的 content_size"，属于真实字节进度。
     */
    private static boolean decryptOne(Path cia, long doneBytes, long totalBytes) {
        Path romsDir = cia.getParent();
        String fileName = cia.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - 4);
        long expected = expectedContentSize(cia);      // 来自 CIA 头，失败则退回文件大小
        long sourceSize = safeSize(cia);
        Path work = null;

        try {
            work = Files.createTempDirectory("minetendo-cia");
            Files.copy(toolsDir().resolve("decrypt.exe"), work.resolve("decrypt.exe"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(toolsDir().resolve("seeddb.bin"), work.resolve("seeddb.bin"), StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder pb = new ProcessBuilder(work.resolve("decrypt.exe").toString(),
                    cia.toAbsolutePath().toString());
            pb.directory(work.toFile());
            // 子进程的 TEMP/TMP 必须指向这个干净目录，否则 PyInstaller 解包失败
            pb.environment().put("TEMP", work.toString());
            pb.environment().put("TMP", work.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    FMLPaths.GAMEDIR.get().resolve("minetendo").resolve("3ds").resolve("cia-decrypt.log").toFile()));

            // 这个脚本结尾会 input() 等回车；不给输入就抛 EOFError 直接退出（产物其实已写好）
            Path enterFile = work.resolve("enter.txt");
            Files.writeString(enterFile, "\n\n\n");
            pb.redirectInput(enterFile.toFile());
            Process p = pb.start();

            // ── 轮询：这个工具是"一次性写出"（写满才落盘），且**写完不退出**
            //    （它会去连 Nintendo 服务器查 seed）。所以：产物写满 / 大小停止增长 / 超时 → 直接判定完成并结束进程。
            long lastSize = 0, lastChange = System.currentTimeMillis(), startMs = lastChange;
            while (p.isAlive()) {
                long size = ncchBytes(work, base);
                long now = System.currentTimeMillis();
                if (size != lastSize) { lastSize = size; lastChange = now; }
                double frac = expected > 0 ? Math.min(1.0, size / (double) expected) : 0;
                if (totalBytes > 0) {
                    progress = Math.max(progress, Math.min(0.999, (doneBytes + frac * sourceSize) / (double) totalBytes));
                }
                boolean full = expected > 0 && size >= expected * 0.99;
                boolean stalled = size > 0 && (now - lastChange) > 4000;
                boolean timeout = (now - startMs) > 15 * 60_000L;
                if (full || stalled || timeout) {
                    p.destroyForcibly();
                    break;
                }
                Thread.sleep(150);
            }
            try { p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { }

            // 取回结果：<base>.0.ncch 是主内容
            List<Path> ncch = new ArrayList<>();
            try (Stream<Path> s = Files.list(work)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().startsWith(base + "."))
                        .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".ncch"))
                        .forEach(ncch::add);
            }
            if (ncch.isEmpty()) {
                message = "解密失败：" + fileName + "（见 cia-decrypt.log）";
                System.out.println("[Minetendo] 解密未产生 ncch: " + fileName);
                return false;
            }
            ncch.sort(Comparator.comparing(f -> f.getFileName().toString()));
            Path main = ncch.get(0);
            Path app = romsDir.resolve(base + ".app");
            Files.move(main, app, StandardCopyOption.REPLACE_EXISTING);
            for (Path extra : ncch) {
                if (!extra.equals(main)) Files.deleteIfExists(extra);
            }
            Files.deleteIfExists(cia);
            if (totalBytes > 0) {
                progress = Math.min(1.0, (doneBytes + sourceSize) / (double) totalBytes);
            }
            System.out.println("[Minetendo] CIA → APP 完成: " + app.getFileName()
                    + "  (" + (Files.size(app) / 1024 / 1024) + "MB)");
            return true;
        } catch (Throwable e) {
            message = "解密异常：" + fileName;
            System.out.println("[Minetendo] 解密 " + fileName + " 失败: " + e);
            return false;
        } finally {
            try { deleteRecursive(work); } catch (Throwable ignored) { }
        }
    }

    /** 读 CIA 头里的 content_size（偏移 0x18，uint64），用作进度分母。 */
    private static long expectedContentSize(Path cia) {
        try (FileChannel ch = FileChannel.open(cia, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(0x20);
            ch.read(buf);
            buf.flip();
            if (buf.remaining() < 0x20) return safeSize(cia);
            buf.position(0x18);
            long size = buf.getLong();
            return size > 0 ? size : safeSize(cia);
        } catch (Throwable t) {
            return safeSize(cia);
        }
    }

    /** 临时目录里已写出的 ncch 字节数。 */
    private static long ncchBytes(Path work, String base) {
        long sum = 0;
        try (Stream<Path> s = Files.list(work)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(base + ".") && n.toLowerCase().endsWith(".ncch")) {
                    sum += safeSize(p);
                }
            }
        } catch (Throwable ignored) {
        }
        return sum;
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (Throwable t) { return 0; }
    }

    /** cia 已被删除时用 0，避免影响总进度收尾。 */
    private static long safeSizeOfSource(Path cia) {
        return safeSize(cia);
    }

    private static void deleteRecursive(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (Exception ignored) { }
            });
        }
    }
}
