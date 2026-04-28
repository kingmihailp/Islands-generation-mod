package com.kingmihailp.islandsmod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Procedurally generates a small copper-tuff dungeon room around the player,
 * populated with trial spawners and vaults.
 *
 * Room size: innerR 4-6 blocks, height 4-5 blocks.
 * Wall palette: tuff bricks + copper grate windows + chiseled tuff accents.
 * Ceiling: tuff bricks with copper bulb lights.
 * Spawners: 2-4 trial spawners, each with a randomly assigned mob type.
 * Vaults: 2 normal + 1 ominous, placed against interior walls.
 */
public class PocketDungeonStructure {

    // Mobs that can appear in the trial spawners
    private static final String[] MOBS = {
        "minecraft:breeze",
        "minecraft:bogged",
        "minecraft:slime",
        "minecraft:zombie",
        "minecraft:pillager"
    };

    public static void generate(ServerLevel level, Player player) {
        long seed = player.blockPosition().asLong() ^ level.getGameTime();
        Random rng = new Random(seed);

        int innerR   = 4 + rng.nextInt(3);  // 4, 5, or 6 — interior half-size
        int height   = 4 + rng.nextInt(2);  // 4 or 5 — wall height
        int wallDist = innerR + 1;           // outer wall at ±wallDist

        int cx = player.getBlockX();
        int cy = Math.max(player.getBlockY(), level.getMinBuildHeight() + 2);
        int cz = player.getBlockZ();
        Direction facing = player.getDirection();

        buildRoom(level, cx, cy, cz, innerR, wallDist, height, facing, rng);

        int spawnerCount = 2 + rng.nextInt(3);
        placeSpawners(level, cx, cy, cz, innerR, spawnerCount, rng);
        placeVaults(level, cx, cy, cz, innerR, facing, rng);
    }

    // ── Room ─────────────────────────────────────────────────────────────────

    private static void buildRoom(ServerLevel level, int cx, int cy, int cz,
                                   int innerR, int wallDist, int height,
                                   Direction facing, Random rng) {
        // Floor at cy-1: polished tuff interior, tuff bricks border
        for (int dx = -wallDist; dx <= wallDist; dx++) {
            for (int dz = -wallDist; dz <= wallDist; dz++) {
                boolean isEdge = Math.abs(dx) == wallDist || Math.abs(dz) == wallDist;
                level.setBlock(new BlockPos(cx + dx, cy - 1, cz + dz),
                        isEdge ? Blocks.TUFF_BRICKS.defaultBlockState()
                               : Blocks.POLISHED_TUFF.defaultBlockState(), 3);
            }
        }

        // Walls (outer edge) and interior (cleared to air)
        for (int dx = -wallDist; dx <= wallDist; dx++) {
            for (int dz = -wallDist; dz <= wallDist; dz++) {
                boolean onWallX = Math.abs(dx) == wallDist;
                boolean onWallZ = Math.abs(dz) == wallDist;
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (onWallX || onWallZ) {
                        level.setBlock(pos, wallBlock(dx, dz, dy, height, wallDist, rng), 3);
                    } else {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // Ceiling at cy+height: tuff bricks + copper bulb lights on a 3-block grid
        for (int dx = -wallDist; dx <= wallDist; dx++) {
            for (int dz = -wallDist; dz <= wallDist; dz++) {
                boolean isInterior = Math.abs(dx) < wallDist && Math.abs(dz) < wallDist;
                boolean isLight    = isInterior && (dx % 3 == 0) && (dz % 3 == 0);
                BlockState ceilBlock = isLight
                        ? Blocks.COPPER_BULB.defaultBlockState()
                            .setValue(BlockStateProperties.LIT, true)
                            .setValue(BlockStateProperties.POWERED, false)
                        : Blocks.TUFF_BRICKS.defaultBlockState();
                level.setBlock(new BlockPos(cx + dx, cy + height, cz + dz), ceilBlock, 3);
            }
        }

        // Entrance: 2-wide, (height-1)-tall opening in the player-facing wall
        cutEntrance(level, cx, cy, cz, wallDist, height, facing);

        // Inner corner pillars: chiseled tuff, 1 block from each wall
        int po = wallDist - 1;
        for (int sdx : new int[]{-po, po}) {
            for (int sdz : new int[]{-po, po}) {
                for (int dy = 0; dy < height; dy++) {
                    level.setBlock(new BlockPos(cx + sdx, cy + dy, cz + sdz),
                            Blocks.CHISELED_TUFF.defaultBlockState(), 3);
                }
            }
        }
    }

    private static BlockState wallBlock(int dx, int dz, int dy,
                                         int height, int wallDist, Random rng) {
        boolean isCorner = Math.abs(dx) == wallDist && Math.abs(dz) == wallDist;

        // Chiseled copper accent on outer corners at mid height
        if (isCorner && dy == height / 2) {
            return Blocks.CHISELED_COPPER.defaultBlockState();
        }

        // Copper grate windows at height row 1, every 3rd block along wall
        if (!isCorner && dy == 1 && dy < height - 1) {
            boolean onWallX = Math.abs(dx) == wallDist;
            int coord = onWallX ? dz : dx;  // coordinate running along this wall
            if ((coord % 3) == 0) {
                return rng.nextBoolean()
                        ? Blocks.COPPER_GRATE.defaultBlockState()
                        : Blocks.OXIDIZED_COPPER_GRATE.defaultBlockState();
            }
        }

        // Occasional chiseled tuff at top wall row
        if (!isCorner && dy == height - 1 && rng.nextInt(5) == 0) {
            return Blocks.CHISELED_TUFF.defaultBlockState();
        }

        return Blocks.TUFF_BRICKS.defaultBlockState();
    }

    private static void cutEntrance(ServerLevel level, int cx, int cy, int cz,
                                     int wallDist, int height, Direction facing) {
        // 2-wide opening, keeping the top wall block (height-1 rows cleared)
        boolean isNS = facing.getAxis() == Direction.Axis.Z;
        for (int off = -1; off <= 0; off++) {
            for (int dy = 0; dy < height - 1; dy++) {
                int bx = isNS ? cx + off : cx + facing.getStepX() * wallDist;
                int bz = isNS ? cz + facing.getStepZ() * wallDist : cz + off;
                level.setBlock(new BlockPos(bx, cy + dy, bz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    // ── Trial spawners ────────────────────────────────────────────────────────

    private static void placeSpawners(ServerLevel level, int cx, int cy, int cz,
                                       int innerR, int count, Random rng) {
        int sg = Math.max(innerR - 2, 2);
        List<int[]> candidates = new ArrayList<>(Arrays.asList(
                new int[]{-sg, -sg}, new int[]{ sg, -sg},
                new int[]{-sg,  sg}, new int[]{ sg,  sg},
                new int[]{  0, -sg}, new int[]{  0,  sg},
                new int[]{ -sg,  0}, new int[]{ sg,   0}
        ));
        Collections.shuffle(candidates, rng);

        List<String> mobs = new ArrayList<>(Arrays.asList(MOBS));
        Collections.shuffle(mobs, rng);

        for (int i = 0; i < Math.min(count, candidates.size()); i++) {
            int[] off = candidates.get(i);
            placeTrialSpawner(level,
                    new BlockPos(cx + off[0], cy, cz + off[1]),
                    mobs.get(i % mobs.size()));
        }
    }

    private static void placeTrialSpawner(ServerLevel level, BlockPos pos, String mobId) {
        level.setBlock(pos, Blocks.TRIAL_SPAWNER.defaultBlockState()
                .setValue(TrialSpawnerBlock.OMINOUS, false), 3);

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TrialSpawnerBlockEntity)) return;

        ListTag potentials = buildSpawnPotentials(mobId);
        CompoundTag root = new CompoundTag();
        root.put("normal_config", spawnerConfig(
                6, 2.0f, 2, 1, 40,
                potentials.copy(),
                weightedLootList("minecraft:items/trial_chambers/key")));
        root.put("ominous_config", spawnerConfig(
                9, 3.0f, 3, 2, 20,
                potentials.copy(),
                weightedLootList("minecraft:items/trial_chambers/key_ominous")));

        be.load(root);
        be.setChanged();
        level.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3);
    }

    private static ListTag buildSpawnPotentials(String mobId) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", mobId);
        CompoundTag spawnData = new CompoundTag();
        spawnData.put("entity", entity);
        CompoundTag entry = new CompoundTag();
        entry.putInt("weight", 1);
        entry.put("data", spawnData);
        ListTag list = new ListTag();
        list.add(entry);
        return list;
    }

    private static CompoundTag spawnerConfig(int totalMobs, float perPlayer,
                                              int simMobs, int simPerPlayer,
                                              int ticksBetween,
                                              ListTag potentials, ListTag loot) {
        CompoundTag cfg = new CompoundTag();
        cfg.putInt("required_player_range", 14);
        cfg.putInt("spawn_range", 4);
        cfg.putInt("total_mobs", totalMobs);
        cfg.putFloat("total_mobs_added_per_player", perPlayer);
        cfg.putInt("simultaneous_mobs", simMobs);
        cfg.putInt("simultaneous_mobs_added_per_player", simPerPlayer);
        cfg.putInt("ticks_between_spawn", ticksBetween);
        cfg.put("spawn_potentials", potentials);
        cfg.put("loot_tables_to_eject", loot);
        return cfg;
    }

    private static ListTag weightedLootList(String lootTable) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("weight", 1);
        entry.putString("data", lootTable);
        ListTag list = new ListTag();
        list.add(entry);
        return list;
    }

    // ── Vaults ───────────────────────────────────────────────────────────────

    private static void placeVaults(ServerLevel level, int cx, int cy, int cz,
                                     int innerR, Direction facing, Random rng) {
        // Pick 3 non-entrance walls, shuffle for ominous assignment
        List<Direction> walls = new ArrayList<>(Arrays.asList(
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        walls.remove(facing);
        Collections.shuffle(walls, rng);

        // First 2 → normal vault, third → ominous vault
        for (int i = 0; i < Math.min(3, walls.size()); i++) {
            Direction wall = walls.get(i);
            BlockPos pos = new BlockPos(
                    cx + wall.getStepX() * (innerR - 1),
                    cy,
                    cz + wall.getStepZ() * (innerR - 1));
            placeVault(level, pos, wall.getOpposite(), i == 2);
        }
    }

    private static void placeVault(ServerLevel level, BlockPos pos,
                                    Direction vaultFacing, boolean ominous) {
        level.setBlock(pos, Blocks.VAULT.defaultBlockState()
                .setValue(VaultBlock.FACING, vaultFacing)
                .setValue(VaultBlock.OMINOUS, ominous), 3);

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;

        String lootTable = ominous
                ? "minecraft:chests/trial_chambers/reward_ominous"
                : "minecraft:chests/trial_chambers/reward";
        String keyId = ominous
                ? "minecraft:ominous_trial_key"
                : "minecraft:trial_key";

        CompoundTag keyNbt = new CompoundTag();
        keyNbt.putString("id", keyId);
        keyNbt.putInt("count", 1);

        CompoundTag config = new CompoundTag();
        config.putString("loot_table", lootTable);
        config.putDouble("activation_range", 4.0);
        config.putDouble("deactivation_range", 4.5);
        config.put("key_item", keyNbt);

        CompoundTag root = new CompoundTag();
        root.put("config", config);

        be.load(root);
        be.setChanged();
        level.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3);
    }
}
