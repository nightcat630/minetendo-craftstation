// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
// 注册在 Minetendo 构造函数里（注解扫描在开发环境下没生效，改手动挂事件总线）
import net.neoforged.fml.common.EventBusSubscriber;
import com.november.minetendo.Minetendo;
import net.neoforged.api.distmarker.Dist;

/**
 * 电视投屏渲染 —— 照搬参考项目的方式：
 * <ul>
 *   <li>在 {@link RenderLevelStageEvent} 的 {@code AFTER_BLOCK_ENTITIES} 阶段绘制；</li>
 *   <li>用 {@link TvScreenRenderType}（NEW_ENTITY / 不透明 / 不剔除 / 写深度）；</li>
 *   <li>顶点全亮（FULL_BRIGHT）+ NO_OVERLAY，UV 为 (0,1)(1,1)(1,0)(0,0)；</li>
 *   <li>尺寸与位置仍用 1.20 的常量（HALF_W / HALF_H / CENTER_Y / CENTER_X / FACE_Z）。</li>
 * </ul>
 */
public final class TvScreenLayer {

    // 由 crt_tv.obj 实测：正面玻璃面 Z=1.00，范围 X 0.18~1.82、Y 0.07~1.98（机身 2×2）
    // 屏幕实测（源模型 22.0×16.5 单位 / 16 = 1.375×1.03125 方块，UV 反查确认）
    private static final float HALF_W = 0.6875f; // 1.375 / 2
    private static final float HALF_H = 0.5156f; // 1.03125 / 2
    private static final float CENTER_Y = 1.0750f; // 屏幕竖直中心（17.2 / 16）
    private static final float CENTER_X = 0.8125f; // 屏幕水平中心（-3.0 / 16 + 1），机身偏左
    private static final float FACE_Z = 1.0520f;  // 玻璃前面 1.0481 之外，避免 z-fighting

    private static long lastLog;
    private static long lastLog2;

    private static long lastAspectLog;

    private TvScreenLayer() {}

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        {
            long now = System.currentTimeMillis();
            if (now - lastLog2 > 1000) {
                lastLog2 = now;
                System.out.println("[Minetendo] 投屏层被调用 stage=" + event.getStage()
                        + " tex=" + InWorldSession.textureId()
                        + " active=" + InWorldSession.activeTv());
            }
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // 本地会话纹理（宿主自己）与远端流纹理（观战者）二选一
        ResourceLocation localTex = InWorldSession.textureId();

        Vec3 cam = event.getCamera().getPosition();
        // 投屏日志：每秒一次，看清"有没有激活的电视 / 画了几台"
        {
            long now = System.currentTimeMillis();
            if (now - lastLog > 1000) {
                lastLog = now;
                System.out.println("[Minetendo] 投屏: 激活电视=" + InWorldSession.activeTv()
                        + " 已登记=" + java.util.stream.StreamSupport.stream(InWorldSession.visibleTvs().spliterator(), false).count());
            }
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (BlockPos pos : InWorldSession.visibleTvs()) {
            if (!mc.level.isLoaded(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof CrtTvBlock)) continue;
            boolean isLocal = pos.equals(InWorldSession.activeTv()) && localTex != null;
            boolean isRemote = TvStreamClient.has(pos);
            if (!isLocal && !isRemote) continue;   // 本地在播，或者收到了别人的流
            ResourceLocation tex = isLocal ? localTex : TvStreamClient.texture(pos);
            if (tex == null) continue;
            if (cam.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64) continue;

            Direction facing = CrtTvBlock.facing(state);
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
            pose.translate(0.5, 0.0, 0.5);
            switch (facing) {
                case SOUTH -> { }
                case NORTH -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180f));
                case EAST -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90f));
                case WEST -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(270f));
                default -> { }
            }
            pose.translate(-0.5, 0.0, -0.5);
            pose.translate(CENTER_X, CENTER_Y, FACE_Z);

            PoseStack.Pose last = pose.last();
            VertexConsumer vc = buffers.getBuffer(TvScreenRenderType.of(tex));

            // 等比内嵌：核心画面比例（GBA 3:2、SFC 8:7、NDS/3DS 不同）与屏幕 4:3 不一致时
            // 按 contain 方式缩放，避免拉伸。
            // 用机种固定分辨率算比例（运行时尺寸在启动瞬间可能还没就绪）
            Core core = InWorldSession.core();
            int fw = core != null ? core.baseWidth() : InWorldSession.frameWidth();
            int fh = core != null ? core.baseHeight() : InWorldSession.frameHeight();
            float hw = HALF_W, hh = HALF_H;
            if (fw > 0 && fh > 0) {
                double quadAspect = (2.0 * HALF_W) / (2.0 * HALF_H);
                double texAspect = (double) fw / fh;
                if (texAspect >= quadAspect) {
                    hw = HALF_W;
                    hh = (float) (HALF_W / texAspect);
                } else {
                    hh = HALF_H;
                    hw = (float) (HALF_H * texAspect);
                }
            }
            {
                if (System.currentTimeMillis() - lastAspectLog > 2000) {
                    lastAspectLog = System.currentTimeMillis();
                    System.out.println("[Minetendo] 画面比例 core=" + core + " fw=" + fw + " fh=" + fh
                            + " hw=" + String.format("%.4f", hw) + " hh=" + String.format("%.4f", hh));
                }
            }
            float x0 = -hw, x1 = hw;
            float y0 = -hh, y1 = hh;
            int light = LightTexture.FULL_BRIGHT;
            int overlay = OverlayTexture.NO_OVERLAY;

            add(vc, last, x0, y0, 0f, 0f, 1f, light, overlay, facing);
            add(vc, last, x1, y0, 0f, 1f, 1f, light, overlay, facing);
            add(vc, last, x1, y1, 0f, 1f, 0f, light, overlay, facing);
            add(vc, last, x0, y1, 0f, 0f, 0f, light, overlay, facing);

            pose.popPose();
        }
        buffers.endBatch();
    }

    private static void add(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                            float u, float v, int light, int overlay, Direction facing) {
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, 0f, 0f, 1f);
    }
}
