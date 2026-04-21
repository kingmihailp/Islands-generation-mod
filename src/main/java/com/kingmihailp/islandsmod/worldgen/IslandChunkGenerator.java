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
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;

import net.minecraft.tags.BiomeTags;

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
    private static final int    GRID_SIZE        = 320;
    private static final float  ISLAND_CHANCE    = 0.72f;
    private static final float  SATELLITE_CHANCE = 0.40f;
    private static final float  TRIPLE_CHANCE    = 0.22f;

    // ── Island dimensions ─────────────────────────────────────────────────────
    private static final double MIN_RADIUS_H = 24.0;
    private static final double MAX_RADIUS_H = 120.0;

    // ── Vertical placement ────────────────────────────────────────────────────
    private static final int MIN_ISLAND_Y = 75;
    private static final int MAX_ISLAND_Y = 185;

    // ── Deep root ─────────────────────────────────────────────────────────────
    private static final int    ROOT_BOTTOM    = -58;  // deepslate floor
    private static final int    DEEPSLATE_TOP  = -8;   // Y where deepslate starts dominating
    private static final double ROOT_MIN_RH    = 48.0; // only islands larger than this get roots

    // ── Resource keys ─────────────────────────────────────────────────────────
    private static final ResourceLocation RL_ISLANDS = ResourceLocation.fromNamespaceAndPath("islandsmod", "islands");
    private static final ResourceLocation RL_TERRAIN  = ResourceLocation.fromNamespaceAndPath("islandsmod", "terrain_noise");

    // ── Solid underground layer (guarantees all deep structures are embedded) ────
    // Top surface is noise-warped so the ceiling looks organic, not flat.
    private static final int    UNDERGROUND_BASE  = 16;   // average top Y of underground

    // ── Ocean pool islands (flat islands with water basin for monuments) ───────
    // Pools are placed at the SAME grid positions as vanilla ocean monuments
    // (spacing=32 chunks, separation=5, salt=10387313) so every monument spawns inside a pool.
    private static final int    MONUMENT_SPACING    = 32;        // chunks
    private static final int    MONUMENT_SEPARATION = 5;         // chunks
    private static final long   MONUMENT_SALT       = 10387313L;
    private static final int    POOL_FLOOR_Y        = 38;   // stone base floor
    private static final int    POOL_RIM_TOP_Y      = 66;   // top of outer stone rim
    private static final int    POOL_WATER_BOT      = 40;   // water basin floor
    private static final int    POOL_WATER_TOP      = 62;   // water surface
    private static final double POOL_RIM_RADIUS     = 80.0; // outer rim half-width (wider for coverage)
    private static final double POOL_BASIN_RADIUS   = 40.0; // inner water zone half-width
    private static final double POOL_ROOT_FACTOR    = 0.82; // root top radius = rimRadius * this

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
    // BUILD SURFACE — clears vanilla terrain, places islands + surface + falls
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                              RandomState randomState, ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY   = chunk.getMinBuildHeight();
        int maxY   = chunk.getMaxBuildHeight();

        PositionalRandomFactory islandRand = randomState.getOrCreateRandomFactory(RL_ISLANDS);
        long noiseSeed  = randomState.getOrCreateRandomFactory(RL_TERRAIN).at(0, 0, 0).nextLong();
        long worldSeed  = randomState.legacyLevelSeed();

        List<IslandData>     islands = gatherNearbyIslands(startX + 8, startZ + 8, islandRand);

        // Pool islands: only build in ocean chunks to avoid orphaned platforms in deserts etc.
        // Safe biome query — current chunk only, never a distant pool-center position.
        Holder<Biome> chunkBiome = region.getNoiseBiome(
                (startX + 8) >> 2, 60 >> 2, (startZ + 8) >> 2);
        boolean isOceanChunk = chunkBiome.is(BiomeTags.IS_OCEAN)
                            || chunkBiome.is(BiomeTags.IS_DEEP_OCEAN);
        List<PoolIslandData> pools = isOceanChunk
                ? gatherNearbyPools(startX + 8, startZ + 8, worldSeed)
                : List.of();

        int[] topYCache = new int[16 * 16];
        Arrays.fill(topYCache, Integer.MIN_VALUE);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                // Erase everything fillFromNoise placed
                int vanillaTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz);
                for (int y = minY; y <= vanillaTop; y++) {
                    if (!chunk.getBlockState(new BlockPos(wx, y, wz)).isAir())
                        chunk.setBlockState(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), false);
                }

                // ── Solid underground layer (houses all deep structures) ──────
                // Top surface is noise-warped so the ceiling isn't a flat plane.
                double underNoise = fractalNoise2D(wx * 0.032, wz * 0.032, noiseSeed + 850L, 4);
                int underTop = UNDERGROUND_BASE + (int)(underNoise * 20.0) - 6; // ~Y 10..30
                for (int y = minY; y <= underTop; y++) {
                    BlockState block;
                    if (y < DEEPSLATE_TOP) {
                        block = Blocks.DEEPSLATE.defaultBlockState();
                    } else if (y < 8) {
                        double mix = smoothNoise2D(wx * 0.25 + y * 0.12, wz * 0.25, noiseSeed + 602L);
                        double t   = (y - DEEPSLATE_TOP) / (double)(8 - DEEPSLATE_TOP);
                        block = (mix < t) ? Blocks.STONE.defaultBlockState()
                                          : Blocks.DEEPSLATE.defaultBlockState();
                    } else {
                        block = Blocks.STONE.defaultBlockState();
                    }
                    chunk.setBlockState(new BlockPos(wx, y, wz), block, false);
                }

                // ── Flying island terrain ────────────────────────────────────
                int topY = fillIslandColumn(chunk, wx, wz, minY, maxY, islands, noiseSeed);
                topYCache[lx * 16 + lz] = topY;
                if (topY != Integer.MIN_VALUE) {
                    Holder<Biome> biome = region.getNoiseBiome(wx >> 2, topY >> 2, wz >> 2);
                    applySurfaceBlocks(chunk, wx, wz, topY, biome, noiseSeed);
                }

                // ── Ocean pool islands ────────────────────────────────────────
                for (PoolIslandData pool : pools)
                    fillPoolColumn(chunk, wx, wz, pool, noiseSeed);
            }
        }

        // Waterfall sources at exposed cliff edges
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
    // ISLAND COLUMN FILL
    // ═════════════════════════════════════════════════════════════════════════

    private int fillIslandColumn(ChunkAccess chunk, int wx, int wz,
                                  int minY, int maxY,
                                  List<IslandData> islands, long noiseSeed) {
        int columnTopY = Integer.MIN_VALUE;

        for (IslandData isl : islands) {

            // ── 1. Domain warping — distort sample coordinates ───────────────
            // This is the key to non-round shapes: the distance field is evaluated
            // at a noise-warped position rather than the real (wx, wz).
            double ws  = 0.030;          // spatial frequency of the warp field
            double wr  = isl.rh * 0.44; // max warp distance in blocks
            double swx = wx + (fractalNoise2D(wx * ws,       wz * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
            double swz = wz + (fractalNoise2D(wx * ws + 500, wz * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

            // ── 2. Radial noise — vary effective radius per angle ────────────
            double angle       = Math.atan2(wz - isl.cz, wx - isl.cx);
            double radialNoise = fractalNoise2D(
                    Math.cos(angle) * 4.5 + isl.cx * 0.006,
                    Math.sin(angle) * 4.5 + isl.cz * 0.006,
                    noiseSeed + 300L, 5);
            double effRadius = isl.rh * (0.58 + radialNoise * 0.68);

            // ── 3. Normalised distance in warped space ───────────────────────
            double wdx   = swx - isl.cx;
            double wdz   = swz - isl.cz;
            double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
            double normR = dist / effRadius;
            if (normR > 1.22) continue;

            // Shape value: 1 at centre, 0 at edge, irregular boundary
            double shape = Math.max(0.0, 1.0 - Math.pow(normR, 1.35));
            if (shape < 0.012) continue;

            // ── 4. Vertical extent of the main island body ───────────────────
            double topNoise = fractalNoise2D(wx * 0.09, wz * 0.09, noiseSeed + 400L, 3) * 0.32;
            double topYd    = isl.cy + isl.rv * shape * (1.0 + topNoise);

            double botNoise = fractalNoise2D(wx * 0.07 + 5000, wz * 0.07 + 5000, noiseSeed + 500L, 3) * 0.55;
            double bodyBotYd = isl.cy - isl.rv * 0.82 * Math.max(0.06, shape)
                             - isl.rv * botNoise * shape;

            int iTop     = Math.min((int) Math.round(topYd),    maxY - 1);
            int iBodyBot = (int) Math.round(bodyBotYd);

            // ── 5. Deep root — wide V-cone (see design screenshot) ──────────
            // rootTopRadius = the full radius at the body-bottom attachment point.
            // The cone tapers linearly to 0 at ROOT_BOTTOM, giving the V-shape.
            double realDx       = wx - isl.cx;
            double realDz       = wz - isl.cz;
            double realDist     = Math.sqrt(realDx * realDx + realDz * realDz);
            double rootTopRadius = isl.rh * 0.58; // 58% of island rh → wide V mouth
            boolean hasRoot      = isl.rh >= ROOT_MIN_RH && realDist < rootTopRadius;

            int iBot = hasRoot ? Math.max(ROOT_BOTTOM, minY)
                               : Math.max(iBodyBot, minY);
            if (iTop < iBot) continue;

            // ── 6. Place blocks ──────────────────────────────────────────────
            for (int y = iBot; y <= iTop; y++) {

                // Below the main body: only fill inside the V-cone
                if (y < iBodyBot) {
                    if (!hasRoot) continue;
                    // Linear V-taper: full rootTopRadius at body bottom → 0 at ROOT_BOTTOM
                    double rootProgress = (double)(iBodyBot - y) / (double)(iBodyBot - ROOT_BOTTOM);
                    // Slightly concave profile (slower start, faster end) for visual depth
                    double curRootRad = rootTopRadius * Math.pow(1.0 - rootProgress, 0.85);
                    // Organic edge noise so the boundary isn't perfectly smooth
                    double edgeNoise = fractalNoise2D(realDx * 0.06 + y * 0.02,
                                                      realDz * 0.06,
                                                      noiseSeed + 700L, 3) * 0.16 - 0.08;
                    curRootRad = Math.max(0.0, curRootRad * (1.0 + edgeNoise));
                    if (realDist > curRootRad) continue;
                }

                // Stone/deepslate transition matching vanilla depth
                BlockState block;
                if (y < DEEPSLATE_TOP) {
                    block = Blocks.DEEPSLATE.defaultBlockState();
                } else if (y < 8) {
                    // Gradient zone: mix deepslate and stone with noise
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
        Biome b = biome.value();
        float temp    = b.getBaseTemperature();
        boolean precip = b.hasPrecipitation();
        if (!precip && temp > 1.2f)  return Blocks.SAND.defaultBlockState();
        if (temp < 0.05f && precip)  return Blocks.SNOW_BLOCK.defaultBlockState();
        if (!precip && temp > 0.8f)  return Blocks.RED_SAND.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState chooseSurfaceUnder(Holder<Biome> biome) {
        Biome b = biome.value();
        if (!b.hasPrecipitation() && b.getBaseTemperature() > 1.2f)
            return Blocks.SAND.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CARVERS — vanilla cave carvers run after buildSurface and will naturally
    // carve tunnels through island stone; void areas are unaffected.
    // ═════════════════════════════════════════════════════════════════════════
    // (no override — fall through to NoiseBasedChunkGenerator.applyCarvers)

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
        double rv = rh * (0.22 + rng.nextDouble() * 0.38);

        IslandData primary = new IslandData(wx, baseY, wz, rh, rv);
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
        int satX  = (int)(parent.cx + Math.cos(angle) * dist);
        int satZ  = (int)(parent.cz + Math.sin(angle) * dist);
        int satY  = parent.cy + rng.nextInt(51) - 25;
        double satRH = parent.rh * (0.28 + rng.nextDouble() * 0.52);
        double satRV = satRH * (0.20 + rng.nextDouble() * 0.40);
        return new IslandData(satX, satY, satZ, satRH, satRV);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private int approximateTopY(int x, int z, IslandData isl, long noiseSeed) {
        // Must mirror the domain warp + radial noise from fillIslandColumn
        double ws  = 0.030;
        double wr  = isl.rh * 0.44;
        double swx = x + (fractalNoise2D(x * ws,       z * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
        double swz = z + (fractalNoise2D(x * ws + 500, z * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

        double angle       = Math.atan2(z - isl.cz, x - isl.cx);
        double radialNoise = fractalNoise2D(
                Math.cos(angle) * 4.5 + isl.cx * 0.006,
                Math.sin(angle) * 4.5 + isl.cz * 0.006,
                noiseSeed + 300L, 5);
        double effRadius = isl.rh * (0.58 + radialNoise * 0.68);

        double wdx   = swx - isl.cx;
        double wdz   = swz - isl.cz;
        double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
        double normR = dist / effRadius;
        if (normR > 1.22) return Integer.MIN_VALUE;

        double shape = Math.max(0.0, 1.0 - Math.pow(normR, 1.35));
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
    // OCEAN POOL ISLANDS — flat stone-rimmed islands with a water basin placed
    // in ocean biomes.  Ocean monuments that attempt to generate in ocean biomes
    // will find this water body and embed in it naturally.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns pool islands co-located with vanilla ocean monument spawn points.
     * Replicates WorldgenRandom.setLargeFeatureWithSalt + LegacyRandomSource exactly:
     *   rawSeed = regionX*341873128712 + regionZ*132897987541 + worldSeed + salt
     *   java.util.Random(rawSeed) gives the same LCG state.
     */
    private List<PoolIslandData> gatherNearbyPools(int wx, int wz, long worldSeed) {
        List<PoolIslandData> result = new ArrayList<>();
        int cx = wx >> 4;
        int cz = wz >> 4;
        int regionX = Math.floorDiv(cx, MONUMENT_SPACING);
        int regionZ = Math.floorDiv(cz, MONUMENT_SPACING);
        // Search radius: pool radius in chunks + 1 safety cell
        int searchCells = (int)Math.ceil(POOL_RIM_RADIUS / (MONUMENT_SPACING * 16.0)) + 2;

        for (int dx = -searchCells; dx <= searchCells; dx++) {
            for (int dz = -searchCells; dz <= searchCells; dz++) {
                int rx = regionX + dx;
                int rz = regionZ + dz;
                // Reproduce vanilla RandomSpreadStructurePlacement seed derivation
                long rawSeed = (long)rx * 341873128712L
                             + (long)rz * 132897987541L
                             + worldSeed + MONUMENT_SALT;
                java.util.Random rng = new java.util.Random(rawSeed);
                int range    = MONUMENT_SPACING - MONUMENT_SEPARATION; // 27 chunks
                int structCX = rx * MONUMENT_SPACING + rng.nextInt(range);
                int structCZ = rz * MONUMENT_SPACING + rng.nextInt(range);

                int poolWX = structCX * 16 + 8;
                int poolWZ = structCZ * 16 + 8;

                double dist = Math.hypot(wx - poolWX, wz - poolWZ);
                if (dist > POOL_RIM_RADIUS * 1.5 + 32) continue;

                result.add(new PoolIslandData(poolWX, poolWZ, POOL_RIM_RADIUS, POOL_BASIN_RADIUS));
            }
        }
        return result;
    }

    /** Fills one column of an ocean pool island: stone rim + water basin + V-shaped root. */
    private boolean fillPoolColumn(ChunkAccess chunk, int wx, int wz,
                                    PoolIslandData pool, long noiseSeed) {
        double dx   = wx - pool.cx();
        double dz   = wz - pool.cz();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Organic rim boundary
        double rimNoise = fractalNoise2D(wx * 0.038, wz * 0.038, noiseSeed + 910L, 3) * 0.18 - 0.09;
        double effRim   = pool.rimRadius() * (1.0 + rimNoise);
        double rootTopRadius = pool.rimRadius() * POOL_ROOT_FACTOR;

        boolean inRim   = dist <= effRim;
        boolean inRoot  = dist <= rootTopRadius * 1.12; // slight overreach for noise room
        if (!inRim && !inRoot) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // ── Above-ground body: stone rim + water basin ─────────────────────────
        if (inRim) {
            boolean inBasin = dist <= pool.basinRadius();
            int stoneTop = inBasin ? POOL_WATER_BOT - 1 : POOL_RIM_TOP_Y;
            for (int y = POOL_FLOOR_Y; y <= stoneTop; y++) {
                if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) continue;
                pos.set(wx, y, wz);
                if (chunk.getBlockState(pos).isAir())
                    chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
            }
            if (inBasin) {
                for (int y = POOL_WATER_BOT; y <= POOL_WATER_TOP; y++) {
                    if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) continue;
                    pos.set(wx, y, wz);
                    if (chunk.getBlockState(pos).isAir())
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                }
            }
        }

        // ── V-cone root below pool floor ──────────────────────────────────────
        // Same taper logic as island roots: wide mouth at POOL_FLOOR_Y, tip at ROOT_BOTTOM.
        if (inRoot) {
            int rootBot = Math.max(ROOT_BOTTOM, chunk.getMinBuildHeight());
            for (int y = rootBot; y < POOL_FLOOR_Y; y++) {
                double rootProgress = (double)(POOL_FLOOR_Y - y) / (double)(POOL_FLOOR_Y - ROOT_BOTTOM);
                double curRootRad   = rootTopRadius * Math.pow(1.0 - rootProgress, 0.85);
                double edgeNoise    = fractalNoise2D(dx * 0.06 + y * 0.02, dz * 0.06,
                                                     noiseSeed + 920L, 3) * 0.16 - 0.08;
                curRootRad = Math.max(0.0, curRootRad * (1.0 + edgeNoise));
                if (dist > curRootRad) continue;

                if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) continue;
                pos.set(wx, y, wz);
                if (!chunk.getBlockState(pos).isAir()) continue;

                BlockState block;
                if (y < DEEPSLATE_TOP) {
                    block = Blocks.DEEPSLATE.defaultBlockState();
                } else if (y < 8) {
                    double mix = smoothNoise2D(wx * 0.25 + y * 0.12, wz * 0.25, noiseSeed + 601L);
                    double t   = (y - DEEPSLATE_TOP) / (double)(8 - DEEPSLATE_TOP);
                    block = (mix < t) ? Blocks.STONE.defaultBlockState()
                                      : Blocks.DEEPSLATE.defaultBlockState();
                } else {
                    block = Blocks.STONE.defaultBlockState();
                }
                chunk.setBlockState(pos, block, false);
            }
        }

        return inRim;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DATA
    // ═════════════════════════════════════════════════════════════════════════

    private record IslandData(int cx, int cy, int cz, double rh, double rv) {}
    private record PoolIslandData(int cx, int cz, double rimRadius, double basinRadius) {}
}
