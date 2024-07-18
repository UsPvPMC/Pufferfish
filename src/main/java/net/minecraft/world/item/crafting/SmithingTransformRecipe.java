package net.minecraft.world.item.crafting;

import com.google.gson.JsonObject;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmithingTransformRecipe;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

public class SmithingTransformRecipe implements SmithingRecipe {

    private final ResourceLocation id;
    final Ingredient template;
    final Ingredient base;
    final Ingredient addition;
    final ItemStack result;

    public SmithingTransformRecipe(ResourceLocation id, Ingredient template, Ingredient base, Ingredient addition, ItemStack result) {
        this.id = id;
        this.template = template;
        this.base = base;
        this.addition = addition;
        this.result = result;
    }

    @Override
    public boolean matches(Container inventory, Level world) {
        return this.template.test(inventory.getItem(0)) && this.base.test(inventory.getItem(1)) && this.addition.test(inventory.getItem(2));
    }

    @Override
    public ItemStack assemble(Container inventory, RegistryAccess registryManager) {
        ItemStack itemstack = this.result.copy();
        CompoundTag nbttagcompound = inventory.getItem(1).getTag();

        if (nbttagcompound != null) {
            itemstack.setTag(nbttagcompound.copy());
        }

        return itemstack;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryManager) {
        return this.result;
    }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return this.template.test(stack);
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return this.base.test(stack);
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return this.addition.test(stack);
    }

    @Override
    public ResourceLocation getId() {
        return this.id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return RecipeSerializer.SMITHING_TRANSFORM;
    }

    @Override
    public boolean isIncomplete() {
        return Stream.of(this.template, this.base, this.addition).anyMatch(Ingredient::isEmpty);
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe() {
        CraftItemStack result = CraftItemStack.asCraftMirror(this.result);

        CraftSmithingTransformRecipe recipe = new CraftSmithingTransformRecipe(CraftNamespacedKey.fromMinecraft(this.id), result, CraftRecipe.toBukkit(this.template), CraftRecipe.toBukkit(this.base), CraftRecipe.toBukkit(this.addition));

        return recipe;
    }
    // CraftBukkit end

    public static class Serializer implements RecipeSerializer<SmithingTransformRecipe> {

        public Serializer() {}

        @Override
        public SmithingTransformRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient recipeitemstack = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "template"));
            Ingredient recipeitemstack1 = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "base"));
            Ingredient recipeitemstack2 = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "addition"));
            ItemStack itemstack = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));

            return new SmithingTransformRecipe(id, recipeitemstack, recipeitemstack1, recipeitemstack2, itemstack);
        }

        @Override
        public SmithingTransformRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient recipeitemstack = Ingredient.fromNetwork(buf);
            Ingredient recipeitemstack1 = Ingredient.fromNetwork(buf);
            Ingredient recipeitemstack2 = Ingredient.fromNetwork(buf);
            ItemStack itemstack = buf.readItem();

            return new SmithingTransformRecipe(id, recipeitemstack, recipeitemstack1, recipeitemstack2, itemstack);
        }

        public void toNetwork(FriendlyByteBuf buf, SmithingTransformRecipe recipe) {
            recipe.template.toNetwork(buf);
            recipe.base.toNetwork(buf);
            recipe.addition.toNetwork(buf);
            buf.writeItem(recipe.result);
        }
    }
}
