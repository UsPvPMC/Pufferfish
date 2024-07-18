package net.minecraft.world.level.block.entity;

import com.mojang.logging.LogUtils;
import javax.annotation.Nullable;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

// CraftBukkit start
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.bukkit.inventory.InventoryHolder;
// CraftBukkit end

import org.spigotmc.CustomTimingsHandler; // Spigot

public abstract class BlockEntity {

    public CustomTimingsHandler tickTimer = org.bukkit.craftbukkit.SpigotTimings.getTileEntityTimings(this); // Spigot
    // CraftBukkit start - data containers
    private static final CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new CraftPersistentDataTypeRegistry();
    public CraftPersistentDataContainer persistentDataContainer;
    // CraftBukkit end
    private static final Logger LOGGER = LogUtils.getLogger();
    private final BlockEntityType<?> type;
    @Nullable
    protected Level level;
    protected final BlockPos worldPosition;
    protected boolean remove;
    private BlockState blockState;

    public BlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        this.type = type;
        this.worldPosition = pos.immutable();
        this.blockState = state;
    }

    public static BlockPos getPosFromTag(CompoundTag nbt) {
        return new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));
    }

    @Nullable
    public Level getLevel() {
        return this.level;
    }

    public void setLevel(Level world) {
        this.level = world;
    }

    public boolean hasLevel() {
        return this.level != null;
    }

    // CraftBukkit start - read container
    public void load(CompoundTag nbt) {
        this.persistentDataContainer = new CraftPersistentDataContainer(BlockEntity.DATA_TYPE_REGISTRY);

        net.minecraft.nbt.Tag persistentDataTag = nbt.get("PublicBukkitValues");
        if (persistentDataTag instanceof CompoundTag) {
            this.persistentDataContainer.putAll((CompoundTag) persistentDataTag);
        }
    }
    // CraftBukkit end

    protected void saveAdditional(CompoundTag nbt) {}

    public final CompoundTag saveWithFullMetadata() {
        CompoundTag nbttagcompound = this.saveWithoutMetadata();

        this.saveMetadata(nbttagcompound);
        return nbttagcompound;
    }

    public final CompoundTag saveWithId() {
        CompoundTag nbttagcompound = this.saveWithoutMetadata();

        this.saveId(nbttagcompound);
        return nbttagcompound;
    }

    public final CompoundTag saveWithoutMetadata() {
        CompoundTag nbttagcompound = new CompoundTag();

        this.saveAdditional(nbttagcompound);
        // CraftBukkit start - store container
        if (this.persistentDataContainer != null && !this.persistentDataContainer.isEmpty()) {
            nbttagcompound.put("PublicBukkitValues", this.persistentDataContainer.toTagCompound());
        }
        // CraftBukkit end
        return nbttagcompound;
    }

    private void saveId(CompoundTag nbt) {
        ResourceLocation minecraftkey = BlockEntityType.getKey(this.getType());

        if (minecraftkey == null) {
            throw new RuntimeException(this.getClass() + " is missing a mapping! This is a bug!");
        } else {
            nbt.putString("id", minecraftkey.toString());
        }
    }

    public static void addEntityType(CompoundTag nbt, BlockEntityType<?> type) {
        nbt.putString("id", BlockEntityType.getKey(type).toString());
    }

    public void saveToItem(ItemStack stack) {
        BlockItem.setBlockEntityData(stack, this.getType(), this.saveWithoutMetadata());
    }

    private void saveMetadata(CompoundTag nbt) {
        this.saveId(nbt);
        nbt.putInt("x", this.worldPosition.getX());
        nbt.putInt("y", this.worldPosition.getY());
        nbt.putInt("z", this.worldPosition.getZ());
    }

    @Nullable
    public static BlockEntity loadStatic(BlockPos pos, BlockState state, CompoundTag nbt) {
        String s = nbt.getString("id");
        ResourceLocation minecraftkey = ResourceLocation.tryParse(s);

        if (minecraftkey == null) {
            BlockEntity.LOGGER.error("Block entity has invalid type: {}", s);
            return null;
        } else {
            return (BlockEntity) BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(minecraftkey).map((tileentitytypes) -> {
                try {
                    return tileentitytypes.create(pos, state);
                } catch (Throwable throwable) {
                    BlockEntity.LOGGER.error("Failed to create block entity {}", s, throwable);
                    return null;
                }
            }).map((tileentity) -> {
                try {
                    tileentity.load(nbt);
                    return tileentity;
                } catch (Throwable throwable) {
                    BlockEntity.LOGGER.error("Failed to load data for block entity {}", s, throwable);
                    return null;
                }
            }).orElseGet(() -> {
                BlockEntity.LOGGER.warn("Skipping BlockEntity with id {}", s);
                return null;
            });
        }
    }

    public void setChanged() {
        if (this.level != null) {
            BlockEntity.setChanged(this.level, this.worldPosition, this.blockState);
        }

    }

    protected static void setChanged(Level world, BlockPos pos, BlockState state) {
        world.blockEntityChanged(pos);
        if (!state.isAir()) {
            world.updateNeighbourForOutputSignal(pos, state.getBlock());
        }

    }

    public BlockPos getBlockPos() {
        return this.worldPosition;
    }

    public BlockState getBlockState() {
        return this.blockState;
    }

    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return null;
    }

    public CompoundTag getUpdateTag() {
        return new CompoundTag();
    }

    public boolean isRemoved() {
        return this.remove;
    }

    public void setRemoved() {
        this.remove = true;
    }

    public void clearRemoved() {
        this.remove = false;
    }

    public boolean triggerEvent(int type, int data) {
        return false;
    }

    public void fillCrashReportCategory(CrashReportCategory crashReportSection) {
        crashReportSection.setDetail("Name", () -> {
            ResourceLocation minecraftkey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(this.getType());

            return minecraftkey + " // " + this.getClass().getCanonicalName();
        });
        if (this.level != null) {
            CrashReportCategory.populateBlockDetails(crashReportSection, this.level, this.worldPosition, this.getBlockState());
            CrashReportCategory.populateBlockDetails(crashReportSection, this.level, this.worldPosition, this.level.getBlockState(this.worldPosition));
        }
    }

    public boolean onlyOpCanSetNbt() {
        return false;
    }

    public BlockEntityType<?> getType() {
        return this.type;
    }

    /** @deprecated */
    @Deprecated
    public void setBlockState(BlockState state) {
        this.blockState = state;
    }

    // CraftBukkit start - add method
    public InventoryHolder getOwner() {
        if (this.level == null) return null;
        org.bukkit.block.BlockState state = this.level.getWorld().getBlockAt(this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ()).getState();
        if (state instanceof InventoryHolder) return (InventoryHolder) state;
        return null;
    }
    // CraftBukkit end
}
