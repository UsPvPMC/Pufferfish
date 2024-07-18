package net.minecraft.server.level;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.util.DebugBuffer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
// CraftBukkit start
import net.minecraft.server.MinecraftServer;
// CraftBukkit end

public class ChunkHolder {

    public static final Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> UNLOADED_CHUNK = Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED);
    public static final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> UNLOADED_CHUNK_FUTURE = CompletableFuture.completedFuture(ChunkHolder.UNLOADED_CHUNK);
    public static final Either<LevelChunk, ChunkHolder.ChunkLoadingFailure> UNLOADED_LEVEL_CHUNK = Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED);
    private static final Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> NOT_DONE_YET = Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED);
    private static final CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> UNLOADED_LEVEL_CHUNK_FUTURE = CompletableFuture.completedFuture(ChunkHolder.UNLOADED_LEVEL_CHUNK);
    private static final List<ChunkStatus> CHUNK_STATUSES = ChunkStatus.getStatusList();
    private static final ChunkHolder.FullChunkStatus[] FULL_CHUNK_STATUSES = ChunkHolder.FullChunkStatus.values();
    private static final int BLOCKS_BEFORE_RESEND_FUDGE = 64;
    private final AtomicReferenceArray<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures;
    private final LevelHeightAccessor levelHeightAccessor;
    private volatile CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> fullChunkFuture;
    private volatile CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> tickingChunkFuture;
    private volatile CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> entityTickingChunkFuture;
    private CompletableFuture<ChunkAccess> chunkToSave;
    @Nullable
    private final DebugBuffer<ChunkHolder.ChunkSaveDebug> chunkToSaveHistory;
    public int oldTicketLevel;
    private int ticketLevel;
    private int queueLevel;
    public final ChunkPos pos;
    private boolean hasChangedSections;
    private final ShortSet[] changedBlocksPerSection;
    private final BitSet blockChangedLightSectionFilter;
    private final BitSet skyChangedLightSectionFilter;
    private final LevelLightEngine lightEngine;
    private final ChunkHolder.LevelChangeListener onLevelChange;
    public final ChunkHolder.PlayerProvider playerProvider;
    private boolean wasAccessibleSinceLastSave;
    private boolean resendLight;
    private CompletableFuture<Void> pendingFullStateConfirmation;

    public ChunkHolder(ChunkPos pos, int level, LevelHeightAccessor world, LevelLightEngine lightingProvider, ChunkHolder.LevelChangeListener levelUpdateListener, ChunkHolder.PlayerProvider playersWatchingChunkProvider) {
        this.futures = new AtomicReferenceArray(ChunkHolder.CHUNK_STATUSES.size());
        this.fullChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
        this.tickingChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
        this.entityTickingChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
        this.chunkToSave = CompletableFuture.completedFuture(null); // CraftBukkit - decompile error
        this.chunkToSaveHistory = null;
        this.blockChangedLightSectionFilter = new BitSet();
        this.skyChangedLightSectionFilter = new BitSet();
        this.pendingFullStateConfirmation = CompletableFuture.completedFuture(null); // CraftBukkit - decompile error
        this.pos = pos;
        this.levelHeightAccessor = world;
        this.lightEngine = lightingProvider;
        this.onLevelChange = levelUpdateListener;
        this.playerProvider = playersWatchingChunkProvider;
        this.oldTicketLevel = ChunkMap.MAX_CHUNK_DISTANCE + 1;
        this.ticketLevel = this.oldTicketLevel;
        this.queueLevel = this.oldTicketLevel;
        this.setTicketLevel(level);
        this.changedBlocksPerSection = new ShortSet[world.getSectionsCount()];
    }

    // CraftBukkit start
    public LevelChunk getFullChunkNow() {
        // Note: We use the oldTicketLevel for isLoaded checks.
        if (!ChunkHolder.getFullChunkStatus(this.oldTicketLevel).isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) return null;
        return this.getFullChunkNowUnchecked();
    }

    public LevelChunk getFullChunkNowUnchecked() {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> statusFuture = this.getFutureIfPresentUnchecked(ChunkStatus.FULL);
        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = (Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>) statusFuture.getNow(null);
        return (either == null) ? null : (LevelChunk) either.left().orElse(null);
    }
    // CraftBukkit end

    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getFutureIfPresentUnchecked(ChunkStatus leastStatus) {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = (CompletableFuture) this.futures.get(leastStatus.getIndex());

        return completablefuture == null ? ChunkHolder.UNLOADED_CHUNK_FUTURE : completablefuture;
    }

    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getFutureIfPresent(ChunkStatus leastStatus) {
        return ChunkHolder.getStatus(this.ticketLevel).isOrAfter(leastStatus) ? this.getFutureIfPresentUnchecked(leastStatus) : ChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getTickingChunkFuture() {
        return this.tickingChunkFuture;
    }

    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getEntityTickingChunkFuture() {
        return this.entityTickingChunkFuture;
    }

    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getFullChunkFuture() {
        return this.fullChunkFuture;
    }

    @Nullable
    public LevelChunk getTickingChunk() {
        CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getTickingChunkFuture();
        Either<LevelChunk, ChunkHolder.ChunkLoadingFailure> either = (Either) completablefuture.getNow(null); // CraftBukkit - decompile error

        return either == null ? null : (LevelChunk) either.left().orElse(null); // CraftBukkit - decompile error
    }

    @Nullable
    public LevelChunk getFullChunk() {
        CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getFullChunkFuture();
        Either<LevelChunk, ChunkHolder.ChunkLoadingFailure> either = (Either) completablefuture.getNow(null); // CraftBukkit - decompile error

        return either == null ? null : (LevelChunk) either.left().orElse(null); // CraftBukkit - decompile error
    }

    @Nullable
    public ChunkStatus getLastAvailableStatus() {
        for (int i = ChunkHolder.CHUNK_STATUSES.size() - 1; i >= 0; --i) {
            ChunkStatus chunkstatus = (ChunkStatus) ChunkHolder.CHUNK_STATUSES.get(i);
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getFutureIfPresentUnchecked(chunkstatus);

            if (((Either) completablefuture.getNow(ChunkHolder.UNLOADED_CHUNK)).left().isPresent()) {
                return chunkstatus;
            }
        }

        return null;
    }

    @Nullable
    public ChunkAccess getLastAvailable() {
        for (int i = ChunkHolder.CHUNK_STATUSES.size() - 1; i >= 0; --i) {
            ChunkStatus chunkstatus = (ChunkStatus) ChunkHolder.CHUNK_STATUSES.get(i);
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getFutureIfPresentUnchecked(chunkstatus);

            if (!completablefuture.isCompletedExceptionally()) {
                Optional<ChunkAccess> optional = ((Either) completablefuture.getNow(ChunkHolder.UNLOADED_CHUNK)).left();

                if (optional.isPresent()) {
                    return (ChunkAccess) optional.get();
                }
            }
        }

        return null;
    }

    public CompletableFuture<ChunkAccess> getChunkToSave() {
        return this.chunkToSave;
    }

    public void blockChanged(BlockPos pos) {
        LevelChunk chunk = this.getTickingChunk();

        if (chunk != null) {
            int i = this.levelHeightAccessor.getSectionIndex(pos.getY());

            if (i < 0 || i >= this.changedBlocksPerSection.length) return; // CraftBukkit - SPIGOT-6086, SPIGOT-6296
            if (this.changedBlocksPerSection[i] == null) {
                this.hasChangedSections = true;
                this.changedBlocksPerSection[i] = new ShortOpenHashSet();
            }

            this.changedBlocksPerSection[i].add(SectionPos.sectionRelativePos(pos));
        }
    }

    public void sectionLightChanged(LightLayer lightType, int y) {
        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = (Either) this.getFutureIfPresent(ChunkStatus.FEATURES).getNow(null); // CraftBukkit - decompile error

        if (either != null) {
            ChunkAccess ichunkaccess = (ChunkAccess) either.left().orElse(null); // CraftBukkit - decompile error

            if (ichunkaccess != null) {
                ichunkaccess.setUnsaved(true);
                LevelChunk chunk = this.getTickingChunk();

                if (chunk != null) {
                    int j = this.lightEngine.getMinLightSection();
                    int k = this.lightEngine.getMaxLightSection();

                    if (y >= j && y <= k) {
                        int l = y - j;

                        if (lightType == LightLayer.SKY) {
                            this.skyChangedLightSectionFilter.set(l);
                        } else {
                            this.blockChangedLightSectionFilter.set(l);
                        }

                    }
                }
            }
        }
    }

    public void broadcastChanges(LevelChunk chunk) {
        if (this.hasChangedSections || !this.skyChangedLightSectionFilter.isEmpty() || !this.blockChangedLightSectionFilter.isEmpty()) {
            Level world = chunk.getLevel();
            int i = 0;

            int j;

            for (j = 0; j < this.changedBlocksPerSection.length; ++j) {
                i += this.changedBlocksPerSection[j] != null ? this.changedBlocksPerSection[j].size() : 0;
            }

            this.resendLight |= i >= 64;
            if (!this.skyChangedLightSectionFilter.isEmpty() || !this.blockChangedLightSectionFilter.isEmpty()) {
                this.broadcast(new ClientboundLightUpdatePacket(chunk.getPos(), this.lightEngine, this.skyChangedLightSectionFilter, this.blockChangedLightSectionFilter, true), !this.resendLight);
                this.skyChangedLightSectionFilter.clear();
                this.blockChangedLightSectionFilter.clear();
            }

            for (j = 0; j < this.changedBlocksPerSection.length; ++j) {
                ShortSet shortset = this.changedBlocksPerSection[j];

                if (shortset != null) {
                    int k = this.levelHeightAccessor.getSectionYFromSectionIndex(j);
                    SectionPos sectionposition = SectionPos.of(chunk.getPos(), k);

                    if (shortset.size() == 1) {
                        BlockPos blockposition = sectionposition.relativeToBlockPos(shortset.iterator().nextShort());
                        BlockState iblockdata = world.getBlockState(blockposition);

                        this.broadcast(new ClientboundBlockUpdatePacket(blockposition, iblockdata), false);
                        this.broadcastBlockEntityIfNeeded(world, blockposition, iblockdata);
                    } else {
                        LevelChunkSection chunksection = chunk.getSection(j);
                        ClientboundSectionBlocksUpdatePacket packetplayoutmultiblockchange = new ClientboundSectionBlocksUpdatePacket(sectionposition, shortset, chunksection, this.resendLight);

                        this.broadcast(packetplayoutmultiblockchange, false);
                        packetplayoutmultiblockchange.runUpdates((blockposition1, iblockdata1) -> {
                            this.broadcastBlockEntityIfNeeded(world, blockposition1, iblockdata1);
                        });
                    }

                    this.changedBlocksPerSection[j] = null;
                }
            }

            this.hasChangedSections = false;
        }
    }

    private void broadcastBlockEntityIfNeeded(Level world, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity()) {
            this.broadcastBlockEntity(world, pos);
        }

    }

    private void broadcastBlockEntity(Level world, BlockPos pos) {
        BlockEntity tileentity = world.getBlockEntity(pos);

        if (tileentity != null) {
            Packet<?> packet = tileentity.getUpdatePacket();

            if (packet != null) {
                this.broadcast(packet, false);
            }
        }

    }

    public void broadcast(Packet<?> packet, boolean onlyOnWatchDistanceEdge) {
        this.playerProvider.getPlayers(this.pos, onlyOnWatchDistanceEdge).forEach((entityplayer) -> {
            entityplayer.connection.send(packet);
        });
    }

    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getOrScheduleFuture(ChunkStatus targetStatus, ChunkMap chunkStorage) {
        int i = targetStatus.getIndex();
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = (CompletableFuture) this.futures.get(i);

        if (completablefuture != null) {
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = (Either) completablefuture.getNow(ChunkHolder.NOT_DONE_YET);

            if (either == null) {
                String s = "value in future for status: " + targetStatus + " was incorrectly set to null at chunk: " + this.pos;

                throw chunkStorage.debugFuturesAndCreateReportedException(new IllegalStateException("null value previously set for chunk status"), s);
            }

            if (either == ChunkHolder.NOT_DONE_YET || either.right().isEmpty()) {
                return completablefuture;
            }
        }

        if (ChunkHolder.getStatus(this.ticketLevel).isOrAfter(targetStatus)) {
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture1 = chunkStorage.schedule(this, targetStatus);

            this.updateChunkToSave(completablefuture1, "schedule " + targetStatus);
            this.futures.set(i, completablefuture1);
            return completablefuture1;
        } else {
            return completablefuture == null ? ChunkHolder.UNLOADED_CHUNK_FUTURE : completablefuture;
        }
    }

    protected void addSaveDependency(String thenDesc, CompletableFuture<?> then) {
        if (this.chunkToSaveHistory != null) {
            this.chunkToSaveHistory.push(new ChunkHolder.ChunkSaveDebug(Thread.currentThread(), then, thenDesc));
        }

        this.chunkToSave = this.chunkToSave.thenCombine(then, (ichunkaccess, object) -> {
            return ichunkaccess;
        });
    }

    private void updateChunkToSave(CompletableFuture<? extends Either<? extends ChunkAccess, ChunkHolder.ChunkLoadingFailure>> then, String thenDesc) {
        if (this.chunkToSaveHistory != null) {
            this.chunkToSaveHistory.push(new ChunkHolder.ChunkSaveDebug(Thread.currentThread(), then, thenDesc));
        }

        this.chunkToSave = this.chunkToSave.thenCombine(then, (ichunkaccess, either) -> {
            return (ChunkAccess) either.map((ichunkaccess1) -> {
                return ichunkaccess1;
            }, (playerchunk_failure) -> {
                return ichunkaccess;
            });
        });
    }

    public ChunkHolder.FullChunkStatus getFullStatus() {
        return ChunkHolder.getFullChunkStatus(this.ticketLevel);
    }

    public ChunkPos getPos() {
        return this.pos;
    }

    public int getTicketLevel() {
        return this.ticketLevel;
    }

    public int getQueueLevel() {
        return this.queueLevel;
    }

    private void setQueueLevel(int level) {
        this.queueLevel = level;
    }

    public void setTicketLevel(int level) {
        this.ticketLevel = level;
    }

    private void scheduleFullChunkPromotion(ChunkMap playerchunkmap, CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> completablefuture, Executor executor, ChunkHolder.FullChunkStatus playerchunk_state) {
        this.pendingFullStateConfirmation.cancel(false);
        CompletableFuture<Void> completablefuture1 = new CompletableFuture();

        completablefuture1.thenRunAsync(() -> {
            playerchunkmap.onFullChunkStatusChange(this.pos, playerchunk_state);
        }, executor);
        this.pendingFullStateConfirmation = completablefuture1;
        completablefuture.thenAccept((either) -> {
            either.ifLeft((chunk) -> {
                completablefuture1.complete(null); // CraftBukkit - decompile error
            });
        });
    }

    private void demoteFullChunk(ChunkMap playerchunkmap, ChunkHolder.FullChunkStatus playerchunk_state) {
        this.pendingFullStateConfirmation.cancel(false);
        playerchunkmap.onFullChunkStatusChange(this.pos, playerchunk_state);
    }

    protected void updateFutures(ChunkMap chunkStorage, Executor executor) {
        ChunkStatus chunkstatus = ChunkHolder.getStatus(this.oldTicketLevel);
        ChunkStatus chunkstatus1 = ChunkHolder.getStatus(this.ticketLevel);
        boolean flag = this.oldTicketLevel <= ChunkMap.MAX_CHUNK_DISTANCE;
        boolean flag1 = this.ticketLevel <= ChunkMap.MAX_CHUNK_DISTANCE;
        ChunkHolder.FullChunkStatus playerchunk_state = ChunkHolder.getFullChunkStatus(this.oldTicketLevel);
        ChunkHolder.FullChunkStatus playerchunk_state1 = ChunkHolder.getFullChunkStatus(this.ticketLevel);
        // CraftBukkit start
        // ChunkUnloadEvent: Called before the chunk is unloaded: isChunkLoaded is still true and chunk can still be modified by plugins.
        if (playerchunk_state.isOrAfter(ChunkHolder.FullChunkStatus.BORDER) && !playerchunk_state1.isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
            this.getFutureIfPresentUnchecked(ChunkStatus.FULL).thenAccept((either) -> {
                LevelChunk chunk = (LevelChunk)either.left().orElse(null);
                if (chunk != null) {
                    chunkStorage.callbackExecutor.execute(() -> {
                        // Minecraft will apply the chunks tick lists to the world once the chunk got loaded, and then store the tick
                        // lists again inside the chunk once the chunk becomes inaccessible and set the chunk's needsSaving flag.
                        // These actions may however happen deferred, so we manually set the needsSaving flag already here.
                        chunk.setUnsaved(true);
                        chunk.unloadCallback();
                    });
                }
            }).exceptionally((throwable) -> {
                // ensure exceptions are printed, by default this is not the case
                MinecraftServer.LOGGER.error("Failed to schedule unload callback for chunk " + ChunkHolder.this.pos, throwable);
                return null;
            });

            // Run callback right away if the future was already done
            chunkStorage.callbackExecutor.run();
        }
        // CraftBukkit end

        if (flag) {
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = Either.right(new ChunkHolder.ChunkLoadingFailure() {
                public String toString() {
                    return "Unloaded ticket level " + ChunkHolder.this.pos;
                }
            });

            for (int i = flag1 ? chunkstatus1.getIndex() + 1 : 0; i <= chunkstatus.getIndex(); ++i) {
                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = (CompletableFuture) this.futures.get(i);

                if (completablefuture == null) {
                    this.futures.set(i, CompletableFuture.completedFuture(either));
                }
            }
        }

        boolean flag2 = playerchunk_state.isOrAfter(ChunkHolder.FullChunkStatus.BORDER);
        boolean flag3 = playerchunk_state1.isOrAfter(ChunkHolder.FullChunkStatus.BORDER);

        this.wasAccessibleSinceLastSave |= flag3;
        if (!flag2 && flag3) {
            this.fullChunkFuture = chunkStorage.prepareAccessibleChunk(this);
            this.scheduleFullChunkPromotion(chunkStorage, this.fullChunkFuture, executor, ChunkHolder.FullChunkStatus.BORDER);
            this.updateChunkToSave(this.fullChunkFuture, "full");
        }

        if (flag2 && !flag3) {
            this.fullChunkFuture.complete(ChunkHolder.UNLOADED_LEVEL_CHUNK);
            this.fullChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
        }

        boolean flag4 = playerchunk_state.isOrAfter(ChunkHolder.FullChunkStatus.TICKING);
        boolean flag5 = playerchunk_state1.isOrAfter(ChunkHolder.FullChunkStatus.TICKING);

        if (!flag4 && flag5) {
            this.tickingChunkFuture = chunkStorage.prepareTickingChunk(this);
            this.scheduleFullChunkPromotion(chunkStorage, this.tickingChunkFuture, executor, ChunkHolder.FullChunkStatus.TICKING);
            this.updateChunkToSave(this.tickingChunkFuture, "ticking");
        }

        if (flag4 && !flag5) {
            this.tickingChunkFuture.complete(ChunkHolder.UNLOADED_LEVEL_CHUNK);
            this.tickingChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
        }

        boolean flag6 = playerchunk_state.isOrAfter(ChunkHolder.FullChunkStatus.ENTITY_TICKING);
        boolean flag7 = playerchunk_state1.isOrAfter(ChunkHolder.FullChunkStatus.ENTITY_TICKING);

        if (!flag6 && flag7) {
            if (this.entityTickingChunkFuture != ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE) {
                throw (IllegalStateException) Util.pauseInIde(new IllegalStateException());
            }

            this.entityTickingChunkFuture = chunkStorage.prepareEntityTickingChunk(this.pos);
            this.scheduleFullChunkPromotion(chunkStorage, this.entityTickingChunkFuture, executor, ChunkHolder.FullChunkStatus.ENTITY_TICKING);
            this.updateChunkToSave(this.entityTickingChunkFuture, "entity ticking");
        }

        if (flag6 && !flag7) {
            this.entityTickingChunkFuture.complete(ChunkHolder.UNLOADED_LEVEL_CHUNK);
            this.entityTickingChunkFuture = ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
        }

        if (!playerchunk_state1.isOrAfter(playerchunk_state)) {
            this.demoteFullChunk(chunkStorage, playerchunk_state1);
        }

        this.onLevelChange.onLevelChange(this.pos, this::getQueueLevel, this.ticketLevel, this::setQueueLevel);
        this.oldTicketLevel = this.ticketLevel;
        // CraftBukkit start
        // ChunkLoadEvent: Called after the chunk is loaded: isChunkLoaded returns true and chunk is ready to be modified by plugins.
        if (!playerchunk_state.isOrAfter(ChunkHolder.FullChunkStatus.BORDER) && playerchunk_state1.isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
            this.getFutureIfPresentUnchecked(ChunkStatus.FULL).thenAccept((either) -> {
                LevelChunk chunk = (LevelChunk)either.left().orElse(null);
                if (chunk != null) {
                    chunkStorage.callbackExecutor.execute(() -> {
                        chunk.loadCallback();
                    });
                }
            }).exceptionally((throwable) -> {
                // ensure exceptions are printed, by default this is not the case
                MinecraftServer.LOGGER.error("Failed to schedule load callback for chunk " + ChunkHolder.this.pos, throwable);
                return null;
            });

            // Run callback right away if the future was already done
            chunkStorage.callbackExecutor.run();
        }
        // CraftBukkit end
    }

    public static ChunkStatus getStatus(int level) {
        return level < 33 ? ChunkStatus.FULL : ChunkStatus.getStatusAroundFullChunk(level - 33);
    }

    public static ChunkHolder.FullChunkStatus getFullChunkStatus(int distance) {
        return ChunkHolder.FULL_CHUNK_STATUSES[Mth.clamp(33 - distance + 1, 0, ChunkHolder.FULL_CHUNK_STATUSES.length - 1)];
    }

    public boolean wasAccessibleSinceLastSave() {
        return this.wasAccessibleSinceLastSave;
    }

    public void refreshAccessibility() {
        this.wasAccessibleSinceLastSave = ChunkHolder.getFullChunkStatus(this.ticketLevel).isOrAfter(ChunkHolder.FullChunkStatus.BORDER);
    }

    public void replaceProtoChunk(ImposterProtoChunk chunk) {
        for (int i = 0; i < this.futures.length(); ++i) {
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = (CompletableFuture) this.futures.get(i);

            if (completablefuture != null) {
                Optional<ChunkAccess> optional = ((Either) completablefuture.getNow(ChunkHolder.UNLOADED_CHUNK)).left();

                if (!optional.isEmpty() && optional.get() instanceof ProtoChunk) {
                    this.futures.set(i, CompletableFuture.completedFuture(Either.left(chunk)));
                }
            }
        }

        this.updateChunkToSave(CompletableFuture.completedFuture(Either.left(chunk.getWrapped())), "replaceProto");
    }

    public List<Pair<ChunkStatus, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>>> getAllFutures() {
        List<Pair<ChunkStatus, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>>> list = new ArrayList();

        for (int i = 0; i < ChunkHolder.CHUNK_STATUSES.size(); ++i) {
            list.add(Pair.of((ChunkStatus) ChunkHolder.CHUNK_STATUSES.get(i), (CompletableFuture) this.futures.get(i)));
        }

        return list;
    }

    @FunctionalInterface
    public interface LevelChangeListener {

        void onLevelChange(ChunkPos pos, IntSupplier levelGetter, int targetLevel, IntConsumer levelSetter);
    }

    public interface PlayerProvider {

        List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge);
    }

    private static final class ChunkSaveDebug {

        private final Thread thread;
        private final CompletableFuture<?> future;
        private final String source;

        ChunkSaveDebug(Thread thread, CompletableFuture<?> action, String actionDesc) {
            this.thread = thread;
            this.future = action;
            this.source = actionDesc;
        }
    }

    public static enum FullChunkStatus {

        INACCESSIBLE, BORDER, TICKING, ENTITY_TICKING;

        private FullChunkStatus() {}

        public boolean isOrAfter(ChunkHolder.FullChunkStatus levelType) {
            return this.ordinal() >= levelType.ordinal();
        }
    }

    public interface ChunkLoadingFailure {

        ChunkHolder.ChunkLoadingFailure UNLOADED = new ChunkHolder.ChunkLoadingFailure() {
            public String toString() {
                return "UNLOADED";
            }
        };
    }
}
