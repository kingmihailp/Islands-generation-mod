package com.kingmihailp.islandsmod.item;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ConfettiCannonItem extends Item {

    private static final ResourceLocation RL_CONFETTI =
            ResourceLocation.fromNamespaceAndPath("supplementaries", "confetti");
    private static final int COOLDOWN_TICKS = 40;

    public ConfettiCannonItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.getAbilities().instabuild) {
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        ServerLevel serverLevel = (ServerLevel) level;
        var particleTypeOpt = BuiltInRegistries.PARTICLE_TYPE.getOptional(RL_CONFETTI);
        if (particleTypeOpt.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("item.islandsmod.confetti_cannon.mod_missing"), true);
            return InteractionResultHolder.fail(stack);
        }

        var particleType = particleTypeOpt.get();
        if (!(particleType instanceof ParticleOptions opts)) {
            return InteractionResultHolder.fail(stack);
        }

        double x = player.getX();
        double y = player.getY() + 1.0;
        double z = player.getZ();

        // Two bursts: a tight upward explosion + a wide scattered halo
        serverLevel.sendParticles(opts, x, y, z, 1500, 50.0, 25.0, 50.0, 1.2);
        serverLevel.sendParticles(opts, x, y + 10.0, z, 800, 20.0, 15.0, 20.0, 0.5);

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
