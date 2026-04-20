package com.kingmihailp.islandsmod;

import com.kingmihailp.islandsmod.event.PlayerSpawnHandler;
import com.kingmihailp.islandsmod.init.ModWorldgen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(IslandsMod.MODID)
public class IslandsMod {

    public static final String MODID = "islandsmod";

    public IslandsMod(IEventBus modEventBus) {
        ModWorldgen.register(modEventBus);
        NeoForge.EVENT_BUS.register(PlayerSpawnHandler.class);
    }
}
