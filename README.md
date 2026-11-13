# Minetendo & CraftStation

<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

**在 Minecraft 里，真的把 3DS、NDS、GBA、SFC、PS2、PSP 打开。**

不是贴图方块，不是网页模拟器挂个屏幕 —— 六个真实的 libretro 核心（加上我们自己写的原生宿主）跑在 MC 进程里，画面贴在你能放下、能走过去、能打开的机器上。

`NeoForge 1.21.1` · `Java 21` · `GPL-3.0`

---

## 一句话

把 `minetendo-craftstation-0.3.2.jar` 丢进 `mods/`，进游戏，造一台电视和一台主机，插上游戏，开玩。

核心、BIOS、3DS 解密工具全在 jar 里，首次启动自动解包 —— 玩家不需要下载任何 DLL，也不需要看教程。

---

## 它能做什么

### 六种游戏机，六种核心

| 机种 | 核心 | 分辨率 | 玩法 |
|---|---|---|---|
| 3DS | Azahar | 400×480 | 全屏双屏界面 + 世界内投屏 |
| NDS | Minetendo 自研原生核心 | 256×384 | 全屏双屏、下屏触控 |
| GBA | mGBA | 240×160 | 掌机界面，可捧在手上 |
| SFC | Snes9x | 256×224 | 投到电视 |
| PS2 | PCSX2 | 640×448 | 家用机，只能接电视 |
| PSP | PPSSPP | 480×270 | 掌机界面，双摇杆 |

### 电视是真的显示器

电视不跑游戏，只是一块屏。主机放在电视旁边，右键主机 → 自动找最近的那台电视 → 画面、声音都从电视出来。

- 电视有**位置音频**，走远声音变小
- 关掉电视/挖掉电视，会话自动结束
- 一台电视同一时间只服务一台主机

### 掌机界面是"机器"，不是"按钮贴纸"

GBA / 3DS / NDS / PSP 各自有一套按真机比例画的机身界面：机身轮廓、屏幕位置、十字键、摇杆、面键都按位置摆放，按键会跟着你按的键亮。

PSP 是**双摇杆**：左摇杆 WASD，右摇杆 J/K/L/I，面键 △□×○ = G/H/T/Y。

### 3DS 的 CIA 直接丢进去就行

3DS 模拟器只能读解密后的 `.app`。这个模组内置解密工具链：把 `.cia` 放进 `minetendo/3ds/roms/`，打开选游戏界面，它会自己解密、改名、删掉原文件，然后出现在列表里。带真实字节进度条。

### 存档会记住你在哪

- 世界内投屏：退出世界自动存档位，回来接着玩
- 全屏游玩：`ESC` 保存并退出，`Backspace` 删除存档

---

## 安装

1. 装 **NeoForge 21.1.200+**（Minecraft 1.21.1）
2. 把 jar 放进 `mods/`
3. 启动游戏

首次启动会在 `.minecraft/minetendo/` 下自动建好：

```
minetendo/
├─ 3ds/{core,roms,saves,sysdata,tools}
├─ gba/{core,roms,saves,sysdata}
├─ sfc/{core,roms,saves,sysdata}
├─ nds/{core,roms,saves,sysdata}
├─ ps2/{core,roms,saves,sysdata}     ← BIOS 已内置
└─ psp/{core,roms,saves,sysdata}     ← PPSSPP 资源已内置
```

**你只需要放游戏本体：**

| 机种 | 放进 | 格式 |
|---|---|---|
| 3DS | `minetendo/3ds/roms/` | `.cia`（自动解密）或 `.app` |
| NDS | `minetendo/nds/roms/` | `.nds` |
| GBA | `minetendo/gba/roms/` | `.gba` |
| SFC | `minetendo/sfc/roms/` | `.sfc` / `.smc` |
| PS2 | `minetendo/ps2/roms/` | `.iso` / `.chd` |
| PSP | `minetendo/psp/roms/` | `.iso` / `.cso` |

---

## 操作

**世界内（投屏）**

| 键 | 作用 |
|---|---|
| 方向键 | 十字键 |
| WASD | 左摇杆 |
| K / J | 确认 / 取消 |
| I / L | 第三 / 第四键 |
| Enter / Tab | START / SELECT |
| Q / E | L / R |
| `+` / `-` | 音量 |
| Backspace | 停止会话 |

**掌机界面（全屏）**：PSP 用 `G/H/T/Y` = △□×○、`J/K/L/I` = 右摇杆；其余机种 `K/J/I/L` = A/B/X/Y。`ESC` 存档退出。

---

## 联机观战（别的玩家没有 ROM 也能看）

电视上播的画面会被**流式转播**给服务器里附近的玩家 —— 观战端不需要 ROM、不需要核心、不需要改任何设置。

```
宿主客户端（跑模拟器）
   │  每 1/12 秒抓一帧 → 降采样到宽 320 → JPEG q0.5（约 10~15KB）
   │  按 24KB 切片，走 minetendo:tv_frame 自定义包
   ▼
服务器（TvStreamNet.relay）
   │  按电视坐标中继给 64 格内的其他玩家（不回给宿主）
   ▼
观战客户端（TvStreamClient）
      拼片 → 后台线程解码 → DynamicTexture → 画在电视屏幕上
```

- 画面尺寸跟随核心真实输出（4:3 / 8:7 / 16:9 都等比），和宿主看到的一致
- 观战端纹理按电视坐标独立缓存，走远/宿主停播 10 秒后自动释放
- **按需推流**：宿主先只发"宣告"，观众上报兴趣后服务器告诉宿主"当前 N 人在看"，没人看就停帧（`HostDemand`）
- **租约**：每次开播一个新 UUID，观众只认当前租约，重开一局不会串台（`lease`）
- 带宽：默认 12fps，每人约 150KB/s；只在 64 格内转发；每观众 512KB/s 预算，超了丢帧不排队
- 单人调试：`-Dminetendo.tvLoopback=true` 会把包回环给宿主自己，用来验证编解码链路

**当前限制**：只转视频，不转声音；宿主暂停/退出后观战端最多 10 秒黑屏。
## 它是怎么跑起来的

```
Minecraft 进程
 └─ minetendo.dll（我们写的 JNI 宿主）
      ├─ 加载 libretro 核心（azahar / mGBA / Snes9x / PCSX2 / PPSSPP）
      ├─ 独立工作线程跑 core.run()，音频时钟驱动节拍
      ├─ 每帧把 RGBA 拷进一块 MC 动态纹理
      └─ 输入从 MC 键盘映射成 libretro 的 JOYPAD / ANALOG
```

几个关键设计：

- **音频时钟当节拍器**：按核心产出的采样数推进，不写死帧率，避免 60/76fps 核心被加速或欠载
- **核心只在工作线程碰**：`Access` 只允许会话所属线程调用，渲染线程只读纹理
- **投屏用实体裁剪渲染层**：`AFTER_BLOCK_ENTITIES` + 自定义 no-cull RenderType，画面尺寸跟随核心真实输出
- **碰撞箱跟随朝向**：模型转了、`getShape()` 也按 `FACING` 转，避免"模型和碰撞不一致"

---

## 构建

```bash
# 需要 JDK 21
./gradlew build
# 产物：build/libs/minetendo-craftstation-<version>.jar
```

原生宿主（`minetendo.dll`）源码在 `src/native/minetendo_host.cpp`，用 MinGW g++ 编译后放进
`src/main/resources/minetendo/native/windows-x86_64/`，构建时一起打进 jar。

---

## 发布

```powershell
pwsh -File tools/publish.ps1              # 构建 + 上传到 GitHub Releases
pwsh -File tools/publish.ps1 -Draft       # 先发草稿
pwsh -File tools/publish.ps1 -SkipBuild   # 用现成 jar
```

Windows 默认执行策略（RemoteSigned）会拦未签名脚本，用 `-ExecutionPolicy Bypass` 或先 `Unblock-File`：`powershell -ExecutionPolicy Bypass -File tools/publish.ps1`。

脚本从 Git Credential Manager 读凭据，走 API 上传（**单文件上限 2GB**；网页拖拽上传只有 25MB，43MB 的 jar 会被拒）。上传内容：jar + `SHA256SUMS.txt`。

## 现状与路线

**已经能用**：六机种启动、投屏、掌机界面、存档位、CIA 自动解密、一键安装。

**已知限制**：

- 原生部分目前只有 **Windows x86_64**
- 3DS 性能取决于 CPU；帧数不满时游戏会按比例变慢（Azahar 是逻辑与渲染绑定的核心，无法只跳渲染）
- PS2 走 PCSX2 的软件渲染器（硬件渲染在 MC 的隐藏 GL 上下文里出黑屏）
- 电视投屏同时只支持一台主机

**想做**：Linux/macOS 原生库、多人同服各自串流、金手指、录像。

---

## 关于 ROM 和 BIOS

仓库**不包含任何游戏 ROM**，也不包含商业 BIOS。你需要在本地放自己拥有的游戏。

发布包内置的 PS2 BIOS 仅用于开发测试环境；正式分发请自行替换为你合法持有的 BIOS，或删掉 jar 里的 `minetendo/bios/ps2/` 由玩家自行放入。

第三方核心遵循各自许可证：Azahar (GPL-3.0)、mGBA (MPL-2.0)、Snes9x (自定义非商业)、PCSX2 (LGPL-3.0)、PPSSPP (GPL-2.0)。

---

## 许可证

本项目代码以 **GPL-3.0-or-later** 授权，全文见 [LICENSE](LICENSE)。

第三方组件遵循各自许可证（以 jar 内实际分发为准），完整的许可、再分发与 BIOS/核心说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：

| 组件 | 许可证 |
|---|---|
| Azahar（3DS 核心） | GPL-3.0 |
| mGBA | MPL-2.0 |
| Snes9x | 自定义（非商业） |
| PCSX2 | LGPL-3.0 |
| PPSSPP | GPL-2.0-or-later |
| libretro API 头文件 | 公有领域 / CC0 |

如果你要二次分发打包了核心的 jar，请自行确认上述许可证的合规性（尤其 Snes9x 的非商业条款）。

---

## 致谢

libretro 团队与所有核心作者；没有他们这套 API，这个模组不可能存在。
