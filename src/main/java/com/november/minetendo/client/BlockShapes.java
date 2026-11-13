// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 碰撞/选择箱随方块朝向旋转。
 *
 * <p>MC 只会旋转**模型**（blockstate 的 y 值），{@code getShape()} 拿到的是 BlockState，
 * 需要自己按 {@code FACING} 把盒子转过去 —— 否则模型转了、箱子没转，就出现"模型和碰撞不一致"。
 *
 * <p>这里用"把四个角在水平面上绕方块中心旋转 90° 的整数倍"来实现：
 * 以朝北（{@code -Z} 为正面）为基准，
 * <pre>
 *   90°(北→东)： (x, z) → (1 - z, x)
 *   180°      ： (x, z) → (1 - x, 1 - z)
 *   270°      ： (x, z) → (z, 1 - x)
 * </pre>
 */
public final class BlockShapes {

    private BlockShapes() {}

    /**
     * @param authored 模型在 OBJ/blockstate 里"零旋转"时对应的朝向
     * @param current  方块当前的 FACING
     */
    public static VoxelShape rotate(double x0, double y0, double z0,
                                    double x1, double y1, double z1,
                                    Direction authored, Direction current) {
        int steps = ((index(current) - index(authored)) % 4 + 4) % 4;
        if (steps == 0) return Shapes.box(x0, y0, z0, x1, y1, z1);

        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (double[] c : corners) {
            double x = c[0], z = c[1];
            for (int i = 0; i < steps; i++) {
                double nx = 1.0 - z;
                double nz = x;
                x = nx;
                z = nz;
            }
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return Shapes.box(minX, y0, minZ, maxX, y1, maxZ);
    }

    private static int index(Direction d) {
        return switch (d) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
