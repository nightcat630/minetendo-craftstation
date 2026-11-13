// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 调完模拟器之后把 GL 状态修回来。
 *
 * <p><b>为什么必须用裸 GL 调用</b>：{@link GlStateManager} 对每一项都有缓存。
 * 如果核心把 {@code glDepthMask} 改成 false，而 MC 的缓存里记的还是 true，
 * 那么调 {@code GlStateManager._depthMask(true)} 会被缓存挡掉、根本不发给驱动 ——
 * 表现就是"设置了也没用，照样能透视看洞穴"。所以这里先用 {@code GL11/GL30} 直接改真实状态，
 * 再回头把 GlStateManager 的缓存同步一致。
 *
 * <p>三个症状对应三项状态：
 * <ul>
 *   <li>方块模型整片不可见 → VAO / 着色器程序被换成核心的</li>
 *   <li><b>能透视看到地底洞穴</b> → {@code glDepthMask(false)} 或 depthFunc 被改</li>
 *   <li>颜色发灰/发亮 → colorMask 或混合状态被改</li>
 * </ul>
 */
public final class GlStateReset {

    private static int logged;

    private GlStateReset() {}

    public static void afterEmulatorCall() {
        try {
            // ── 先用裸 GL 把真实状态改回来（绕过 GlStateManager 缓存）──
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glColorMask(true, true, true, true);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            // ── 再把 GlStateManager 的缓存同步成一致（先反后正，强制它真发一次）──
            GlStateManager._depthMask(false);
            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._enableDepthTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._disableBlend();
            GlStateManager._enableCull();
            GlStateManager._glUseProgram(0);
            GlStateManager._glBindVertexArray(0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(0);

            // MC 的主渲染目标重新绑上，防止核心把 FBO 留在绑定状态
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);

            // 前两次打印真实状态，方便排查（之后不再刷屏）
            if (logged++ < 2) {
                System.out.println("[Minetendo] GL 复位: depthMask=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                        + " depthTest=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                        + " colorMask=" + GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK)
                        + " cull=" + GL11.glIsEnabled(GL11.GL_CULL_FACE));
            }
        } catch (Throwable t) {
            // 状态重置失败不该让游戏崩
            if (logged++ < 2) System.out.println("[Minetendo] GL 复位失败: " + t);
        }
    }
}
