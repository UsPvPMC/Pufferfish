package net.minecraft.world.phys.shapes;

import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FluidState;

public class EntityCollisionContext implements CollisionContext {
    protected static final CollisionContext EMPTY = new EntityCollisionContext(false, -Double.MAX_VALUE, ItemStack.EMPTY, (fluidState) -> {
        return false;
    }, (Entity)null) {
        @Override
        public boolean isAbove(VoxelShape shape, BlockPos pos, boolean defaultValue) {
            return defaultValue;
        }
    };
    // Pufferfish start - remove these and pray no plugin uses them
    // private final boolean descending;
    // private final double entityBottom;
    // private final ItemStack heldItem;
    // private final Predicate<FluidState> canStandOnFluid;
    // Pufferfish end
    @Nullable
    private final Entity entity;

    protected EntityCollisionContext(boolean descending, double minY, ItemStack heldItem, Predicate<FluidState> walkOnFluidPredicate, @Nullable Entity entity) {
        // Pufferfish start - remove these
        // this.descending = descending;
        // this.entityBottom = minY;
        // this.heldItem = heldItem;
        // this.canStandOnFluid = walkOnFluidPredicate;
        // Pufferfish end
        this.entity = entity;
    }

    /** @deprecated */
    @Deprecated
    protected EntityCollisionContext(Entity entity) {
        // Pufferfish start - remove this
        // this(entity.isDescending(), entity.getY(), entity instanceof LivingEntity ? ((LivingEntity)entity).getMainHandItem() : ItemStack.EMPTY, entity instanceof LivingEntity ? ((LivingEntity)entity)::canStandOnFluid : (fluidState) -> {
        //     return false;
        // }, entity);
        // Pufferfish end
        this.entity = entity;
    }

    @Override
    public boolean isHoldingItem(Item item) {
        // Pufferfish start
        Entity entity = this.entity;
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity.getMainHandItem().is(item);
        }
        return ItemStack.EMPTY.is(item);
        // Pufferfish end
    }

    @Override
    public boolean canStandOnFluid(FluidState stateAbove, FluidState state) {
        // Pufferfish start
        Entity entity = this.entity;
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity.canStandOnFluid(state) && !stateAbove.getType().isSame(state.getType());
        }
        return false;
        // Pufferfish end
    }

    @Override
    public boolean isDescending() {
        return this.entity != null && this.entity.isDescending(); // Pufferfish
    }

    @Override
    public boolean isAbove(VoxelShape shape, BlockPos pos, boolean defaultValue) {
        return (this.entity == null ? -Double.MAX_VALUE : entity.getY()) > (double)pos.getY() + shape.max(Direction.Axis.Y) - (double)1.0E-5F; // Pufferfish
    }

    @Nullable
    public Entity getEntity() {
        return this.entity;
    }
}
