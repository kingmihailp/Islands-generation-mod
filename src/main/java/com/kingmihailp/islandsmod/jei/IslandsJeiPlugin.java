package com.kingmihailp.islandsmod.jei;

import com.kingmihailp.islandsmod.init.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

@JeiPlugin
public class IslandsJeiPlugin implements IModPlugin {

    public static final RecipeType<JackhammerConversionRecipe> JACKHAMMER_TYPE =
            RecipeType.create("islandsmod", "jackhammer_conversion", JackhammerConversionRecipe.class);

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("islandsmod", "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(
                new JackhammerCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("tfmg", "oil_deposit"))
                .ifPresent(oil -> registration.addRecipes(JACKHAMMER_TYPE, List.of(
                        new JackhammerConversionRecipe(
                                new ItemStack(Blocks.BEDROCK),
                                new ItemStack(oil)))));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(ModItems.JACKHAMMER.get()), JACKHAMMER_TYPE);
    }
}
