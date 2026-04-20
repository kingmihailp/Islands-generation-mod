package com.kingmihailp.islandsmod.init;

import com.kingmihailp.islandsmod.IslandsMod;
import com.kingmihailp.islandsmod.worldgen.IslandChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModWorldgen {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(BuiltInRegistries.CHUNK_GENERATOR, IslandsMod.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<IslandChunkGenerator>>
            ISLAND_GENERATOR = CHUNK_GENERATORS.register("island_generator", () -> IslandChunkGenerator.CODEC);

    public static void register(IEventBus modEventBus) {
        CHUNK_GENERATORS.register(modEventBus);
    }
}
