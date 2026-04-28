package com.kingmihailp.islandsmod.item;

import com.kingmihailp.islandsmod.data.JackhammerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class JackhammerItem extends Item {

    private static final int HITS_TO_CONVERT = 256;
    private static final ResourceLocation OIL_DEPOSIT =
            ResourceLocation.fromNamespaceAndPath("tfmg", "oil_deposit");

    public JackhammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (!state.is(Blocks.BEDROCK)) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide) {
            ServerLevel serverLevel = (ServerLevel) level;
            Player player = context.getPlayer();

            level.playSound(null, pos, SoundEvents.STONE_HIT, SoundSource.BLOCKS,
                    1.0f, 0.7f + level.random.nextFloat() * 0.6f);

            serverLevel.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    12, 0.3, 0.3, 0.3, 0.0);

            ItemStack stack = context.getItemInHand();
            if (player != null && !player.getAbilities().instabuild) {
                stack.hurtAndBreak(1, player, p -> {});
            }

            JackhammerData data = JackhammerData.get(serverLevel);
            int total = data.addHit(pos);

            if (total >= HITS_TO_CONVERT) {
                data.remove(pos);
                Block oil = BuiltInRegistries.BLOCK.getOptional(OIL_DEPOSIT)
                        .orElse(Blocks.AIR);
                if (oil != Blocks.AIR) {
                    level.setBlock(pos, oil.defaultBlockState(), 3);
                }
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }
}
