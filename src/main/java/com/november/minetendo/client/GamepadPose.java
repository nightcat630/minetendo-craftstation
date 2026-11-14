// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.Minetendo;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * 手持手柄时的第三人称姿势：双手举在身前，像拿地图/盾牌一样。
 *
 * <p>1.21.1 的 {@code HumanoidModel.ArmPose} 是枚举，NeoForge 改成"编译期枚举扩展"
 * （{@code IExtensibleEnum} + enumextensions.json），运行时没有 {@code create()}，
 * 所以这里直接复用原版已有的双手姿势枚举值，通过
 * {@link IClientItemExtensions#getArmPose} 只对手柄生效。
 *
 * <p>可选值（都只影响第三人称外观）：
 * <ul>
 *   <li>{@code CROSSBOW_CHARGE} —— 双臂同时抬到身前、双手收拢（当前使用）：<b>唯一</b>既有双手姿势、又会让两条手臂都摆好的低风险值</li>
 *   <li>{@code BOW_AND_ARROW} —— 一手前伸一手后拉，不对称</li>
 *   <li>{@code TOOT_HORN} —— 双手举到脸前（像吹号角），位置更高</li>
 *   <li>{@code BOW_AND_ARROW} —— 一手前伸一手后拉，不适合</li>
 * </ul>
 */
@EventBusSubscriber(modid = Minetendo.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GamepadPose {

    /** 双手捧在胸前 */
    /** 由 META-INF/enumextensions.json 注入的自定义枚举值（双手捧手柄） */
    public static final HumanoidModel.ArmPose PAD_POSE = HumanoidModel.ArmPose.valueOf("minetendo_gamepad");

    private GamepadPose() {}

    @SubscribeEvent
    public static void onRegisterExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return PAD_POSE;
            }
        }, CrtTvRegistry.GAMEPAD.get());
    }
}