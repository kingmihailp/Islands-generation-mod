package com.kingmihailp.islandsmod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

public class PocketAncientCityStructure {

    public static void generate(ServerLevel level, Player player) {
        long seed = player.blockPosition().asLong() ^ level.getGameTime();
        Random rng = new Random(seed);

        int radius = 9 + rng.nextInt(4);  // 9–12
        int cx = player.getBlockX();
        int cy = Math.max(player.getBlockY(), level.getMinBuildHeight() + 3);
        int cz = player.getBlockZ();

        excavateAndFill(level, cx, cy, cz, radius, rng);
        placeSculkFeatures(level, cx, cy, cz, radius, rng);
        placePillars(level, cx, cy, cz, radius, rng);
        placeChests(level, cx, cy, cz, radius, rng);
        placeSpawner(level, cx, cy, cz, rng);
    }

    // ── Terrain ───────────────────────────────────────────────────────────────

    private static void excavateAndFill(ServerLevel level, int cx, int cy, int cz,
                                         int radius, Random rng) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist  = Math.sqrt(dx * dx + dz * dz);
                double angle = Math.atan2(dz, dx);
                // Organic edge noise
                double effectiveR = radius * (0.82 + 0.18 * Math.sin(angle * 3.7 + dx * 0.4 + dz * 0.3));
                if (dist > effectiveR) continue;

                BlockState floor = (rng.nextInt(4) == 0)
                        ? Blocks.DEEPSLATE.defaultBlockState()
                        : Blocks.SCULK.defaultBlockState();
                level.setBlock(new BlockPos(cx + dx, cy - 1, cz + dz), floor, 3);

                for (int dy = 0; dy < 4; dy++) {
                    level.setBlock(new BlockPos(cx + dx, cy + dy, cz + dz),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    // ── Sculk features ────────────────────────────────────────────────────────

    private static void placeSculkFeatures(ServerLevel level, int cx, int cy, int cz,
                                            int radius, Random rng) {
        int inner = radius - 2;
        scatter(level, cx, cy, cz, inner, 3 + rng.nextInt(3), rng,
                Blocks.SCULK_SENSOR.defaultBlockState());
        scatter(level, cx, cy, cz, inner, 2 + rng.nextInt(2), rng,
                Blocks.SCULK_SHRIEKER.defaultBlockState()
                        .setValue(SculkShriekerBlock.CAN_SUMMON, true));
        scatter(level, cx, cy, cz, inner, 1 + rng.nextInt(2), rng,
                Blocks.SCULK_CATALYST.defaultBlockState());
    }

    private static void scatter(ServerLevel level, int cx, int cy, int cz,
                                 int innerR, int count, Random rng, BlockState state) {
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                int dx = rng.nextInt(innerR * 2 + 1) - innerR;
                int dz = rng.nextInt(innerR * 2 + 1) - innerR;
                if (dx * dx + dz * dz <= innerR * innerR) {
                    level.setBlock(new BlockPos(cx + dx, cy, cz + dz), state, 3);
                    break;
                }
            }
        }
    }

    // ── Reinforced deepslate pillars ──────────────────────────────────────────

    private static void placePillars(ServerLevel level, int cx, int cy, int cz,
                                      int radius, Random rng) {
        int inner = radius - 3;
        int count = 2 + rng.nextInt(3);
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                int dx = rng.nextInt(inner * 2 + 1) - inner;
                int dz = rng.nextInt(inner * 2 + 1) - inner;
                if (dx * dx + dz * dz <= inner * inner) {
                    int h = 2 + rng.nextInt(2);
                    for (int dy = 0; dy < h; dy++) {
                        level.setBlock(new BlockPos(cx + dx, cy + dy, cz + dz),
                                Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 3);
                    }
                    break;
                }
            }
        }
    }

    // ── Chests ────────────────────────────────────────────────────────────────

    private static void placeChests(ServerLevel level, int cx, int cy, int cz,
                                     int radius, Random rng) {
        ResourceKey<LootTable> loot = ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath("minecraft", "chests/ancient_city"));
        int inner = radius - 2;
        for (int i = 0; i < 2; i++) {
            for (int attempt = 0; attempt < 16; attempt++) {
                int dx = rng.nextInt(inner * 2 + 1) - inner;
                int dz = rng.nextInt(inner * 2 + 1) - inner;
                if (dx * dx + dz * dz <= inner * inner) {
                    BlockPos pos = new BlockPos(cx + dx, cy, cz + dz);
                    level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RandomizableContainerBlockEntity chest) {
                        chest.setLootTable(loot, rng.nextLong());
                    }
                    break;
                }
            }
        }
    }

    // ── Silverfish spawner ────────────────────────────────────────────────────

    private static void placeSpawner(ServerLevel level, int cx, int cy, int cz,
                                      Random rng) {
        int dx = rng.nextInt(5) - 2;
        int dz = rng.nextInt(5) - 2;
        BlockPos pos = new BlockPos(cx + dx, cy, cz + dz);
        level.setBlock(pos, Blocks.SPAWNER.defaultBlockState(), 3);

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;

        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:silverfish");
        CompoundTag spawnData = new CompoundTag();
        spawnData.put("entity", entity);

        CompoundTag tag = new CompoundTag();
        tag.put("SpawnData", spawnData);
        tag.putShort("Delay", (short) 20);
        tag.putShort("MinSpawnDelay", (short) 200);
        tag.putShort("MaxSpawnDelay", (short) 800);
        tag.putShort("SpawnCount", (short) 4);
        tag.putShort("MaxNearbyEntities", (short) 6);
        tag.putShort("RequiredPlayerRange", (short) 16);
        tag.putShort("SpawnRange", (short) 4);

        loadBlockEntity(be, tag, level.registryAccess());
        be.setChanged();
        level.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void loadBlockEntity(BlockEntity be, CompoundTag tag,
                                         HolderLookup.Provider reg) {
        java.lang.reflect.Method m = findLoadAdditional(be.getClass());
        try {
            if (m != null) {
                m.setAccessible(true);
                m.invoke(be, tag, reg);
            } else {
                be.loadWithComponents(tag, reg);
            }
        } catch (ReflectiveOperationException ignored) {
            be.loadWithComponents(tag, reg);
        }
    }

    private static java.lang.reflect.Method findLoadAdditional(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if ("loadAdditional".equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].isAssignableFrom(CompoundTag.class)) {
                    return m;
                }
            }
        }
        return null;
    }
}
