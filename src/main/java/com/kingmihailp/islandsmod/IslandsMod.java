package com.kingmihailp.islandsmod;

import com.kingmihailp.islandsmod.event.PlayerSpawnHandler;
import com.kingmihailp.islandsmod.init.ModBlocks;
import com.kingmihailp.islandsmod.init.ModItems;
import com.kingmihailp.islandsmod.init.ModWorldgen;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(IslandsMod.MODID)
public class IslandsMod {

    public static final String MODID = "islandsmod";

    public IslandsMod(IEventBus modEventBus) {
        ModWorldgen.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        modEventBus.addListener(this::addCreativeItems);
        NeoForge.EVENT_BUS.register(PlayerSpawnHandler.class);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.POCKET_DUNGEON.get());
            event.accept(ModItems.POCKET_ANCIENT_CITY.get());
            event.accept(ModItems.JACKHAMMER.get());
            event.accept(ModItems.WARPED_LURE.get());
            event.accept(ModItems.PORTAL_CATALYST.get());
            event.accept(ModItems.POCKET_END_FORTRESS.get());
            event.accept(ModItems.CATALYST_LOCATOR.get());
            event.accept(ModItems.CONFETTI_CANNON.get());
        }
    }
}
