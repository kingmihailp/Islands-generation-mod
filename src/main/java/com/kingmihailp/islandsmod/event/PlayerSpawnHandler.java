package com.kingmihailp.islandsmod.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

public class PlayerSpawnHandler {

    /**
     * After the server has fully started, verify that the overworld's shared spawn
     * position sits on solid island ground.  If it's in the void, scan nearby for
     * the first island surface and relocate it there.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();

        // If the block below the spawn isn't solid, we're in the void
        if (!isSolidGround(overworld, spawn)) {
            BlockPos safe = findSafePosition(overworld, spawn.getX(), spawn.getZ(), 512);
            if (safe != null) {
                overworld.setDefaultSpawnPos(safe, 0.0f);
            }
        }
    }

    /**
     * When a player logs in for the first time (no bed/anchor), ensure they are
     * placed on island ground and not falling through the void.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;

        ServerLevel level = player.serverLevel();

        // Only adjust if the player has no custom respawn point and is below Y 0
        if (player.getRespawnPosition() == null && player.getY() < 0) {
            BlockPos safePos = findSafePosition(level, player.getBlockX(), player.getBlockZ(), 256);
            if (safePos != null) {
                player.teleportTo(safePos.getX() + 0.5, safePos.getY() + 1.0, safePos.getZ() + 0.5);
            }
        }
    }

    /**
     * Teleport a player who respawned without a bed/anchor and ended up in the void.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;
        if (event.isEndConquered()) return; // normal post-dragon respawn, leave alone

        // Schedule the check for the next tick so the player's position is finalised
        player.getServer().execute(() -> {
            if (player.getY() < 0 && player.getRespawnPosition() == null) {
                ServerLevel level = player.serverLevel();
                BlockPos worldSpawn = level.getSharedSpawnPos();
                BlockPos safe = findSafePosition(level, worldSpawn.getX(), worldSpawn.getZ(), 256);
                if (safe != null) {
                    player.teleportTo(safe.getX() + 0.5, safe.getY() + 1.0, safe.getZ() + 0.5);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isSolidGround(ServerLevel level, BlockPos pos) {
        // The block directly below spawn must be solid
        BlockPos below = pos.below();
        return !level.getBlockState(below).isAir()
                && !level.getBlockState(below).is(Blocks.WATER);
    }

    /**
     * Spiral outward from (originX, originZ) looking for the highest solid surface
     * with breathable air above it.  Scans from Y = 250 downward per column.
     *
     * @param radius Maximum search radius in blocks
     * @return The surface BlockPos, or null if none found within radius
     */
    private static BlockPos findSafePosition(ServerLevel level, int originX, int originZ, int radius) {
        for (int r = 0; r <= radius; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // only shell of square
                    BlockPos surface = highestSurface(level, originX + dx, originZ + dz);
                    if (surface != null) return surface;
                }
            }
        }
        return null;
    }

    private static BlockPos highestSurface(ServerLevel level, int x, int z) {
        for (int y = 250; y > 0; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && level.getBlockState(pos.above(2)).isAir()) {
                return pos;
            }
        }
        return null;
    }
}
