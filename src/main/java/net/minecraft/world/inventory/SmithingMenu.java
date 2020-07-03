package net.minecraft.world.inventory;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.inventory.CraftInventoryView; // CraftBukkit

public class SmithingMenu extends ItemCombinerMenu {

    public static final int TEMPLATE_SLOT = 0;
    public static final int BASE_SLOT = 1;
    public static final int ADDITIONAL_SLOT = 2;
    public static final int RESULT_SLOT = 3;
    public static final int TEMPLATE_SLOT_X_PLACEMENT = 8;
    public static final int BASE_SLOT_X_PLACEMENT = 26;
    public static final int ADDITIONAL_SLOT_X_PLACEMENT = 44;
    private static final int RESULT_SLOT_X_PLACEMENT = 98;
    public static final int SLOT_Y_PLACEMENT = 48;
    private final Level level;
    @Nullable
    private SmithingRecipe selectedRecipe;
    private final List<SmithingRecipe> recipes;
    // CraftBukkit start
    private CraftInventoryView bukkitEntity;
    // CraftBukkit end

    public SmithingMenu(int syncId, Inventory playerInventory) {
        this(syncId, playerInventory, ContainerLevelAccess.NULL);
    }

    public SmithingMenu(int syncId, Inventory playerInventory, ContainerLevelAccess context) {
        super(MenuType.SMITHING, syncId, playerInventory, context);
        this.level = playerInventory.player.level;
        this.recipes = this.level.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING);
    }

    @Override
    protected ItemCombinerMenuSlotDefinition createInputSlotDefinitions() {
        return ItemCombinerMenuSlotDefinition.create().withSlot(0, 8, 48, (itemstack) -> {
            return this.recipes.stream().anyMatch((smithingrecipe) -> {
                return smithingrecipe.isTemplateIngredient(itemstack);
            });
        }).withSlot(1, 26, 48, (itemstack) -> {
            return this.recipes.stream().anyMatch((smithingrecipe) -> {
                return smithingrecipe.isBaseIngredient(itemstack) && smithingrecipe.isTemplateIngredient(((Slot) this.slots.get(0)).getItem());
            });
        }).withSlot(2, 44, 48, (itemstack) -> {
            return this.recipes.stream().anyMatch((smithingrecipe) -> {
                return smithingrecipe.isAdditionIngredient(itemstack) && smithingrecipe.isTemplateIngredient(((Slot) this.slots.get(0)).getItem());
            });
        }).withResultSlot(3, 98, 48).build();
    }

    @Override
    protected boolean isValidBlock(BlockState state) {
        return state.is(Blocks.SMITHING_TABLE);
    }

    @Override
    protected boolean mayPickup(Player player, boolean present) {
        return this.selectedRecipe != null && this.selectedRecipe.matches(this.inputSlots, this.level);
    }

    @Override
    protected void onTake(Player player, ItemStack stack) {
        stack.onCraftedBy(player.level, player, stack.getCount());
        this.resultSlots.awardUsedRecipes(player);
        this.shrinkStackInSlot(0);
        this.shrinkStackInSlot(1);
        this.shrinkStackInSlot(2);
        this.access.execute((world, blockposition) -> {
            world.levelEvent(1044, blockposition, 0);
        });
    }

    private void shrinkStackInSlot(int slot) {
        ItemStack itemstack = this.inputSlots.getItem(slot);

        itemstack.shrink(1);
        this.inputSlots.setItem(slot, itemstack);
    }

    @Override
    public void createResult() {
        List<SmithingRecipe> list = this.level.getRecipeManager().getRecipesFor(RecipeType.SMITHING, this.inputSlots, this.level);

        if (list.isEmpty()) {
            org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareSmithingEvent(this.getBukkitView(), ItemStack.EMPTY); // CraftBukkit
        } else {
            SmithingRecipe smithingrecipe = (SmithingRecipe) list.get(0);
            ItemStack itemstack = smithingrecipe.assemble(this.inputSlots, this.level.registryAccess());

            if (itemstack.isItemEnabled(this.level.enabledFeatures())) {
                this.selectedRecipe = smithingrecipe;
                this.resultSlots.setRecipeUsed(smithingrecipe);
                // CraftBukkit start
                org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareSmithingEvent(this.getBukkitView(), itemstack);
                // CraftBukkit end
            }
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(this, RESULT_SLOT); // Paper
    }

    @Override
    public int getSlotToQuickMoveTo(ItemStack stack) {
        return (Integer) ((Optional) this.recipes.stream().map((smithingrecipe) -> {
            return SmithingMenu.findSlotMatchingIngredient(smithingrecipe, stack);
        }).filter(Optional::isPresent).findFirst().orElse(Optional.of(0))).get();
    }

    private static Optional<Integer> findSlotMatchingIngredient(SmithingRecipe recipe, ItemStack stack) {
        return recipe.isTemplateIngredient(stack) ? Optional.of(0) : (recipe.isBaseIngredient(stack) ? Optional.of(1) : (recipe.isAdditionIngredient(stack) ? Optional.of(2) : Optional.empty()));
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != this.resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean canMoveIntoInputSlots(ItemStack stack) {
        return this.recipes.stream().map((smithingrecipe) -> {
            return SmithingMenu.findSlotMatchingIngredient(smithingrecipe, stack);
        }).anyMatch(Optional::isPresent);
    }

    // CraftBukkit start
    @Override
    public CraftInventoryView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        org.bukkit.craftbukkit.inventory.CraftInventory inventory = new org.bukkit.craftbukkit.inventory.CraftInventorySmithingNew(
                access.getLocation(), this.inputSlots, this.resultSlots);
        this.bukkitEntity = new CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}
