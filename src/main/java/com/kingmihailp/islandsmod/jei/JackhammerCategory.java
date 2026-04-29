package com.kingmihailp.islandsmod.jei;

import com.kingmihailp.islandsmod.init.ModItems;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class JackhammerCategory implements IRecipeCategory<JackhammerConversionRecipe> {

    private final IDrawable background;
    private final IDrawable icon;

    public JackhammerCategory(IGuiHelper guiHelper) {
        background = guiHelper.createBlankDrawable(76, 36);
        icon = guiHelper.createDrawableItemStack(new ItemStack(ModItems.JACKHAMMER.get()));
    }

    @Override
    public RecipeType<JackhammerConversionRecipe> getRecipeType() {
        return IslandsJeiPlugin.JACKHAMMER_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.islandsmod.jackhammer_conversion");
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, JackhammerConversionRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, 3, 10)
               .addItemStack(recipe.input());
        builder.addSlot(RecipeIngredientRole.OUTPUT, 55, 10)
               .addItemStack(recipe.output());
    }

    @Override
    public void draw(JackhammerConversionRecipe recipe, IRecipeSlotsView recipeSlotsView,
                     GuiGraphics guiGraphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, "×256", 22, 1, 0x808080, false);
        guiGraphics.drawString(font, "→", 27, 14, 0x404040, false);
    }
}
