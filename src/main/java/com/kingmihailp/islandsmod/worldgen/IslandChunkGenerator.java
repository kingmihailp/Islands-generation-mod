package com.kingmihailp.islandsmod.worldgen;

import com.kingmihailp.islandsmod.init.ModWorldgen;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Climate;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class IslandChunkGenerator extends NoiseBasedChunkGenerator {

    public static final MapCodec<IslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.generatorSettings())
            ).apply(instance, IslandChunkGenerator::new)
    );

    // ── Grid / placement ──────────────────────────────────────────────────────
    private static final int    GRID_SIZE        = 320;
    private static final float  ISLAND_CHANCE    = 0.72f;
    private static final float  SATELLITE_CHANCE = 0.40f;
    private static final float  TRIPLE_CHANCE    = 0.22f;

    // ── Island dimensions ─────────────────────────────────────────────────────
    private static final double MIN_RADIUS_H = 24.0;
    private static final double MAX_RADIUS_H = 120.0;

    // ── Vertical placement: center Y freely from -40 to 85 ───────────────────
    private static final int MIN_ISLAND_Y = -40;
    private static final int MAX_ISLAND_Y = 85;

    // ── Depth transition (deepslate below this Y) ─────────────────────────────
    private static final int DEEPSLATE_TOP = -8;

    // ── Resource keys ─────────────────────────────────────────────────────────
    private static final ResourceLocation RL_ISLANDS = ResourceLocation.fromNamespaceAndPath("islandsmod", "islands");
    private static final ResourceLocation RL_TERRAIN  = ResourceLocation.fromNamespaceAndPath("islandsmod", "terrain_noise");

    // ── Cardinal directions ───────────────────────────────────────────────────
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
    // BUILD SURFACE
    // 3-pass: fill stone bodies → carve ocean lakes → apply surface blocks
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
        List<BoundingBox> structurePlatforms = gatherStructurePlatforms(chunk, structureManager);

        // Islands whose center biome is ocean get a central lake carved in them.
        // Use biomeSource directly (no WorldGenRegion deadlock risk at distant coords).
        Climate.Sampler climateSampler = randomState.sampler();
        List<IslandData> oceanIslands = new ArrayList<>();
        for (IslandData isl : islands) {
            Holder<Biome> b = biomeSource.getNoiseBiome(isl.cx() >> 2, isl.cy() >> 2, isl.cz() >> 2, climateSampler);
            if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN))
                oceanIslands.add(isl);
        }

        int[] topYCache = new int[16 * 16];
        Arrays.fill(topYCache, Integer.MIN_VALUE);

        // ── Pass 1: erase vanilla terrain, fill island stone bodies ──────────
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                int vanillaTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz);
                for (int y = minY; y <= vanillaTop; y++) {
                    if (!chunk.getBlockState(new BlockPos(wx, y, wz)).isAir())
                        chunk.setBlockState(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), false);
                }

                int islandTop   = fillIslandColumn(chunk, wx, wz, minY, maxY, islands, noiseSeed);
                int platformTop = fillStructurePlatformColumn(chunk, wx, wz, minY, maxY, structurePlatforms, noiseSeed);
                topYCache[lx * 16 + lz] = Math.max(islandTop, platformTop);
            }
        }

        // ── Pass 2: carve lakes for ocean islands ─────────────────────────────
        for (IslandData isl : oceanIslands) {
            int lakeDepth   = 8;
            int lakeSurface = isl.cy() + (int)(isl.rv() * 0.30);
            int lakeFloor   = lakeSurface - lakeDepth;
            double lakeR    = isl.rh() * 0.35;
            double lakeR2   = lakeR * lakeR;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = startX + lx;
                    int wz = startZ + lz;
                    double ddx = wx - isl.cx();
                    double ddz = wz - isl.cz();
                    if (ddx * ddx + ddz * ddz > lakeR2) continue;

                    int topY = topYCache[lx * 16 + lz];
                    if (topY < lakeSurface) continue; // column doesn't reach lake level

                    // Clear stone above lake surface
                    for (int y = lakeSurface + 1; y <= topY; y++) {
                        if (y >= minY && y < maxY)
                            chunk.setBlockState(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), false);
                    }
                    // Fill water from (lakeFloor+1) to lakeSurface
                    int wBot = Math.max(lakeFloor + 1, minY);
                    for (int y = wBot; y <= lakeSurface && y < maxY; y++)
                        chunk.setBlockState(new BlockPos(wx, y, wz), Blocks.WATER.defaultBlockState(), false);

                    // Mark as lake column — skip surface application
                    topYCache[lx * 16 + lz] = Integer.MIN_VALUE;
                }
            }
        }

        // ── Pass 3: apply surface blocks ──────────────────────────────────────
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int topY = topYCache[lx * 16 + lz];
                if (topY == Integer.MIN_VALUE) continue;
                int wx = startX + lx;
                int wz = startZ + lz;
                Holder<Biome> biome = region.getNoiseBiome(wx >> 2, topY >> 2, wz >> 2);
                applySurfaceBlocks(chunk, wx, wz, topY, biome, noiseSeed);
            }
        }

        // ── Waterfall sources at exposed cliff edges ───────────────────────────
        for (int lx = 1; lx < 15; lx++) {
            for (int lz = 1; lz < 15; lz++) {
                int topY = topYCache[lx * 16 + lz];
                if (topY < 90) continue;
                int wx = startX + lx;
                int wz = startZ + lz;
                RandomSource rng = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(wx, topY, wz);
                if (rng.nextFloat() > 0.07f) continue;
                for (int[] d : DIRS4) {
                    int nTop = topYCache[(lx + d[0]) * 16 + (lz + d[1])];
                    if (nTop == Integer.MIN_VALUE || topY - nTop > 12) {
                        chunk.setBlockState(new BlockPos(wx, topY + 1, wz),
                                Blocks.WATER.defaultBlockState(), false);
                        break;
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ISLAND COLUMN FILL — no V-roots; islands float freely
    // ═════════════════════════════════════════════════════════════════════════

    private int fillIslandColumn(ChunkAccess chunk, int wx, int wz,
                                  int minY, int maxY,
                                  List<IslandData> islands, long noiseSeed) {
        int columnTopY = Integer.MIN_VALUE;

        for (IslandData isl : islands) {

            // ── Style-dependent shape parameters ────────────────────────────
            double warpFactor, exponent, radialBase, radialRange, botFactor;
            switch (isl.style) {
                case 1  -> { warpFactor=0.28; exponent=3.0;  radialBase=0.72; radialRange=0.28; botFactor=0.30; } // flat mesa
                case 2  -> { warpFactor=0.22; exponent=0.72; radialBase=0.60; radialRange=0.52; botFactor=1.25; } // mountain
                case 3  -> { warpFactor=0.75; exponent=1.10; radialBase=0.44; radialRange=1.20; botFactor=0.90; } // wispy
                case 4  -> { warpFactor=0.42; exponent=6.0;  radialBase=0.82; radialRange=0.28; botFactor=0.12; } // disk
                default -> { warpFactor=0.44; exponent=1.35; radialBase=0.58; radialRange=0.68; botFactor=0.82; } // round
            }

            // ── 1. Domain warping ────────────────────────────────────────────
            double ws  = 0.030;
            double wr  = isl.rh * warpFactor;
            double swx = wx + (fractalNoise2D(wx * ws,       wz * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
            double swz = wz + (fractalNoise2D(wx * ws + 500, wz * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

            // ── 2. Radial noise ──────────────────────────────────────────────
            double angle       = Math.atan2(wz - isl.cz, wx - isl.cx);
            double radialNoise = fractalNoise2D(
                    Math.cos(angle) * 4.5 + isl.cx * 0.006,
                    Math.sin(angle) * 4.5 + isl.cz * 0.006,
                    noiseSeed + 300L, 5);
            double effRadius = isl.rh * (radialBase + radialNoise * radialRange);

            // ── 3. Normalised distance in warped space ───────────────────────
            double wdx   = swx - isl.cx;
            double wdz   = swz - isl.cz;
            double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
            double normR = dist / effRadius;
            if (normR > 1.22) continue;

            double shape = Math.max(0.0, 1.0 - Math.pow(normR, exponent));
            if (shape < 0.012) continue;

            // ── 4. Vertical extent ───────────────────────────────────────────
            double topNoise  = fractalNoise2D(wx * 0.09, wz * 0.09, noiseSeed + 400L, 3) * 0.32;
            double topYd     = isl.cy + isl.rv * shape * (1.0 + topNoise);

            double botNoise  = fractalNoise2D(wx * 0.07 + 5000, wz * 0.07 + 5000, noiseSeed + 500L, 3) * 0.55;
            double bodyBotYd = isl.cy - isl.rv * botFactor * Math.max(0.06, shape)
                             - isl.rv * botNoise * shape;

            int iTop     = Math.min((int) Math.round(topYd),    maxY - 1);
            int iBodyBot = (int) Math.round(bodyBotYd);
            int iBot     = Math.max(iBodyBot, minY);
            if (iTop < iBot) continue;

            // ── 5. Place blocks ──────────────────────────────────────────────
            for (int y = iBot; y <= iTop; y++) {
                BlockState block;
                if (y < DEEPSLATE_TOP) {
                    block = Blocks.DEEPSLATE.defaultBlockState();
                } else if (y < 8) {
                    double mix = smoothNoise2D(wx * 0.25 + y * 0.12, wz * 0.25, noiseSeed + 600L);
                    double t   = (y - DEEPSLATE_TOP) / (double)(8 - DEEPSLATE_TOP);
                    block = (mix < t) ? Blocks.STONE.defaultBlockState()
                                      : Blocks.DEEPSLATE.defaultBlockState();
                } else {
                    block = Blocks.STONE.defaultBlockState();
                }
                chunk.setBlockState(new BlockPos(wx, y, wz), block, false);
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
            BlockState cur = chunk.getBlockState(new BlockPos(wx, y, wz));
            if (!cur.is(Blocks.STONE) && !cur.is(Blocks.DEEPSLATE)) break;
            chunk.setBlockState(new BlockPos(wx, y, wz), underBlock, false);
        }
    }

    private BlockState chooseSurfaceTop(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_BADLANDS))  return Blocks.RED_SAND.defaultBlockState();
        if (biome.is(BiomeTags.IS_SAVANNA))   return Blocks.GRASS_BLOCK.defaultBlockState();
        Biome b = biome.value();
        float temp     = b.getBaseTemperature();
        boolean precip = b.hasPrecipitation();
        if (!precip && temp > 1.5f)           return Blocks.SAND.defaultBlockState();
        if (temp < 0.05f && precip)           return Blocks.SNOW_BLOCK.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState chooseSurfaceUnder(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_BADLANDS))  return Blocks.TERRACOTTA.defaultBlockState();
        if (biome.is(BiomeTags.IS_SAVANNA))   return Blocks.DIRT.defaultBlockState();
        Biome b = biome.value();
        if (!b.hasPrecipitation() && b.getBaseTemperature() > 1.5f)
            return Blocks.SAND.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STRUCTURE STARTS — skip chunks with no island terrain so structures
    // never generate floating in the void
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void createStructures(RegistryAccess registryAccess,
                                  ChunkGeneratorStructureState structureState,
                                  StructureManager structureManager,
                                  ChunkAccess chunk,
                                  StructureTemplateManager templateManager) {
        if (chunkHasTerrain(chunk, structureState.randomState())) {
            super.createStructures(registryAccess, structureState, structureManager, chunk, templateManager);
        }
    }

    /** Returns true if any island has its centre within 1.5× rh of the chunk centre. */
    private boolean chunkHasTerrain(ChunkAccess chunk, RandomState randomState) {
        int cx = chunk.getPos().getMinBlockX() + 8;
        int cz = chunk.getPos().getMinBlockZ() + 8;
        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        for (IslandData isl : gatherNearbyIslands(cx, cz, islandRand)) {
            double dx = cx - isl.cx(), dz = cz - isl.cz();
            if (dx * dx + dz * dz < isl.rh * isl.rh * 2.25) return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HEIGHT QUERY
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
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                buildCellIslands(gx + dx, gz + dz, randFac, result);
        return result;
    }

    private void buildCellIslands(int cellX, int cellZ,
                                   PositionalRandomFactory randFac, List<IslandData> out) {
        RandomSource rng = randFac.at(cellX, 0, cellZ);
        if (rng.nextFloat() > ISLAND_CHANCE) return;

        int wx    = cellX * GRID_SIZE + rng.nextInt(GRID_SIZE);
        int wz    = cellZ * GRID_SIZE + rng.nextInt(GRID_SIZE);
        int baseY = MIN_ISLAND_Y + rng.nextInt(MAX_ISLAND_Y - MIN_ISLAND_Y);
        double rh = MIN_RADIUS_H + rng.nextDouble() * (MAX_RADIUS_H - MIN_RADIUS_H);
        int style = rng.nextInt(5);
        double rv = styleRv(rh, style, rng);

        IslandData primary = new IslandData(wx, baseY, wz, rh, rv, style);
        out.add(primary);

        if (rng.nextFloat() < SATELLITE_CHANCE) {
            IslandData sat = spawnSatellite(primary, rng);
            out.add(sat);
            if (rng.nextFloat() < TRIPLE_CHANCE)
                out.add(spawnSatellite(sat, rng));
        }
    }

    private static double styleRv(double rh, int style, RandomSource rng) {
        return switch (style) {
            case 1 -> rh * (0.06 + rng.nextDouble() * 0.10); // flat mesa
            case 2 -> rh * (0.45 + rng.nextDouble() * 0.55); // mountain
            case 4 -> rh * (0.03 + rng.nextDouble() * 0.06); // disk
            default -> rh * (0.22 + rng.nextDouble() * 0.38); // round / jagged
        };
    }

    private IslandData spawnSatellite(IslandData parent, RandomSource rng) {
        double angle  = rng.nextDouble() * 2.0 * Math.PI;
        double dist   = parent.rh * (1.1 + rng.nextDouble() * 1.3);
        int satX  = (int)(parent.cx + Math.cos(angle) * dist);
        int satZ  = (int)(parent.cz + Math.sin(angle) * dist);
        int satY  = parent.cy + rng.nextInt(51) - 25;
        double satRH  = parent.rh * (0.28 + rng.nextDouble() * 0.52);
        int satStyle  = rng.nextInt(5);
        double satRV  = styleRv(satRH, satStyle, rng);
        return new IslandData(satX, satY, satZ, satRH, satRV, satStyle);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STRUCTURE PLATFORMS — flat island under every structure
    // ═════════════════════════════════════════════════════════════════════════

    /** Collects bounding boxes of all structures that start in or reference this chunk. */
    private List<BoundingBox> gatherStructurePlatforms(ChunkAccess chunk, StructureManager structureManager) {
        List<BoundingBox> result = new ArrayList<>();

        // Structures whose start is in this chunk
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (start.isValid()) result.add(start.getBoundingBox());
        }

        // Structures whose start is elsewhere but references this chunk
        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
            Structure structure = entry.getKey();
            for (long packed : entry.getValue().toLongArray()) {
                try {
                    ChunkPos cp = new ChunkPos(packed);
                    SectionPos sp = SectionPos.of(cp, 0);
                    for (StructureStart start : structureManager.startsForStructure(sp, structure)) {
                        if (start.isValid()) result.add(start.getBoundingBox());
                    }
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /**
     * Fills stone/deepslate columns for a structure platform.
     * The platform top sits one block below the structure floor (bb.minY-1),
     * with 8-block XZ padding and organic edge tapering.
     */
    private int fillStructurePlatformColumn(ChunkAccess chunk, int wx, int wz,
                                             int minY, int maxY,
                                             List<BoundingBox> platforms, long noiseSeed) {
        int columnTopY = Integer.MIN_VALUE;

        for (BoundingBox bb : platforms) {
            final int PAD = 8;
            int distX = Math.max(0, Math.max(bb.minX() - wx, wx - bb.maxX()));
            int distZ = Math.max(0, Math.max(bb.minZ() - wz, wz - bb.maxZ()));
            int dist  = Math.max(distX, distZ);
            if (dist > PAD) continue;

            int topY = bb.minY() - 1;
            if (topY < minY + 2 || topY >= maxY) continue;

            float fade = 1.0f - (float) dist / PAD;
            double noiseThick = fractalNoise2D(wx * 0.14, wz * 0.14, noiseSeed + 7777L, 3);
            int thickness = Math.max(1, (int)(fade * (4 + noiseThick * 5)));

            int botY = Math.max(topY - thickness, minY);
            for (int y = botY; y <= topY; y++) {
                BlockPos pos = new BlockPos(wx, y, wz);
                if (chunk.getBlockState(pos).isAir()) {
                    BlockState bs = y < DEEPSLATE_TOP
                            ? Blocks.DEEPSLATE.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
                    chunk.setBlockState(pos, bs, false);
                }
            }
            if (topY > columnTopY) columnTopY = topY;
        }
        return columnTopY;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private int approximateTopY(int x, int z, IslandData isl, long noiseSeed) {
        double warpFactor, exponent, radialBase, radialRange;
        switch (isl.style) {
            case 1  -> { warpFactor=0.28; exponent=3.0;  radialBase=0.72; radialRange=0.28; }
            case 2  -> { warpFactor=0.22; exponent=0.72; radialBase=0.60; radialRange=0.52; }
            case 3  -> { warpFactor=0.75; exponent=1.10; radialBase=0.44; radialRange=1.20; }
            case 4  -> { warpFactor=0.42; exponent=6.0;  radialBase=0.82; radialRange=0.28; }
            default -> { warpFactor=0.44; exponent=1.35; radialBase=0.58; radialRange=0.68; }
        }

        double ws  = 0.030;
        double wr  = isl.rh * warpFactor;
        double swx = x + (fractalNoise2D(x * ws,       z * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
        double swz = z + (fractalNoise2D(x * ws + 500, z * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

        double angle       = Math.atan2(z - isl.cz, x - isl.cx);
        double radialNoise = fractalNoise2D(
                Math.cos(angle) * 4.5 + isl.cx * 0.006,
                Math.sin(angle) * 4.5 + isl.cz * 0.006,
                noiseSeed + 300L, 5);
        double effRadius = isl.rh * (radialBase + radialNoise * radialRange);

        double wdx   = swx - isl.cx;
        double wdz   = swz - isl.cz;
        double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
        double normR = dist / effRadius;
        if (normR > 1.22) return Integer.MIN_VALUE;

        double shape = Math.max(0.0, 1.0 - Math.pow(normR, exponent));
        if (shape < 0.012) return Integer.MIN_VALUE;

        double topNoise = fractalNoise2D(x * 0.09, z * 0.09, noiseSeed + 400L, 3) * 0.32;
        return (int) Math.round(isl.cy + isl.rv * shape * (1.0 + topNoise));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NOISE  (self-contained)
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

    /**
     * style 0 – round/standard
     * style 1 – flat mesa (low rv, sharp edge)
     * style 2 – mountain/spike (tall rv, narrow)
     * style 3 – wispy/jagged (heavy domain warp)
     * style 4 – pancake/disk (ultra-flat)
     */
    private record IslandData(int cx, int cy, int cz, double rh, double rv, int style) {}
}
