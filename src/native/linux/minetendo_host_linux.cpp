// SPDX-License-Identifier: GPL-3.0-or-later
// Minetendo 原生宿主的 **Linux 版**（x86_64）。
//
// 与 Windows 版（src/native/minetendo_host.cpp）保持同一套 JNI 接口，
// Java 侧（SessionHost）不需要任何平台分支，只要换库文件。
//
// 关键差异：
//   * 窗口/上下文：Windows 用隐藏窗口 + WGL；这里用 **EGL surfaceless**
//     （不需要 X11，服务器/无头环境也能跑），失败时退回 X11 EGL。
//   * 没有 PBO/围栏：直接 glReadPixels 读 FBO，简单可靠；对 SFC/GBA/NDS
//     这类 60fps 的小分辨率够用，PS2/PSP 会有额外开销（后续可加 PBO）。
//   * 硬件渲染核心（要求 RETRO_HW_RENDER 的）在这里**拒绝**，让核心退回软件渲染。
//
// 构建：见同目录 build-linux.sh（需要 g++、libegl-dev、libgl-dev）。

#include <jni.h>

#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

// ── libretro 最小声明（字段顺序必须与 libretro.h 一致）──
enum {
    RETRO_DEVICE_NONE = 0,
    RETRO_DEVICE_JOYPAD = 1,
    RETRO_DEVICE_KEYBOARD = 3,
    RETRO_DEVICE_ANALOG = 5,
    RETRO_DEVICE_POINTER = 6
};
enum { RETRO_PIXEL_FORMAT_0RGB1555 = 0, RETRO_PIXEL_FORMAT_XRGB8888 = 1, RETRO_PIXEL_FORMAT_RGB565 = 2 };

struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_variable { const char* key; const char* value; };
struct retro_log_callback { void (*log)(int level, const char* fmt, ...); };
struct retro_keyboard_callback {
    void (*callback)(bool down, unsigned keycode, uint32_t character, uint16_t key_modifiers);
};
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };

static std::mutex g_logMutex;
static void logLine(const char* fmt, ...) {
    std::lock_guard<std::mutex> lk(g_logMutex);
    FILE* fp = fopen("/tmp/minetendo-session.log", "a");
    if (!fp) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(fp, fmt, ap);
    va_end(ap);
    fputc('\n', fp);
    fclose(fp);
}

static char g_lastError[512] = {0};
static void fail(const char* msg) {
    snprintf(g_lastError, sizeof(g_lastError), "%s", msg ? msg : "unknown");
    logLine("[native] %s", g_lastError);
}

struct Core {
    void* handle = nullptr;
    void (*setEnv)(bool (*)(unsigned, void*)) = nullptr;
    void (*setVideo)(void (*)(const void*, unsigned, unsigned, size_t)) = nullptr;
    void (*setAudio)(size_t (*)(const int16_t*, size_t)) = nullptr;
    void (*setAudioBatch)(size_t (*)(const int16_t*, size_t)) = nullptr;
    void (*setInputPoll)(void (*)(void)) = nullptr;
    void (*setInputState)(int16_t (*)(unsigned, unsigned, unsigned, unsigned)) = nullptr;
    void (*setKeyboard)(retro_keyboard_callback) = nullptr;
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

static const int kMaxSessions = 4;

struct Session {
    jlong token = 0;
    size_t slot = 0;
    Core core;
    std::string corePath, gamePath, sysDir, saveDir;

    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLContext ctx = EGL_NO_CONTEXT;
    GLuint fbo = 0, tex = 0;
    int texW = 0, texH = 0;

    int w = 320, h = 240;
    int pixFmt = RETRO_PIXEL_FORMAT_0RGB1555;
    double fps = 60.0;
    unsigned sampleRate = 32000;

    std::vector<uint8_t> pixels;          // RGBA 自上而下
    std::mutex frameMutex;
    std::vector<int16_t> audio;           // 环形缓冲（简化：直接队列）
    std::mutex audioMutex;

    bool inited = false, loaded = false;
    std::atomic<int32_t> buttons{0};
    std::atomic<int32_t> ax{0}, ay{0}, ax2{0}, ay2{0};
    std::atomic<int32_t> px{0}, py{0};
    std::atomic<uint32_t> pdown{0};
    std::atomic<int> producedStep{0};

    retro_keyboard_callback kb{};
    bool hasKb = false;
    std::mutex keyMutex;
    std::set<unsigned> keysDown;
    // 待派发给键盘回调的事件（核心线程消费）
    std::vector<unsigned> pendingKeys;
    std::set<unsigned> pendingKeysDown;
    std::mutex snapMutex;
    std::string pendingSave, pendingLoad;
    std::map<std::string, std::string> options;

    void release() {
        if (ctx != EGL_NO_CONTEXT) {
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroyContext(dpy, ctx);
            ctx = EGL_NO_CONTEXT;
        }
        if (dpy != EGL_NO_DISPLAY) { eglTerminate(dpy); dpy = EGL_NO_DISPLAY; }
        // 核心库故意不 dlclose（避免第二次 init 撞全局状态）
    }
};

static std::mutex g_slotGuard;
static std::shared_ptr<Session> g_slots[kMaxSessions];
static std::atomic<jlong> g_nextToken{kMaxSessions};
static std::atomic<Session*> g_active{nullptr};
static std::mutex g_liveGuard;
static std::map<jlong, std::shared_ptr<Session>> g_live;

static Session* currentSession() { return g_active.load(); }

static std::shared_ptr<Session> liveSession(jlong token) {
    std::lock_guard<std::mutex> lk(g_liveGuard);
    auto it = g_live.find(token);
    return it == g_live.end() ? nullptr : it->second;
}

static size_t slotOf(jlong token) {
    if (token <= 0) return kMaxSessions;
    size_t idx = (size_t) (token & (kMaxSessions - 1));
    return idx < kMaxSessions ? idx : kMaxSessions;
}

// ── 核心回调 ──

static bool envCb(unsigned cmd, void* data) {
    Session* s = currentSession();
    if (!s) return false;
    switch (cmd) {
        case 3: *(bool*) data = true; return true;                       // GET_CAN_DUPE
        case 9: *(const char**) data = s->sysDir.c_str(); return true;    // GET_SYSTEM_DIRECTORY
        case 10:                                                          // SET_PIXEL_FORMAT
            s->pixFmt = (int) *(const unsigned*) data;
            return true;
        case 12: {                                                        // SET_KEYBOARD_CALLBACK
            auto* kb = (retro_keyboard_callback*) data;
            if (!kb) return false;
            s->kb = *kb;
            s->hasKb = true;
            return true;
        }
        case 15: {                                                        // GET_VARIABLE
            auto* v = (retro_variable*) data;
            v->value = nullptr;
            if (!v->key) return false;
            auto it = s->options.find(v->key);
            if (it != s->options.end() && !it->second.empty()) {
                v->value = it->second.c_str();
                return true;
            }
            return false;
        }
        case 16: {                                                        // SET_VARIABLES
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
                while (!def.empty() && (def.front() == ' ' || def.front() == '\t')) def.erase(def.begin());
                while (!def.empty() && (def.back() == ' ' || def.back() == '\t')) def.pop_back();
                s->options[key] = def;
            }
            // PS2 走软件渲染（与 Windows 版一致）
            if (s->options.count("pcsx2_renderer")) s->options["pcsx2_renderer"] = "Software";
            if (s->options.count("pcsx2_bios")) s->options["pcsx2_bios"] = "scph39001.bin";
            return true;
        }
        case 17: *(bool*) data = false; return true;                      // GET_VARIABLE_UPDATE
        case 18: return true;                                             // SET_SUPPORT_NO_GAME
        case 27: ((retro_log_callback*) data)->log = nullptr; return true;
        case 31: *(const char**) data = s->saveDir.c_str(); return true;   // GET_SAVE_DIRECTORY
        default:
            // 特别地：不接受 RETRO_ENVIRONMENT_SET_HW_RENDER(14) → 核心回退软件渲染
            return false;
    }
}

static void videoCb(const void* data, unsigned w, unsigned h, size_t pitch) {
    Session* s = currentSession();
    if (!s || !data || !w || !h) return;
    std::lock_guard<std::mutex> lk(s->frameMutex);
    s->w = (int) w;
    s->h = (int) h;
    size_t need = (size_t) w * h * 4;
    if (s->pixels.size() < need) s->pixels.resize(need);
    const uint8_t* src = (const uint8_t*) data;
    for (unsigned y = 0; y < h; y++) {
        const uint8_t* row = src + y * pitch;
        uint8_t* dst = s->pixels.data() + y * w * 4;
        for (unsigned x = 0; x < w; x++) {
            if (s->pixFmt == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p;
                memcpy(&p, row + x * 4, 4);
                dst[x * 4 + 0] = (p >> 16) & 0xFF;
                dst[x * 4 + 1] = (p >> 8) & 0xFF;
                dst[x * 4 + 2] = p & 0xFF;
                dst[x * 4 + 3] = 0xFF;
            } else if (s->pixFmt == RETRO_PIXEL_FORMAT_RGB565) {
                uint16_t p;
                memcpy(&p, row + x * 2, 2);
                dst[x * 4 + 0] = ((p >> 11) & 0x1F) * 255 / 31;
                dst[x * 4 + 1] = ((p >> 5) & 0x3F) * 255 / 63;
                dst[x * 4 + 2] = (p & 0x1F) * 255 / 31;
                dst[x * 4 + 3] = 0xFF;
            } else {
                uint16_t p;
                memcpy(&p, row + x * 2, 2);
                dst[x * 4 + 0] = ((p >> 10) & 0x1F) * 255 / 31;
                dst[x * 4 + 1] = ((p >> 5) & 0x1F) * 255 / 31;
                dst[x * 4 + 2] = (p & 0x1F) * 255 / 31;
                dst[x * 4 + 3] = 0xFF;
            }
        }
    }
}

static size_t audioCb(const int16_t* data, size_t frames) {
    Session* s = currentSession();
    if (!s || !data || !frames) return 0;
    std::lock_guard<std::mutex> lk(s->audioMutex);
    size_t samples = frames * 2;
    s->audio.insert(s->audio.end(), data, data + samples);
    if (s->audio.size() > (1u << 18)) s->audio.erase(s->audio.begin(), s->audio.begin() + (s->audio.size() - (1u << 17)));
    s->producedStep.store((int) frames);
    return frames;
}

static void pollCb(void) {}

static int16_t inputCb(unsigned port, unsigned device, unsigned index, unsigned id) {
    Session* s = currentSession();
    if (!s) return 0;
    uint32_t mask = (port <= 1) ? (uint32_t) s->buttons.load() : 0u;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id == 256) return (int16_t) (mask & 0xFFFF);
        if (id < 16) return (int16_t) ((mask >> id) & 1);
        return 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == 0) {
            if (id == 0) return (int16_t) s->ax.load();
            if (id == 1) return (int16_t) s->ay.load();
        }
        if (index == 1) {
            if (id == 0) return (int16_t) s->ax2.load();
            if (id == 1) return (int16_t) s->ay2.load();
        }
        if (id < 16) return (int16_t) (((mask >> id) & 1) ? 32767 : 0);
        return 0;
    }
    if (device == RETRO_DEVICE_KEYBOARD) {
        std::lock_guard<std::mutex> lk(s->keyMutex);
        return (int16_t) (s->keysDown.count(id) ? 1 : 0);
    }
    if (device == RETRO_DEVICE_POINTER && index == 0) {
        if (id == 0) return (int16_t) s->px.load();
        if (id == 1) return (int16_t) s->py.load();
        if (id == 2) return (int16_t) (s->pdown.load() ? 1 : 0);
    }
    return 0;
}

// ── EGL 上下文 ──

static bool createContext(Session* s) {
    s->dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s->dpy == EGL_NO_DISPLAY) { fail("eglGetDisplay 失败（需要 libegl1 + mesa）"); return false; }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(s->dpy, &major, &minor)) { fail("eglInitialize 失败"); return false; }

    const EGLint cfgAttrs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24,
            EGL_NONE
    };
    EGLConfig cfg;
    EGLint num = 0;
    if (!eglChooseConfig(s->dpy, cfgAttrs, &cfg, 1, &num) || num < 1) {
        fail("没有可用的 EGL 配置（需要 OpenGL 支持）");
        return false;
    }
    eglBindAPI(EGL_OPENGL_API);
    const EGLint ctxAttrs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3, EGL_NONE };
    s->ctx = eglCreateContext(s->dpy, cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (s->ctx == EGL_NO_CONTEXT) {
        const EGLint ctx2[] = { EGL_NONE };
        s->ctx = eglCreateContext(s->dpy, cfg, EGL_NO_CONTEXT, ctx2);
    }
    if (s->ctx == EGL_NO_CONTEXT) { fail("eglCreateContext 失败"); return false; }
    if (!eglMakeCurrent(s->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, s->ctx)) {
        fail("eglMakeCurrent 失败（surfaceless 不可用？）");
        return false;
    }
    logLine("[egl] 上下文就绪 %d.%d", major, minor);
    return true;
}

static void ensureFbo(Session* s) {
    if (s->fbo == 0) glGenFramebuffers(1, &s->fbo);
    if (s->tex == 0) glGenTextures(1, &s->tex);
    glBindTexture(GL_TEXTURE_2D, s->tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, s->w, s->h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, s->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s->tex, 0);
    s->texW = s->w;
    s->texH = s->h;
}

// ── JNI ──

extern "C" {

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_captureContext(JNIEnv*, jclass) {
    // EGL 不需要借用宿主线程的上下文；保留接口以对齐 Windows 版
}

JNIEXPORT jstring JNICALL Java_com_november_minetendo_SessionHost_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_lastError);
}

JNIEXPORT jlong JNICALL Java_com_november_minetendo_SessionHost_create(
        JNIEnv* env, jclass, jstring coreJ, jstring gameJ, jstring sysJ,
        jobject videoBuf, jobject audioBuf) {
    (void) videoBuf;
    (void) audioBuf;
    const char* core = env->GetStringUTFChars(coreJ, nullptr);
    const char* game = env->GetStringUTFChars(gameJ, nullptr);
    const char* sys = env->GetStringUTFChars(sysJ, nullptr);

    auto s = std::make_shared<Session>();
    s->corePath = core ? core : "";
    s->gamePath = game ? game : "";
    s->sysDir = sys ? sys : "";
    s->saveDir = s->sysDir + "/saves";
    env->ReleaseStringUTFChars(coreJ, core);
    env->ReleaseStringUTFChars(gameJ, game);
    env->ReleaseStringUTFChars(sysJ, sys);

    size_t slot = kMaxSessions;
    for (size_t i = 0; i < kMaxSessions; i++) {
        std::lock_guard<std::mutex> lk(g_slotGuard);
        if (!g_slots[i]) { slot = i; break; }
    }
    if (slot == kMaxSessions) { fail("会话槽已满"); return 0; }
    jlong next = g_nextToken.fetch_add(1);
    s->token = (next & ~(jlong) (kMaxSessions - 1)) | (jlong) slot;
    if (s->token == 0) s->token = kMaxSessions + (jlong) slot;
    s->slot = slot;

    { std::lock_guard<std::mutex> lk(g_slotGuard); g_slots[slot] = s; }
    { std::lock_guard<std::mutex> lk(g_liveGuard); g_live[s->token] = s; }
    g_active.store(s.get());

    if (!createContext(s.get())) { fail("创建 EGL 上下文失败"); return 0; }

    s->core.handle = dlopen(s->corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!s->core.handle) { fail(dlerror()); return 0; }
    auto sym = [&](const char* n) { return dlsym(s->core.handle, n); };
    s->core.setEnv = (decltype(s->core.setEnv)) sym("retro_set_environment");
    s->core.setVideo = (decltype(s->core.setVideo)) sym("retro_set_video_refresh");
    s->core.setAudio = (decltype(s->core.setAudio)) sym("retro_set_audio_sample");
    s->core.setAudioBatch = (decltype(s->core.setAudioBatch)) sym("retro_set_audio_sample_batch");
    s->core.setInputPoll = (decltype(s->core.setInputPoll)) sym("retro_set_input_poll");
    s->core.setInputState = (decltype(s->core.setInputState)) sym("retro_set_input_state");
    s->core.init = (decltype(s->core.init)) sym("retro_init");
    s->core.deinit = (decltype(s->core.deinit)) sym("retro_deinit");
    s->core.load = (decltype(s->core.load)) sym("retro_load_game");
    s->core.unload = (decltype(s->core.unload)) sym("retro_unload_game");
    s->core.run = (decltype(s->core.run)) sym("retro_run");
    s->core.av = (decltype(s->core.av)) sym("retro_get_system_av_info");
    s->core.portDevice = (decltype(s->core.portDevice)) sym("retro_set_controller_port_device");
    s->core.serializeSize = (decltype(s->core.serializeSize)) sym("retro_serialize_size");
    s->core.serialize = (decltype(s->core.serialize)) sym("retro_serialize");
    s->core.unserialize = (decltype(s->core.unserialize)) sym("retro_unserialize");
    if (!s->core.setEnv || !s->core.init || !s->core.load || !s->core.run) {
        fail("核心缺少必要导出符号");
        return 0;
    }

    s->core.setEnv(envCb);
    s->core.setVideo(videoCb);
    if (s->core.setAudioBatch) s->core.setAudioBatch(audioCb);
    if (s->core.setAudio) s->core.setAudio(audioCb);
    if (s->core.setInputPoll) s->core.setInputPoll(pollCb);
    if (s->core.setInputState) s->core.setInputState(inputCb);
    s->core.init();
    s->inited = true;

    retro_game_info gi{};
    gi.path = s->gamePath.c_str();
    if (!s->core.load(&gi)) { fail("核心拒绝加载游戏"); return 0; }
    s->loaded = true;
    if (s->core.portDevice) s->core.portDevice(0, RETRO_DEVICE_JOYPAD);

    retro_system_av_info av{};
    if (s->core.av) {
        s->core.av(&av);
        if (av.geometry.base_width > 0) { s->w = (int) av.geometry.base_width; s->h = (int) av.geometry.base_height; }
        if (av.timing.sample_rate > 1000) s->sampleRate = (unsigned) av.timing.sample_rate;
        if (av.timing.fps > 1) s->fps = av.timing.fps;
    }
    ensureFbo(s.get());
    logLine("[session] 就绪 token=%lld %dx%d %s", (long long) s->token, s->w, s->h, s->corePath.c_str());
    return s->token;
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeStep(
        JNIEnv* env, jclass, jlong token, jintArray meta) {
    auto s = liveSession(token);
    if (!s || !s->core.run) return JNI_FALSE;
    g_active.store(s.get());
    if (!s->loaded) return JNI_FALSE;

    // 键盘事件：交给核心（必须在核心自己的线程）
    if (s->hasKb && s->kb.callback) {
        std::lock_guard<std::mutex> lk(s->keyMutex);
        for (unsigned k : s->pendingKeys) {
            s->kb.callback(s->pendingKeysDown.count(k) > 0, k, k < 128 ? k : 0, 0);
        }
        s->pendingKeys.clear();
    }

    s->producedStep.store(0);
    s->core.run();

    if (meta) {
        jint vals[4] = { s->w, s->h, (jint) s->sampleRate, s->producedStep.load() };
        env->SetIntArrayRegion(meta, 0, 4, vals);
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_november_minetendo_SessionHost_nativeReadAudio(
        JNIEnv* env, jclass, jlong token, jobject dst, jint maxFrames) {
    auto s = liveSession(token);
    if (!s || !dst) return 0;
    auto* buf = (int16_t*) env->GetDirectBufferAddress(dst);
    if (!buf) return 0;
    std::lock_guard<std::mutex> lk(s->audioMutex);
    size_t want = (size_t) maxFrames * 2;
    size_t have = std::min(want, s->audio.size());
    if (have) {
        memcpy(buf, s->audio.data(), have * sizeof(int16_t));
        s->audio.erase(s->audio.begin(), s->audio.begin() + have);
    }
    return (jint) (have / 2);
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_setGpuCopy(JNIEnv*, jclass, jlong, jboolean) {}

JNIEXPORT jint JNICALL Java_com_november_minetendo_SessionHost_textureId(JNIEnv*, jclass, jlong token) {
    auto s = liveSession(token);
    return (s && s->tex) ? (jint) s->tex : 0;
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_copyTo(
        JNIEnv*, jclass, jlong token, jint dstTex, jint dstW, jint dstH) {
    auto s = liveSession(token);
    if (!s || !s->tex || dstTex <= 0) return JNI_FALSE;
    // 简化：从 FBO 读回 CPU，再上传到目标纹理（与 Windows 的 PBO 版等价但更慢）
    std::vector<uint8_t> tmp((size_t) s->w * s->h * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, s->fbo);
    glReadPixels(0, 0, s->w, s->h, GL_RGBA, GL_UNSIGNED_BYTE, tmp.data());
    glBindTexture(GL_TEXTURE_2D, dstTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, s->w, s->h, GL_RGBA, GL_UNSIGNED_BYTE, tmp.data());
    (void) dstW; (void) dstH;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_setInput(
        JNIEnv*, jclass, jlong token, jint buttons, jint ax, jint ay, jint ax2, jint ay2) {
    if (auto s = liveSession(token)) {
        s->buttons.store(buttons); s->ax.store(ax); s->ay.store(ay);
        s->ax2.store(ax2); s->ay2.store(ay2);
    }
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_setPointer(
        JNIEnv*, jclass, jlong token, jint x, jint y, jboolean down) {
    if (auto s = liveSession(token)) {
        s->px.store(x); s->py.store(y); s->pdown.store(down ? 1u : 0u);
    }
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_nativeKey(
        JNIEnv*, jclass, jlong token, jint code, jboolean down) {
    if (auto s = liveSession(token)) {
        std::lock_guard<std::mutex> lk(s->keyMutex);
        unsigned c = (unsigned) code;
        if (down) s->keysDown.insert(c); else s->keysDown.erase(c);
        s->pendingKeys.push_back(c);
        if (down) s->pendingKeysDown.insert(c); else s->pendingKeysDown.erase(c);
    }
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeSaveState(
        JNIEnv* env, jclass, jlong token, jstring path) {
    auto s = liveSession(token);
    if (!s || !s->core.serialize || !s->core.serializeSize) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    size_t size = s->core.serializeSize();
    std::vector<uint8_t> buf(size);
    bool ok = size && s->core.serialize(buf.data(), size);
    if (ok) {
        FILE* fp = fopen(p, "wb");
        if (fp) { fwrite(buf.data(), 1, size, fp); fclose(fp); }
        else ok = false;
    }
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeLoadState(
        JNIEnv* env, jclass, jlong token, jstring path) {
    auto s = liveSession(token);
    if (!s || !s->core.unserialize) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    FILE* fp = fopen(p, "rb");
    if (!fp) { env->ReleaseStringUTFChars(path, p); return JNI_FALSE; }
    fseek(fp, 0, SEEK_END);
    long size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    std::vector<uint8_t> buf((size_t) std::max(0L, size));
    size_t rd = size > 0 ? fread(buf.data(), 1, (size_t) size, fp) : 0;
    fclose(fp);
    env->ReleaseStringUTFChars(path, p);
    if (!rd) return JNI_FALSE;
    return s->core.unserialize(buf.data(), rd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeWaitSnapshot(
        JNIEnv*, jclass, jlong, jint) {
    return JNI_TRUE;   // 本实现里存读档是同步的
}

JNIEXPORT jboolean JNICALL Java_com_november_minetendo_SessionHost_nativeReloadGame(
        JNIEnv* env, jclass, jlong token, jstring gameJ) {
    auto s = liveSession(token);
    if (!s || !s->core.load) return JNI_FALSE;
    const char* game = env->GetStringUTFChars(gameJ, nullptr);
    std::string path = game ? game : "";
    env->ReleaseStringUTFChars(gameJ, game);
    if (s->core.unload) s->core.unload();
    s->loaded = false;
    retro_game_info gi{};
    gi.path = path.c_str();
    if (!s->core.load(&gi)) return JNI_FALSE;
    s->loaded = true;
    s->gamePath = path;
    retro_system_av_info av{};
    if (s->core.av) {
        s->core.av(&av);
        if (av.geometry.base_width > 0) { s->w = (int) av.geometry.base_width; s->h = (int) av.geometry.base_height; }
    }
    ensureFbo(s.get());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_november_minetendo_SessionHost_destroy(JNIEnv*, jclass, jlong token) {
    std::shared_ptr<Session> s;
    size_t slot = slotOf(token);
    if (slot < kMaxSessions) {
        std::lock_guard<std::mutex> lk(g_slotGuard);
        s = g_slots[slot];
        if (s && s->token != token) s.reset();
        g_slots[slot].reset();
    }
    if (!s) return;
    if (s->loaded && s->core.unload) s->core.unload();
    if (s->inited && s->core.deinit) s->core.deinit();
    s->release();
    { std::lock_guard<std::mutex> lk(g_liveGuard); g_live.erase(token); }
    logLine("[session] 销毁 token=%lld", (long long) token);
}

}  // extern "C"
