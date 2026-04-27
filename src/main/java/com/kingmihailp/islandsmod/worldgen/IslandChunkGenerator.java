package com.kingmihailp.islandsmod.worldgen;

import com.kingmihailp.islandsmod.init.ModWorldgen;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
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
    private static final ResourceLocation RL_ISLANDS    = ResourceLocation.fromNamespaceAndPath("islandsmod", "islands");
    private static final ResourceLocation RL_TERRAIN    = ResourceLocation.fromNamespaceAndPath("islandsmod", "terrain_noise");

    // ── Structures that must never receive an island or platform ──────────────
    // skyvillages:skyvillage already floats; an island beneath it is unwanted.
    private static final ResourceLocation RL_SKYVILLAGE = ResourceLocation.fromNamespaceAndPath("skyvillages", "skyvillage");

    // ── Cardinal directions ───────────────────────────────────────────────────
    private static final int[][] DIRS4 = {{1,0},{-1,0},{0,1},{0,-1}};

    // ── Structure platform descriptor ─────────────────────────────────────────
    // floorY = island surface at the structure's spawn point (reference chunk
    // centre), which is exactly the Y getBaseHeight() returned when the structure
    // was placed.  Using bb.minY() or BB-centre samples is wrong for large
    // structures whose spawn point is offset from the geometric BB centre.
    private record PlatformDef(BoundingBox bb, int floorY) {}

    // Carries BB + the reference-chunk spawn point so buildSurface can query
    // approximateTopY() at the right location instead of the BB centre.
    // id may be null for structures not found in the registry (treated as unknown).
    private record StructureInfo(BoundingBox bb, int spawnX, int spawnZ, ResourceLocation id) {}

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

        List<IslandData>   islands        = gatherNearbyIslands(startX + 8, startZ + 8, islandRand);
        List<StructureInfo> structureInfos = gatherStructurePlatforms(chunk, structureManager, region);

        // Build PlatformDef list for surface structures only (bb.minY >= 20).
        // Underground structures (trial chambers, ancient cities) are excluded — their
        // large BBs would punch rectangular holes through islands.
        //
        // floorY is sampled at the structure's REFERENCE-CHUNK CENTRE (spawnX/spawnZ),
        // which is exactly where getBaseHeight() was queried when the structure was
        // placed.  Previous approaches (BB centre, 5-point cross) failed for large
        // structures (villages, mansions) whose spawn point is far from the BB centre.
        List<PlatformDef> surfacePlatforms = new ArrayList<>();
        List<IslandData>  augmentedIslands = new ArrayList<>(islands);

        for (StructureInfo si : structureInfos) {
            BoundingBox bb     = si.bb();
            // Skip structures whose entire BB sits below Y=30: these are true
            // underground structures (strongholds, modded cave dungeons).
            // Filtering on bb.maxY() instead of bb.minY() lets modded surface
            // structures through even when they have deep foundations (minY < 20).
            // Trial chambers and ancient cities are already disabled via JSON, so
            // the earlier bb.minY() guard is no longer needed.
            if (bb.maxY() < 30) continue;
            // Sky-floating structures must not receive an island or platform.
            if (RL_SKYVILLAGE.equals(si.id())) continue;
            int spawnX = si.spawnX();
            int spawnZ = si.spawnZ();

            // Sample the island surface at the exact spawn point.
            int floorY = Integer.MIN_VALUE;
            for (IslandData isl : islands) {
                int t = approximateTopY(spawnX, spawnZ, isl, noiseSeed);
                if (t > floorY) floorY = t;
            }

            if (floorY == Integer.MIN_VALUE) {
                // Void structure — getBaseHeight returned the fallback (63 for
                // WORLD_SURFACE_WG), so the structure floor sits at Y=63.
                // Using bb.minY() would be wrong: bb.minY() < 63 for any structure
                // with underground foundations, producing a gap below the floor.
                floorY = 63;
                // Size the island to cover the BB from the spawn-point perspective.
                double R_bb_x = Math.max(spawnX - bb.minX(), bb.maxX() - spawnX);
                double R_bb_z = Math.max(spawnZ - bb.minZ(), bb.maxZ() - spawnZ);
                double R_bb   = Math.max(Math.max(R_bb_x, R_bb_z), 12.0);
                double rh  = Math.max(R_bb * 1.7, 24.0);
                double rv  = Math.max(R_bb * 0.15, 12.0);
                int    vcy = floorY - (int)(rv * 0.5);
                augmentedIslands.add(new IslandData(spawnX, vcy, spawnZ, rh, rv,
                        0.3, 2.0, 0.85, 0.15, 0.8,
                        0.32, 0.55, 1.0, 1.0, 0.0));
            } else {
                floorY = Math.max(floorY, bb.minY());
            }
            surfacePlatforms.add(new PlatformDef(bb, floorY));
        }

        // Islands whose center biome is ocean get a central lake carved in them.
        // Ocean pool islands must stay circular (stretchX=stretchZ=1.0) so the
        // lake radius makes sense relative to the island footprint.  Replace any
        // stretched ocean island in augmentedIslands before Pass 1 runs.
        Climate.Sampler climateSampler = randomState.sampler();
        List<IslandData> oceanIslands = new ArrayList<>();
        for (int i = 0; i < islands.size(); i++) {
            IslandData isl = augmentedIslands.get(i);
            Holder<Biome> b = biomeSource.getNoiseBiome(isl.cx() >> 2, isl.cy() >> 2, isl.cz() >> 2, climateSampler);
            if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN)) {
                IslandData circular = new IslandData(isl.cx(), isl.cy(), isl.cz(), isl.rh(), isl.rv(),
                        isl.warpF(), isl.exp(), isl.radBase(), isl.radRange(), isl.botF(),
                        isl.topAmp(), isl.botAmp(), 1.0, 1.0, isl.warpAngle());
                augmentedIslands.set(i, circular);
                oceanIslands.add(circular);
            }
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

                int islandTop   = fillIslandColumn(chunk, wx, wz, minY, maxY, augmentedIslands, noiseSeed, surfacePlatforms);
                int platformTop = fillStructurePlatformColumn(chunk, wx, wz, minY, maxY, surfacePlatforms, noiseSeed);
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
                                  List<IslandData> islands, long noiseSeed,
                                  List<PlatformDef> noBuildZones) {
        int columnTopY = Integer.MIN_VALUE;

        for (IslandData isl : islands) {

            // ── 1. Domain warping (rotated noise input for unique grain per island) ──
            double ws   = 0.030;
            double wr   = isl.rh * isl.warpF;
            double cosA = Math.cos(isl.warpAngle), sinA = Math.sin(isl.warpAngle);
            double wxi  = wx * cosA - wz * sinA;
            double wzi  = wx * sinA + wz * cosA;
            double swx  = wx + (fractalNoise2D(wxi * ws,       wzi * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
            double swz  = wz + (fractalNoise2D(wxi * ws + 500, wzi * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

            // ── 2. Radial noise ──────────────────────────────────────────────
            double angle       = Math.atan2(wz - isl.cz, wx - isl.cx);
            double radialNoise = fractalNoise2D(
                    Math.cos(angle) * 4.5 + isl.cx * 0.006,
                    Math.sin(angle) * 4.5 + isl.cz * 0.006,
                    noiseSeed + 300L, 5);
            double effRadius = isl.rh * (isl.radBase + radialNoise * isl.radRange);

            // ── 3. Normalised distance in warped + elliptical space ──────────
            double wdx   = (swx - isl.cx) / isl.stretchX;
            double wdz   = (swz - isl.cz) / isl.stretchZ;
            double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
            double normR = dist / effRadius;
            if (normR > 1.22) continue;

            double shape = Math.max(0.0, 1.0 - Math.pow(normR, isl.exp));
            if (shape < 0.012) continue;

            // ── 4. Vertical extent ───────────────────────────────────────────
            double topNoise  = fractalNoise2D(wx * 0.09, wz * 0.09, noiseSeed + 400L, 3) * isl.topAmp;
            double topYd     = isl.cy + isl.rv * shape * (1.0 + topNoise);

            double botNoise  = fractalNoise2D(wx * 0.07 + 5000, wz * 0.07 + 5000, noiseSeed + 500L, 3) * isl.botAmp;
            double bodyBotYd = isl.cy - isl.rv * isl.botF * Math.max(0.06, shape)
                             - isl.rv * botNoise * shape;

            int iTop     = Math.min((int) Math.round(topYd),    maxY - 1);
            int iBodyBot = (int) Math.round(bodyBotYd);
            int iBot     = Math.max(iBodyBot, minY);
            if (iTop < iBot) continue;

            // ── 5. Place blocks ──────────────────────────────────────────────
            // Blocks inside structure bounding boxes are skipped entirely so
            // that structure templates can place their own air/blocks in
            // applyBiomeDecoration without island stone creating holes.
            for (int y = iBot; y <= iTop; y++) {
                // Skip blocks strictly ABOVE the structure floor inside the BB.
                // Allowing the island to fill up to floorY (inclusive) gives the
                // structure organic terrain beneath it; blocking only y > floorY
                // preserves the empty space the structure template needs for its
                // walls, rooms, and air blocks above floor level.
                boolean inBB = false;
                for (PlatformDef pd : noBuildZones) {
                    if (wx >= pd.bb().minX() && wx <= pd.bb().maxX()
                            && wz >= pd.bb().minZ() && wz <= pd.bb().maxZ()
                            && y  >  pd.floorY()
                            && y  <= pd.bb().maxY()) {
                        inBB = true;
                        break;
                    }
                }
                if (inBB) continue;

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
                if (y > columnTopY) columnTopY = y;
            }
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
        //   OCEAN_FLOOR_* → 62  (just below sea_level 63; satisfies the validator
        //                        check "floor < sea_level" while placing shipwrecks
        //                        close to island surface instead of 15-20 blocks below)
        //   everything else → 63 (sea surface / terrain surface)
        if (highest != Integer.MIN_VALUE) return highest;
        boolean isFloor = (types == Heightmap.Types.OCEAN_FLOOR_WG
                        || types == Heightmap.Types.OCEAN_FLOOR);
        return isFloor ? 62 : 63;
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
                randomWarpF(rng), randomExp(rng), randomRadBase(rng), randomRadRange(rng), randomBotF(rng),
                randomTopAmp(rng), randomBotAmp(rng), randomStretchX(rng), randomStretchZ(rng), randomWarpAngle(rng));
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
                randomWarpF(rng), randomExp(rng), randomRadBase(rng), randomRadRange(rng), randomBotF(rng),
                randomTopAmp(rng), randomBotAmp(rng), randomStretchX(rng), randomStretchZ(rng), randomWarpAngle(rng));
    }

    // ── Per-parameter random ranges ───────────────────────────────────────────
    private static double randomRv       (double rh, RandomSource r) { return rh * (0.03 + r.nextDouble() * 0.75); }
    private static double randomWarpF    (RandomSource r) { return 0.10 + r.nextDouble() * 0.75; }
    private static double randomExp      (RandomSource r) { return 0.45 + r.nextDouble() * 6.55; }
    private static double randomRadBase  (RandomSource r) { return 0.42 + r.nextDouble() * 0.43; }
    private static double randomRadRange (RandomSource r) { return 0.12 + r.nextDouble() * 1.28; }
    private static double randomBotF     (RandomSource r) { return 0.04 + r.nextDouble() * 1.46; }
    private static double randomTopAmp   (RandomSource r) { return 0.05 + r.nextDouble() * 0.50; }
    private static double randomBotAmp   (RandomSource r) { return 0.10 + r.nextDouble() * 0.80; }
    private static double randomStretchX (RandomSource r) { return 0.55 + r.nextDouble() * 1.25; }
    private static double randomStretchZ (RandomSource r) { return 0.55 + r.nextDouble() * 1.25; }
    private static double randomWarpAngle(RandomSource r) { return r.nextDouble() * 2.0 * Math.PI; }

    // ═════════════════════════════════════════════════════════════════════════
    // STRUCTURE PLATFORMS — organic contour island around every structure
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Collects bounding boxes of all structures relevant to this chunk.
     * Primary path: scans chunk starts in a ±3-chunk neighbourhood so the
     * contour pad generates seamlessly even in chunks that are adjacent to a
     * structure BB but carry no reference.  Fallback path: StructureManager
     * section-Y scan for large/deep structures whose start chunk is farther away.
     */
    private List<StructureInfo> gatherStructurePlatforms(ChunkAccess chunk,
                                                          StructureManager structureManager,
                                                          WorldGenRegion region) {
        // Use a LinkedHashSet for automatic deduplication — the same structure
        // start is discovered multiple times when the primary radius is large.
        // StructureInfo is a record so equals/hashCode cover all three fields.
        java.util.Set<StructureInfo> seen = new java.util.LinkedHashSet<>();
        ChunkPos cp0 = chunk.getPos();
        var structReg = region.registryAccess().registryOrThrow(Registries.STRUCTURE);

        // Primary: scan all chunk starts within an 8-chunk radius.
        // Large structures (villages, mansions) have BBs up to 200×200 blocks
        // (≈13 chunks).  The start chunk can be up to ~8 chunks from any BB
        // edge, so ±3 was insufficient and caused the contour/interior to be
        // cut off at chunk lines far from the structure centre.
        for (int dcx = -8; dcx <= 8; dcx++) {
            for (int dcz = -8; dcz <= 8; dcz++) {
                try {
                    ChunkAccess nbr = region.getChunk(cp0.x + dcx, cp0.z + dcz);
                    if (nbr == null) continue;
                    // getAllStarts() returns starts anchored in THIS chunk, so
                    // nbr.getPos() IS the structure's reference chunk.
                    int sx = nbr.getPos().getMinBlockX() + 8;
                    int sz = nbr.getPos().getMinBlockZ() + 8;
                    for (StructureStart s : nbr.getAllStarts().values()) {
                        if (s.isValid()) seen.add(new StructureInfo(s.getBoundingBox(), sx, sz,
                                structReg.getKey(s.getStructure())));
                    }
                } catch (Exception ignored) {}
            }
        }

        // Fallback: StructureManager reference lookup for underground/very large
        // structures whose start chunk may still be outside the ±8 primary scan
        // (trial chambers, ancient cities).  Scans every section Y.
        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
            Structure structure = entry.getKey();
            ResourceLocation structId = structReg.getKey(structure);
            for (long packed : entry.getValue().toLongArray()) {
                try {
                    // packed encodes the reference chunk position.
                    ChunkPos cp = new ChunkPos(packed);
                    int sx = cp.getMinBlockX() + 8;
                    int sz = cp.getMinBlockZ() + 8;
                    boolean found = false;
                    for (int sy = -5; sy <= 20 && !found; sy++) {
                        for (StructureStart s : structureManager.startsForStructure(SectionPos.of(cp, sy), structure)) {
                            if (s.isValid()) {
                                seen.add(new StructureInfo(s.getBoundingBox(), sx, sz, structId));
                                found = true;
                            }
                        }
                    }
                    if (!found) {
                        try {
                            ChunkAccess startChunk = region.getChunk(cp.x, cp.z);
                            if (startChunk != null) {
                                StructureStart s = startChunk.getAllStarts().get(structure);
                                if (s != null && s.isValid())
                                    seen.add(new StructureInfo(s.getBoundingBox(), sx, sz, structId));
                            }
                        } catch (Exception ignored2) {}
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Generates the island base for a surface structure.
     *
     * Uses a rounded-rectangle signed distance function (SDF) instead of a hard
     * rectangular interior/exterior split.  The SDF naturally produces rounded
     * corners whose radius scales with the BB size (25 % of the smaller half-
     * dimension, capped at 14 blocks).  Additional fractal noise shifts the
     * rounded boundary so the silhouette is organic rather than geometric.
     *
     * sdf < 0 (inside rounded rect, after noise): full fill from bb.minY to floorY.
     * sdf ≥ 0 (exterior contour zone):            tapered shelf that slopes from
     *          floorY at the rounded edge down to floorY-6 at effectivePad distance.
     */
    private int fillStructurePlatformColumn(ChunkAccess chunk, int wx, int wz,
                                             int minY, int maxY,
                                             List<PlatformDef> platforms, long noiseSeed) {
        int columnTopY = Integer.MIN_VALUE;

        for (PlatformDef pd : platforms) {
            BoundingBox bb     = pd.bb();
            int         floorY = pd.floorY();

            double bbCX    = (bb.minX() + bb.maxX()) * 0.5;
            double bbCZ    = (bb.minZ() + bb.maxZ()) * 0.5;
            double halfBBW = (bb.maxX() - bb.minX()) * 0.5;
            double halfBBD = (bb.maxZ() - bb.minZ()) * 0.5;

            // Corner rounding radius: 25 % of the shorter half-dimension, capped.
            // Small structures get subtle rounding; large ones (villages, mansions)
            // get up to 14-block rounded corners.
            double cornerR = Math.min(Math.min(halfBBW, halfBBD) * 0.25, 14.0);

            // Rounded-rectangle SDF — equivalent to Minkowski sum of a rectangle
            // and a disk of radius cornerR.
            //   sdf < 0  →  inside the rounded rectangle
            //   sdf = 0  →  on the rounded boundary
            //   sdf > 0  →  outside (contour zone)
            double qx  = Math.abs(wx - bbCX) - halfBBW + cornerR;
            double qz  = Math.abs(wz - bbCZ) - halfBBD + cornerR;
            double sdf = Math.min(Math.max(qx, qz), 0.0)
                       + Math.sqrt(Math.max(qx, 0.0) * Math.max(qx, 0.0)
                                 + Math.max(qz, 0.0) * Math.max(qz, 0.0))
                       - cornerR;

            // Organic exterior pad: angle-based noise gives a unique silhouette per
            // structure, position-based noise prevents chunk-boundary seams.
            double angle = Math.atan2(wz - bbCZ, wx - bbCX);
            double angN  = fractalNoise2D(
                    Math.cos(angle) * 4.0 + bbCX * 0.014,
                    Math.sin(angle) * 4.0 + bbCZ * 0.014,
                    noiseSeed + 9999L, 4);
            double posN  = fractalNoise2D(wx * 0.055, wz * 0.055, noiseSeed + 8888L, 3);
            double effectivePad = 12.0 * (0.42 + angN * 0.40 + posN * 0.18);

            // Shift the SDF boundary by fractal noise so the interior edge is also
            // rough rather than a perfect rounded rectangle.
            double edgeNoise  = fractalNoise2D(wx * 0.09, wz * 0.09, noiseSeed + 11111L, 3);
            double organicSdf = sdf + (edgeNoise * 2.0 - 1.0) * Math.min(cornerR * 0.5, 4.0);

            double extDist = Math.max(organicSdf, 0.0);
            if (extDist > effectivePad) continue;

            if (organicSdf < 0.0) {
                // ── Interior (inside rounded+noisy boundary) ──────────────────
                // Fill from the BB bottom to floorY so the structure floor is
                // fully supported.  fillIslandColumn already ran; isAir() skips
                // blocks that are already stone/deepslate.
                int topYi = floorY;
                if (topYi < minY + 1 || topYi >= maxY) continue;
                int botYi = Math.max(bb.minY(), minY);
                for (int y = botYi; y <= topYi; y++) {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    if (chunk.getBlockState(pos).isAir()) {
                        BlockState bs = y < DEEPSLATE_TOP
                                ? Blocks.DEEPSLATE.defaultBlockState()
                                : Blocks.STONE.defaultBlockState();
                        chunk.setBlockState(pos, bs, false);
                    }
                }
                if (topYi > columnTopY) columnTopY = topYi;
            } else {
                // ── Exterior contour ──────────────────────────────────────────
                double slopeFactor = extDist / effectivePad;
                double slopeNoise  = fractalNoise2D(wx * 0.08, wz * 0.08, noiseSeed + 6666L, 2);
                int topY = Math.max(
                        (int)(floorY - slopeFactor * (3.0 + slopeNoise * 3.0)),
                        minY + 1);
                if (topY >= maxY) continue;

                float  fade       = (float)(1.0 - slopeFactor);
                double noiseThick = fractalNoise2D(wx * 0.10, wz * 0.10, noiseSeed + 7777L, 3);
                int    thickness  = Math.max(1, (int)(fade * (4 + noiseThick * 4)));
                int    botY       = Math.max(topY - thickness, minY);

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
        }
        return columnTopY;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private int approximateTopY(int x, int z, IslandData isl, long noiseSeed) {
        double ws   = 0.030;
        double wr   = isl.rh * isl.warpF;
        double cosA = Math.cos(isl.warpAngle), sinA = Math.sin(isl.warpAngle);
        double wxi  = x * cosA - z * sinA;
        double wzi  = x * sinA + z * cosA;
        double swx  = x + (fractalNoise2D(wxi * ws,       wzi * ws,       noiseSeed + 100L, 4) * 2 - 1) * wr;
        double swz  = z + (fractalNoise2D(wxi * ws + 500, wzi * ws + 500, noiseSeed + 200L, 4) * 2 - 1) * wr;

        double angle       = Math.atan2(z - isl.cz, x - isl.cx);
        double radialNoise = fractalNoise2D(
                Math.cos(angle) * 4.5 + isl.cx * 0.006,
                Math.sin(angle) * 4.5 + isl.cz * 0.006,
                noiseSeed + 300L, 5);
        double effRadius = isl.rh * (isl.radBase + radialNoise * isl.radRange);

        double wdx   = (swx - isl.cx) / isl.stretchX;
        double wdz   = (swz - isl.cz) / isl.stretchZ;
        double dist  = Math.sqrt(wdx * wdx + wdz * wdz);
        double normR = dist / effRadius;
        if (normR > 1.22) return Integer.MIN_VALUE;

        double shape = Math.max(0.0, 1.0 - Math.pow(normR, isl.exp));
        if (shape < 0.012) return Integer.MIN_VALUE;

        double topNoise = fractalNoise2D(x * 0.09, z * 0.09, noiseSeed + 400L, 3) * isl.topAmp;
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
    // warpF    : domain-warp strength (0.10–0.85)
    // exp      : shape falloff exponent (0.45–7.0)  low=spike, high=flat disk
    // radBase  : base radial multiplier (0.42–0.85)
    // radRange : radial-noise amplitude (0.12–1.40)
    // botF     : bottom-extension factor (0.04–1.50)
    // topAmp   : top-surface noise amplitude (0.05–0.55)
    // botAmp   : bottom-surface noise amplitude (0.10–0.90)
    // stretchX : elliptical X-axis scale (0.55–1.80)
    // stretchZ : elliptical Z-axis scale (0.55–1.80)
    // warpAngle: rotation angle of domain-warp noise input (0–2π)
    private record IslandData(int cx, int cy, int cz, double rh, double rv,
                               double warpF, double exp, double radBase, double radRange, double botF,
                               double topAmp, double botAmp, double stretchX, double stretchZ, double warpAngle) {}
}
