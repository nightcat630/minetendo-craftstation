// SPDX-License-Identifier: GPL-3.0-or-later
// Minetendo 原生层（重写版）。
//
// 结构、并发模型、生命周期全部参照 game-console 的 piq_libretro_jni.cpp：
//
//   Slot { mutex guard; SharedSession session; }          // 每槽一把锁
//   Session { token, slot, ownerThread, 自己的一份资源 }   // 会话即一切
//   Access { try_lock 本槽 + 校验 token + 校验所属线程 }   // 调核心期间的守卫
//   moduleLock() 只保护 HMODULE 集合，绝不包住核心调用
//
// 与旧实现的根本差别（都是踩过坑换来的）：
//   * 每会话自带 隐藏窗口 / WGL 上下文 / FBO / 像素与音频缓冲 —— 与 MC 窗口无关，
//     所以窗口尺寸变化不会让核心画面变黑。
//   * 换系统 = 新建会话；**从不卸载旧核心**，避免 Azahar teardown 崩驱动。
//   * 先 createFramebuffer → 再 hw.context_reset()；回调里绝不再重建 FBO。
//   * 视频/音频直接写进 Java 传入的 DirectByteBuffer，Java 侧零像素搬运。
//   * JOYPAD=1 / ANALOG=5 / POINTER=6。
#include <jni.h>
#include <windows.h>
#include <GL/gl.h>
#include <array>
#include <atomic>
#include <stdexcept>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

// ── libretro 最小声明（字段顺序必须严格一致）──
#define RETRO_HW_FRAME_BUFFER_VALID ((const void*) (intptr_t) -1)
enum { RETRO_PIXEL_FORMAT_0RGB1555 = 0, RETRO_PIXEL_FORMAT_XRGB8888 = 1, RETRO_PIXEL_FORMAT_RGB565 = 2 };
enum { RETRO_DEVICE_NONE = 0, RETRO_DEVICE_JOYPAD = 1, RETRO_DEVICE_ANALOG = 5, RETRO_DEVICE_POINTER = 6 };

struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_variable { const char* key; const char* value; };
struct retro_log_callback { void (*log)(int level, const char* fmt, ...); };
struct retro_hw_render_callback {
    unsigned context_type;
    void   (*context_reset)(void);
    void*    get_current_framebuffer;
    void*    get_proc_address;
    bool     depth, stencil, bottom_left_origin;
    unsigned version_major, version_minor;
    bool     cache_context;
    void   (*context_destroy)(void);
    void   (*set_shared_context)(void);
};
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };

// ── GL 扩展（GL 1.1 头里没有）──
#ifndef GL_FRAMEBUFFER
#define GL_FRAMEBUFFER       0x8D40
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_RGBA8             0x8058
#endif
typedef void (APIENTRY* PFN_GenFbo)(GLsizei, GLuint*);
typedef void (APIENTRY* PFN_BindFbo)(GLenum, GLuint);
typedef void (APIENTRY* PFN_FboTex)(GLenum, GLenum, GLenum, GLuint, GLint);
typedef void (APIENTRY* PFN_DelFbo)(GLsizei, const GLuint*);
typedef GLenum (APIENTRY* PFN_CheckFbo)(GLenum);
typedef void (APIENTRY* PFN_BindBuffer)(GLenum, GLuint);
typedef void (APIENTRY* PFN_BufferData)(GLenum, long long, const void*, GLenum);
typedef void* (APIENTRY* PFN_MapBufferRange)(GLenum, long long, long long, GLbitfield);
typedef unsigned char (APIENTRY* PFN_UnmapBuffer)(GLenum);
typedef void (APIENTRY* PFN_GenBuffers)(GLsizei, GLuint*);
typedef void* (APIENTRY* PFN_FenceSync)(GLenum, GLbitfield);
typedef GLenum (APIENTRY* PFN_ClientWaitSync)(void*, GLbitfield, unsigned long long);
typedef void (APIENTRY* PFN_DeleteSync)(void*);
static PFN_GenFbo  pGenFbo;
static PFN_BindFbo pBindFbo;
static PFN_FboTex  pFboTex;
static PFN_DelFbo  pDelFbo;
static PFN_CheckFbo pCheckFbo;
static PFN_BindBuffer     pBindBuffer;
static PFN_BufferData     pBufferData;
static PFN_MapBufferRange pMapBufferRange;
static PFN_UnmapBuffer    pUnmapBuffer;
static PFN_GenBuffers     pGenBuffers;
static PFN_FenceSync      pFenceSync;
static PFN_ClientWaitSync  pClientWaitSync;
static PFN_DeleteSync      pDeleteSync;

static constexpr int kMaxSessions = 4;
static constexpr int kMaxDim = 2048;

// ── 日志 ──
static void logLine(const char* fmt, ...) {
    static std::mutex m;
    std::lock_guard<std::mutex> lk(m);
    wchar_t tmp[MAX_PATH]; GetTempPathW(MAX_PATH, tmp);
    std::wstring path = std::wstring(tmp) + L"minetendo-session.log";
    FILE* fp = nullptr;
    if (_wfopen_s(&fp, path.c_str(), L"a") != 0 || !fp) return;
    va_list ap; va_start(ap, fmt);
    vfprintf(fp, fmt, ap);
    va_end(ap);
    fputc('\n', fp);
    fclose(fp);
}

static char g_lastError[512] = {0};
static void fail(const char* msg) {
    strncpy_s(g_lastError, msg ? msg : "", _TRUNCATE);
    logLine("错误: %s", msg ? msg : "");
}

struct Core {
    HMODULE mod = nullptr;
    bool (*env)(bool (*)(unsigned, void*)) = nullptr;
    void (*setVideo)(void (*)(const void*, unsigned, unsigned, size_t)) = nullptr;
    void (*setAudio)(size_t (*)(const int16_t*, size_t)) = nullptr;
    void (*setPoll)(void (*)(void)) = nullptr;
    void (*setInput)(int16_t (*)(unsigned, unsigned, unsigned, unsigned)) = nullptr;
    void (*init)(void) = nullptr;
    void (*deinit)(void) = nullptr;
    bool (*load)(const retro_game_info*) = nullptr;
    void (*unload)(void) = nullptr;
    void (*run)(void) = nullptr;
    void (*av)(retro_system_av_info*) = nullptr;
    void (*portDevice)(unsigned, unsigned) = nullptr;
    size_t (*serializeSize)(void) = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
};

struct Session;
struct Slot {
    std::mutex guard;
    std::shared_ptr<Session> session;
};
static std::array<Slot, kMaxSessions> g_slots;
static std::atomic<jlong> g_nextToken{kMaxSessions};   // 从 4 开始：低两位必须留给槽位，且 token 不能为 0（0 = 失败）
static thread_local Session* t_invoking = nullptr;
// 回调可能来自核心自己的渲染线程，thread_local 在那里是空的；
// 用一个全局"当前会话"给所有回调定位（同一时刻只有一个会话在跑）。
static std::atomic<Session*> g_activeSession{nullptr};
// 创建会话前记录 MC 当前的 GL 上下文：新上下文与它共享纹理/FBO，
// 这样核心渲染的纹理可以直接给 MC 使用，不必回读。
static HGLRC g_mcContext = nullptr;
// 微秒级计时（GetTickCount64 精度只有 15.6ms，量不了每帧分段）
static long long nowUs() { LARGE_INTEGER f, c; QueryPerformanceFrequency(&f); QueryPerformanceCounter(&c);
    return (long long) (c.QuadPart * 1000000LL / f.QuadPart); }
static long long* g_tmpSumRun = nullptr;   // 指向帧率统计块里的累加器
static long long* g_tmpSumRead = nullptr;
static inline Session* currentSession() { Session* s = g_activeSession.load(); return s ? s : t_invoking; }
static std::mutex g_moduleGuard;
static std::set<HMODULE> g_modules;

struct Session {
    jlong token = 0;
    size_t slot = 0;
    DWORD owner = 0;
    std::wstring wndClass;
    HWND wnd = nullptr;
    HDC dc = nullptr;
    HGLRC ctx = nullptr;
    GLuint fbo = 0, tex = 0;
    // 双 PBO 异步回读：本帧读进 PBO，上一帧的数据才映射取走 —— GPU 不等待
    GLuint pbo[2] = {0, 0};
    int pboIndex = 0;
    bool pboReady = false;
    // 每个 PBO 对应一个围栏：只有 GPU 写完了才映射，绝不阻塞核心
    void* fence[2] = {nullptr, nullptr};
    Core core;
    retro_hw_render_callback hw{};
    std::string corePath, gamePath, sysDir, saveDir;
    // 核心下发的选项（键 → 默认值）：GET_VARIABLE 时按这个回答
    std::map<std::string, std::string> options;
    // 软渲染核心的像素格式：0=0RGB1555 1=XRGB8888 2=RGB565
    int pixFmt = 0;   // libretro 默认 0RGB1555（核心不调 SET_PIXEL_FORMAT 时就是它）
    bool inited = false, loaded = false, hardware = false;
    int w = 400, h = 480;
    // 固定输出尺寸（取 av_info 基准分辨率）：软核会逐帧切换 224/239 行，
    // 若跟着变尺寸，纹理会反复重建 → 画面整体漂移。参考项目就是把每帧缩放进固定尺寸。
    int texW = 0, texH = 0;
    double fps = 60.0;
    unsigned sampleRate = 32730;
    std::vector<uint8_t> pixels;         // RGBA 自上而下（读回后按需翻转）
    std::vector<int16_t> audio;
    std::mutex audioMutex;
    // 帧缓冲锁：工作线程写 pixels、渲染线程读它上传，必须互斥（否则画面滚动/撕裂）
    std::mutex frameMutex;
    // 存/读档请求（渲染线程投递，模拟线程在两帧之间执行 —— retro_serialize 不能在任意线程调用）
    std::mutex snapMutex;
    std::string pendingSave, pendingLoad;
    // 真实进度：核心已产出的帧数、已编译完成的着色器程序数（LoadProgram 完成一次算一个）
    std::atomic<int> frames{0};
    std::atomic<int> programs{0};
    // 本帧核心产出的音频采样帧数（节拍要用它，不能用"缓冲里待播总数"）
    std::atomic<int> producedStep{0};
    // true：画面用 GPU 内 blit 直接给 MC（不回读），省掉每帧的 GPU→CPU 同步
    std::atomic<bool> gpuCopy{false};
    std::atomic<uint32_t> buttons{0};
    std::atomic<int32_t> ax{0}, ay{0}, ax2{0}, ay2{0}, px{0}, py{0};
    std::atomic<uint32_t> pdown{0};
    uint8_t* videoDst = nullptr;          // 指向 Java DirectByteBuffer
    size_t videoCap = 0;
    int16_t* audioDst = nullptr;
    size_t audioCap = 0;

    ~Session() { release(); }

    void release() {
        if (ctx) { wglMakeCurrent(nullptr, nullptr); wglDeleteContext(ctx); ctx = nullptr; }
        if (dc && wnd) { ReleaseDC(wnd, dc); dc = nullptr; }
        if (wnd) { DestroyWindow(wnd); wnd = nullptr; }
        // 注意：**不 FreeLibrary**。换系统靠新会话，卸载旧核心正是崩溃根因。
    }
};

using Lock = std::unique_lock<std::mutex>;
static Lock lockModules() { return Lock(g_moduleGuard); }

static size_t slotOf(jlong token) {
    if (token <= 0) throw std::runtime_error("非法 token");
    size_t idx = (size_t) (token & (kMaxSessions - 1));
    if (idx >= kMaxSessions) throw std::runtime_error("非法槽位");
    return idx;
}

// 调用核心期间的守卫：本槽 try_lock + token 校验 + 所属线程校验
struct Access {
    Lock lock;
    std::shared_ptr<Session> s;
    Session* prev;
    explicit Access(jlong token)
        : lock(g_slots[slotOf(token)].guard, std::try_to_lock), prev(t_invoking) {
        if (!lock.owns_lock()) throw std::runtime_error("该会话正被其所属线程占用");
        s = g_slots[slotOf(token)].session;
        if (!s || s->token != token) throw std::runtime_error("会话不存在");
        // owner 为 0 表示"还没绑定"：首个调用者接管该会话（Java 侧在工作线程里 step）。
        // 已绑定则必须是同一线程 —— 这样既能跨线程迁移一次，也不会被两个线程同时用。
        DWORD self = GetCurrentThreadId();
        if (s->owner == 0) s->owner = self;
        else if (s->owner != self) throw std::runtime_error("会话已被其他线程占用");
        t_invoking = s.get();
        g_activeSession.store(s.get());
    }
    ~Access() { t_invoking = prev; g_activeSession.store(prev); }
    Access(const Access&) = delete;
};

// ── GL 上下文 ──
static bool makeCurrent(Session* s) {
    // 会话没有自有上下文（借用 MC 渲染线程的）：保持当前绑定不动，绝不能 wglMakeCurrent(nullptr)
    if (!s || !s->ctx || !s->dc) return true;
    if (wglGetCurrentContext() != s->ctx) return wglMakeCurrent(s->dc, s->ctx) != FALSE;
    return true;
}

static bool createFramebuffer(Session* s) {
    if (!pGenFbo) {
        pGenFbo  = (PFN_GenFbo)  wglGetProcAddress("glGenFramebuffers");
        pBindFbo = (PFN_BindFbo) wglGetProcAddress("glBindFramebuffer");
        pFboTex  = (PFN_FboTex)  wglGetProcAddress("glFramebufferTexture2D");
        pDelFbo  = (PFN_DelFbo)  wglGetProcAddress("glDeleteFramebuffers");
        pCheckFbo = (PFN_CheckFbo) wglGetProcAddress("glCheckFramebufferStatus");
        pBindBuffer    = (PFN_BindBuffer) wglGetProcAddress("glBindBuffer");
        pBufferData    = (PFN_BufferData) wglGetProcAddress("glBufferData");
        pMapBufferRange = (PFN_MapBufferRange) wglGetProcAddress("glMapBufferRange");
        pUnmapBuffer   = (PFN_UnmapBuffer) wglGetProcAddress("glUnmapBuffer");
        pGenBuffers    = (PFN_GenBuffers) wglGetProcAddress("glGenBuffers");
        pFenceSync     = (PFN_FenceSync) wglGetProcAddress("glFenceSync");
        pClientWaitSync = (PFN_ClientWaitSync) wglGetProcAddress("glClientWaitSync");
        pDeleteSync    = (PFN_DeleteSync) wglGetProcAddress("glDeleteSync");
    }
    if (!pGenFbo || !pBindFbo || !pFboTex) { fail("FBO 扩展不可用"); return false; }

    const int w = s->w > 0 ? s->w : 400;
    const int h = s->h > 0 ? s->h : 480;
    glGenTextures(1, &s->tex);
    glBindTexture(GL_TEXTURE_2D, s->tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    pGenFbo(1, &s->fbo);
    pBindFbo(GL_FRAMEBUFFER, s->fbo);
    pFboTex(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s->tex, 0);
    pBindFbo(GL_FRAMEBUFFER, 0);
    s->pixels.assign((size_t) w * h * 4, 0);
    if (pBindBuffer && pBufferData) {
        if (pGenBuffers) pGenBuffers(2, s->pbo);
        for (int i = 0; i < 2; i++) {
            pBindBuffer(0x88EB /*PIXEL_PACK*/, s->pbo[i]);
            pBufferData(0x88EB, (long long) w * h * 4, nullptr, 0x88E1 /*STREAM_READ*/);
        }
        pBindBuffer(0x88EB, 0);
        logLine("PBO 就绪 %u/%u %dx%d", s->pbo[0], s->pbo[1], w, h);
    }
    logLine("FBO 就绪 fbo=%u tex=%u %dx%d", s->fbo, s->tex, w, h);
    return true;
}

static bool createContext(Session* s) {
    s->wndClass = L"minetendo-session-" + std::to_wstring(s->token);
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = s->wndClass.c_str();
    RegisterClassExW(&wc);
    s->wnd = CreateWindowExW(0, s->wndClass.c_str(), L"minetendo", WS_POPUP, 0, 0, 16, 16,
                             nullptr, nullptr, wc.hInstance, nullptr);
    if (!s->wnd) { fail("创建隐藏窗口失败"); return false; }
    s->dc = GetDC(s->wnd);

    PIXELFORMATDESCRIPTOR p{};
    p.nSize = sizeof(p); p.nVersion = 1;
    p.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    p.iPixelType = PFD_TYPE_RGBA;
    p.cColorBits = 32; p.cDepthBits = 24; p.cStencilBits = 8;
    int fmt = ChoosePixelFormat(s->dc, &p);
    if (!fmt || !SetPixelFormat(s->dc, fmt, &p)) { fail("像素格式不可用"); return false; }

    HGLRC legacy = wglCreateContext(s->dc);
    if (!legacy || !wglMakeCurrent(s->dc, legacy)) { fail("创建 GL 上下文失败"); return false; }

    // 硬件渲染核心（Azahar）要 Core Profile；先拿扩展再建。
    typedef HGLRC (WINAPI* PFN_CreateARB)(HDC, HGLRC, const int*);
    auto createARB = (PFN_CreateARB) wglGetProcAddress("wglCreateContextAttribsARB");
    HGLRC core = nullptr;
    HGLRC sharedWith = nullptr;   // 不共享：Azahar 在共享上下文下会崩（azahar_libretro+0x397ba6）
    if (createARB) {
        const int a45[] = { 0x2091, 4, 0x2092, 5, 0x2093, (int)(intptr_t) sharedWith, 0x9126, 1, 0 };
        core = createARB(s->dc, sharedWith, a45);
        if (!core) {
            const int a33[] = { 0x2091, 3, 0x2092, 3, 0x9126, 1, 0 };
            core = createARB(s->dc, nullptr, a33);
        }
    }
    if (core) {
        wglMakeCurrent(nullptr, nullptr);
        wglDeleteContext(legacy);
        s->ctx = core;
        if (!wglMakeCurrent(s->dc, s->ctx)) { fail("绑定 Core 上下文失败"); return false; }
        logLine("GL: Core Profile");
    } else {
        s->ctx = legacy;
        logLine("GL: 兼容上下文");
    }
    return true;
}

// ── 核心回调（用 thread_local 定位会话）──
static void videoCb(const void* data, unsigned w, unsigned h, size_t pitch) {
    Session* s = currentSession();
    if (!s) return;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        s->hardware = true;
        if (w) s->w = (int) w;
        if (h) s->h = (int) h;
        return;   // 硬件核心：真正读取在 step() 里做
    }
    if (!data || !w || !h) return;
    {
        static int dumpCount = 0;
        dumpCount++;
        if (dumpCount >= 100 && dumpCount <= 106) {
            wchar_t tmp[MAX_PATH]; GetTempPathW(MAX_PATH, tmp);
            std::wstring name = std::wstring(tmp) + L"raw_dump_" + std::to_wstring(dumpCount) + L"_" + std::to_wstring(w) + L"x" + std::to_wstring(h) + L".bin";
            FILE* fp = nullptr;
            if (_wfopen_s(&fp, name.c_str(), L"wb") == 0 && fp) { fwrite(data, 1, pitch * h, fp); fclose(fp); }
            logLine("raw_dump #%d: pixFmt=%d %ux%u pitch=%zu", dumpCount, s->pixFmt, w, h, pitch);
        }
    }

    // 原样按核心给出的尺寸与行距取帧（不做缩放/裁切/居中）。
    std::lock_guard<std::mutex> frameLock(s->frameMutex);
    s->w = (int) w;
    s->h = (int) h;
    s->pixels.resize((size_t) w * h * 4);

    // 行距：libretro 规定是字节；个别核心会把 pitch 报成"像素"，那会让每行错位（横向剪切）。
    const size_t bpp = (s->pixFmt == 1) ? 4u : 2u;
    size_t stride = pitch;
    if (stride < (size_t) w * bpp) {
        logLine("行距修正: pitch=%zu < w*bpp=%zu，按像素换算", stride, (size_t) w * bpp);
        stride = (size_t) w * bpp;
    }
    {
        static int logged = 0;
        if (logged < 3) { logged++; logLine("video_cb %ux%u pixFmt=%d pitch=%zu stride=%zu", w, h, s->pixFmt, pitch, stride); }
    }
    const uint8_t* src = (const uint8_t*) data;
    if (s->pixFmt == 1) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = src + (size_t) y * stride;
            uint8_t* dst = s->pixels.data() + (size_t) y * w * 4;
            for (unsigned x = 0; x < w; x++) {
                dst[x * 4 + 0] = row[x * 4 + 2];
                dst[x * 4 + 1] = row[x * 4 + 1];
                dst[x * 4 + 2] = row[x * 4 + 0];
                dst[x * 4 + 3] = 255;
            }
        }
    } else {
        for (unsigned y = 0; y < h; y++) {
            const uint16_t* row = (const uint16_t*) (src + (size_t) y * stride);
            uint8_t* dst = s->pixels.data() + (size_t) y * w * 4;
            for (unsigned x = 0; x < w; x++) {
                const uint16_t p = row[x];
                unsigned r, g, b;
                if (s->pixFmt == 2) {
                    r = (p >> 11) & 0x1F; r = (r << 3) | (r >> 2);
                    g = (p >> 5) & 0x3F;  g = (g << 2) | (g >> 4);
                    b = p & 0x1F;         b = (b << 3) | (b >> 2);
                } else {
                    r = (p >> 10) & 0x1F; r = (r << 3) | (r >> 2);
                    g = (p >> 5) & 0x1F;  g = (g << 3) | (g >> 2);
                    b = p & 0x1F;         b = (b << 3) | (b >> 2);
                }
                dst[x * 4 + 0] = (uint8_t) r;
                dst[x * 4 + 1] = (uint8_t) g;
                dst[x * 4 + 2] = (uint8_t) b;
                dst[x * 4 + 3] = 255;
            }
        }
    }
    {
        static int dump2 = 0;
        dump2++;
        if (dump2 == 100) {
            wchar_t tmp[MAX_PATH]; GetTempPathW(MAX_PATH, tmp);
            std::wstring name = std::wstring(tmp) + L"rgba_" + std::to_wstring((int) w) + L"x" + std::to_wstring((int) h) + L".bin";
            FILE* fp = nullptr;
            if (_wfopen_s(&fp, name.c_str(), L"wb") == 0 && fp) { fwrite(s->pixels.data(), 1, (size_t) w * h * 4, fp); fclose(fp); }
            logLine("rgba dump %ux%u -> %ls", w, h, name.c_str());
        }
    }
}

static size_t audioCb(const int16_t* data, size_t frames) {
    {
        // 定期统计：每 600 次回调（约 3 秒）打印一次峰值，判断核心到底有没有产出非静音音频
        static int calls = 0;
        static int peak = 0;
        static long long total = 0;
        if (data) {
            for (size_t i = 0; i < frames * 2; i++) {
                int v = data[i] < 0 ? -data[i] : data[i];
                if (v > peak) peak = v;
            }
            total += (long long) frames;
            if (++calls % 600 == 0) {
                logLine("audio_cb 累计 %d 次 共 %lld 采样帧 本段峰值=%d", calls, total, peak);
                peak = 0;
            }
        }
    }
    Session* s = currentSession();
    if (!s || !data) return frames;
    std::lock_guard<std::mutex> lk(s->audioMutex);
    const size_t cap = 1u << 16;
    if (s->audio.size() + frames * 2 > cap) s->audio.clear();
    s->audio.insert(s->audio.end(), data, data + frames * 2);
    s->producedStep.fetch_add((int) frames);
    return frames;
}

static void pollCb() {}
// 返回值非 0 时打一行日志，用于确认"核心真的收到了按键"
static int16_t logPad(unsigned device, unsigned index, unsigned id, int16_t v) {
    if (v) {
        static unsigned lastDevice = 999, lastIndex = 999, lastId = 999;
        if (device != lastDevice || index != lastIndex || id != lastId) {
            lastDevice = device; lastIndex = index; lastId = id;
            logLine("输入被取走: device=%u index=%u id=%u v=%d", device, index, id, (int) v);
        }
    }
    return v;
}

static int16_t inputCb(unsigned port, unsigned device, unsigned index, unsigned id) {
    Session* s = currentSession();
    if (!s) return 0;
    // 两个端口给同一份输入：不确定核心把 1P 落在哪个 port，两个都给最稳
    const uint32_t mask = (port <= 1) ? (uint32_t) s->buttons : 0u;

    if (device == RETRO_DEVICE_JOYPAD) {
        if (id == 256) {                       // RETRO_DEVICE_ID_JOYPAD_MASK
            int16_t v = (int16_t) (mask & 0xFFFF);
            return logPad(device, index, id, v);
        }
        if (id < 16) return logPad(device, index, id, (int16_t) ((mask >> id) & 1));
        return 0;
    }

    if (device == RETRO_DEVICE_ANALOG) {
        if (index == 0) {
            if (id == 0) return (int16_t) s->ax;
            if (id == 1) return (int16_t) s->ay;
        if (index == 1) {   // 右摇杆
            if (id == 0) return (int16_t) s->ax2;
            if (id == 1) return (int16_t) s->ay2;
        }
        }
        // 面键通道（PCSX2 用 index=2）：这里要返回**压力值**，按下给满量程 32767。
        // 之前返回 1 等于 0.003% 力度，模拟手柄视为未按；十字键走数字位所以不受影响。
        if (index == 2 || index == 0 || index == 1) {
            if (id < 16) {
                bool pressed = ((mask >> id) & 1) != 0;
                return logPad(device, index, id, (int16_t) (pressed ? 32767 : 0));
            }
        }
        return 0;
    }

    if (device == RETRO_DEVICE_POINTER && index == 0) {
        if (id == 0) return (int16_t) s->px;
        if (id == 1) return (int16_t) s->py;
        if (id == 2) return (int16_t) (s->pdown ? 1 : 0);
    }
    return 0;
}

static uintptr_t currentFbCb() {
    Session* s = currentSession();
    static int calls = 0;
    if (++calls <= 5) logLine("核心索取当前 FBO 第 %d 次 → %u", calls, s ? s->fbo : 0u);
    return s ? (uintptr_t) s->fbo : 0;
}

static void* procCb(const char* sym) {
    void* p = (void*) wglGetProcAddress(sym);
    if (!p) {
        HMODULE gl = GetModuleHandleW(L"opengl32.dll");
        if (!gl) gl = LoadLibraryW(L"opengl32.dll");
        if (gl) p = (void*) GetProcAddress(gl, sym);
    }
    return p;
}

static void contextResetCb() {
    Session* s = currentSession();
    if (!s) return;
    makeCurrent(s);
    // 不重建 FBO：核心已通过 get_current_framebuffer 拿到并缓存了它。
    logLine("context_reset（沿用 fbo=%u %dx%d）", s->fbo, s->w, s->h);
    s->hardware = true;
}

static void setSharedContextCb() {
    // Azahar(Citra) 有自己的 GPU 线程，它通过这个回调把宿主上下文绑到当前线程。
    // 不提供的话核心永远拿不到上下文 → 不渲染 → video_cb 恒为 0x0（我们刚踩到）。
    Session* s = currentSession();
    if (!s) return;
    if (wglGetCurrentContext() != s->ctx) wglMakeCurrent(s->dc, s->ctx);
    logLine("set_shared_context：已绑定上下文到线程 %lu", (unsigned long) GetCurrentThreadId());
}

static void contextDestroyCb() {}
static void logCb(int level, const char* fmt, ...) {
    char buf[1024];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    logLine("core[%d] %s", level, buf);
    Session* s = currentSession();
    if (s && strstr(buf, "LoadProgram") && strstr(buf, "Linking program")) s->programs.fetch_add(1);
}

static bool envCb(unsigned cmd, void* data) {
    Session* s = currentSession();
    if (!s) return false;
    switch (cmd) {
        // 正确的 libretro.h 编号（之前整体错位，导致核心取不到选项 → 音频被当成 Disabled → 静音）
        case 3:   // GET_CAN_DUPE
            *(bool*) data = true;
            return true;
        case 9:   // GET_SYSTEM_DIRECTORY
            *(const char**) data = s->sysDir.c_str();
            return true;
        case 10:  // SET_PIXEL_FORMAT
            s->pixFmt = (int) *(const unsigned*) data;
            logLine("SET_PIXEL_FORMAT=%d", s->pixFmt);
            return true;
        case 14: { // SET_HW_RENDER：只换两个"宿主提供"的回调，其余保留核心自己的
            auto* cb = (retro_hw_render_callback*) data;
            s->hw = *cb;
            s->hardware = true;
            s->hw.get_current_framebuffer = (void*) currentFbCb;
            s->hw.get_proc_address = (void*) procCb;
            if (!s->hw.context_reset) s->hw.context_reset = contextResetCb;
            if (!s->hw.context_destroy) s->hw.context_destroy = contextDestroyCb;
            if (!s->hw.set_shared_context) s->hw.set_shared_context = setSharedContextCb;
            *(retro_hw_render_callback*) data = s->hw;
            logLine("SET_HW_RENDER type=%u ver=%u.%u", cb->context_type, cb->version_major, cb->version_minor);
            return true;
        }
        case 15: { // GET_VARIABLE：核心来问选项（音频仿真模式等），必须给出值
            auto* v = (retro_variable*) data;
            v->value = nullptr;
            if (!v->key) return false;
            auto it = s->options.find(v->key);
            if (it != s->options.end() && !it->second.empty()) {
                v->value = it->second.c_str();
                return true;
            }
            logLine("GET_VARIABLE 未定义: %s", v->key);
            return false;
        }
        case 16: { // SET_VARIABLES：核心下发选项清单（含默认值），记住默认值
            auto* vars = (retro_variable*) data;
            if (!vars) return true;
            for (auto* p = vars; p->key; p++) {
                std::string line = p->value ? p->value : "";
                std::string key = p->key;
                std::string def;
                size_t semi = line.find(';');
                if (semi != std::string::npos) {
                    std::string rest = line.substr(semi + 1);
                    size_t bar = rest.find('|');
                    def = (bar == std::string::npos) ? rest : rest.substr(0, bar);
                }
                s->options[key] = def;
            }
            // 触摸屏：核心默认可能关闭鼠标/指针触摸，这里显式打开
            if (s->options.count("citra_enable_mouse_touchscreen")) {
                s->options["citra_enable_mouse_touchscreen"] = "enabled";
            }
            if (s->options.count("citra_touch_touchscreen")) {
                s->options["citra_touch_touchscreen"] = "enabled";
            }
            logLine("SET_VARIABLES: 收到 %zu 个选项", s->options.size());
            for (const auto& kv : s->options) {
                logLine("选项 %s = %s", kv.first.c_str(), kv.second.c_str());
            }
            // 触摸屏：显式打开（核心默认可能是关闭）
            // PS2（PCSX2）：默认走硬件渲染，画面进它自己的 FBO，我们读回全黑；
            // 改为软件渲染后走普通 video_cb，和 SFC/GBA 同一条链路。
            if (s->options.count("pcsx2_renderer")) {
                s->options["pcsx2_renderer"] = "Software";
            }
            if (s->options.count("pcsx2_bios")) {
                s->options["pcsx2_bios"] = "scph39001.bin";
            }
            if (s->options.count("pcsx2_analog_mode1")) {
                s->options["pcsx2_analog_mode1"] = "enabled";   // 手柄进入模拟模式（DualShock2）
            }
            if (s->options.count("pcsx2_shared_memory_cards")) {
                s->options["pcsx2_shared_memory_cards"] = "disabled";  // 用独立记忆卡，别走共享卡目录
            }
            if (s->options.count("pcsx2_fastboot")) {
                s->options["pcsx2_fastboot"] = "enabled";
            }

            s->options["citra_enable_touch_touchscreen"] = "enabled";
            s->options["citra_enable_mouse_touchscreen"] = "enabled";
            s->options["citra_enable_touch_pointer_timeout"] = "5";

            // ── 3DS 性能：把 Azahar 的加速选项全部拉满（帧数跑不满就会整体放慢）──
            auto setOpt = [&](const char* k, const char* v) { if (s->options.count(k)) s->options[k] = v; };
            setOpt("citra_use_hw_shader", "enabled");          // 硬件着色器
            setOpt("citra_use_shader_jit", "enabled");         // 着色器 JIT
            setOpt("citra_use_disk_shader_cache", "enabled");  // 磁盘着色器缓存（避免重复编译）
            setOpt("citra_shaders_accurate_mul", "disabled");  // 关闭精确乘法（默认慢）
            setOpt("citra_use_cpu_jit", "enabled");
            setOpt("citra_resolution_factor", "1");            // 1x 原生分辨率
            setOpt("citra_texture_filter", "0");               // 最近邻，最快
            setOpt("citra_texture_sampling", "0");
            setOpt("citra_is_new_3ds", "enabled");             // 以 New3DS 运行，CPU 上限更高
            setOpt("citra_render_3d", "0");                    // 关 3D，省一半光栅化
            return true;
        }
        case 17:  // GET_VARIABLE_UPDATE
            *(bool*) data = false;
            return true;
        case 18:  // SET_SUPPORT_NO_GAME
            return true;
        case 27:  // GET_LOG_INTERFACE
            ((retro_log_callback*) data)->log = logCb;
            return true;
        case 31:  // GET_SAVE_DIRECTORY（必须存在且可写）
            *(const char**) data = s->saveDir.c_str();
            return true;
        case 67:  // GET_JIT_CAPABLE
            *(bool*) data = true;
            return true;
        default:
            return false;
    }
}
static std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int) w.length(), nullptr, 0, nullptr, nullptr);
    std::string out((size_t) n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int) w.length(), out.data(), n, nullptr, nullptr);
    return out;
}

static std::wstring widen(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int) s.length(), nullptr, 0);
    std::wstring out((size_t) n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int) s.length(), out.data(), n);
    return out;
}

static Session* newSession(const std::string& corePath, const std::string& game,
                           const std::string& sysDir, uint8_t* video, size_t videoCap,
                           int16_t* audio, size_t audioCap) {
    for (size_t i = 0; i < kMaxSessions; i++) {
        Lock lk(g_slots[i].guard, std::try_to_lock);
        if (!lk.owns_lock()) continue;
        if (g_slots[i].session) continue;
        auto s = std::make_shared<Session>();
        s->slot = i;
        // token：高位是会话序号、低两位是槽位。必须保证非 0，否则会被当成"创建失败"。
        jlong next = g_nextToken.fetch_add(1);
        s->token = (next & ~(jlong) (kMaxSessions - 1)) | (jlong) i;
        if (s->token == 0) s->token = (jlong) kMaxSessions + (jlong) i;
        s->owner = GetCurrentThreadId();
        s->corePath = corePath;
        s->gamePath = game;
        s->sysDir = sysDir;
        s->saveDir = sysDir + "\\saves";
        CreateDirectoryA(s->saveDir.c_str(), nullptr);
        s->videoDst = video; s->videoCap = videoCap;
        s->audioDst = audio; s->audioCap = audioCap;
        g_slots[i].session = s;
        logLine("新建会话 token=%lld 槽=%zu", (long long) s->token, i);
        return s.get();
    }
    fail("会话槽已满（最多 4 个）");
    return nullptr;
}

extern "C" {

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_captureContext(JNIEnv*, jclass) {
    // 必须在 MC 渲染线程调用：把 MC 的 GL 上下文记下来，供会话上下文共享纹理/FBO。
    g_mcContext = wglGetCurrentContext();
    logLine("captureContext: MC HGLRC=%p", (void*) g_mcContext);
}

JNIEXPORT jlong JNICALL Java_com_november_minetendo_SessionHost_create(
        JNIEnv* env, jclass, jstring coreJ, jstring gameJ, jstring sysJ,
        jobject videoBuf, jobject audioBuf) {
    const char* core = env->GetStringUTFChars(coreJ, nullptr);
    const char* game = env->GetStringUTFChars(gameJ, nullptr);
    const char* sys = env->GetStringUTFChars(sysJ, nullptr);
    uint8_t* video = videoBuf ? (uint8_t*) env->GetDirectBufferAddress(videoBuf) : nullptr;
    size_t videoCap = videoBuf ? (size_t) env->GetDirectBufferCapacity(videoBuf) : 0;
    int16_t* audio = audioBuf ? (int16_t*) env->GetDirectBufferAddress(audioBuf) : nullptr;
    size_t audioCap = audioBuf ? (size_t) env->GetDirectBufferCapacity(audioBuf) : 0;

    Session* s = nullptr;
    try {
        s = newSession(core, game, sys, video, videoCap, audio, audioCap);
        if (!s) throw std::runtime_error(g_lastError);
        t_invoking = s;
        g_activeSession.store(s);

        // 自有隐藏窗口 + 独立 GL 上下文（工作线程里跑核心）。
        // 这条路径实测能出画面；窗口尺寸变化时核心会崩，所以界面侧对 resize 不做任何处理。
        if (!createContext(s)) throw std::runtime_error(g_lastError);
        makeCurrent(s);

        s->core.mod = LoadLibraryW(widen(core).c_str());
        if (!s->core.mod) throw std::runtime_error("加载核心 DLL 失败");
        { auto lk = lockModules(); g_modules.insert(s->core.mod); }

        auto sym = [&](const char* n) { return (void*) GetProcAddress(s->core.mod, n); };
        s->core.env       = (decltype(s->core.env)) sym("retro_set_environment");
        s->core.setVideo  = (decltype(s->core.setVideo)) sym("retro_set_video_refresh");
        s->core.setAudio  = (decltype(s->core.setAudio)) sym("retro_set_audio_sample_batch");
        s->core.setPoll   = (decltype(s->core.setPoll)) sym("retro_set_input_poll");
        s->core.setInput  = (decltype(s->core.setInput)) sym("retro_set_input_state");
        s->core.init      = (decltype(s->core.init)) sym("retro_init");
        s->core.deinit    = (decltype(s->core.deinit)) sym("retro_deinit");
        s->core.load      = (decltype(s->core.load)) sym("retro_load_game");
        s->core.unload    = (decltype(s->core.unload)) sym("retro_unload_game");
        s->core.run       = (decltype(s->core.run)) sym("retro_run");
        s->core.av        = (decltype(s->core.av)) sym("retro_get_system_av_info");
        s->core.portDevice = (decltype(s->core.portDevice)) sym("retro_set_controller_port_device");
        s->core.serializeSize = (decltype(s->core.serializeSize)) sym("retro_serialize_size");
        s->core.serialize = (decltype(s->core.serialize)) sym("retro_serialize");
        s->core.unserialize = (decltype(s->core.unserialize)) sym("retro_unserialize");
        if (!s->core.env || !s->core.init || !s->core.load || !s->core.run)
            throw std::runtime_error("核心缺少必要导出");

        s->core.env(envCb);
        s->core.setVideo(videoCb);
        s->core.setAudio(audioCb);
        s->core.setPoll(pollCb);
        s->core.setInput(inputCb);
        s->core.init();
        s->inited = true;

        retro_game_info gi{};
        gi.path = game;
        if (!s->core.load(&gi)) throw std::runtime_error("核心拒绝加载游戏");
        s->loaded = true;

        if (s->core.av) {
            retro_system_av_info av{};
            s->core.av(&av);
            if (av.timing.sample_rate > 1000) s->sampleRate = (unsigned) av.timing.sample_rate;
            if (av.timing.fps > 1) s->fps = av.timing.fps;
            if (av.geometry.base_width > 0) {
                s->w = (int) av.geometry.base_width;
                s->h = (int) av.geometry.base_height;
                s->texW = s->w;
                s->texH = s->h;
            }
            logLine("av_info %dx%d rate=%u fps=%.2f", s->w, s->h, s->sampleRate, s->fps);
        }
        // PCSX2 是 DualShock2（模拟手柄）核心：1P 声明成 ANALOG，否则它可能忽略按键。
        bool isPcsx2 = s->corePath.find("pcsx2") != std::string::npos;
    // 回退：声明 ANALOG 会连十字键一起失效，改回 JOYPAD（十字键已验证可用）
    if (s->core.portDevice) s->core.portDevice(0, RETRO_DEVICE_JOYPAD);
        logLine("1P 设备声明为 %s", isPcsx2 ? "ANALOG" : "JOYPAD");

        // 顺序关键：先建 FBO，再通知核心（参考项目就是这样）
        makeCurrent(s);
        if (!createFramebuffer(s)) throw std::runtime_error(g_lastError);
        if (s->hw.context_reset) s->hw.context_reset();

        g_activeSession.store(nullptr);
        t_invoking = nullptr;
        env->ReleaseStringUTFChars(coreJ, core);
        env->ReleaseStringUTFChars(gameJ, game);
        env->ReleaseStringUTFChars(sysJ, sys);
        logLine("会话就绪 token=%lld %dx%d", (long long) s->token, s->w, s->h);
        return s->token;
    } catch (const std::exception& ex) {
        t_invoking = nullptr;
        fail(ex.what());
        env->ReleaseStringUTFChars(coreJ, core);
        env->ReleaseStringUTFChars(gameJ, game);
        env->ReleaseStringUTFChars(sysJ, sys);
        return 0;
    }
}

// 跑一帧：核心 → 读 FBO → 写 Java 的视频缓冲；音频同样写进音频缓冲。
JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeStep(
        JNIEnv* env, jclass, jlong token, jintArray meta) {
    try {
        // 抢锁失败只说明这一帧撞上了别的调用（比如输入），重试几次即可，
        // 不能把它当成会话异常 —— 之前就是这个误判让界面报"会话正被占用"。
        std::unique_ptr<Access> holder;
        for (int attempt = 0; attempt < 8 && !holder; attempt++) {
            try {
                holder = std::make_unique<Access>(token);
            } catch (const std::exception&) {
                Sleep(1);
            }
        }
        if (!holder) throw std::runtime_error("会话忙（多次重试仍被占用）");
        Session* s = holder->s.get();
        makeCurrent(s);

        GLint prev = 0;
        glGetIntegerv(0x8CA9, &prev);
        pBindFbo(GL_FRAMEBUFFER, s->fbo);
        glViewport(0, 0, s->w, s->h);
        {
            static bool loggedOnce = false;
            if (!loggedOnce) {
                loggedOnce = true;
                GLenum st = pCheckFbo ? pCheckFbo(GL_FRAMEBUFFER) : 0xFFFF;
                GLint vp[4] = {0,0,0,0}; glGetIntegerv(GL_VIEWPORT, vp);
                logLine("step 前: fbo=%u status=0x%x viewport=%dx%d", s->fbo, (unsigned) st, vp[2], vp[3]);
            }
        }
        long long tA = nowUs();
        s->producedStep.store(0);   // 本帧产出清零（节拍用）
        // 处理存/读档请求（两帧之间）
        {
            std::string savePath, loadPath;
            { std::lock_guard<std::mutex> lk(s->snapMutex); savePath.swap(s->pendingSave); loadPath.swap(s->pendingLoad); }
            if (!loadPath.empty() && s->core.unserialize) {
                FILE* fp = nullptr;
                if (fopen_s(&fp, loadPath.c_str(), "rb") == 0 && fp) {
                    fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                    if (size > 0) {
                        std::vector<uint8_t> buf((size_t) size);
                        if (fread(buf.data(), 1, (size_t) size, fp) == (size_t) size) {
                            bool ok = s->core.unserialize(buf.data(), (size_t) size);
                            logLine("loadState(%s) ok=%d", loadPath.c_str(), (int) ok);
                        }
                    }
                    fclose(fp);
                }
            }
            if (!savePath.empty() && s->core.serializeSize && s->core.serialize) {
                size_t size = s->core.serializeSize();
                if (size) {
                    std::vector<uint8_t> buf(size);
                    if (s->core.serialize(buf.data(), size)) {
                        FILE* fp = nullptr;
                        if (fopen_s(&fp, savePath.c_str(), "wb") == 0 && fp) { fwrite(buf.data(), 1, size, fp); fclose(fp); }
                        logLine("saveState(%s) %zu 字节", savePath.c_str(), size);
                    }
                }
            }
        }
        s->core.run();
        long long tB = nowUs();
        {
            static long long t0 = 0; static int n = 0; static long long sumRun = 0, sumRead = 0;
            long long now = nowUs();
            if (t0 == 0) t0 = now;
            if (++n >= 60) {
                double ms = (double) (now - t0) / n / 1000.0;
                double fps = ms > 0 ? 1000.0 / ms : 0;
                logLine("真实帧率 %.1f FPS（每帧 %.1f ms）核心 %lld ms/帧 读回 %lld ms/帧",
                        fps, ms, (long long) (sumRun / n), (long long) (sumRead / n));
                t0 = now; n = 0; sumRun = 0; sumRead = 0;
            }
            g_tmpSumRun = &sumRun;
            g_tmpSumRead = &sumRead;
        }
        {
            static int dbg2 = 0;
            if ((++dbg2 % 5) == 0) {
                GLint after = 0;
                glGetIntegerv(0x8CA9, &after);
                logLine("step 后: 绑定 fbo=%d（我们希望 %u）", (int) after, s->fbo);
            }
        }
        pBindFbo(GL_FRAMEBUFFER, (GLuint) prev);

        if (s->hardware && !s->gpuCopy.load()) {
            const size_t need = (size_t) s->w * s->h * 4;
            if (s->pixels.size() != need) s->pixels.assign(need, 0);
            long long tC = nowUs();
            pBindFbo(GL_FRAMEBUFFER, s->fbo);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);

            if (s->pbo[0] && pBindBuffer && pMapBufferRange) {
                // 本帧：把画面读进当前 PBO（DMA 提交，不等 GPU）
                pBindBuffer(0x88EB, s->pbo[s->pboIndex]);
                glReadPixels(0, 0, s->w, s->h, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
                pBindBuffer(0x88EB, 0);
                if (pFenceSync) s->fence[s->pboIndex] = pFenceSync(0x9117 /*SYNC_GPU_COMMANDS_COMPLETE*/, 0);

                // 取上一帧的 PBO：先问围栏"写完没"，没写完就跳过本帧，绝不等 GPU。
                // 在核心线程上等 GPU，就是"机器很强却被拖慢"的根因。
                if (s->pboReady) {
                    int prevIdx = s->pboIndex ^ 1;
                    bool ready = true;
                    if (pClientWaitSync && s->fence[prevIdx]) {
                        GLenum r = pClientWaitSync(s->fence[prevIdx], 0x00000001 /*SYNC_FLUSH_COMMANDS_BIT*/, 0);
                        ready = (r == 0x911A /*ALREADY_SIGNALED*/ || r == 0x911B /*CONDITION_SATISFIED*/);
                        if (ready && pDeleteSync) { pDeleteSync(s->fence[prevIdx]); s->fence[prevIdx] = nullptr; }
                    }
                    if (ready) {
                        pBindBuffer(0x88EB, s->pbo[prevIdx]);
                        void* ptr = pMapBufferRange(0x88EB, 0, (long long) need, 0x0001 /*MAP_READ*/);
                        if (ptr) {
                            memcpy(s->pixels.data(), ptr, need);
                            if (pUnmapBuffer) pUnmapBuffer(0x88EB);
                        }
                        pBindBuffer(0x88EB, 0);
                    }
                }
                s->pboIndex ^= 1;
                s->pboReady = true;
            } else {
                glReadPixels(0, 0, s->w, s->h, GL_RGBA, GL_UNSIGNED_BYTE, s->pixels.data());
            }
            pBindFbo(GL_FRAMEBUFFER, (GLuint) prev);
            if (s->hw.bottom_left_origin) {
                const size_t stride = (size_t) s->w * 4;
                std::vector<uint8_t> row(stride);
                for (int y = 0; y < s->h / 2; y++) {
                    memcpy(row.data(), s->pixels.data() + (size_t) y * stride, stride);
                    memcpy(s->pixels.data() + (size_t) y * stride, s->pixels.data() + (size_t) (s->h - 1 - y) * stride, stride);
                    memcpy(s->pixels.data() + (size_t) (s->h - 1 - y) * stride, row.data(), stride);
                }
            }
            for (size_t i = 3; i < s->pixels.size(); i += 4) s->pixels[i] = 255;

            // 诊断：每 60 帧打印一次读回内容的非黑像素数。
            // 0 → 核心没画进我们的 FBO（或读错缓冲）；>0 → 画面有内容，问题在下游。
            static int dbg = 0;
            if ((++dbg % 5) == 0) {
                size_t nonBlack = 0;
                for (size_t i = 0; i < s->pixels.size(); i += 4)
                    if (s->pixels[i] || s->pixels[i + 1] || s->pixels[i + 2]) nonBlack++;

                // 对照：核心若画在默认帧缓冲（FBO 0）上，这里会有内容。
                // （Azahar 从不调用 get_current_framebuffer，所以它很可能自己在 FBO 0 上画。）
                std::vector<uint8_t> probe((size_t) s->w * s->h * 4, 0);
                pBindFbo(GL_FRAMEBUFFER, 0);
                glReadBuffer(GL_BACK);
                glPixelStorei(GL_PACK_ALIGNMENT, 1);
                glReadPixels(0, 0, s->w, s->h, GL_RGBA, GL_UNSIGNED_BYTE, probe.data());
                size_t nonBlack0 = 0;
                for (size_t i = 0; i < probe.size(); i += 4)
                    if (probe[i] || probe[i + 1] || probe[i + 2]) nonBlack0++;
                pBindFbo(GL_FRAMEBUFFER, s->fbo);

                logLine("读回 %dx%d fbo=%u 非黑=%zu/%zu | FBO0 非黑=%zu hw=%d",
                        s->w, s->h, s->fbo, nonBlack, (size_t) s->w * s->h, nonBlack0, (int) s->hardware);
            }
        }

        if (s->videoDst && s->videoCap >= s->pixels.size())
            memcpy(s->videoDst, s->pixels.data(), s->pixels.size());

        // 音频不再在帧里搬走：核心写进会话环形缓冲，Java 侧用 readAudio() 加锁取。
        // 以前是工作线程写、渲染线程读同一块内存且无同步 → 读到半截数据，就是电流声。
        size_t audioFrames = (size_t) s->producedStep.load();
        {
            std::lock_guard<std::mutex> lk(s->audioMutex);
            // 待播总数单独查询；这里只报本帧产出
        }

        if (meta && env->GetArrayLength(meta) >= 5) {
            jint m[5] = { s->w, s->h, (jint) s->sampleRate, (jint) audioFrames, (jint) s->programs.load() };
            env->SetIntArrayRegion(meta, 0, 5, m);
        }
        return JNI_TRUE;
    } catch (const std::exception& ex) {
        fail(ex.what());
        return JNI_FALSE;
    }
}

// 把核心的 FBO 纹理用 GPU 内 blit 拷到目标纹理（不做 GPU→CPU 回读）。
// 从会话的音频环形缓冲里取数据（加锁，最多 copy 帧）。返回实际取到的采样帧数。
JNIEXPORT jint JNICALL Java_com_november_minetendo_SessionHost_nativeReadAudio(
        JNIEnv* env, jclass, jlong token, jobject dst, jint maxFrames) {
    try {
        // 不能用 Access：它会校验"必须是会话所属线程"，而音频是从 MC 渲染线程来取的，
        // 会话属于工作线程 → 直接被拒（表现就是完全没声音）。
        // 音频本身有 audioMutex 保护，这里只需要安全拿到会话指针。
        std::shared_ptr<Session> holder;
        {
            size_t idx = (size_t) (token & (kMaxSessions - 1));
            std::lock_guard<std::mutex> slot(g_slots[idx].guard);
            if (g_slots[idx].session && g_slots[idx].session->token == token) {
                holder = g_slots[idx].session;
            }
        }
        if (!holder) return 0;
        Session* s = holder.get();
        int16_t* out = (int16_t*) env->GetDirectBufferAddress(dst);
        size_t capShorts = (size_t) env->GetDirectBufferCapacity(dst) / sizeof(int16_t);
        if (!out || capShorts < 2 || maxFrames <= 0) return 0;
        std::lock_guard<std::mutex> lk(s->audioMutex);
        size_t want = (size_t) maxFrames * 2;
        if (want > capShorts) want = capShorts;
        want &= ~(size_t) 1;
        size_t n = s->audio.size() < want ? s->audio.size() : want;
        n &= ~(size_t) 1;
        if (n) {
            memcpy(out, s->audio.data(), n * sizeof(int16_t));
            s->audio.erase(s->audio.begin(), s->audio.begin() + n);
        }
        return (jint) (n / 2);
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_setGpuCopy(JNIEnv*, jclass, jlong token, jboolean on) {
    try { Access a(token); a.s->gpuCopy = on != JNI_FALSE; } catch (...) {}
}

JNIEXPORT jint JNICALL Java_com_november_minetendo_SessionHost_textureId(JNIEnv*, jclass, jlong token) {
    try { Access a(token); return (jint) a.s->fbo ? (jint) a.s->tex : 0; } catch (...) { return 0; }
}

// 取会话但不校验线程归属（渲染线程要调用；真正的 serialize 由模拟线程在两帧之间做）
static std::shared_ptr<Session> sessionForSnapshot(jlong token) {
    size_t idx = (size_t) (token & (kMaxSessions - 1));
    std::lock_guard<std::mutex> slot(g_slots[idx].guard);
    if (g_slots[idx].session && g_slots[idx].session->token == token) return g_slots[idx].session;
    return nullptr;
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeSaveState(JNIEnv* env, jclass, jlong token, jstring path) {
    try {
        auto holder = sessionForSnapshot(token);
        if (!holder) return JNI_FALSE;
        const char* p = env->GetStringUTFChars(path, nullptr);
        { std::lock_guard<std::mutex> lk(holder->snapMutex); holder->pendingSave = p ? p : ""; }
        env->ReleaseStringUTFChars(path, p);
        return JNI_TRUE;
    } catch (...) { return JNI_FALSE; }
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeLoadState(JNIEnv* env, jclass, jlong token, jstring path) {
    try {
        auto holder = sessionForSnapshot(token);
        if (!holder) return JNI_FALSE;
        const char* p = env->GetStringUTFChars(path, nullptr);
        { std::lock_guard<std::mutex> lk(holder->snapMutex); holder->pendingLoad = p ? p : ""; }
        env->ReleaseStringUTFChars(path, p);
        return JNI_TRUE;
    } catch (...) { return JNI_FALSE; }
}

// 等模拟线程把待处理的存/读档做完（ESC 之后马上关会话时必需）
JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeWaitSnapshot(JNIEnv*, jclass, jlong token, jint timeoutMs) {
    auto holder = sessionForSnapshot(token);
    if (!holder) return JNI_FALSE;
    const long long deadline = (long long) GetTickCount64() + timeoutMs;
    for (;;) {
        bool done;
        { std::lock_guard<std::mutex> lk(holder->snapMutex); done = holder->pendingSave.empty() && holder->pendingLoad.empty(); }
        if (done) return JNI_TRUE;
        if ((long long) GetTickCount64() > deadline) return JNI_FALSE;
        Sleep(5);
    }
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_copyTo(
        JNIEnv*, jclass, jlong token, jint dstTex, jint dstW, jint dstH) {
    try {
        Access a(token);
        Session* s = a.s.get();
        makeCurrent(s);
        if (!s->tex || dstTex <= 0) return JNI_FALSE;
        glBindTexture(GL_TEXTURE_2D, dstTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, dstW, dstH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindTexture(GL_TEXTURE_2D, 0);

        GLuint readFbo = 0, drawFbo = 0;
        pGenFbo(1, &readFbo);
        pGenFbo(1, &drawFbo);
        pFboTex(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s->tex, 0);
        pBindFbo(GL_FRAMEBUFFER, readFbo);
        pFboTex(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s->tex, 0);
        pBindFbo(GL_FRAMEBUFFER, drawFbo);
        pFboTex(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, (GLuint) dstTex, 0);
        typedef void (APIENTRY* PFN_Blit)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);
        static PFN_Blit blit = nullptr;
        if (!blit) blit = (PFN_Blit) wglGetProcAddress("glBlitFramebuffer");
        if (blit) {
            pBindFbo(0x8CA9 /*DRAW_FRAMEBUFFER*/, drawFbo);
            pBindFbo(0x8CA8 /*READ_FRAMEBUFFER*/, readFbo);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            blit(0, 0, s->w, s->h, 0, 0, dstW, dstH, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        }
        pBindFbo(GL_FRAMEBUFFER, 0);
        if (pDelFbo) { pDelFbo(1, &readFbo); pDelFbo(1, &drawFbo); }
        return blit ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_destroy(JNIEnv*, jclass, jlong token) {
    try {
        size_t idx = slotOf(token);
        std::shared_ptr<Session> s;
        {
            Lock lk(g_slots[idx].guard, std::try_to_lock);
            if (!lk.owns_lock()) return;
            s = g_slots[idx].session;
            if (!s || s->token != token) return;
            g_slots[idx].session.reset();
        }
        logLine("销毁会话 token=%lld", (long long) token);
        // 关键：先把核心正常收尾（unload_game → deinit），再释放会话。
        // 少了这两步，核心内部仍处于"已加载"状态，第二次打开同一个核心时
        // retro_init 会撞上旧全局状态 —— 表现为"第二次打开游戏崩溃"。
        try {
            if (s->loaded && s->core.unload) {
                s->core.unload();
                s->loaded = false;
            }
            if (s->inited && s->core.deinit) {
                s->core.deinit();
                s->inited = false;
            }
            logLine("核心已收尾（unload_game + deinit）");
        } catch (...) {
        }
        // 核心模块仍然故意不 FreeLibrary（换系统靠新会话）。
    } catch (...) {
    }
}

JNIEXPORT jstring JNICALL Java_com_november_minetendo_SessionHost_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_lastError);
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_setInput(
        JNIEnv*, jclass, jlong token, jint buttons, jint ax, jint ay, jint ax2, jint ay2) {
    try { Access a(token); a.s->buttons = (uint32_t) buttons; a.s->ax = ax; a.s->ay = ay; a.s->ax2 = ax2; a.s->ay2 = ay2; } catch (...) {}
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_setPointer(
        JNIEnv*, jclass, jlong token, jint x, jint y, jboolean down) {
    try { Access a(token); a.s->px = x; a.s->py = y; a.s->pdown = down ? 1u : 0u; } catch (...) {}
}

}  // extern "C"
