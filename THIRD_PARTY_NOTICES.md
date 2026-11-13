# 第三方组件与分发说明

本文件说明 MiniTendo & CraftStation 打包分发的第三方组件、各自许可证，以及再分发时需要注意的地方。

## 1. 本项目自有代码

Java 代码、原生宿主（`minetendo_host.cpp`）、模型与贴图、文档：
**GPL-3.0-or-later**（全文见 [LICENSE](LICENSE)）。
源文件均带 `SPDX-License-Identifier: GPL-3.0-or-later`。

## 2. 模拟器核心

发布包（`minetendo-craftstation-*.jar`）内置以下核心的 Windows x86_64 动态库，
以及 PS2 BIOS、PPSSPP 资源、3DS 解密工具。**这些不是本项目的代码**。

| 组件 | 打包位置 | 上游许可证 | 与本项目（GPL-3.0-or-later）的关系 |
|---|---|---|---|
| Azahar（3DS） | `minetendo/native/windows-x86_64/azahar_libretro.dll` | GPL-3.0 | 兼容 |
| mGBA | `.../mgba_libretro.dll` | MPL-2.0 | 兼容（文件级 copyleft，需保留其声明） |
| PCSX2（PS2） | `.../pcsx2_libretro.dll` | LGPL-3.0 | 兼容（动态链接） |
| PPSSPP（PSP） | `.../ppsspp_libretro.dll` | GPL-2.0-or-later | 兼容 |
| Snes9x（SFC） | `.../snes9x_libretro.dll` | **Snes9x 自定义许可（禁止商业使用）** | 见第 3 节，需特别注意 |
| libretro API 头文件 | `src/native/`（仅头文件接口） | 公有领域 / CC0 | 无冲突 |

## 3. 关于 Snes9x（重要）

Snes9x 使用**自定义的非商业许可证**：允许自由使用与再分发，但**禁止商业用途**，
且其许可证与 GPL 并存时，GPL-3.0 的"附加限制"条款（GPL-3.0 §7）允许这种组合分发，
但**不能因此把 Snes9x 当成 GPL 代码**，也不能对整包主张"纯 GPL"。

本项目的处理方式：

1. Snes9x 核心以**独立的动态库文件**分发（`snes9x_libretro.dll`），由 `minetendo.dll`
   在运行时按需 `LoadLibrary` 加载，不与本项目代码静态链接；
2. 该 DLL 保持上游原样，不修改、不重打包；
3. 本项目的 GPL-3.0-or-later **只覆盖本项目自有代码**，不延伸到该 DLL；
4. 再分发者需自行确认 Snes9x 的许可条款（尤其是商业分发、以及"是否为 Snes9x 附加限制"的判断）。

**本项目未取得、也不主张任何对 Snes9x 的额外授权例外。** 如果你要把打包了 Snes9x 的
整包用于商业目的，需要自行联系 Snes9x 作者取得许可。

如果你不希望处理这个问题，有两个干净的做法：

- 分发时**不含** `snes9x_libretro.dll`，让玩家自行放置（`RuntimeBootstrap` 会提示缺失）；
- 用许可证更宽松的 SFC 核心替换（例如 bsnes 的 GPL 版本，需评估性能与兼容性）。

## 4. PS2 BIOS

发布包内含 `minetendo/bios/ps2/` 下的三份 PS2 BIOS 镜像（`scph10000.bin`、`scph39001.bin`、`rom1.bin`）。

**这些文件是 Sony 的版权作品，本项目不拥有其任何权利。** 它们仅用于本地开发测试环境。
正式分发时建议：

- 从发布包中删除 `minetendo/bios/ps2/`，让玩家放入自己合法持有的 BIOS；
- 或改为"缺失时提示路径"的模式（`RuntimeBootstrap` 已支持缺失检测）。

## 5. 3DS 解密工具

`minetendo/tools/3ds/` 下的 `decrypt.exe`（作者 54634564@hcs64.com）、`ctrtool.exe`、
`seeddb.bin` 为第三方工具，按其原始发布条款使用。本项目只做调用，不修改其代码。

## 6. 模型与贴图

`assets/minetendo/models/**` 与 `assets/minetendo/textures/**` 为本项目自制（Blender 脚本生成），
随本项目以 GPL-3.0-or-later 分发。

## 7. 不作担保

本项目按"现状"提供，不提供任何明示或暗示的担保。使用模拟器核心、BIOS、
游戏 ROM 的合法性由使用者自行负责；本项目不包含任何游戏 ROM。
