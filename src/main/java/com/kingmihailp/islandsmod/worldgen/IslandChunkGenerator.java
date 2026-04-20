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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IslandChunkGenerator extends NoiseBasedChunkGenerator {

    public static final MapCodec<IslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.generatorSettings())
            ).apply(instance, IslandChunkGenerator::new)
    );

    // ── Grid / placement ──────────────────────────────────────────────────────
    private static final int GRID_SIZE         = 320;
    private static final float ISLAND_CHANCE   = 0.72f;
    private static final float SATELLITE_CHANCE = 0.40f;
    private static final float TRIPLE_CHANCE   = 0.22f;

    // ── Island dimensions ─────────────────────────────────────────────────────
    private static final double MIN_RADIUS_H = 24.0;
    private static final double MAX_RADIUS_H = 120.0;

    // ── Vertical placement ────────────────────────────────────────────────────
    private static final int MIN_ISLAND_Y = 75;
    private static final int MAX_ISLAND_Y = 185;

    // ── Resource keys for deterministic random factories ──────────────────────
    private static final ResourceLocation RL_ISLANDS = ResourceLocation.fromNamespaceAndPath("islandsmod", "islands");
    private static final ResourceLocation RL_TERRAIN  = ResourceLocation.fromNamespaceAndPath("islandsmod", "terrain_noise");

    // ── Cardinal directions for cliff-edge detection ──────────────────────────
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
    // BUILD SURFACE — generates island terrain + applies surface + waterfalls
    // (fillFromNoise is final in NoiseBasedChunkGenerator; we override
    //  buildSurface instead, which runs after it, and replace all terrain here)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                              RandomState randomState, ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY   = chunk.getMinBuildHeight();
        int maxY   = chunk.getMaxBuildHeight();

        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        long noiseSeed = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(0, 0, 0).nextLong();

        List<IslandData> islands = gatherNearbyIslands(startX + 8, startZ + 8, islandRand);

        int[] topYCache = new int[16 * 16];
        Arrays.fill(topYCache, Integer.MIN_VALUE);

        // ── Per-column: clear vanilla terrain, place islands, apply surface ──
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                // Remove whatever fillFromNoise placed in this column.
                // Use the WG heightmap to avoid iterating the full 384-block range.
                int vanillaTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz);
                for (int y = minY; y <= vanillaTop; y++) {
                    BlockState bs = chunk.getBlockState(new BlockPos(wx, y, wz));
                    if (!bs.isAir()) {
                        chunk.setBlockState(new BlockPos(wx, y, wz),
                                Blocks.AIR.defaultBlockState(), false);
                    }
                }

                // Place island stone and record topY for this column
                int topY = fillIslandColumn(chunk, wx, wz, minY, maxY, islands, noiseSeed);
                topYCache[lx * 16 + lz] = topY;

                // Apply surface blocks (grass / dirt / sand …)
                if (topY != Integer.MIN_VALUE) {
                    Holder<Biome> biome = region.getNoiseBiome(wx >> 2, topY >> 2, wz >> 2);
                    applySurfaceBlocks(chunk, wx, wz, topY, biome, noiseSeed);
                }
            }
        }

        // ── Waterfall sources at cliff edges (interior columns only) ──────────
        for (int lx = 1; lx < 15; lx++) {
            for (int lz = 1; lz < 15; lz++) {
                int topY = topYCache[lx * 16 + lz];
                if (topY < 90) continue;

                int wx = startX + lx;
                int wz = startZ + lz;

                RandomSource rng = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(wx, topY, wz);
                if (rng.nextFloat() > 0.07f) continue;

                boolean isCliff = false;
                for (int[] d : DIRS4) {
                    int nTop = topYCache[(lx + d[0]) * 16 + (lz + d[1])];
                    if (nTop == Integer.MIN_VALUE || topY - nTop > 12) {
                        isCliff = true;
                        break;
                    }
                }
                if (isCliff) {
                    chunk.setBlockState(new BlockPos(wx, topY + 1, wz),
                            Blocks.WATER.defaultBlockState(), false);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ISLAND COLUMN FILL  — places stone and returns the column's topmost Y
    // ═════════════════════════════════════════════════════════════════════════

    private int fillIslandColumn(ChunkAccess chunk, int wx, int wz,
                                  int minY, int maxY,
                                  List<IslandData> islands, long noiseSeed) {
        int columnTopY = Integer.MIN_VALUE;

        for (IslandData isl : islands) {
            double dx = wx - isl.cx;
            double dz = wz - isl.cz;
            double dist  = Math.sqrt(dx * dx + dz * dz);
            double normR = dist / isl.rh;

            if (normR > 1.40) continue;

            double baseShape = Math.max(0.0, 1.0 - Math.pow(normR, 1.5));
            if (baseShape < 0.02) continue;

            double shapeNoise = fractalNoise2D(wx * 0.06, wz * 0.06, noiseSeed, 3) * 0.38 - 0.06;
            double shape = baseShape + shapeNoise * baseShape;
            if (shape < 0.04) continue;

            double topNoise = fractalNoise2D(wx * 0.11, wz * 0.11, noiseSeed + 271L, 2) * 0.28;
            double topY = isl.cy + isl.rv * shape * (1.0 + topNoise * baseShape);

            double botNoise = fractalNoise2D(wx * 0.09 + 5000, wz * 0.09 + 5000, noiseSeed + 137L, 2) * 0.45;
            double bottomY  = isl.cy - isl.rv * 0.65 * Math.max(0.08, baseShape)
                              - isl.rv * botNoise * baseShape;

            int iTop = Math.min((int) Math.round(topY), maxY - 1);
            int iBot = Math.max((int) Math.round(bottomY), minY);
            if (iTop < iBot) continue;

            for (int y = iBot; y <= iTop; y++) {
                chunk.setBlockState(new BlockPos(wx, y, wz), Blocks.STONE.defaultBlockState(), false);
            }
            if (iTop > columnTopY) columnTopY = iTop;
        }

        return columnTopY;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SURFACE APPLICATION
    // ═════════════════════════════════════════════════════════════════════════

    private void applySurfaceBlocks(ChunkAccess chunk, int wx, int wz, int topY,
                                     Holder<Biome> biome, long noiseSeed) {
        BlockState topBlock   = chooseSurfaceTop(biome);
        BlockState underBlock = chooseSurfaceUnder(biome);

        chunk.setBlockState(new BlockPos(wx, topY, wz), topBlock, false);

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

        if (!hasPrecip && temp > 1.2f)          return Blocks.SAND.defaultBlockState();
        if (temp < 0.05f && hasPrecip)          return Blocks.SNOW_BLOCK.defaultBlockState();
        if (!hasPrecip && temp > 0.8f)          return Blocks.RED_SAND.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState chooseSurfaceUnder(Holder<Biome> biome) {
        Biome b = biome.value();
        if (!b.hasPrecipitation() && b.getBaseTemperature() > 1.2f)
            return Blocks.SAND.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CARVERS — disabled (cave openings at island edges look wrong)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                              BiomeManager biomeManager, StructureManager structureManager,
                              ChunkAccess chunk, GenerationStep.Carving step) {
        // intentionally empty
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HEIGHT QUERY — used by structure placement and spawn finder
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

    private void buildCellIslands(int cellX, int cellZ,
                                   PositionalRandomFactory randFac, List<IslandData> out) {
        RandomSource rng = randFac.at(cellX, 0, cellZ);
        if (rng.nextFloat() > ISLAND_CHANCE) return;

        int wx   = cellX * GRID_SIZE + rng.nextInt(GRID_SIZE);
        int wz   = cellZ * GRID_SIZE + rng.nextInt(GRID_SIZE);
        int baseY = MIN_ISLAND_Y + rng.nextInt(MAX_ISLAND_Y - MIN_ISLAND_Y);
        double rh = MIN_RADIUS_H + rng.nextDouble() * (MAX_RADIUS_H - MIN_RADIUS_H);
        double rv = rh * (0.22 + rng.nextDouble() * 0.38);

        IslandData primary = new IslandData(wx, baseY, wz, rh, rv);
        out.add(primary);

        if (rng.nextFloat() < SATELLITE_CHANCE) {
            IslandData sat = spawnSatellite(primary, rng);
            out.add(sat);
            if (rng.nextFloat() < TRIPLE_CHANCE) {
                out.add(spawnSatellite(sat, rng));
            }
        }
    }

    private IslandData spawnSatellite(IslandData parent, RandomSource rng) {
        double angle = rng.nextDouble() * 2.0 * Math.PI;
        double dist  = parent.rh * (1.1 + rng.nextDouble() * 1.3);
        int satX = (int)(parent.cx + Math.cos(angle) * dist);
        int satZ = (int)(parent.cz + Math.sin(angle) * dist);
        int satY = parent.cy + rng.nextInt(51) - 25;
        double satRH = parent.rh * (0.28 + rng.nextDouble() * 0.52);
        double satRV = satRH * (0.20 + rng.nextDouble() * 0.40);
        return new IslandData(satX, satY, satZ, satRH, satRV);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

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
        return (int) Math.round(isl.cy + isl.rv * shape * (1.0 + topNoise * base));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NOISE  (self-contained, no external dependency)
    // ═════════════════════════════════════════════════════════════════════════

    private static double hash2D(int ix, int iz, long seed) {
        long h = seed ^ ((long) ix * 0x6C62272E07BB0142L) ^ ((long) iz * 0x4B7A5FEA3FA9B7E9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (double)(h & 0x7FFFFFFFFFFFFFFFL) / (double) Long.MAX_VALUE;
    }

    private static double smoothNoise2D(double x, double z, long seed) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double n00 = hash2D(ix,   iz,   seed), n10 = hash2D(ix+1, iz,   seed);
        double n01 = hash2D(ix,   iz+1, seed), n11 = hash2D(ix+1, iz+1, seed);
        return n00*(1-fx)*(1-fz) + n10*fx*(1-fz) + n01*(1-fx)*fz + n11*fx*fz;
    }

    private static double fractalNoise2D(double x, double z, long seed, int octaves) {
        double value = 0, amplitude = 1, frequency = 1, max = 0;
        for (int i = 0; i < octaves; i++) {
            value     += smoothNoise2D(x * frequency, z * frequency, seed + i * 8_364_517L) * amplitude;
            max       += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return value / max;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DATA
    // ═════════════════════════════════════════════════════════════════════════

    private record IslandData(int cx, int cy, int cz, double rh, double rv) {}
}
