package net.minecraft.world.inventory;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.LegacyUpgradeRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.inventory.CraftInventoryView; // CraftBukkit

/** @deprecated */
@Deprecated(forRemoval = true)
public class LegacySmithingMenu extends ItemCombinerMenu {

    private final Level level;
    public static final int INPUT_SLOT = 0;
    public static final int ADDITIONAL_SLOT = 1;
    public static final int RESULT_SLOT = 2;
    private static final int INPUT_SLOT_X_PLACEMENT = 27;
    private static final int ADDITIONAL_SLOT_X_PLACEMENT = 76;
    private static final int RESULT_SLOT_X_PLACEMENT = 134;
    private static final int SLOT_Y_PLACEMENT = 47;
    @Nullable
    private LegacyUpgradeRecipe selectedRecipe;
    private final List<LegacyUpgradeRecipe> recipes;
    // CraftBukkit start
    private CraftInventoryView bukkitEntity;
    // CraftBukkit end

    public LegacySmithingMenu(int syncId, Inventory playerInventory) {
        this(syncId, playerInventory, ContainerLevelAccess.NULL);
    }

    public LegacySmithingMenu(int syncId, Inventory playerInventory, ContainerLevelAccess context) {
        super(MenuType.LEGACY_SMITHING, syncId, playerInventory, context);
        this.level = playerInventory.player.level;
        this.recipes = this.level.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING).stream().filter((smithingrecipe) -> {
            return smithingrecipe instanceof LegacyUpgradeRecipe;
        }).map((smithingrecipe) -> {
            return (LegacyUpgradeRecipe) smithingrecipe;
        }).toList();
    }

    @Override
    protected ItemCombinerMenuSlotDefinition createInputSlotDefinitions() {
        return ItemCombinerMenuSlotDefinition.create().withSlot(0, 27, 47, (itemstack) -> {
            return true;
        }).withSlot(1, 76, 47, (itemstack) -> {
            return true;
        }).withResultSlot(2, 134, 47).build();
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
        List<LegacyUpgradeRecipe> list = this.level.getRecipeManager().getRecipesFor(RecipeType.SMITHING, this.inputSlots, this.level).stream().filter((smithingrecipe) -> {
            return smithingrecipe instanceof LegacyUpgradeRecipe;
        }).map((smithingrecipe) -> {
            return (LegacyUpgradeRecipe) smithingrecipe;
        }).toList();

        if (list.isEmpty()) {
            org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareSmithingEvent(this.getBukkitView(), ItemStack.EMPTY); // CraftBukkit
        } else {
            LegacyUpgradeRecipe legacyupgraderecipe = (LegacyUpgradeRecipe) list.get(0);
            ItemStack itemstack = legacyupgraderecipe.assemble(this.inputSlots, this.level.registryAccess());

            if (itemstack.isItemEnabled(this.level.enabledFeatures())) {
                this.selectedRecipe = legacyupgraderecipe;
                this.resultSlots.setRecipeUsed(legacyupgraderecipe);
                // CraftBukkit start
                org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareSmithingEvent(this.getBukkitView(), itemstack);
                // CraftBukkit end
            }
        }

    }

    @Override
    public int getSlotToQuickMoveTo(ItemStack stack) {
        return this.shouldQuickMoveToAdditionalSlot(stack) ? 1 : 0;
    }

    protected boolean shouldQuickMoveToAdditionalSlot(ItemStack stack) {
        return this.recipes.stream().anyMatch((legacyupgraderecipe) -> {
            return legacyupgraderecipe.isAdditionIngredient(stack);
        });
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != this.resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    // CraftBukkit start
    @Override
    public CraftInventoryView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        org.bukkit.craftbukkit.inventory.CraftInventory inventory = new org.bukkit.craftbukkit.inventory.CraftInventorySmithing(
                access.getLocation(), this.inputSlots, this.resultSlots);
        this.bukkitEntity = new CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}
