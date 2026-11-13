// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.SessionHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 世界内会话（第一版）：核心在后台跑，画面贴到 CRT 电视上。
 * 对应 1.20 的 {@code SfcSession}：界面关掉后会话仍在运行。
 */
public final class InWorldSession {

    private static final Logger LOG = LoggerFactory.getLogger("Minetendo/TV");
    private static final ResourceLocation TEX_ID =
            ResourceLocation.fromNamespaceAndPath("minetendo", "in_world_screen");

    private static SessionHost session;
    private static DynamicTexture texture;
    private static Core core;
    private static String error = "";
    /** 已登记的电视位置（由 CrtTvBlockEntity 登记/注销） */
    private static final java.util.Set<net.minecraft.core.BlockPos> TVS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static Iterable<net.minecraft.core.BlockPos> visibleTvs() { return TVS; }
    public static void registerTv(net.minecraft.core.BlockPos pos) { TVS.add(pos); }
    public static void unregisterTv(net.minecraft.core.BlockPos pos) { TVS.remove(pos); }

    /** 当前被激活的电视（只有它显示画面） */
    private static net.minecraft.core.BlockPos activeTv;

    public static net.minecraft.core.BlockPos activeTv() { return activeTv; }

    /** 直接把某台电视设为激活目标（右键电视时用）。 */
    public static void activate(net.minecraft.core.BlockPos pos) { activeTv = pos; }

    /**
     * 从 SFC 主机出发选一台电视并激活。
     *
     * <p>规则（按需求）：
     * <ol>
     *   <li>只考虑**相邻**范围（4 格内）的电视；</li>
     *   <li>只激活**最近**的一台；</li>
     *   <li>距离相同 → 优先主机**正前方**，其次**左方**。</li>
     * </ol>
     */
    public static net.minecraft.core.BlockPos activateFromConsole(net.minecraft.core.BlockPos consolePos,
                                                                  net.minecraft.core.Direction facing) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        activeTv = null;
        if (level == null) return null;

        net.minecraft.core.BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    var p = consolePos.offset(dx, dy, dz);
                    if (!(level.getBlockState(p).getBlock() instanceof CrtTvBlock)) continue;

                    double dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    // 优先级：正前方 0，左方 1，其余 2（作为同距离时的次序）
                    int side = 2;
                    var toTv = new net.minecraft.core.BlockPos(dx, 0, dz);
                    if (dx * facing.getStepX() + dz * facing.getStepZ() > 0) side = 0;      // 前方
                    else if (dx * facing.getCounterClockWise().getStepX()
                            + dz * facing.getCounterClockWise().getStepZ() > 0) side = 1;    // 左方

                    double score = dist * 10 + side;
                    if (score < bestScore) { bestScore = score; best = p; }
                }
            }
        }
        activeTv = best;
        return best;
    }

    /** 音量随距离衰减的半径（格）：超过这个距离基本听不见 */
    private static final double ATTENUATION_RANGE = 24.0;
    /** 玩家自己调的音量（+/- 键） */
    private static float userVolume = 1.0f;
    /** 当前生效的距离开关系数（日志/调试用） */
    private static float distanceFactor = 1.0f;

    public static float userVolume() { return userVolume; }

    /** 当前画面尺寸（用于电视上按比例内嵌，不再拉伸）。 */
    public static int frameWidth() { return session != null ? session.width() : 0; }
    public static int frameHeight() { return session != null ? session.height() : 0; }

    /**
     * 把声源放到电视方块上：
     * <ul>
     *   <li>距离越远越轻（到 {@link #ATTENUATION_RANGE} 格时为 0）；</li>
     *   <li>按电视在玩家左右侧做声道偏移（左侧偏左声道，右侧偏右声道）。</li>
     * </ul>
     */
    private static void updateSpatialAudio() {
        if (audioInstance == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || activeTv == null) {
            distanceFactor = 1.0f;
            audioInstance.setVolume(userVolume);
            audioInstance.setPan(0f);
            return;
        }
        var src = net.minecraft.world.phys.Vec3.atCenterOf(activeTv);
        double dist = mc.player.position().distanceTo(src);
        double f = 1.0 - dist / ATTENUATION_RANGE;
        distanceFactor = (float) Math.max(0.0, Math.min(1.0, f));
        // 距离衰减用平方，近处更饱满、远处衰减更快
        audioInstance.setVolume(userVolume * distanceFactor * distanceFactor);

        // 方位：把"电视方向"投到玩家左右轴上
        var look = mc.player.getLookAngle();
        var right = new net.minecraft.world.phys.Vec3(-look.z, 0, look.x).normalize();
        var toSrc = src.subtract(mc.player.position());
        double side = right.dot(new net.minecraft.world.phys.Vec3(toSrc.x, 0, toSrc.z));
        double horiz = Math.max(0.001, Math.sqrt(toSrc.x * toSrc.x + toSrc.z * toSrc.z));
        audioInstance.setPan((float) Math.max(-1.0, Math.min(1.0, side / (horiz * 0.8))));
    }

    private static int lastSeq;
    private static EmuAudio audioInstance;

    private InWorldSession() {}

    public static boolean running() { return session != null && session.ready() && !session.failed(); }
    public static ResourceLocation textureId() { return texture != null ? TEX_ID : null; }
    public static String error() { return error; }
    public static Core core() { return core; }

    public static boolean start(Core targetCore, Path rom) {
        // stop() 会清掉 activeTv；先记住再恢复，否则选完游戏没有任何电视被激活（只有声音）
        net.minecraft.core.BlockPos keepTv = activeTv;
        stop();
        activeTv = keepTv;
        if (!SessionHost.load()) { error = "原生库加载失败"; return false; }
        Path base = FMLPaths.GAMEDIR.get().resolve("minetendo").resolve(targetCore.dir);
        Path corePath = base.resolve("core").resolve(targetCore.coreDll);
        Path sys = base.resolve("sysdata");
        if (!Files.isRegularFile(corePath)) { error = "缺少核心：" + corePath; return false; }
        if (rom == null || !Files.isRegularFile(rom)) { error = "游戏文件不存在：" + rom; return false; }

        SessionHost.captureContext();
        session = SessionHost.open(corePath, rom, sys);
        if (session == null) { error = "会话启动失败：" + SessionHost.lastError(); return false; }
        core = targetCore;
        error = "";

        texture = new DynamicTexture(256, 224, false);
        Minecraft.getInstance().getTextureManager().register(TEX_ID, texture);
        LOG.info("[Minetendo] 世界内会话启动：{} {}", targetCore.label, rom.getFileName());
        // 开局提示：世界内没有界面可画按键表，用动作栏提示一次（不同机种按键名不同）
        var mc0 = Minecraft.getInstance();
        if (mc0.player != null) {
            String hint = switch (targetCore) {
                case PS2 -> "PS2：K=× 确认  J=○ 取消  I=□  L=△  Enter=START  Tab=SELECT  退格=停止";
                case SFC, GBA -> "K=A  J=B  I=X  L=Y  Enter=START  Tab=SELECT  退格=停止";
                default -> "方向键=十字键  Enter=START  Tab=SELECT  退格=停止";
            };
            mc0.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(hint)
                            .withStyle(net.minecraft.ChatFormatting.AQUA), true);
        }
        return true;
    }

    public static void tick() {
        if (session == null) return;
        if (session.failed()) { LOG.warn("[Minetendo] 会话异常：{}", session.failure()); stop(); return; }
        if (!session.ready()) return;

        // 与 1.20 的 SfcSession 一致：纹理固定 256×224 建一次，之后**不重建**。
        // 核心在游戏内会切到 239 行，按纹理尺寸裁切上传即可；重建/越界写会让画面整体跳动。
        // 纹理尺寸必须跟随核心输出：GBA 240x160 / SFC 256x224。
        // 若固定成 256x224，240 宽的数据按 240 行距写进 256 宽纹理 → 每行错位（横向剪切）。
        int fw = session.width(), fh = session.height();
        var cur = texture == null ? null : texture.getPixels();
        if (fw > 0 && fh > 0 && (cur == null || cur.getWidth() != fw || cur.getHeight() != fh)) {
            Minecraft.getInstance().getTextureManager().release(TEX_ID);
            texture = new DynamicTexture(fw, fh, false);
            Minecraft.getInstance().getTextureManager().register(TEX_ID, texture);
        }

        int seq = session.frameSeq();
        if (seq != lastSeq) {
            lastSeq = seq;
            session.uploadToSized(texture.getId(), fw, fh);   // 直传纹理（NativeImage 路径对 GBA 会出现横向剪切）
        }

        ByteBuffer ab = session.audioBuffer();
        ab.clear();
        int af = session.readAudio(ab, 4096);
        if (af > 0) {
            if (audioInstance == null) {
                audioInstance = new EmuAudio();
                audioInstance.start(session.sampleRate());
            }
            updateSpatialAudio();
            audioInstance.pump(ab.asShortBuffer(), af * 2);
        }

        pollKeyboard();
    }

    /** NativeImage 的像素指针（1.21 里是私有字段；参考项目靠 AT 直接访问，这里用反射拿一次）。 */
    private static long pixelsAddress(net.minecraft.client.renderer.texture.DynamicTexture tex) {
        var img = tex.getPixels();
        try {
            var f = com.mojang.blaze3d.platform.NativeImage.class.getDeclaredField("pixels");
            f.setAccessible(true);
            return (long) f.get(img);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 照参考项目：写 NativeImage.pixels 再 texture.upload()。 */
    private static void uploadViaNativeImage() {
        if (texture == null || session == null) return;
        var img = texture.getPixels();
        if (img == null) return;
        int w = Math.min(session.width(), img.getWidth());
        int h = Math.min(session.height(), img.getHeight());
        if (w <= 0 || h <= 0) return;
        long addr = pixelsAddress(texture);
        if (addr == 0L) return;
        var dst = org.lwjgl.system.MemoryUtil.memByteBuffer(addr, img.getWidth() * img.getHeight() * 4);
        session.copyFrameTo(dst, img.getWidth(), img.getHeight());
        texture.upload();
    }

    /**
     * 世界内键盘操控（不看 GUI 是否有焦点，直接读 GLFW 键状态）。
     *
     * <p>映射：方向键/WASD=十字键，K=A、J=B、I=X、L=Y，Enter=START，Tab=SELECT，Q/E=L/R。
     * <b>退格键 = 停止游戏</b>（关掉会话、画面变黑）。
     */
    private static void pollKeyboard() {
        if (session == null) return;
        var mc = Minecraft.getInstance();
        long win = mc.getWindow().getWindow();
        if (org.lwjgl.glfw.GLFW.glfwGetKey(win, org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            LOG.info("[Minetendo] 退格键：停止世界内会话");
            stop();
            return;
        }
        int b = 0;
        int x = 0, y = 0;
        // 注意：世界内**不绑 WASD** —— 那是玩家走路键，会和主机输入冲突。方向只用箭头。
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_UP)) { b |= 1 << 4; y -= 32767; }
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN)) { b |= 1 << 5; y += 32767; }
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT)) { b |= 1 << 6; x -= 32767; }
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)) { b |= 1 << 7; x += 32767; }
        // PSP：△=G □=H ×=T ○=Y，右摇杆 J/K/L/I；其它机种 K=A J=B I=X L=Y（PS2 为 ○ 确认）
        if (core == Core.PSP) {
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_G)) b |= 1 << 9;   // △
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_H)) b |= 1 << 1;   // □
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_T)) b |= 1 << 0;   // ×
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_Y)) b |= 1 << 8;   // ○
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) || down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) b |= 1 << 3;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) || down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT)) b |= 1 << 2;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_Q)) b |= 1 << 10;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_E)) b |= 1 << 11;
            int rx = 0, ry = 0;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_J)) rx -= 32767;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_L)) rx += 32767;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_I)) ry -= 32767;
            if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_K)) ry += 32767;
            session.setInput(b, x, y, rx, ry);
            return;
        }
        //   任系（SFC/GBA/NDS/3DS）：K=A J=B I=X L=Y
        //   PlayStation（PS2）：K=× J=○ I=□ L=△ —— libretro 里 × 走 B 位、○ 走 A 位
        boolean playstation = core == Core.PS2;
        int confirmBit = 8;   // 两家"确认"都在 A 位：任系 A、PS2 的 ○（日版 BIOS 惯例）
        int cancelBit  = 0;   // 两家"取消"都在 B 位：任系 B、PS2 的 ×（美版 BIOS 的确认）
        int bitX = 9;   // 两家都是"上"键
        int bitY = 1;   // 两家都是"左"键
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_K)) b |= 1 << confirmBit;   // K = 确认（PS2 为 ×）
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_J)) b |= 1 << cancelBit;    // J = 取消（○ / B）
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_I)) b |= 1 << bitX;
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_L)) b |= 1 << bitY;
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) || down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) b |= 1 << 3;
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) || down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT)) b |= 1 << 2;
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_Q)) b |= 1 << 10;
        if (down(win, org.lwjgl.glfw.GLFW.GLFW_KEY_E)) b |= 1 << 11;
        session.setInput(b, x, y);
    }

    private static boolean down(long win, int key) {
        return org.lwjgl.glfw.GLFW.glfwGetKey(win, key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    public static void stop() {
        if (session != null) { session.close(); session = null; }
        if (audioInstance != null) { audioInstance.stop(); audioInstance = null; }
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEX_ID);
            texture = null;
        }
        lastSeq = 0;
        activeTv = null;
    }
}

