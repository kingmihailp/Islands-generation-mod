package com.kingmihailp.islandsmod.item;

import com.kingmihailp.islandsmod.init.ModBlocks;
import com.kingmihailp.islandsmod.worldgen.PocketEndFortressStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class PocketEndFortressItem extends Item {

    public PocketEndFortressItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        var pos = context.getClickedPos();
        Player player = context.getPlayer();

        if (!level.getBlockState(pos).is(ModBlocks.PORTAL_CATALYST.get())) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide && player != null) {
            level.removeBlock(pos, false);
            PocketEndFortressStructure.generate((ServerLevel) level, pos);
            if (!player.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
