# 原生宿主（minetendo_host.cpp）

把 libretro 核心装进 Minecraft 的那层胶水：JNI 入口、独立工作线程、音频时钟节拍、
RGBA 帧拷贝、输入映射（JOYPAD / ANALOG / POINTER）、快照与存档位。

产物是一个 DLL，JNI 类名固定为 `com.november.minetendo.SessionHost`。

## 依赖

- MinGW-w64 g++（实测 `x86_64-w64-mingw32-g++` / MSYS2 mingw64）
- JDK 17+ 的 `include` 与 `include/win32`（只用 JNI 头，不需要链接 jvm）
- OpenGL32（`-lopengl32`）、GDI32、User32：核心在自己的隐藏窗口里建 GL 上下文

## 编译

```bash
g++ -shared -O2 -std=c++17 -static -static-libgcc -static-libstdc++ \
    -o minetendo.dll minetendo_host.cpp \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/win32" \
    -lopengl32 -lgdi32 -luser32
```

然后把 `minetendo.dll` 放到：

```
src/main/resources/minetendo/native/windows-x86_64/minetendo.dll
```

Gradle 打包时会把它一起塞进 jar，运行时由 `SessionHost` 按 SHA-256 解出到临时目录再 `System.load`。

## 注意

- JNI 函数名必须与 Java 侧全限定名严格对应（`Java_com_november_minetendo_SessionHost_xxx`），改名会让运行时静默找不到方法
- 核心只能在会话所属线程里碰，跨线程访问走 token 校验
- `videoCb` 里要按 core 给的 `pitch` 拷贝，pitch 小于 `w*bpp` 时要纠正，否则画面会错行