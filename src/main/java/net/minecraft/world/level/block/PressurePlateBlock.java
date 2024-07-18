package net.minecraft.world.level.block;

import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import org.bukkit.event.entity.EntityInteractEvent;
// CraftBukkit end

public class PressurePlateBlock extends BasePressurePlateBlock {

    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    private final PressurePlateBlock.Sensitivity sensitivity;

    protected PressurePlateBlock(PressurePlateBlock.Sensitivity type, BlockBehaviour.Properties settings, BlockSetType blockSetType) {
        super(settings, blockSetType);
        this.registerDefaultState((BlockState) ((BlockState) this.stateDefinition.any()).setValue(PressurePlateBlock.POWERED, false));
        this.sensitivity = type;
    }

    @Override
    protected int getSignalForState(BlockState state) {
        return (Boolean) state.getValue(PressurePlateBlock.POWERED) ? 15 : 0;
    }

    @Override
    protected BlockState setSignalForState(BlockState state, int rsOut) {
        return (BlockState) state.setValue(PressurePlateBlock.POWERED, rsOut > 0);
    }

    @Override
    protected int getSignalStrength(Level world, BlockPos pos) {
        AABB axisalignedbb = PressurePlateBlock.TOUCH_AABB.move(pos);
        List list;

        switch (this.sensitivity) {
            case EVERYTHING:
                list = world.getEntities((Entity) null, axisalignedbb);
                break;
            case MOBS:
                list = world.getEntitiesOfClass(LivingEntity.class, axisalignedbb);
                break;
            default:
                return 0;
        }

        if (!list.isEmpty()) {
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                Entity entity = (Entity) iterator.next();
                if (entity.isIgnoringBlockTriggers()) continue; // Paper - don't call event for ignored entities

                // CraftBukkit start - Call interact event when turning on a pressure plate
                if (this.getSignalForState(world.getBlockState(pos)) == 0) {
                    org.bukkit.World bworld = world.getWorld();
                    org.bukkit.plugin.PluginManager manager = world.getCraftServer().getPluginManager();
                    org.bukkit.event.Cancellable cancellable;

                    if (entity instanceof Player) {
                        cancellable = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent((Player) entity, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null);
                    } else {
                        cancellable = new EntityInteractEvent(entity.getBukkitEntity(), bworld.getBlockAt(pos.getX(), pos.getY(), pos.getZ()));
                        manager.callEvent((EntityInteractEvent) cancellable);
                    }

                    // We only want to block turning the plate on if all events are cancelled
                    if (cancellable.isCancelled()) {
                        continue;
                    }
                }
                // CraftBukkit end

                if (!entity.isIgnoringBlockTriggers()) {
                    return 15;
                }
            }
        }

        return 0;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PressurePlateBlock.POWERED);
    }

    public static enum Sensitivity {

        EVERYTHING, MOBS;

        private Sensitivity() {}
    }
}
