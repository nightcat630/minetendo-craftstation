// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.client.model.HumanoidModel;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/**
 * 枚举扩展参数（供 {@code META-INF/enumextensions.json} 引用）。
 *
 * <p>为什么单独一个类：文档明确要求"提供参数的类要分开，避免过早加载模组类"。
 *
 * <p>构造器签名（{@code HumanoidModel.ArmPose}）：
 * {@code (Z, Lnet/neoforged/neoforge/client/IArmPoseTransformer;)V}
 * —— 第一个参数是 twoHanded，第二个是实际摆手臂的 transformer。
 *
 * <p>姿势目标：**双手捧在手柄两侧**，两臂前抬、肘部外张，两手之间留出约半格的宽度。
 */
public final class GamepadPoseParams {

    private GamepadPoseParams() {}

    /** 两臂前抬角度（负数 = 向前抬） */
    private static final float ARM_FORWARD = -0.75f;   // 前抬约 43°
    /** 两臂外张角度：越大两手间距越宽 */
    /** 两臂左右开合：0 = 平行前伸。**正值是内合（拥抱状）**，要张开得用负值 */
    private static final float ARM_SPREAD = -0.30f;

    public static final IArmPoseTransformer TRANSFORMER = (HumanoidModel<?> model,
                                                           net.minecraft.world.entity.LivingEntity entity,
                                                           net.minecraft.world.entity.HumanoidArm arm) -> {
        model.rightArm.xRot = ARM_FORWARD;
        model.rightArm.yRot = ARM_SPREAD;
        model.rightArm.zRot = 0.0f;
        model.leftArm.xRot = ARM_FORWARD;
        model.leftArm.yRot = -ARM_SPREAD;
        model.leftArm.zRot = 0.0f;
    };

    /** 给枚举扩展用的参数工厂：idx 0 = twoHanded，idx 1 = transformer。 */
    public static Object param(int index, Class<?> type) {
        return switch (index) {
            case 0 -> Boolean.TRUE;   // 基本类型 boolean 不能走 Class.cast，直接给装箱值
            case 1 -> type.cast(TRANSFORMER);
            default -> throw new IllegalArgumentException("unexpected parameter index: " + index);
        };
    }
}
