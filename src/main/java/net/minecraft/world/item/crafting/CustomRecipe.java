package net.minecraft.world.item.crafting;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public abstract class CustomRecipe implements CraftingRecipe {

    private final ResourceLocation id;
    private final CraftingBookCategory category;

    public CustomRecipe(ResourceLocation id, CraftingBookCategory category) {
        this.id = id;
        this.category = category;
    }

    @Override
    public ResourceLocation getId() {
        return this.id;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryManager) {
        return ItemStack.EMPTY;
    }

    @Override
    public CraftingBookCategory category() {
        return this.category;
    }

    // CraftBukkit start
    @Override
    public org.bukkit.inventory.Recipe toBukkitRecipe() {
        return new org.bukkit.craftbukkit.inventory.CraftComplexRecipe(this);
    }
    // CraftBukkit end
}
