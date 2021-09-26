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
import org.bukkit.craftbukkit.inventory.CraftSmithingRecipe;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

/** @deprecated */
@Deprecated(forRemoval = true)
public class LegacyUpgradeRecipe implements SmithingRecipe {

    final Ingredient base;
    final Ingredient addition;
    final ItemStack result;
    private final ResourceLocation id;
    final boolean copyNBT; // Paper

    public LegacyUpgradeRecipe(ResourceLocation id, Ingredient base, Ingredient addition, ItemStack result) {
        // Paper start
        this(id, base, addition, result, true);
    }
    public LegacyUpgradeRecipe(ResourceLocation id, Ingredient base, Ingredient addition, ItemStack result, boolean copyNBT) {
        this.copyNBT = copyNBT;
        // Paper end
        this.id = id;
        this.base = base;
        this.addition = addition;
        this.result = result;
    }

    @Override
    public boolean matches(Container inventory, Level world) {
        return this.base.test(inventory.getItem(0)) && this.addition.test(inventory.getItem(1));
    }

    @Override
    public ItemStack assemble(Container inventory, RegistryAccess registryManager) {
        ItemStack itemstack = this.result.copy();
        if (this.copyNBT) { // Paper - copy nbt conditionally
        CompoundTag nbttagcompound = inventory.getItem(0).getTag();

        if (nbttagcompound != null) {
            itemstack.setTag(nbttagcompound.copy());
        }
        } // Paper

        return itemstack;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryManager) {
        return this.result;
    }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return false;
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
        return RecipeSerializer.SMITHING;
    }

    @Override
    public boolean isIncomplete() {
        return Stream.of(this.base, this.addition).anyMatch((recipeitemstack) -> {
            return recipeitemstack.getItems().length == 0;
        });
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe() {
        CraftItemStack result = CraftItemStack.asCraftMirror(this.result);

        CraftSmithingRecipe recipe = new CraftSmithingRecipe(CraftNamespacedKey.fromMinecraft(this.id), result, CraftRecipe.toBukkit(this.base), CraftRecipe.toBukkit(this.addition), this.copyNBT); // Paper

        return recipe;
    }
    // CraftBukkit end

    public static class Serializer implements RecipeSerializer<LegacyUpgradeRecipe> {

        public Serializer() {}

        @Override
        public LegacyUpgradeRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient recipeitemstack = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "base"));
            Ingredient recipeitemstack1 = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "addition"));
            ItemStack itemstack = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));

            return new LegacyUpgradeRecipe(id, recipeitemstack, recipeitemstack1, itemstack);
        }

        @Override
        public LegacyUpgradeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient recipeitemstack = Ingredient.fromNetwork(buf);
            Ingredient recipeitemstack1 = Ingredient.fromNetwork(buf);
            ItemStack itemstack = buf.readItem();

            return new LegacyUpgradeRecipe(id, recipeitemstack, recipeitemstack1, itemstack);
        }

        public void toNetwork(FriendlyByteBuf buf, LegacyUpgradeRecipe recipe) {
            recipe.base.toNetwork(buf);
            recipe.addition.toNetwork(buf);
            buf.writeItem(recipe.result);
        }
    }
}
