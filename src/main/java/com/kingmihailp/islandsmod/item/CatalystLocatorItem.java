package com.kingmihailp.islandsmod.item;

import com.kingmihailp.islandsmod.worldgen.IslandChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class CatalystLocatorItem extends Item {

    private static final int COOLDOWN_TICKS = 100;
    private static final int SEARCH_RADIUS  = 8;

    public CatalystLocatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        BlockPos origin = player.blockPosition();
        BlockPos target = IslandChunkGenerator.findNearestCatalyst((ServerLevel) level, origin, SEARCH_RADIUS);

        if (target == null) {
            player.displayClientMessage(Component.translatable("item.islandsmod.catalyst_locator.not_found"), true);
        } else {
            int dx = target.getX() - origin.getX();
            int dz = target.getZ() - origin.getZ();
            double dist = Math.sqrt(dx * dx + (target.getY() - origin.getY()) * (double)(target.getY() - origin.getY()) + dz * (double) dz);
            String dir = cardinalDirection(dx, dz);
            player.displayClientMessage(
                Component.translatable("item.islandsmod.catalyst_locator.found",
                    target.getX(), target.getY(), target.getZ(),
                    (int) dist, dir),
                true);
        }

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static String cardinalDirection(int dx, int dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0) angle += 360.0;
        // atan2(z, x): 0=East, 90=South, 180=West, 270=North
        if (angle < 22.5 || angle >= 337.5) return "E";
        if (angle < 67.5)  return "SE";
        if (angle < 112.5) return "S";
        if (angle < 157.5) return "SW";
        if (angle < 202.5) return "W";
        if (angle < 247.5) return "NW";
        if (angle < 292.5) return "N";
        return "NE";
    }
}
