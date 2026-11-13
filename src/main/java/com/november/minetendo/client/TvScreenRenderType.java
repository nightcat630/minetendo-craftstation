// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * 电视屏幕专用渲染材质。
 *
 * <p><b>为什么要单独做</b>：之前用 {@code RenderType.text(...)} 画电视画面，
 * 那是**字体**渲染类型 —— 它的着色器带文字偏移（用于字形排布），
 * 拿它贴整块画面会导致画面纵向漂移（"不断上移再回弹"）。
 * 实测核心输出 7 帧完全相同、位移 0 行，证明抖动来自这个材质，而不是模拟器。
 *
 * <p>这里改用实体无剔除材质：{@code NEW_ENTITY} 顶点、不透明、不剔除、写深度，
 * 全亮顶点（FULL_BRIGHT），与参考项目一致。
 */
public final class TvScreenRenderType extends RenderType {

    private static final Function<ResourceLocation, RenderType> TYPES = net.minecraft.Util.memoize(texture ->
            create("minetendo_tv_screen", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536,
                    false, false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityCutoutNoCullShader))
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_DEPTH_WRITE)
                            .createCompositeState(false)));

    private TvScreenRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                               boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sorted, setup, clear);
    }

    public static RenderType of(ResourceLocation texture) {
        return TYPES.apply(texture);
    }
}
