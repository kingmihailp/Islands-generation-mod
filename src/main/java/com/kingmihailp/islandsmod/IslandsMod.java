package com.kingmihailp.islandsmod;

import com.kingmihailp.islandsmod.event.PlayerSpawnHandler;
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
        ModItems.register(modEventBus);
        modEventBus.addListener(this::addCreativeItems);
        NeoForge.EVENT_BUS.register(PlayerSpawnHandler.class);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.POCKET_DUNGEON.get());
            event.accept(ModItems.POCKET_ANCIENT_CITY.get());
        }
    }
}
