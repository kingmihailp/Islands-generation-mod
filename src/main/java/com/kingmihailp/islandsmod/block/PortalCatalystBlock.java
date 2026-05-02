package com.kingmihailp.islandsmod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

public class PortalCatalystBlock extends Block {

    public PortalCatalystBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        for (int i = 0; i < 4; i++) {
            double x = pos.getX() + 0.1 + random.nextDouble() * 0.8;
            double y = pos.getY() + 0.8 + random.nextDouble() * 0.4;
            double z = pos.getZ() + 0.1 + random.nextDouble() * 0.8;
            double vx = (random.nextDouble() - 0.5) * 0.04;
            double vy = random.nextDouble() * 0.06 + 0.02;
            double vz = (random.nextDouble() - 0.5) * 0.04;
            level.addParticle(ParticleTypes.END_ROD, x, y, z, vx, vy, vz);
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                               @Nullable BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
        if (level.isClientSide) return;

        MobEffectInstance levitation = new MobEffectInstance(MobEffects.LEVITATION, 8 * 20, 1);
        player.addEffect(levitation);

        AABB box = new AABB(pos).inflate(12.0);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player);
        for (LivingEntity entity : nearby) {
            entity.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 8 * 20, 1));
        }
    }
}
