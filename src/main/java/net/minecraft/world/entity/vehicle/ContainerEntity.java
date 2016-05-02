package net.minecraft.world.entity.vehicle;

import javax.annotation.Nullable;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

public interface ContainerEntity extends Container, MenuProvider {
    Vec3 position();

    @Nullable
    ResourceLocation getLootTable();

    void setLootTable(@Nullable ResourceLocation lootTableId);

    long getLootTableSeed();

    void setLootTableSeed(long lootTableSeed);

    NonNullList<ItemStack> getItemStacks();

    void clearItemStacks();

    Level getLevel();

    boolean isRemoved();

    @Override
    default boolean isEmpty() {
        return this.isChestVehicleEmpty();
    }

    default void addChestVehicleSaveData(CompoundTag nbt) {
        if (this.getLootTable() != null) {
            nbt.putString("LootTable", this.getLootTable().toString());
            if (this.getLootTableSeed() != 0L) {
                nbt.putLong("LootTableSeed", this.getLootTableSeed());
            }
        } else if (true) { // Paper - always load the items, table may still remain
            ContainerHelper.saveAllItems(nbt, this.getItemStacks());
        }

    }

    default void readChestVehicleSaveData(CompoundTag nbt) {
        this.clearItemStacks();
        if (nbt.contains("LootTable", 8)) {
            this.setLootTable(new ResourceLocation(nbt.getString("LootTable")));
            this.setLootTableSeed(nbt.getLong("LootTableSeed"));
        } else if (true) { // Paper - always load the items, table may still remain
            ContainerHelper.loadAllItems(nbt, this.getItemStacks());
        }

    }

    default void chestVehicleDestroyed(DamageSource source, Level world, Entity vehicle) {
        if (world.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            Containers.dropContents(world, vehicle, this);
            if (!world.isClientSide) {
                Entity entity = source.getDirectEntity();
                if (entity != null && entity.getType() == EntityType.PLAYER) {
                    PiglinAi.angerNearbyPiglins((Player)entity, true);
                }
            }

        }
    }

    default InteractionResult interactWithContainerVehicle(Player player) {
        player.openMenu(this);
        return !player.level.isClientSide ? InteractionResult.CONSUME : InteractionResult.SUCCESS;
    }

    default void unpackChestVehicleLootTable(@Nullable Player player) {
        MinecraftServer minecraftServer = this.getLevel().getServer();
        if (this.getLootableData().shouldReplenish(player) && minecraftServer != null) { // Paper
            LootTable lootTable = minecraftServer.getLootTables().get(this.getLootTable());
            if (player != null) {
                CriteriaTriggers.GENERATE_LOOT.trigger((ServerPlayer)player, this.getLootTable());
            }

            // this.setLootTable((ResourceLocation)null); // Paper
            this.getLootableData().processRefill(player); // Paper

            LootContext.Builder builder = (new LootContext.Builder((ServerLevel)this.getLevel())).withParameter(LootContextParams.ORIGIN, this.position()).withOptionalRandomSeed(this.getLootTableSeed());
            if (player != null) {
                builder.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
            }

            lootTable.fill(this, builder.create(LootContextParamSets.CHEST));
        }

    }

    default void clearChestVehicleContent() {
        this.unpackChestVehicleLootTable((Player)null);
        this.getItemStacks().clear();
    }

    default boolean isChestVehicleEmpty() {
        for(ItemStack itemStack : this.getItemStacks()) {
            if (!itemStack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    default ItemStack removeChestVehicleItemNoUpdate(int slot) {
        this.unpackChestVehicleLootTable((Player)null);
        ItemStack itemStack = this.getItemStacks().get(slot);
        if (itemStack.isEmpty()) {
            return ItemStack.EMPTY;
        } else {
            this.getItemStacks().set(slot, ItemStack.EMPTY);
            return itemStack;
        }
    }

    default ItemStack getChestVehicleItem(int slot) {
        this.unpackChestVehicleLootTable((Player)null);
        return this.getItemStacks().get(slot);
    }

    default ItemStack removeChestVehicleItem(int slot, int amount) {
        this.unpackChestVehicleLootTable((Player)null);
        return ContainerHelper.removeItem(this.getItemStacks(), slot, amount);
    }

    default void setChestVehicleItem(int slot, ItemStack stack) {
        this.unpackChestVehicleLootTable((Player)null);
        this.getItemStacks().set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > this.getMaxStackSize()) {
            stack.setCount(this.getMaxStackSize());
        }

    }

    default SlotAccess getChestVehicleSlot(final int slot) {
        return slot >= 0 && slot < this.getContainerSize() ? new SlotAccess() {
            @Override
            public ItemStack get() {
                return ContainerEntity.this.getChestVehicleItem(slot);
            }

            @Override
            public boolean set(ItemStack stack) {
                ContainerEntity.this.setChestVehicleItem(slot, stack);
                return true;
            }
        } : SlotAccess.NULL;
    }

    default boolean isChestVehicleStillValid(Player player) {
        return !this.isRemoved() && this.position().closerThan(player.position(), 8.0D);
    }
    // Paper start
    default Entity getEntity() {
        throw new UnsupportedOperationException();
    }

    default com.destroystokyo.paper.loottable.PaperLootableInventoryData getLootableData() {
        throw new UnsupportedOperationException();
    }
    // Paper end
}
