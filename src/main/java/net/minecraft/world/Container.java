package net.minecraft.world;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

import net.minecraft.core.Direction; // Pufferfish
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
// CraftBukkit end

public interface Container extends Clearable {
    // Pufferfish start - allow the inventory to override and optimize these frequent calls
    default boolean hasEmptySlot(@org.jetbrains.annotations.Nullable Direction enumdirection) { // there is a slot with 0 items in it
        if (this instanceof WorldlyContainer worldlyContainer) {
            for (int i : worldlyContainer.getSlotsForFace(enumdirection)) {
                if (this.getItem(i).isEmpty()) {
                    return true;
                }
            }
        } else {
            int size = this.getContainerSize();
            for (int i = 0; i < size; i++) {
                if (this.getItem(i).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    default boolean isCompletelyFull(@org.jetbrains.annotations.Nullable Direction enumdirection) { // every stack is maxed
        if (this instanceof WorldlyContainer worldlyContainer) {
            for (int i : worldlyContainer.getSlotsForFace(enumdirection)) {
                ItemStack itemStack = this.getItem(i);
                if (itemStack.getCount() < itemStack.getMaxStackSize()) {
                    return false;
                }
            }
        } else {
            int size = this.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack itemStack = this.getItem(i);
                if (itemStack.getCount() < itemStack.getMaxStackSize()) {
                    return false;
                }
            }
        }
        return true;
    }

    default boolean isCompletelyEmpty(@org.jetbrains.annotations.Nullable Direction enumdirection) {
        if (this instanceof WorldlyContainer worldlyContainer) {
            for (int i : worldlyContainer.getSlotsForFace(enumdirection)) {
                if (!this.getItem(i).isEmpty()) {
                    return false;
                }
            }
        } else {
            int size = this.getContainerSize();
            for (int i = 0; i < size; i++) {
                if (!this.getItem(i).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }
    // Pufferfish end

    int LARGE_MAX_STACK_SIZE = 64;
    int DEFAULT_DISTANCE_LIMIT = 8;

    int getContainerSize();

    boolean isEmpty();

    ItemStack getItem(int slot);

    ItemStack removeItem(int slot, int amount);

    ItemStack removeItemNoUpdate(int slot);

    void setItem(int slot, ItemStack stack);

    int getMaxStackSize(); // CraftBukkit

    void setChanged();

    boolean stillValid(Player player);

    default void startOpen(Player player) {}

    default void stopOpen(Player player) {}

    default boolean canPlaceItem(int slot, ItemStack stack) {
        return true;
    }

    default boolean canTakeItem(Container hopperInventory, int slot, ItemStack stack) {
        return true;
    }

    default int countItem(Item item) {
        int i = 0;

        for (int j = 0; j < this.getContainerSize(); ++j) {
            ItemStack itemstack = this.getItem(j);

            if (itemstack.getItem().equals(item)) {
                i += itemstack.getCount();
            }
        }

        return i;
    }

    default boolean hasAnyOf(Set<Item> items) {
        return this.hasAnyMatching((itemstack) -> {
            return !itemstack.isEmpty() && items.contains(itemstack.getItem());
        });
    }

    default boolean hasAnyMatching(Predicate<ItemStack> predicate) {
        for (int i = 0; i < this.getContainerSize(); ++i) {
            ItemStack itemstack = this.getItem(i);

            if (predicate.test(itemstack)) {
                return true;
            }
        }

        return false;
    }

    static boolean stillValidBlockEntity(BlockEntity blockEntity, Player player) {
        return Container.stillValidBlockEntity(blockEntity, player, 8);
    }

    static boolean stillValidBlockEntity(BlockEntity blockEntity, Player player, int range) {
        Level world = blockEntity.getLevel();
        BlockPos blockposition = blockEntity.getBlockPos();

        return world == null ? false : (world.getBlockEntity(blockposition) != blockEntity ? false : player.distanceToSqr((double) blockposition.getX() + 0.5D, (double) blockposition.getY() + 0.5D, (double) blockposition.getZ() + 0.5D) <= (double) (range * range));
    }

    // CraftBukkit start
    java.util.List<ItemStack> getContents();

    void onOpen(CraftHumanEntity who);

    void onClose(CraftHumanEntity who);

    java.util.List<org.bukkit.entity.HumanEntity> getViewers();

    org.bukkit.inventory.InventoryHolder getOwner();

    void setMaxStackSize(int size);

    org.bukkit.Location getLocation();

    default Recipe getCurrentRecipe() {
        return null;
    }

    default void setCurrentRecipe(Recipe recipe) {
    }

    int MAX_STACK = 64;
    // CraftBukkit end
}
