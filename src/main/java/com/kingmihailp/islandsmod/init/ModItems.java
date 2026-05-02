package com.kingmihailp.islandsmod.init;

import com.kingmihailp.islandsmod.IslandsMod;
import com.kingmihailp.islandsmod.item.JackhammerItem;
import com.kingmihailp.islandsmod.item.PocketAncientCityItem;
import com.kingmihailp.islandsmod.item.PocketDungeonItem;
import com.kingmihailp.islandsmod.item.CatalystLocatorItem;
import com.kingmihailp.islandsmod.item.ConfettiCannonItem;
import com.kingmihailp.islandsmod.item.PocketEndFortressItem;
import com.kingmihailp.islandsmod.item.WarpedLureItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, IslandsMod.MODID);

    public static final DeferredHolder<Item, PocketDungeonItem> POCKET_DUNGEON =
            ITEMS.register("pocket_dungeon",
                    () -> new PocketDungeonItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<Item, PocketAncientCityItem> POCKET_ANCIENT_CITY =
            ITEMS.register("pocket_ancient_city",
                    () -> new PocketAncientCityItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<Item, JackhammerItem> JACKHAMMER =
            ITEMS.register("jackhammer",
                    () -> new JackhammerItem(new Item.Properties().stacksTo(1).durability(512)));

    public static final DeferredHolder<Item, WarpedLureItem> WARPED_LURE =
            ITEMS.register("warped_lure",
                    () -> new WarpedLureItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredHolder<Item, BlockItem> PORTAL_CATALYST =
            ITEMS.register("portal_catalyst",
                    () -> new BlockItem(ModBlocks.PORTAL_CATALYST.get(),
                            new Item.Properties().rarity(Rarity.RARE)));

    public static final DeferredHolder<Item, PocketEndFortressItem> POCKET_END_FORTRESS =
            ITEMS.register("pocket_end_fortress",
                    () -> new PocketEndFortressItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredHolder<Item, CatalystLocatorItem> CATALYST_LOCATOR =
            ITEMS.register("catalyst_locator",
                    () -> new CatalystLocatorItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredHolder<Item, ConfettiCannonItem> CONFETTI_CANNON =
            ITEMS.register("confetti_cannon",
                    () -> new ConfettiCannonItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
