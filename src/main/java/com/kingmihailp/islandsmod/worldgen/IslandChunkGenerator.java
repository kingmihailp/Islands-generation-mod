package com.kingmihailp.islandsmod.worldgen;

import com.kingmihailp.islandsmod.init.ModWorldgen;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
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
        List<BoundingBox> structurePlatforms = gatherStructurePlatforms(chunk, structureManager, region);

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

            // ── 1. Domain warping ────────────────────────────────────────────
            double ws  = 0.030;
            double wr  = isl.rh * isl.warpF;
            double swx = wx + (fractalNoise2D(wx * ws,       wz * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
            double swz = wz + (fractalNoise2D(wx * ws + 500, wz * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

            // ── 2. Radial noise ──────────────────────────────────────────────
            double angle       = Math.atan2(wz - isl.cz, wx - isl.cx);
            double radialNoise = fractalNoise2D(
                    Math.cos(angle) * 4.5 + isl.cx * 0.006,
                    Math.sin(angle) * 4.5 + isl.cz * 0.006,
                    noiseSeed + 300L, 5);
            double effRadius = isl.rh * (isl.radBase + radialNoise * isl.radRange);

            // ── 3. Normalised distance in warped space ───────────────────────
            double wdx   = swx - isl.cx;
            double wdz   = swz - isl.cz;
            double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
            double normR = dist / effRadius;
            if (normR > 1.22) continue;

            double shape = Math.max(0.0, 1.0 - Math.pow(normR, isl.exp));
            if (shape < 0.012) continue;

            // ── 4. Vertical extent ───────────────────────────────────────────
            double topNoise  = fractalNoise2D(wx * 0.09, wz * 0.09, noiseSeed + 400L, 3) * 0.32;
            double topYd     = isl.cy + isl.rv * shape * (1.0 + topNoise);

            double botNoise  = fractalNoise2D(wx * 0.07 + 5000, wz * 0.07 + 5000, noiseSeed + 500L, 3) * 0.55;
            double bodyBotYd = isl.cy - isl.rv * isl.botF * Math.max(0.06, shape)
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
    // HEIGHT QUERY
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types types,
                              LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        long noiseSeed = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(0, 0, 0).nextLong();
        List<IslandData> islands = gatherNearbyIslands(x, z, islandRand);
        int highest = Integer.MIN_VALUE;
        for (IslandData isl : islands) {
            int t = approximateTopY(x, z, isl, noiseSeed);
            if (t > highest) highest = t;
        }
        // For positions with no island, return a sensible height per heightmap type
        // so vanilla structure placement anchors at realistic positions:
        //   OCEAN_FLOOR_* → 46  (typical seafloor, below sea_level 63)
        //   everything else → 63 (sea surface / terrain surface)
        // Returning 63 for OCEAN_FLOOR would equal sea_level, causing validators
        // to treat the position as "no ocean" and discard shipwreck placements.
        if (highest != Integer.MIN_VALUE) return highest;
        boolean isFloor = (types == Heightmap.Types.OCEAN_FLOOR_WG
                        || types == Heightmap.Types.OCEAN_FLOOR);
        return isFloor ? 46 : 63;
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

        IslandData primary = new IslandData(wx, baseY, wz, rh, randomRv(rh, rng),
                randomWarpF(rng), randomExp(rng), randomRadBase(rng), randomRadRange(rng), randomBotF(rng));
        out.add(primary);

        if (rng.nextFloat() < SATELLITE_CHANCE) {
            IslandData sat = spawnSatellite(primary, rng);
            out.add(sat);
            if (rng.nextFloat() < TRIPLE_CHANCE)
                out.add(spawnSatellite(sat, rng));
        }
    }

    private IslandData spawnSatellite(IslandData parent, RandomSource rng) {
        double angle = rng.nextDouble() * 2.0 * Math.PI;
        double dist  = parent.rh * (1.1 + rng.nextDouble() * 1.3);
        int satX = (int)(parent.cx + Math.cos(angle) * dist);
        int satZ = (int)(parent.cz + Math.sin(angle) * dist);
        int satY = parent.cy + rng.nextInt(51) - 25;
        double satRH = parent.rh * (0.28 + rng.nextDouble() * 0.52);
        return new IslandData(satX, satY, satZ, satRH, randomRv(satRH, rng),
                randomWarpF(rng), randomExp(rng), randomRadBase(rng), randomRadRange(rng), randomBotF(rng));
    }

    // ── Per-parameter random ranges ───────────────────────────────────────────
    private static double randomRv      (double rh, RandomSource r) { return rh * (0.03 + r.nextDouble() * 0.75); }
    private static double randomWarpF   (RandomSource r) { return 0.10 + r.nextDouble() * 0.75; }
    private static double randomExp     (RandomSource r) { return 0.45 + r.nextDouble() * 6.55; }
    private static double randomRadBase (RandomSource r) { return 0.42 + r.nextDouble() * 0.43; }
    private static double randomRadRange(RandomSource r) { return 0.12 + r.nextDouble() * 1.28; }
    private static double randomBotF    (RandomSource r) { return 0.04 + r.nextDouble() * 1.46; }

    // ═════════════════════════════════════════════════════════════════════════
    // STRUCTURE PLATFORMS — organic contour island around every structure
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Collects bounding boxes of all structures relevant to this chunk.
     * Tries StructureManager for every section-Y (deep structures like ancient
     * cities live at section Y=-4, not 0), then falls back to a direct chunk
     * read from the WorldGenRegion to handle chunks near the region boundary.
     */
    private List<BoundingBox> gatherStructurePlatforms(ChunkAccess chunk,
                                                        StructureManager structureManager,
                                                        WorldGenRegion region) {
        List<BoundingBox> result = new ArrayList<>();

        for (StructureStart start : chunk.getAllStarts().values()) {
            if (start.isValid()) result.add(start.getBoundingBox());
        }

        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
            Structure structure = entry.getKey();
            for (long packed : entry.getValue().toLongArray()) {
                try {
                    ChunkPos cp = new ChunkPos(packed);
                    boolean found = false;
                    // Scan all section Y values so we don't miss deep structures
                    // (ancient cities at Y≈-52 live in section Y=-4, not 0).
                    for (int sy = -5; sy <= 20 && !found; sy++) {
                        List<StructureStart> starts =
                                structureManager.startsForStructure(SectionPos.of(cp, sy), structure);
                        for (StructureStart s : starts) {
                            if (s.isValid()) { result.add(s.getBoundingBox()); found = true; }
                        }
                    }
                    if (!found) {
                        ChunkAccess startChunk = region.getChunk(cp.x, cp.z);
                        if (startChunk != null) {
                            StructureStart s = startChunk.getAllStarts().get(structure);
                            if (s != null && s.isValid()) result.add(s.getBoundingBox());
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /**
     * Builds an organic stone contour shelf around the structure's perimeter.
     *
     * Three key design decisions vs the old approach:
     *  1. CONTOUR only — columns inside the BB footprint are skipped; only the
     *     exterior ring receives stone so the structure interior is untouched.
     *  2. Top flush with structure floor — topY = bb.minY() so the shelf is at
     *     the same height as the structure floor and players can step straight
     *     from any door/exit onto the island without a vertical drop.
     *  3. No chunk seams — effectivePad blends angle-based noise (gives each
     *     structure a unique organic silhouette) with position-based noise
     *     (eliminates the hard seam that appeared when pure angle noise changed
     *     sign across a chunk boundary).
     */
    private int fillStructurePlatformColumn(ChunkAccess chunk, int wx, int wz,
                                             int minY, int maxY,
                                             List<BoundingBox> platforms, long noiseSeed) {
        int columnTopY = Integer.MIN_VALUE;

        for (BoundingBox bb : platforms) {
            // ── Part 1: contour only ─────────────────────────────────────────
            // Columns inside the structure's footprint are left untouched;
            // the structure places its own blocks there via applyBiomeDecoration.
            if (wx >= bb.minX() && wx <= bb.maxX()
                    && wz >= bb.minZ() && wz <= bb.maxZ()) continue;

            // Distance from this column to the nearest point on the BB edge
            double nearX = Math.max(bb.minX(), Math.min(wx, bb.maxX()));
            double nearZ = Math.max(bb.minZ(), Math.min(wz, bb.maxZ()));
            double dx = wx - nearX, dz = wz - nearZ;
            double edgeDist = Math.sqrt(dx * dx + dz * dz);

            // ── Part 2: chunk-seam-free organic pad ──────────────────────────
            // Angle-based component → unique silhouette per structure position.
            double cx = (bb.minX() + bb.maxX()) * 0.5;
            double cz = (bb.minZ() + bb.maxZ()) * 0.5;
            double angle = Math.atan2(wz - cz, wx - cx);
            double angNoise = fractalNoise2D(
                    Math.cos(angle) * 4.0 + cx * 0.014,
                    Math.sin(angle) * 4.0 + cz * 0.014,
                    noiseSeed + 9999L, 4);
            // Position-based component → smooths the transition at chunk edges.
            double posNoise = fractalNoise2D(wx * 0.055, wz * 0.055, noiseSeed + 8888L, 3);
            // Coefficients sum to 1.0 → range is [basePad×0.42, basePad×1.0]
            double basePad = bb.minY() < 0 ? 16.0 : 12.0;
            double effectivePad = basePad * (0.42 + angNoise * 0.40 + posNoise * 0.18);

            if (edgeDist > effectivePad) continue;

            // ── Part 3: topY flush with structure floor ───────────────────────
            // The shelf top equals bb.minY() so it is walkable from any
            // ground-level exit of the structure without a step down.
            int topY = Math.max(bb.minY(), minY + 1);
            if (topY >= maxY) continue;

            float fade = (float)(1.0 - edgeDist / effectivePad);
            double noiseThick = fractalNoise2D(wx * 0.10, wz * 0.10, noiseSeed + 7777L, 3);
            int baseThick = bb.minY() < 0 ? 5 : 4;
            int thickness = Math.max(1, (int)(fade * (baseThick + noiseThick * 4)));

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
        double ws  = 0.030;
        double wr  = isl.rh * isl.warpF;
        double swx = x + (fractalNoise2D(x * ws,       z * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
        double swz = z + (fractalNoise2D(x * ws + 500, z * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

        double angle       = Math.atan2(z - isl.cz, x - isl.cx);
        double radialNoise = fractalNoise2D(
                Math.cos(angle) * 4.5 + isl.cx * 0.006,
                Math.sin(angle) * 4.5 + isl.cz * 0.006,
                noiseSeed + 300L, 5);
        double effRadius = isl.rh * (isl.radBase + radialNoise * isl.radRange);

        double wdx   = swx - isl.cx;
        double wdz   = swz - isl.cz;
        double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
        double normR = dist / effRadius;
        if (normR > 1.22) return Integer.MIN_VALUE;

        double shape = Math.max(0.0, 1.0 - Math.pow(normR, isl.exp));
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

    // All shape parameters are drawn independently per island — fully procedural.
    // warpF   : domain-warp strength (0.10–0.85)
    // exp     : shape falloff exponent (0.45–7.0)  low=spike, high=flat disk
    // radBase : base radial multiplier (0.42–0.85)
    // radRange: radial-noise amplitude (0.12–1.40)
    // botF    : bottom-extension factor (0.04–1.50)
    private record IslandData(int cx, int cy, int cz, double rh, double rv,
                               double warpF, double exp, double radBase, double radRange, double botF) {}
}
