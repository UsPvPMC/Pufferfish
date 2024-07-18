package net.minecraft.world.level.block.entity;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
// CraftBukkit end

public class HopperBlockEntity extends RandomizableContainerBlockEntity implements Hopper {

    public static final int MOVE_ITEM_SPEED = 8;
    public static final int HOPPER_CONTAINER_SIZE = 5;
    private NonNullList<ItemStack> items;
    private int cooldownTime;
    private long tickedGameTime;

    // CraftBukkit start - add fields and methods
    public List<HumanEntity> transaction = new java.util.ArrayList<HumanEntity>();
    private int maxStack = MAX_STACK;

    public List<ItemStack> getContents() {
        return this.items;
    }

    public void onOpen(CraftHumanEntity who) {
        this.transaction.add(who);
    }

    public void onClose(CraftHumanEntity who) {
        this.transaction.remove(who);
    }

    public List<HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    public HopperBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.HOPPER, pos, state);
        this.items = NonNullList.withSize(5, ItemStack.EMPTY);
        this.cooldownTime = -1;
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(nbt)) {
            ContainerHelper.loadAllItems(nbt, this.items);
        }

        this.cooldownTime = nbt.getInt("TransferCooldown");
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        if (!this.trySaveLootTable(nbt)) {
            ContainerHelper.saveAllItems(nbt, this.items);
        }

        nbt.putInt("TransferCooldown", this.cooldownTime);
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        this.unpackLootTable((Player) null);
        return ContainerHelper.removeItem(this.getItems(), slot, amount);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.unpackLootTable((Player) null);
        this.getItems().set(slot, stack);
        if (stack.getCount() > this.getMaxStackSize()) {
            stack.setCount(this.getMaxStackSize());
        }

    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.hopper");
    }

    public static void pushItemsTick(Level world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity) {
        --blockEntity.cooldownTime;
        blockEntity.tickedGameTime = world.getGameTime();
        if (!blockEntity.isOnCooldown()) {
            blockEntity.setCooldown(0);
            // Spigot start
            boolean result = HopperBlockEntity.tryMoveItems(world, pos, state, blockEntity, () -> {
                return HopperBlockEntity.suckInItems(world, blockEntity);
            });
            if (!result && blockEntity.level.spigotConfig.hopperCheck > 1) {
                blockEntity.setCooldown(blockEntity.level.spigotConfig.hopperCheck);
            }
            // Spigot end
        }

    }

    private static boolean tryMoveItems(Level world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, BooleanSupplier booleansupplier) {
        if (world.isClientSide) {
            return false;
        } else {
            if (!blockEntity.isOnCooldown() && (Boolean) state.getValue(HopperBlock.ENABLED)) {
                boolean flag = false;

                if (!blockEntity.isEmpty()) {
                    flag = HopperBlockEntity.ejectItems(world, pos, state, (Container) blockEntity, blockEntity); // CraftBukkit
                }

                if (!blockEntity.inventoryFull()) {
                    flag |= booleansupplier.getAsBoolean();
                }

                if (flag) {
                    blockEntity.setCooldown(world.spigotConfig.hopperTransfer); // Spigot
                    setChanged(world, pos, state);
                    return true;
                }
            }

            return false;
        }
    }

    private boolean inventoryFull() {
        Iterator iterator = this.items.iterator();

        ItemStack itemstack;

        do {
            if (!iterator.hasNext()) {
                return true;
            }

            itemstack = (ItemStack) iterator.next();
        } while (!itemstack.isEmpty() && itemstack.getCount() == itemstack.getMaxStackSize());

        return false;
    }

    private static boolean ejectItems(Level world, BlockPos blockposition, BlockState iblockdata, Container iinventory, HopperBlockEntity hopper) { // CraftBukkit
        Container iinventory1 = HopperBlockEntity.getAttachedContainer(world, blockposition, iblockdata);

        if (iinventory1 == null) {
            return false;
        } else {
            Direction enumdirection = ((Direction) iblockdata.getValue(HopperBlock.FACING)).getOpposite();

            if (HopperBlockEntity.isFullContainer(iinventory1, enumdirection)) {
                return false;
            } else {
                for (int i = 0; i < iinventory.getContainerSize(); ++i) {
                    if (!iinventory.getItem(i).isEmpty()) {
                        ItemStack itemstack = iinventory.getItem(i).copy();
                        // ItemStack itemstack1 = addItem(iinventory, iinventory1, iinventory.removeItem(i, 1), enumdirection);

                        // CraftBukkit start - Call event when pushing items into other inventories
                        CraftItemStack oitemstack = CraftItemStack.asCraftMirror(iinventory.removeItem(i, world.spigotConfig.hopperAmount)); // Spigot

                        Inventory destinationInventory;
                        // Have to special case large chests as they work oddly
                        if (iinventory1 instanceof CompoundContainer) {
                            destinationInventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest((CompoundContainer) iinventory1);
                        } else if (iinventory1.getOwner() != null) {
                            destinationInventory = iinventory1.getOwner().getInventory();
                        } else {
                            destinationInventory = new CraftInventory(iinventory);
                        }

                        InventoryMoveItemEvent event = new InventoryMoveItemEvent(iinventory.getOwner().getInventory(), oitemstack.clone(), destinationInventory, true);
                        world.getCraftServer().getPluginManager().callEvent(event);
                        if (event.isCancelled()) {
                            hopper.setItem(i, itemstack);
                            hopper.setCooldown(world.spigotConfig.hopperTransfer); // Spigot
                            return false;
                        }
                        int origCount = event.getItem().getAmount(); // Spigot
                        ItemStack itemstack1 = HopperBlockEntity.addItem(iinventory, iinventory1, CraftItemStack.asNMSCopy(event.getItem()), enumdirection);
                        // CraftBukkit end

                        if (itemstack1.isEmpty()) {
                            iinventory1.setChanged();
                            return true;
                        }

                        itemstack.shrink(origCount - itemstack1.getCount()); // Spigot
                        iinventory.setItem(i, itemstack);
                    }
                }

                return false;
            }
        }
    }

    private static IntStream getSlots(Container inventory, Direction side) {
        return inventory instanceof WorldlyContainer ? IntStream.of(((WorldlyContainer) inventory).getSlotsForFace(side)) : IntStream.range(0, inventory.getContainerSize());
    }

    private static boolean isFullContainer(Container inventory, Direction direction) {
        return HopperBlockEntity.getSlots(inventory, direction).allMatch((i) -> {
            ItemStack itemstack = inventory.getItem(i);

            return itemstack.getCount() >= itemstack.getMaxStackSize();
        });
    }

    private static boolean isEmptyContainer(Container inv, Direction facing) {
        return HopperBlockEntity.getSlots(inv, facing).allMatch((i) -> {
            return inv.getItem(i).isEmpty();
        });
    }

    public static boolean suckInItems(Level world, Hopper hopper) {
        Container iinventory = HopperBlockEntity.getSourceContainer(world, hopper);

        if (iinventory != null) {
            Direction enumdirection = Direction.DOWN;

            return HopperBlockEntity.isEmptyContainer(iinventory, enumdirection) ? false : HopperBlockEntity.getSlots(iinventory, enumdirection).anyMatch((i) -> {
                return HopperBlockEntity.a(hopper, iinventory, i, enumdirection, world); // Spigot
            });
        } else {
            Iterator iterator = HopperBlockEntity.getItemsAtAndAbove(world, hopper).iterator();

            ItemEntity entityitem;

            do {
                if (!iterator.hasNext()) {
                    return false;
                }

                entityitem = (ItemEntity) iterator.next();
            } while (!HopperBlockEntity.addItem(hopper, entityitem));

            return true;
        }
    }

    private static boolean a(Hopper ihopper, Container iinventory, int i, Direction enumdirection, Level world) { // Spigot
        ItemStack itemstack = iinventory.getItem(i);

        if (!itemstack.isEmpty() && HopperBlockEntity.canTakeItemFromContainer(ihopper, iinventory, itemstack, i, enumdirection)) {
            ItemStack itemstack1 = itemstack.copy();
            // ItemStack itemstack2 = addItem(iinventory, ihopper, iinventory.removeItem(i, 1), (EnumDirection) null);
            // CraftBukkit start - Call event on collection of items from inventories into the hopper
            CraftItemStack oitemstack = CraftItemStack.asCraftMirror(iinventory.removeItem(i, world.spigotConfig.hopperAmount)); // Spigot

            Inventory sourceInventory;
            // Have to special case large chests as they work oddly
            if (iinventory instanceof CompoundContainer) {
                sourceInventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest((CompoundContainer) iinventory);
            } else if (iinventory.getOwner() != null) {
                sourceInventory = iinventory.getOwner().getInventory();
            } else {
                sourceInventory = new CraftInventory(iinventory);
            }

            InventoryMoveItemEvent event = new InventoryMoveItemEvent(sourceInventory, oitemstack.clone(), ihopper.getOwner().getInventory(), false);

            Bukkit.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                iinventory.setItem(i, itemstack1);

                if (ihopper instanceof HopperBlockEntity) {
                    ((HopperBlockEntity) ihopper).setCooldown(world.spigotConfig.hopperTransfer); // Spigot
                }

                return false;
            }
            int origCount = event.getItem().getAmount(); // Spigot
            ItemStack itemstack2 = HopperBlockEntity.addItem(iinventory, ihopper, CraftItemStack.asNMSCopy(event.getItem()), null);
            // CraftBukkit end

            if (itemstack2.isEmpty()) {
                iinventory.setChanged();
                return true;
            }

            itemstack1.shrink(origCount - itemstack2.getCount()); // Spigot
            iinventory.setItem(i, itemstack1);
        }

        return false;
    }

    public static boolean addItem(Container inventory, ItemEntity itemEntity) {
        boolean flag = false;
        // CraftBukkit start
        InventoryPickupItemEvent event = new InventoryPickupItemEvent(inventory.getOwner().getInventory(), (org.bukkit.entity.Item) itemEntity.getBukkitEntity());
        itemEntity.level.getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        // CraftBukkit end
        ItemStack itemstack = itemEntity.getItem().copy();
        ItemStack itemstack1 = HopperBlockEntity.addItem((Container) null, inventory, itemstack, (Direction) null);

        if (itemstack1.isEmpty()) {
            flag = true;
            itemEntity.discard();
        } else {
            itemEntity.setItem(itemstack1);
        }

        return flag;
    }

    public static ItemStack addItem(@Nullable Container from, Container to, ItemStack stack, @Nullable Direction side) {
        int i;

        if (to instanceof WorldlyContainer) {
            WorldlyContainer iworldinventory = (WorldlyContainer) to;

            if (side != null) {
                int[] aint = iworldinventory.getSlotsForFace(side);

                for (i = 0; i < aint.length && !stack.isEmpty(); ++i) {
                    stack = HopperBlockEntity.tryMoveInItem(from, to, stack, aint[i], side);
                }

                return stack;
            }
        }

        int j = to.getContainerSize();

        for (i = 0; i < j && !stack.isEmpty(); ++i) {
            stack = HopperBlockEntity.tryMoveInItem(from, to, stack, i, side);
        }

        return stack;
    }

    private static boolean canPlaceItemInContainer(Container inventory, ItemStack stack, int slot, @Nullable Direction side) {
        if (!inventory.canPlaceItem(slot, stack)) {
            return false;
        } else {
            boolean flag;

            if (inventory instanceof WorldlyContainer) {
                WorldlyContainer iworldinventory = (WorldlyContainer) inventory;

                if (!iworldinventory.canPlaceItemThroughFace(slot, stack, side)) {
                    flag = false;
                    return flag;
                }
            }

            flag = true;
            return flag;
        }
    }

    private static boolean canTakeItemFromContainer(Container hopperInventory, Container fromInventory, ItemStack stack, int slot, Direction facing) {
        if (!fromInventory.canTakeItem(hopperInventory, slot, stack)) {
            return false;
        } else {
            boolean flag;

            if (fromInventory instanceof WorldlyContainer) {
                WorldlyContainer iworldinventory = (WorldlyContainer) fromInventory;

                if (!iworldinventory.canTakeItemThroughFace(slot, stack, facing)) {
                    flag = false;
                    return flag;
                }
            }

            flag = true;
            return flag;
        }
    }

    private static ItemStack tryMoveInItem(@Nullable Container from, Container to, ItemStack stack, int slot, @Nullable Direction side) {
        ItemStack itemstack1 = to.getItem(slot);

        if (HopperBlockEntity.canPlaceItemInContainer(to, stack, slot, side)) {
            boolean flag = false;
            boolean flag1 = to.isEmpty();

            if (itemstack1.isEmpty()) {
                // Spigot start - SPIGOT-6693, InventorySubcontainer#setItem
                if (!stack.isEmpty() && stack.getCount() > to.getMaxStackSize()) {
                    stack = stack.split(to.getMaxStackSize());
                }
                // Spigot end
                to.setItem(slot, stack);
                stack = ItemStack.EMPTY;
                flag = true;
            } else if (HopperBlockEntity.canMergeItems(itemstack1, stack)) {
                int j = stack.getMaxStackSize() - itemstack1.getCount();
                int k = Math.min(stack.getCount(), j);

                stack.shrink(k);
                itemstack1.grow(k);
                flag = k > 0;
            }

            if (flag) {
                if (flag1 && to instanceof HopperBlockEntity) {
                    HopperBlockEntity tileentityhopper = (HopperBlockEntity) to;

                    if (!tileentityhopper.isOnCustomCooldown()) {
                        byte b0 = 0;

                        if (from instanceof HopperBlockEntity) {
                            HopperBlockEntity tileentityhopper1 = (HopperBlockEntity) from;

                            if (tileentityhopper.tickedGameTime >= tileentityhopper1.tickedGameTime) {
                                b0 = 1;
                            }
                        }

                        tileentityhopper.setCooldown(tileentityhopper.level.spigotConfig.hopperTransfer - b0); // Spigot
                    }
                }

                to.setChanged();
            }
        }

        return stack;
    }

    // CraftBukkit start
    @Nullable
    private static Container runHopperInventorySearchEvent(Container inventory, CraftBlock hopper, CraftBlock searchLocation, HopperInventorySearchEvent.ContainerType containerType) {
        HopperInventorySearchEvent event = new HopperInventorySearchEvent((inventory != null) ? new CraftInventory(inventory) : null, containerType, hopper, searchLocation);
        Bukkit.getServer().getPluginManager().callEvent(event);
        CraftInventory craftInventory = (CraftInventory) event.getInventory();
        return (craftInventory != null) ? craftInventory.getInventory() : null;
    }
    // CraftBukkit end

    @Nullable
    private static Container getAttachedContainer(Level world, BlockPos pos, BlockState state) {
        Direction enumdirection = (Direction) state.getValue(HopperBlock.FACING);

        // CraftBukkit start
        BlockPos searchPosition = pos.relative(enumdirection);
        Container inventory = HopperBlockEntity.getContainerAt(world, pos.relative(enumdirection));

        CraftBlock hopper = CraftBlock.at(world, pos);
        CraftBlock searchBlock = CraftBlock.at(world, searchPosition);
        return HopperBlockEntity.runHopperInventorySearchEvent(inventory, hopper, searchBlock, HopperInventorySearchEvent.ContainerType.DESTINATION);
        // CraftBukkit end
    }

    @Nullable
    private static Container getSourceContainer(Level world, Hopper hopper) {
        // CraftBukkit start
        Container inventory = HopperBlockEntity.getContainerAt(world, hopper.getLevelX(), hopper.getLevelY() + 1.0D, hopper.getLevelZ());

        BlockPos blockPosition = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        CraftBlock hopper1 = CraftBlock.at(world, blockPosition);
        CraftBlock container = CraftBlock.at(world, blockPosition.above());
        return HopperBlockEntity.runHopperInventorySearchEvent(inventory, hopper1, container, HopperInventorySearchEvent.ContainerType.SOURCE);
        // CraftBukkit end
    }

    public static List<ItemEntity> getItemsAtAndAbove(Level world, Hopper hopper) {
        return (List) hopper.getSuckShape().toAabbs().stream().flatMap((axisalignedbb) -> {
            return world.getEntitiesOfClass(ItemEntity.class, axisalignedbb.move(hopper.getLevelX() - 0.5D, hopper.getLevelY() - 0.5D, hopper.getLevelZ() - 0.5D), EntitySelector.ENTITY_STILL_ALIVE).stream();
        }).collect(Collectors.toList());
    }

    @Nullable
    public static Container getContainerAt(Level world, BlockPos pos) {
        return HopperBlockEntity.getContainerAt(world, (double) pos.getX() + 0.5D, (double) pos.getY() + 0.5D, (double) pos.getZ() + 0.5D);
    }

    @Nullable
    private static Container getContainerAt(Level world, double x, double y, double z) {
        Object object = null;
        BlockPos blockposition = BlockPos.containing(x, y, z);
        if ( !world.spigotConfig.hopperCanLoadChunks && !world.hasChunkAt( blockposition ) ) return null; // Spigot
        BlockState iblockdata = world.getBlockState(blockposition);
        Block block = iblockdata.getBlock();

        if (block instanceof WorldlyContainerHolder) {
            object = ((WorldlyContainerHolder) block).getContainer(iblockdata, world, blockposition);
        } else if (iblockdata.hasBlockEntity()) {
            BlockEntity tileentity = world.getBlockEntity(blockposition);

            if (tileentity instanceof Container) {
                object = (Container) tileentity;
                if (object instanceof ChestBlockEntity && block instanceof ChestBlock) {
                    object = ChestBlock.getContainer((ChestBlock) block, iblockdata, world, blockposition, true);
                }
            }
        }

        if (object == null) {
            List<Entity> list = world.getEntities((Entity) null, new AABB(x - 0.5D, y - 0.5D, z - 0.5D, x + 0.5D, y + 0.5D, z + 0.5D), EntitySelector.CONTAINER_ENTITY_SELECTOR);

            if (!list.isEmpty()) {
                object = (Container) list.get(world.random.nextInt(list.size()));
            }
        }

        return (Container) object;
    }

    private static boolean canMergeItems(ItemStack first, ItemStack second) {
        return !first.is(second.getItem()) ? false : (first.getDamageValue() != second.getDamageValue() ? false : (first.getCount() > first.getMaxStackSize() ? false : ItemStack.tagMatches(first, second)));
    }

    @Override
    public double getLevelX() {
        return (double) this.worldPosition.getX() + 0.5D;
    }

    @Override
    public double getLevelY() {
        return (double) this.worldPosition.getY() + 0.5D;
    }

    @Override
    public double getLevelZ() {
        return (double) this.worldPosition.getZ() + 0.5D;
    }

    private void setCooldown(int transferCooldown) {
        this.cooldownTime = transferCooldown;
    }

    private boolean isOnCooldown() {
        return this.cooldownTime > 0;
    }

    private boolean isOnCustomCooldown() {
        return this.cooldownTime > 8;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> list) {
        this.items = list;
    }

    public static void entityInside(Level world, BlockPos pos, BlockState state, Entity entity, HopperBlockEntity blockEntity) {
        if (entity instanceof ItemEntity && Shapes.joinIsNotEmpty(Shapes.create(entity.getBoundingBox().move((double) (-pos.getX()), (double) (-pos.getY()), (double) (-pos.getZ()))), blockEntity.getSuckShape(), BooleanOp.AND)) {
            HopperBlockEntity.tryMoveItems(world, pos, state, blockEntity, () -> {
                return HopperBlockEntity.addItem(blockEntity, (ItemEntity) entity);
            });
        }

    }

    @Override
    protected AbstractContainerMenu createMenu(int syncId, net.minecraft.world.entity.player.Inventory playerInventory) {
        return new HopperMenu(syncId, playerInventory, this);
    }
}
