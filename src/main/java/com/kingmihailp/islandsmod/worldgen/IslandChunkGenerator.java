package com.kingmihailp.islandsmod.worldgen;

import com.kingmihailp.islandsmod.init.ModWorldgen;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class IslandChunkGenerator extends NoiseBasedChunkGenerator {

    public static final MapCodec<IslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.generatorSettings())
            ).apply(instance, IslandChunkGenerator::new)
    );

    // ── Grid / placement ──────────────────────────────────────────────────────
    private static final int GRID_SIZE          = 320;   // blocks between grid cell origins
    private static final float ISLAND_CHANCE     = 0.72f; // probability that a cell has a primary island
    private static final float SATELLITE_CHANCE  = 0.40f; // chance primary spawns one satellite
    private static final float TRIPLE_CHANCE     = 0.22f; // chance satellite spawns another satellite

    // ── Island dimensions ─────────────────────────────────────────────────────
    private static final double MIN_RADIUS_H = 24.0;
    private static final double MAX_RADIUS_H = 120.0;

    // ── Vertical placement ────────────────────────────────────────────────────
    private static final int MIN_ISLAND_Y = 75;
    private static final int MAX_ISLAND_Y = 185;

    // ── Noise resource keys ───────────────────────────────────────────────────
    private static final ResourceLocation RL_ISLANDS = ResourceLocation.fromNamespaceAndPath("islandsmod", "islands");
    private static final ResourceLocation RL_TERRAIN  = ResourceLocation.fromNamespaceAndPath("islandsmod", "terrain_noise");

    // ── Surface directions ────────────────────────────────────────────────────
    private static final int[][] DIRS4 = {{1,0},{-1,0},{0,1},{0,-1}};

    // ─────────────────────────────────────────────────────────────────────────

    public IslandChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return ModWorldgen.ISLAND_GENERATOR.get();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TERRAIN GENERATION
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Executor executor, Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {

        return CompletableFuture.supplyAsync(() -> {
            generateIslandTerrain(chunk, randomState);
            return chunk;
        }, executor);
    }

    private void generateIslandTerrain(ChunkAccess chunk, RandomState randomState) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        long noiseSeed = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(0, 0, 0).nextLong();

        List<IslandData> islands = gatherNearbyIslands(startX + 8, startZ + 8, islandRand);
        if (islands.isEmpty()) return;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                fillColumn(chunk, startX + lx, startZ + lz, islands, noiseSeed);
            }
        }
    }

    private void fillColumn(ChunkAccess chunk, int wx, int wz,
                             List<IslandData> islands, long noiseSeed) {
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();

        for (IslandData isl : islands) {
            double dx = wx - isl.cx;
            double dz = wz - isl.cz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double normR = dist / isl.rh;

            if (normR > 1.40) continue;

            // Ellipsoid base density, falls off with radius
            double baseShape = Math.max(0.0, 1.0 - Math.pow(normR, 1.5));
            if (baseShape < 0.02) continue;

            // Horizontal noise warps the island outline
            double shapeNoise = fractalNoise2D(wx * 0.06, wz * 0.06, noiseSeed, 3) * 0.38 - 0.06;
            double shape = baseShape + shapeNoise * baseShape;
            if (shape < 0.04) continue;

            // Top surface – higher and more irregular
            double topNoise = fractalNoise2D(wx * 0.11, wz * 0.11, noiseSeed + 271L, 2) * 0.28;
            double topY = isl.cy + isl.rv * shape * (1.0 + topNoise * baseShape);

            // Bottom surface – concave underside / stalactites / overhangs
            double botNoise = fractalNoise2D(wx * 0.09 + 5000, wz * 0.09 + 5000, noiseSeed + 137L, 2) * 0.45;
            double bottomY = isl.cy - isl.rv * 0.65 * Math.max(0.08, baseShape)
                             - isl.rv * botNoise * baseShape;

            int iTop = Math.min((int) Math.round(topY), maxY - 1);
            int iBot = Math.max((int) Math.round(bottomY), minY);
            if (iTop < iBot) continue;

            for (int y = iBot; y <= iTop; y++) {
                chunk.setBlockState(new BlockPos(wx, y, wz), Blocks.STONE.defaultBlockState(), false);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SURFACE BUILDING  (replaces NoiseBasedChunkGenerator's surface pass)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                              RandomState randomState, ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        long noiseSeed = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(0, 0, 0).nextLong();

        int[] topYCache = new int[16 * 16];
        Arrays.fill(topYCache, Integer.MIN_VALUE);

        // ── First pass: find tops and apply surface blocks ────────────────────
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;
                int topY = findTopStoneY(chunk, wx, wz);
                topYCache[lx * 16 + lz] = topY;
                if (topY == Integer.MIN_VALUE) continue;

                Holder<Biome> biome = region.getNoiseBiome(wx >> 2, topY >> 2, wz >> 2);

                applySurfaceBlocks(chunk, wx, wz, topY, biome, noiseSeed);
            }
        }

        // ── Second pass: waterfall sources at cliff edges ─────────────────────
        // Only check interior columns (avoids cross-chunk boundary access)
        for (int lx = 1; lx < 15; lx++) {
            for (int lz = 1; lz < 15; lz++) {
                int topY = topYCache[lx * 16 + lz];
                if (topY < 90) continue; // only high islands get waterfalls

                int wx = startX + lx;
                int wz = startZ + lz;

                // Use a seeded random so waterfalls are deterministic
                RandomSource rng = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(wx, topY, wz);
                if (rng.nextFloat() > 0.07f) continue;

                // Check if any direct neighbor column drops sharply (island edge)
                boolean isCliffEdge = false;
                for (int[] d : DIRS4) {
                    int nTopY = topYCache[(lx + d[0]) * 16 + (lz + d[1])];
                    if (nTopY == Integer.MIN_VALUE || topY - nTopY > 12) {
                        isCliffEdge = true;
                        break;
                    }
                }

                if (isCliffEdge) {
                    // Place water source one above the surface top
                    BlockPos waterPos = new BlockPos(wx, topY + 1, wz);
                    chunk.setBlockState(waterPos, Blocks.WATER.defaultBlockState(), false);
                }
            }
        }
    }

    private void applySurfaceBlocks(ChunkAccess chunk, int wx, int wz, int topY,
                                     Holder<Biome> biome, long noiseSeed) {
        BlockState topBlock    = chooseSurfaceTop(biome);
        BlockState underBlock  = chooseSurfaceUnder(biome);

        // Top block
        chunk.setBlockState(new BlockPos(wx, topY, wz), topBlock, false);

        // 3-5 sub-surface blocks (dirt / sand / etc.)
        int depth = 3 + (int)(fractalNoise2D(wx * 0.25, wz * 0.25, noiseSeed + 999L, 1) * 3);
        for (int i = 1; i <= depth; i++) {
            int y = topY - i;
            if (y < chunk.getMinBuildHeight()) break;
            if (!chunk.getBlockState(new BlockPos(wx, y, wz)).is(Blocks.STONE)) break;
            chunk.setBlockState(new BlockPos(wx, y, wz), underBlock, false);
        }
    }

    private BlockState chooseSurfaceTop(Holder<Biome> biome) {
        Biome b = biome.value();
        float temp = b.getBaseTemperature();
        boolean hasPrecip = b.hasPrecipitation();

        // No precipitation + hot = desert-like surface
        if (!hasPrecip && temp > 1.2f) {
            return Blocks.SAND.defaultBlockState();
        }
        // Very cold with precipitation = frozen tundra
        if (temp < 0.05f && hasPrecip) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        // Hot-ish without much rain = savanna-style
        if (!hasPrecip && temp > 0.8f) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState chooseSurfaceUnder(Holder<Biome> biome) {
        Biome b = biome.value();
        if (!b.hasPrecipitation() && b.getBaseTemperature() > 1.2f) {
            return Blocks.SAND.defaultBlockState();
        }
        return Blocks.DIRT.defaultBlockState();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CARVERS – disabled; caves through island edges look wrong
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                              BiomeManager biomeManager,
                              StructureManager structureManager, ChunkAccess chunk,
                              GenerationStep.Carving step) {
        // No carvers — island terrain is too thin for vanilla carver sizes.
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HEIGHT QUERIES  (used by structure placement, spawn finding, etc.)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types types,
                              LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        long noiseSeed = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(0, 0, 0).nextLong();

        List<IslandData> islands = gatherNearbyIslands(x, z, islandRand);

        int highest = levelHeightAccessor.getMinBuildHeight();
        for (IslandData isl : islands) {
            int t = approximateTopY(x, z, isl, noiseSeed);
            if (t > highest) highest = t;
        }
        return highest;
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
        lines.add("Flying Islands Generator");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ISLAND PLACEMENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Collects all island data objects that might influence the column at (wx, wz).
     * Checks a 5×5 grid neighbourhood to handle large islands and clusters.
     */
    private List<IslandData> gatherNearbyIslands(int wx, int wz, PositionalRandomFactory randFac) {
        List<IslandData> result = new ArrayList<>();
        int gx = Math.floorDiv(wx, GRID_SIZE);
        int gz = Math.floorDiv(wz, GRID_SIZE);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                buildCellIslands(gx + dx, gz + dz, randFac, result);
            }
        }
        return result;
    }

    /**
     * Creates 0-3 islands for one grid cell, deterministically seeded by cell coords.
     */
    private void buildCellIslands(int cellX, int cellZ,
                                   PositionalRandomFactory randFac,
                                   List<IslandData> out) {
        RandomSource rng = randFac.at(cellX, 0, cellZ);

        if (rng.nextFloat() > ISLAND_CHANCE) return;

        // ── Primary island ─────────────────────────────────────────────────
        int wx = cellX * GRID_SIZE + rng.nextInt(GRID_SIZE);
        int wz = cellZ * GRID_SIZE + rng.nextInt(GRID_SIZE);
        int baseY = MIN_ISLAND_Y + rng.nextInt(MAX_ISLAND_Y - MIN_ISLAND_Y);

        double rh = MIN_RADIUS_H + rng.nextDouble() * (MAX_RADIUS_H - MIN_RADIUS_H);
        double rv = rh * (0.22 + rng.nextDouble() * 0.38);

        IslandData primary = new IslandData(wx, baseY, wz, rh, rv);
        out.add(primary);

        // ── First satellite ────────────────────────────────────────────────
        if (rng.nextFloat() < SATELLITE_CHANCE) {
            IslandData sat = spawnSatellite(primary, rng);
            out.add(sat);

            // ── Second satellite (triple cluster) ──────────────────────────
            if (rng.nextFloat() < TRIPLE_CHANCE) {
                out.add(spawnSatellite(sat, rng));
            }
        }
    }

    private IslandData spawnSatellite(IslandData parent, RandomSource rng) {
        double angle    = rng.nextDouble() * 2.0 * Math.PI;
        double dist     = parent.rh * (1.1 + rng.nextDouble() * 1.3);
        int satX  = (int)(parent.cx + Math.cos(angle) * dist);
        int satZ  = (int)(parent.cz + Math.sin(angle) * dist);
        int satY  = parent.cy + rng.nextInt(51) - 25;
        double satRH    = parent.rh * (0.28 + rng.nextDouble() * 0.52);
        double satRV    = satRH * (0.20 + rng.nextDouble() * 0.40);
        return new IslandData(satX, satY, satZ, satRH, satRV);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Analytical approximation of topY at (x,z) for a given island (no iteration needed). */
    private int approximateTopY(int x, int z, IslandData isl, long noiseSeed) {
        double dx = x - isl.cx;
        double dz = z - isl.cz;
        double dist  = Math.sqrt(dx * dx + dz * dz);
        double normR = dist / isl.rh;
        if (normR > 1.40) return Integer.MIN_VALUE;

        double base = Math.max(0.0, 1.0 - Math.pow(normR, 1.5));
        if (base < 0.02) return Integer.MIN_VALUE;

        double shapeNoise = fractalNoise2D(x * 0.06, z * 0.06, noiseSeed, 3) * 0.38 - 0.06;
        double shape = base + shapeNoise * base;

        double topNoise = fractalNoise2D(x * 0.11, z * 0.11, noiseSeed + 271L, 2) * 0.28;
        double topY = isl.cy + isl.rv * shape * (1.0 + topNoise * base);
        return (int) Math.round(topY);
    }

    /** Finds the highest STONE block in the column; returns Integer.MIN_VALUE if none. */
    private int findTopStoneY(ChunkAccess chunk, int wx, int wz) {
        for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
            if (chunk.getBlockState(new BlockPos(wx, y, wz)).is(Blocks.STONE)) return y;
        }
        return Integer.MIN_VALUE;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NOISE LIBRARY  (simple, self-contained, no external dependency)
    // ═════════════════════════════════════════════════════════════════════════

    /** Deterministic pseudo-random in [0, 1) from integer grid coordinates + seed. */
    private static double hash2D(int ix, int iz, long seed) {
        long h = seed ^ ((long) ix * 0x6C62272E07BB0142L) ^ ((long) iz * 0x4B7A5FEA3FA9B7E9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (double)(h & 0x7FFFFFFFFFFFFFFFL) / (double)Long.MAX_VALUE;
    }

    /** Bilinear interpolated smooth noise. */
    private static double smoothNoise2D(double x, double z, long seed) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;
        // Smoothstep
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);

        double n00 = hash2D(ix,     iz,     seed);
        double n10 = hash2D(ix + 1, iz,     seed);
        double n01 = hash2D(ix,     iz + 1, seed);
        double n11 = hash2D(ix + 1, iz + 1, seed);

        return n00 * (1-fx)*(1-fz) + n10 * fx*(1-fz) + n01 * (1-fx)*fz + n11 * fx*fz;
    }

    /** Multi-octave fractal noise, result in [0, 1). */
    private static double fractalNoise2D(double x, double z, long seed, int octaves) {
        double value = 0.0, amplitude = 1.0, frequency = 1.0, max = 0.0;
        for (int i = 0; i < octaves; i++) {
            value     += smoothNoise2D(x * frequency, z * frequency, seed + i * 8_364_517L) * amplitude;
            max       += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return value / max;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ISLAND DATA RECORD
    // ═════════════════════════════════════════════════════════════════════════

    /** Immutable description of one flying island. */
    private record IslandData(
            int cx, int cy, int cz,   // world-space centre
            double rh,                 // horizontal radius
            double rv                  // vertical half-height
    ) {}
}
