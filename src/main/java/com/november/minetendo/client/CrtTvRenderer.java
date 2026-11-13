package com.november.minetendo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把模拟器画面贴到 CRT 屏幕上 —— 与 1.20 的 {@code CrtTvRenderer} 相同常量与画法。
 * 电视本体由 neoforge:obj loader 渲染（crt_tv.obj），这里只画屏幕四边形。
 */
public class CrtTvRenderer implements BlockEntityRenderer<CrtTvBlockEntity> {

    private static final float HALF_W = 0.6875f;   // 22/16/2
    private static final float HALF_H = 0.5156f;   // 16.5/16/2
    private static final float CENTER_Y = 1.05f;
    private static final float CENTER_X = 0.2f;
    private static final float FACE_Z = -0.05f;

    public CrtTvRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CrtTvBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        ResourceLocation tex = InWorldSession.textureId();
        if (tex == null) return;

        BlockState state = be.getBlockState();
        Direction facing = CrtTvBlock.facing(state);

        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        switch (facing) {
            case SOUTH -> { }
            case NORTH -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180f));
            case EAST -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(270f));
            case WEST -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90f));
            default -> { }
        }
        pose.translate(-0.5, 0.0, -0.5);
        pose.translate(CENTER_X, CENTER_Y, FACE_Z);

        PoseStack.Pose last = pose.last();
        // entitySolid：标准方块实体材质，采样不做任何文字偏移（text 类型会导致画面漂移）
        VertexConsumer vc = buffer.getBuffer(RenderType.entitySolid(tex));

        float x0 = -HALF_W, x1 = HALF_W;
        float y0 = -HALF_H, y1 = HALF_H;

        final int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        vertex(vc, last, x0, y0, 1f, 1f, light);
        vertex(vc, last, x1, y0, 0f, 1f, light);
        vertex(vc, last, x1, y1, 0f, 0f, light);
        vertex(vc, last, x0, y1, 1f, 0f, light);
        vertex(vc, last, x0, y1, 1f, 0f, light);
        vertex(vc, last, x1, y1, 0f, 0f, light);
        vertex(vc, last, x1, y0, 0f, 1f, light);
        vertex(vc, last, x0, y0, 1f, 1f, light);

        // entitySolid 有背面剔除，所以正反各画一遍，保证任意视角都能看到

        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose last,
                               float x, float y, float u, float v, int light) {
        vc.addVertex(last, x, y, 0f).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(last, 0f, 0f, 1f);
    }

    @Override
    public boolean shouldRenderOffScreen(CrtTvBlockEntity be) {
        return false;
    }
}
