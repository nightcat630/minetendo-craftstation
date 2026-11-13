package com.november.minetendo.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 坐下玩电视（与 1.20 一致：直接把玩家挪到主机前方，面朝电视）。 */
public final class SfcSeating {

    private SfcSeating() {}

    public static BlockPos findTv(Level level, BlockPos consolePos) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = consolePos.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (st.getBlock() instanceof CrtTvBlock) return p;
                }
        return null;
    }

    public static boolean sit(ServerLevel level, BlockPos consolePos, Player player) {
        BlockPos tvPos = findTv(level, consolePos);
        if (tvPos == null) return false;
        Direction toTv = horizontalDirection(consolePos, tvPos);
        BlockPos seatPos = consolePos.relative(toTv.getOpposite());
        double x = seatPos.getX() + 0.5, y = seatPos.getY(), z = seatPos.getZ() + 0.5;
        float yaw = yawTo(toTv);
        player.teleportTo(x, y, z);
        player.setYRot(yaw);
        player.setXRot(8f);
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.connection.teleport(x, y, z, yaw, 8f);
        return true;
    }

    private static Direction horizontalDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static float yawTo(Direction dir) {
        return switch (dir) {
            case EAST -> 270f;
            case WEST -> 90f;
            case NORTH -> 180f;
            default -> 0f;
        };
    }
}