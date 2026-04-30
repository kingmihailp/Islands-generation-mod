package com.kingmihailp.islandsmod.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class WarpedLureItem extends Item {

    private static final ResourceLocation WARPED_MOSCO_ID =
            ResourceLocation.fromNamespaceAndPath("alexsmobs", "warped_mosco");
    private static final double SPAWN_HEALTH = 500.0;

    public WarpedLureItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            ServerLevel serverLevel = (ServerLevel) level;
            var entityTypeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(WARPED_MOSCO_ID);
            if (entityTypeOpt.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("item.islandsmod.warped_lure.mod_missing"), true);
                return InteractionResultHolder.fail(stack);
            }
            var entity = entityTypeOpt.get().create(serverLevel);
            if (entity instanceof Mob mob) {
                double yaw = Math.toRadians(player.getYRot());
                mob.moveTo(
                        player.getX() - Math.sin(yaw) * 3,
                        player.getY(),
                        player.getZ() + Math.cos(yaw) * 3,
                        player.getYRot(), 0.0f);
                mob.finalizeSpawn(serverLevel,
                        serverLevel.getCurrentDifficultyAt(mob.blockPosition()),
                        MobSpawnType.MOB_SUMMONED, null);
                var maxHealth = mob.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(SPAWN_HEALTH);
                }
                mob.setHealth((float) SPAWN_HEALTH);
                serverLevel.addFreshEntity(mob);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
