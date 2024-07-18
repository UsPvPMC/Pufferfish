package net.minecraft.world.item.crafting;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmithingTrimRecipe;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

public class SmithingTrimRecipe implements SmithingRecipe {

    private final ResourceLocation id;
    final Ingredient template;
    final Ingredient base;
    final Ingredient addition;

    public SmithingTrimRecipe(ResourceLocation id, Ingredient template, Ingredient base, Ingredient addition) {
        this.id = id;
        this.template = template;
        this.base = base;
        this.addition = addition;
    }

    @Override
    public boolean matches(Container inventory, Level world) {
        return this.template.test(inventory.getItem(0)) && this.base.test(inventory.getItem(1)) && this.addition.test(inventory.getItem(2));
    }

    @Override
    public ItemStack assemble(Container inventory, RegistryAccess registryManager) {
        ItemStack itemstack = inventory.getItem(1);

        if (this.base.test(itemstack)) {
            Optional<Holder.Reference<TrimMaterial>> optional = TrimMaterials.getFromIngredient(registryManager, inventory.getItem(2));
            Optional<Holder.Reference<TrimPattern>> optional1 = TrimPatterns.getFromTemplate(registryManager, inventory.getItem(0));

            if (optional.isPresent() && optional1.isPresent()) {
                Optional<ArmorTrim> optional2 = ArmorTrim.getTrim(registryManager, itemstack);

                if (optional2.isPresent() && ((ArmorTrim) optional2.get()).hasPatternAndMaterial((Holder) optional1.get(), (Holder) optional.get())) {
                    return ItemStack.EMPTY;
                }

                ItemStack itemstack1 = itemstack.copy();

                itemstack1.setCount(1);
                ArmorTrim armortrim = new ArmorTrim((Holder) optional.get(), (Holder) optional1.get());

                if (ArmorTrim.setTrim(registryManager, itemstack1, armortrim)) {
                    return itemstack1;
                }
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryManager) {
        ItemStack itemstack = new ItemStack(Items.IRON_CHESTPLATE);
        Optional<Holder.Reference<TrimPattern>> optional = registryManager.registryOrThrow(Registries.TRIM_PATTERN).holders().findFirst();

        if (optional.isPresent()) {
            Optional<Holder.Reference<TrimMaterial>> optional1 = registryManager.registryOrThrow(Registries.TRIM_MATERIAL).getHolder(TrimMaterials.REDSTONE);

            if (optional1.isPresent()) {
                ArmorTrim armortrim = new ArmorTrim((Holder) optional1.get(), (Holder) optional.get());

                ArmorTrim.setTrim(registryManager, itemstack, armortrim);
            }
        }

        return itemstack;
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
        return RecipeSerializer.SMITHING_TRIM;
    }

    @Override
    public boolean isIncomplete() {
        return Stream.of(this.template, this.base, this.addition).anyMatch(Ingredient::isEmpty);
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe() {
        return new CraftSmithingTrimRecipe(CraftNamespacedKey.fromMinecraft(this.id), CraftRecipe.toBukkit(this.template), CraftRecipe.toBukkit(this.base), CraftRecipe.toBukkit(this.addition));
    }
    // CraftBukkit end

    public static class Serializer implements RecipeSerializer<SmithingTrimRecipe> {

        public Serializer() {}

        @Override
        public SmithingTrimRecipe fromJson(ResourceLocation id, JsonObject json) {
            Ingredient recipeitemstack = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "template"));
            Ingredient recipeitemstack1 = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "base"));
            Ingredient recipeitemstack2 = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "addition"));

            return new SmithingTrimRecipe(id, recipeitemstack, recipeitemstack1, recipeitemstack2);
        }

        @Override
        public SmithingTrimRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient recipeitemstack = Ingredient.fromNetwork(buf);
            Ingredient recipeitemstack1 = Ingredient.fromNetwork(buf);
            Ingredient recipeitemstack2 = Ingredient.fromNetwork(buf);

            return new SmithingTrimRecipe(id, recipeitemstack, recipeitemstack1, recipeitemstack2);
        }

        public void toNetwork(FriendlyByteBuf buf, SmithingTrimRecipe recipe) {
            recipe.template.toNetwork(buf);
            recipe.base.toNetwork(buf);
            recipe.addition.toNetwork(buf);
        }
    }
}
