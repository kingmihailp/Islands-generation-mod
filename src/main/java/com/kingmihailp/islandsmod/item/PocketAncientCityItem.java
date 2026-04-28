package com.kingmihailp.islandsmod.item;

import com.kingmihailp.islandsmod.worldgen.PocketAncientCityStructure;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class PocketAncientCityItem extends Item {

    private static final ResourceLocation DEEP_DARK =
            ResourceLocation.fromNamespaceAndPath("minecraft", "deep_dark");

    public PocketAncientCityItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            if (!level.getBiome(player.blockPosition()).is(DEEP_DARK)) {
                player.displayClientMessage(
                        Component.translatable("item.islandsmod.pocket_ancient_city.wrong_biome"),
                        true);
                return InteractionResultHolder.fail(stack);
            }
            PocketAncientCityStructure.generate((ServerLevel) level, player);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
