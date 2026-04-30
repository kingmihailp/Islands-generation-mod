package com.kingmihailp.islandsmod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

public class PocketEndFortressStructure {

    // Room outer half-size (walls at ±HALF from centre)
    private static final int HALF = 5;

    public static void generate(ServerLevel level, BlockPos origin) {
        int cx = origin.getX();
        int cy = origin.getY();
        int cz = origin.getZ();

        buildRoom(level, cx, cy, cz);
        buildPortalPlatform(level, cx, cy, cz);
        placePortalFrames(level, cx, cy, cz);
    }

    // ── Stone brick room ──────────────────────────────────────────────────────

    private static void buildRoom(ServerLevel level, int cx, int cy, int cz) {
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -2; dy <= 6; dy++) {
                    boolean wall = Math.abs(dx) == HALF || Math.abs(dz) == HALF
                                || dy == -2 || dy == 6;
                    BlockState bs = wall ? stoneBrick(dx, dy, dz) : Blocks.AIR.defaultBlockState();
                    level.setBlock(new BlockPos(cx + dx, cy + dy, cz + dz), bs, 3);
                }
            }
        }
    }

    private static BlockState stoneBrick(int dx, int dy, int dz) {
        boolean isCorner = Math.abs(dx) == HALF && Math.abs(dz) == HALF;
        if (isCorner) return Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        if (dy == 5 && (dx % 3 == 0) && (dz % 3 == 0)
                && Math.abs(dx) < HALF && Math.abs(dz) < HALF)
            return Blocks.SEA_LANTERN.defaultBlockState();
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    // ── Lava pit + stone-brick platform ──────────────────────────────────────

    private static void buildPortalPlatform(ServerLevel level, int cx, int cy, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean isLava = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                BlockState bs = isLava
                        ? Blocks.LAVA.defaultBlockState()
                        : Blocks.STONE_BRICKS.defaultBlockState();
                // Platform layer just below frame level
                level.setBlock(new BlockPos(cx + dx, cy - 1, cz + dz), bs, 3);
            }
        }
    }

    // ── 12-frame End Portal ring (no Eye of Ender) ────────────────────────────
    //
    // Frame FACING points inward (toward the portal centre) so that a player
    // standing outside the ring faces the correct direction to insert an eye.
    //   North wall (z-2) → faces SOUTH   West wall (x-2) → faces EAST
    //   South wall (z+2) → faces NORTH   East wall (x+2) → faces WEST

    private static void placePortalFrames(ServerLevel level, int cx, int cy, int cz) {
        BlockState noEye = Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(EndPortalFrameBlock.HAS_EYE, false);

        // North row
        for (int dx = -1; dx <= 1; dx++)
            level.setBlock(new BlockPos(cx + dx, cy, cz - 2),
                    noEye.setValue(EndPortalFrameBlock.FACING, Direction.SOUTH), 3);
        // South row
        for (int dx = -1; dx <= 1; dx++)
            level.setBlock(new BlockPos(cx + dx, cy, cz + 2),
                    noEye.setValue(EndPortalFrameBlock.FACING, Direction.NORTH), 3);
        // West column
        for (int dz = -1; dz <= 1; dz++)
            level.setBlock(new BlockPos(cx - 2, cy, cz + dz),
                    noEye.setValue(EndPortalFrameBlock.FACING, Direction.EAST), 3);
        // East column
        for (int dz = -1; dz <= 1; dz++)
            level.setBlock(new BlockPos(cx + 2, cy, cz + dz),
                    noEye.setValue(EndPortalFrameBlock.FACING, Direction.WEST), 3);
    }
}
