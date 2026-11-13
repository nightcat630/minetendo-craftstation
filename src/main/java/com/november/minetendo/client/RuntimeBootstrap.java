// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 首次启动自解包：让 mod 真正做到"把 jar 丢进 mods 就能玩"。
 *
 * <p>从 jar 里解出：
 * <ul>
 *   <li>各机种核心 → {@code <gamedir>/minetendo/<dir>/core/<coreDll>}</li>
 *   <li>PS2 BIOS → {@code <gamedir>/minetendo/ps2/sysdata/}（并复制到 bios/ 与 pcsx2/bios/）</li>
 *   <li>PPSSPP 资源 → {@code <gamedir>/minetendo/psp/sysdata/PPSSPP/assets/}</li>
 *   <li>目录骨架：每机种的 core / roms / saves / sysdata</li>
 * </ul>
 *
 * <p>只在文件缺失时写入，已存在的一律不覆盖（玩家自己放的核心不会被顶掉）。
 */
public final class RuntimeBootstrap {

    private static final String NATIVE = "/minetendo/native/windows-x86_64/";
    private static final String BIOS = "/minetendo/bios/ps2/";
    private static final String PPSSPP_ASSETS = "/minetendo/ppsspp-assets/";
    private static boolean done;

    private RuntimeBootstrap() {}

    public static synchronized void install() {
        if (done) return;
        done = true;
        Path root = FMLPaths.GAMEDIR.get().resolve("minetendo");
        try {
            for (Core core : Core.values()) {
                Path base = root.resolve(core.dir);
                Files.createDirectories(base.resolve("roms"));
                Files.createDirectories(base.resolve("saves"));
                Files.createDirectories(base.resolve("sysdata"));
                Files.createDirectories(base.resolve("core"));
                // 核心：只拷缺失的
                Path dll = base.resolve("core").resolve(core.coreDll);
                if (!Files.isRegularFile(dll)) {
                    copyResource(NATIVE + core.coreDll, dll);
                }
            }
            // NDS 用的是自研库（名字不同），单独处理
            Path nds = root.resolve("nds").resolve("core").resolve("minetendo_nds.dll");
            if (!Files.isRegularFile(nds)) copyResource(NATIVE + "minetendo_nds.dll", nds);

            // PS2 BIOS：三处都放一份（不同 PCSX2 构建找的路径不同）
            Path ps2sys = root.resolve("ps2").resolve("sysdata");
            for (String name : new String[]{"scph10000.bin", "scph39001.bin", "rom1.bin"}) {
                Path a = ps2sys.resolve(name);
                if (!Files.isRegularFile(a)) copyResource(BIOS + name, a);
                Path b = ps2sys.resolve("bios").resolve(name);
                if (!Files.isRegularFile(b)) copyResource(BIOS + name, b);
                Path c = ps2sys.resolve("pcsx2").resolve("bios").resolve(name);
                if (!Files.isRegularFile(c)) copyResource(BIOS + name, c);
            }
            Files.createDirectories(ps2sys.resolve("pcsx2").resolve("memcards"));
            Files.createDirectories(ps2sys.resolve("memcards"));
            Files.createDirectories(ps2sys.resolve("pcsx2").resolve("sstates"));

            // PPSSPP 资源
            Path assets = root.resolve("psp").resolve("sysdata").resolve("PPSSPP").resolve("assets");
            Files.createDirectories(assets);
            for (String name : new String[]{"ppge_atlas.zim", "ppge_atlas.meta", "compat.ini", "font_atlas.zim"}) {
                Path target = assets.resolve(name);
                if (!Files.isRegularFile(target)) copyResource(PPSSPP_ASSETS + name, target);
            }

            System.out.println("[Minetendo] 运行环境已就绪: " + root);
        } catch (Throwable t) {
            System.out.println("[Minetendo] 首次解包失败: " + t);
        }
    }

    private static void copyResource(String resource, Path target) {
        try (InputStream in = RuntimeBootstrap.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("[Minetendo] 资源缺失（跳过）: " + resource);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Minetendo] 已解出: " + target.getFileName()
                    + " (" + (Files.size(target) / 1024 / 1024) + "MB)");
        } catch (Throwable t) {
            System.out.println("[Minetendo] 解出失败 " + resource + ": " + t);
        }
    }
}
