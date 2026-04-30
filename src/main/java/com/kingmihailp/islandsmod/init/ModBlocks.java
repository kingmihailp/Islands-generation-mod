package com.kingmihailp.islandsmod.init;

import com.kingmihailp.islandsmod.IslandsMod;
import com.kingmihailp.islandsmod.block.PortalCatalystBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, IslandsMod.MODID);

    // Hardness 640 → 2 minutes with an unenchanted diamond pickaxe (30 * 640 / 8 = 2400 ticks)
    public static final DeferredHolder<Block, PortalCatalystBlock> PORTAL_CATALYST =
            BLOCKS.register("portal_catalyst",
                    () -> new PortalCatalystBlock(Block.Properties.of()
                            .strength(640.0f, 1200.0f)
                            .requiresCorrectToolForDrops()
                            .lightLevel(state -> 7)
                            .sound(SoundType.DEEPSLATE)));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
