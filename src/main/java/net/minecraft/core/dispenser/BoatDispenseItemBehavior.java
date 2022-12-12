package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockSource;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
// CraftBukkit start
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockDispenseEvent;
// CraftBukkit end

public class BoatDispenseItemBehavior extends DefaultDispenseItemBehavior {

    private final DefaultDispenseItemBehavior defaultDispenseItemBehavior;
    private final Boat.Type type;
    private final boolean isChestBoat;

    public BoatDispenseItemBehavior(Boat.Type type) {
        this(type, false);
    }

    public BoatDispenseItemBehavior(Boat.Type boatType, boolean chest) {
        this.defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();
        this.type = boatType;
        this.isChestBoat = chest;
    }

    @Override
    public ItemStack execute(BlockSource pointer, ItemStack stack) {
        Direction enumdirection = (Direction) pointer.getBlockState().getValue(DispenserBlock.FACING);
        ServerLevel worldserver = pointer.getLevel();
        double d0 = pointer.x() + (double) ((float) enumdirection.getStepX() * 1.125F);
        double d1 = pointer.y() + (double) ((float) enumdirection.getStepY() * 1.125F);
        double d2 = pointer.z() + (double) ((float) enumdirection.getStepZ() * 1.125F);
        BlockPos blockposition = pointer.getPos().relative(enumdirection);
        double d3;

        if (worldserver.getFluidState(blockposition).is(FluidTags.WATER)) {
            d3 = 1.0D;
        } else {
            if (!worldserver.getBlockState(blockposition).isAir() || !worldserver.getFluidState(blockposition.below()).is(FluidTags.WATER)) {
                return this.defaultDispenseItemBehavior.dispense(pointer, stack);
            }

            d3 = 0.0D;
        }

        // EntityBoat entityboat = new EntityBoat(worldserver, d0, d1 + d3, d2);
        // CraftBukkit start
        ItemStack itemstack1 = stack.copyWithCount(1); // Paper - shrink at end and single item in event
        org.bukkit.block.Block block = worldserver.getWorld().getBlockAt(pointer.getPos().getX(), pointer.getPos().getY(), pointer.getPos().getZ());
        CraftItemStack craftItem = CraftItemStack.asCraftMirror(itemstack1);

        BlockDispenseEvent event = new BlockDispenseEvent(block, craftItem.clone(), new org.bukkit.util.Vector(d0, d1 + d3, d2));
        if (!DispenserBlock.eventFired) {
            worldserver.getCraftServer().getPluginManager().callEvent(event);
        }

        if (event.isCancelled()) {
            // stack.grow(1); // Paper - shrink below
            return stack;
        }

        boolean shrink = true; // Paper
        if (!event.getItem().equals(craftItem)) {
            shrink = false; // Paper - shrink below
            // Chain to handler for new item
            ItemStack eventStack = CraftItemStack.asNMSCopy(event.getItem());
            DispenseItemBehavior idispensebehavior = (DispenseItemBehavior) DispenserBlock.DISPENSER_REGISTRY.get(eventStack.getItem());
            if (idispensebehavior != DispenseItemBehavior.NOOP && idispensebehavior != this) {
                idispensebehavior.dispense(pointer, eventStack);
                return stack;
            }
        }

        Object object = this.isChestBoat ? new ChestBoat(worldserver, event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ()) : new Boat(worldserver, event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ());
        // CraftBukkit end

        ((Boat) object).setVariant(this.type);
        ((Boat) object).setYRot(enumdirection.toYRot());
        if (worldserver.addFreshEntity((Entity) object) && shrink) stack.shrink(1); // Paper - if entity add was successful and supposed to shrink
        return stack;
    }

    @Override
    protected void playSound(BlockSource pointer) {
        pointer.getLevel().levelEvent(1000, pointer.getPos(), 0);
    }
}
