package net.minecraft.world.level.block.entity;

import com.google.common.collect.Iterables;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Services;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class SkullBlockEntity extends BlockEntity {
    public static final String TAG_SKULL_OWNER = "SkullOwner";
    public static final String TAG_NOTE_BLOCK_SOUND = "note_block_sound";
    @Nullable
    private static GameProfileCache profileCache;
    @Nullable
    private static MinecraftSessionService sessionService;
    @Nullable
    private static Executor mainThreadExecutor;
    @Nullable
    public GameProfile owner;
    @Nullable
    public ResourceLocation noteBlockSound;
    private int animationTickCount;
    private boolean isAnimating;

    public SkullBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.SKULL, pos, state);
    }

    public static void setup(Services apiServices, Executor executor) {
        profileCache = apiServices.profileCache();
        sessionService = apiServices.sessionService();
        mainThreadExecutor = executor;
    }

    public static void clear() {
        profileCache = null;
        sessionService = null;
        mainThreadExecutor = null;
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        if (this.owner != null) {
            CompoundTag compoundTag = new CompoundTag();
            NbtUtils.writeGameProfile(compoundTag, this.owner);
            nbt.put("SkullOwner", compoundTag);
        }

        if (this.noteBlockSound != null) {
            nbt.putString("note_block_sound", this.noteBlockSound.toString());
        }

    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        if (nbt.contains("SkullOwner", 10)) {
            this.setOwner(NbtUtils.readGameProfile(nbt.getCompound("SkullOwner")));
        } else if (nbt.contains("ExtraType", 8)) {
            String string = nbt.getString("ExtraType");
            if (!StringUtil.isNullOrEmpty(string)) {
                this.setOwner(new GameProfile((UUID)null, string));
            }
        }

        if (nbt.contains("note_block_sound", 8)) {
            this.noteBlockSound = ResourceLocation.tryParse(nbt.getString("note_block_sound"));
        }

    }

    public static void animation(Level world, BlockPos pos, BlockState state, SkullBlockEntity blockEntity) {
        if (world.hasNeighborSignal(pos)) {
            blockEntity.isAnimating = true;
            ++blockEntity.animationTickCount;
        } else {
            blockEntity.isAnimating = false;
        }

    }

    public float getAnimation(float tickDelta) {
        return this.isAnimating ? (float)this.animationTickCount + tickDelta : (float)this.animationTickCount;
    }

    @Nullable
    public GameProfile getOwnerProfile() {
        return this.owner;
    }

    @Nullable
    public ResourceLocation getNoteBlockSound() {
        return this.noteBlockSound;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    public void setOwner(@Nullable GameProfile owner) {
        synchronized(this) {
            this.owner = owner;
        }

        this.updateOwnerProfile();
    }

    private void updateOwnerProfile() {
        updateGameprofile(this.owner, (owner) -> {
            this.owner = owner;
            this.setChanged();
        });
    }

    public static void updateGameprofile(@Nullable GameProfile owner, Consumer<GameProfile> callback) {
        if (owner != null && !StringUtil.isNullOrEmpty(owner.getName()) && (!owner.isComplete() || !owner.getProperties().containsKey("textures")) && profileCache != null && sessionService != null) {
            profileCache.getAsync(owner.getName(), (profile) -> {
                Util.backgroundExecutor().execute(() -> {
                    Util.ifElse(profile, (profilex) -> {
                        Property property = Iterables.getFirst(profilex.getProperties().get("textures"), (Property)null);
                        if (property == null) {
                            MinecraftSessionService minecraftSessionService = sessionService;
                            if (minecraftSessionService == null) {
                                return;
                            }

                            profilex = minecraftSessionService.fillProfileProperties(profilex, true);
                        }

                        GameProfile gameProfile = profilex;
                        Executor executor = mainThreadExecutor;
                        if (executor != null) {
                            executor.execute(() -> {
                                GameProfileCache gameProfileCache = profileCache;
                                if (gameProfileCache != null) {
                                    gameProfileCache.add(gameProfile);
                                    callback.accept(gameProfile);
                                }

                            });
                        }

                    }, () -> {
                        Executor executor = mainThreadExecutor;
                        if (executor != null) {
                            executor.execute(() -> {
                                callback.accept(owner);
                            });
                        }

                    });
                });
            });
        } else {
            callback.accept(owner);
        }
    }
}
