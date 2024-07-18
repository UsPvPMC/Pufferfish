package net.minecraft.world.level.block.entity;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.slf4j.Logger;

// CraftBukkit start
import java.util.List;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
// CraftBukkit end

public class ChiseledBookShelfBlockEntity extends BlockEntity implements Container {

    public static final int MAX_BOOKS_IN_STORAGE = 6;
    private static final Logger LOGGER = LogUtils.getLogger();
    private final NonNullList<ItemStack> items;
    public int lastInteractedSlot;
    // CraftBukkit start - add fields and methods
    public List<HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = 1;

    @Override
    public List<ItemStack> getContents() {
        return this.items;
    }

    @Override
    public void onOpen(CraftHumanEntity who) {
        this.transaction.add(who);
    }

    @Override
    public void onClose(CraftHumanEntity who) {
        this.transaction.remove(who);
    }

    @Override
    public List<HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    @Override
    public Location getLocation() {
        if (level == null) return null;
        return new org.bukkit.Location(level.getWorld(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }
    // CraftBukkit end

    public ChiseledBookShelfBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.CHISELED_BOOKSHELF, pos, state);
        this.items = NonNullList.withSize(6, ItemStack.EMPTY);
        this.lastInteractedSlot = -1;
    }

    private void updateState(int interactedSlot) {
        if (interactedSlot >= 0 && interactedSlot < 6) {
            this.lastInteractedSlot = interactedSlot;
            BlockState iblockdata = this.getBlockState();

            for (int j = 0; j < ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.size(); ++j) {
                boolean flag = !this.getItem(j).isEmpty();
                BooleanProperty blockstateboolean = (BooleanProperty) ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(j);

                iblockdata = (BlockState) iblockdata.setValue(blockstateboolean, flag);
            }

            ((Level) Objects.requireNonNull(this.level)).setBlock(this.worldPosition, iblockdata, 3);
        } else {
            ChiseledBookShelfBlockEntity.LOGGER.error("Expected slot 0-5, got {}", interactedSlot);
        }
    }

    @Override
    public void load(CompoundTag nbt) {
        this.items.clear();
        ContainerHelper.loadAllItems(nbt, this.items);
        this.lastInteractedSlot = nbt.getInt("last_interacted_slot");
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        ContainerHelper.saveAllItems(nbt, this.items, true);
        nbt.putInt("last_interacted_slot", this.lastInteractedSlot);
    }

    public int count() {
        return (int) this.items.stream().filter(Predicate.not(ItemStack::isEmpty)).count();
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    @Override
    public int getContainerSize() {
        return 6;
    }

    @Override
    public boolean isEmpty() {
        return this.items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return (ItemStack) this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack itemstack = (ItemStack) Objects.requireNonNullElse((ItemStack) this.items.get(slot), ItemStack.EMPTY);

        this.items.set(slot, ItemStack.EMPTY);
        if (!itemstack.isEmpty()) {
            this.updateState(slot);
        }

        return itemstack;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return this.removeItem(slot, 1);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (stack.is(ItemTags.BOOKSHELF_BOOKS)) {
            this.items.set(slot, stack);
            this.updateState(slot);
        }

    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack; // CraftBukkit
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.is(ItemTags.BOOKSHELF_BOOKS) && this.getItem(slot).isEmpty();
    }

    public int getLastInteractedSlot() {
        return this.lastInteractedSlot;
    }
}
