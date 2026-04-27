package com.kingmihailp.islandsmod.init;

import com.kingmihailp.islandsmod.IslandsMod;
import com.kingmihailp.islandsmod.item.PocketDungeonItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, IslandsMod.MODID);

    public static final DeferredHolder<Item, PocketDungeonItem> POCKET_DUNGEON =
            ITEMS.register("pocket_dungeon",
                    () -> new PocketDungeonItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
