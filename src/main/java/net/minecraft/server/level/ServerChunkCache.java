package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;

public class ServerChunkCache extends ChunkSource {

    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger(); // Paper
    private static final List<ChunkStatus> CHUNK_STATUSES = ChunkStatus.getStatusList();
    private final DistanceManager distanceManager;
    final ServerLevel level;
    public final Thread mainThread;
    final ThreadedLevelLightEngine lightEngine;
    public final ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    public final ChunkMap chunkMap;
    private final DimensionDataStorage dataStorage;
    private long lastInhabitedUpdate;
    public boolean spawnEnemies = true;
    public boolean spawnFriendlies = true;
    private static final int CACHE_SIZE = 4;
    private final long[] lastChunkPos = new long[4];
    private final ChunkStatus[] lastChunkStatus = new ChunkStatus[4];
    private final ChunkAccess[] lastChunk = new ChunkAccess[4];
    @Nullable
    @VisibleForDebug
    private NaturalSpawner.SpawnState lastSpawnState;
    // Paper start
    final com.destroystokyo.paper.util.concurrent.WeakSeqLock loadedChunkMapSeqLock = new com.destroystokyo.paper.util.concurrent.WeakSeqLock();
    final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LevelChunk> loadedChunkMap = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(8192, 0.5f);

    private final LevelChunk[] lastLoadedChunks = new LevelChunk[4 * 4];

    private static int getChunkCacheKey(int x, int z) {
        return x & 3 | ((z & 3) << 2);
    }

    public void addLoadedChunk(LevelChunk chunk) {
        this.loadedChunkMapSeqLock.acquireWrite();
        try {
            this.loadedChunkMap.put(chunk.coordinateKey, chunk);
        } finally {
            this.loadedChunkMapSeqLock.releaseWrite();
        }

        // rewrite cache if we have to
        // we do this since we also cache null chunks
        int cacheKey = getChunkCacheKey(chunk.locX, chunk.locZ);

        this.lastLoadedChunks[cacheKey] = chunk;
    }

    public void removeLoadedChunk(LevelChunk chunk) {
        this.loadedChunkMapSeqLock.acquireWrite();
        try {
            this.loadedChunkMap.remove(chunk.coordinateKey);
        } finally {
            this.loadedChunkMapSeqLock.releaseWrite();
        }

        // rewrite cache if we have to
        // we do this since we also cache null chunks
        int cacheKey = getChunkCacheKey(chunk.locX, chunk.locZ);

        LevelChunk cachedChunk = this.lastLoadedChunks[cacheKey];
        if (cachedChunk != null && cachedChunk.coordinateKey == chunk.coordinateKey) {
            this.lastLoadedChunks[cacheKey] = null;
        }
    }

    public final LevelChunk getChunkAtIfLoadedMainThread(int x, int z) {
        int cacheKey = getChunkCacheKey(x, z);

        LevelChunk cachedChunk = this.lastLoadedChunks[cacheKey];
        if (cachedChunk != null && cachedChunk.locX == x & cachedChunk.locZ == z) {
            return cachedChunk;
        }

        long chunkKey = ChunkPos.asLong(x, z);

        cachedChunk = this.loadedChunkMap.get(chunkKey);
        // Skipping a null check to avoid extra instructions to improve inline capability
        this.lastLoadedChunks[cacheKey] = cachedChunk;
        return cachedChunk;
    }

    public final LevelChunk getChunkAtIfLoadedMainThreadNoCache(int x, int z) {
        return this.loadedChunkMap.get(ChunkPos.asLong(x, z));
    }

    public final LevelChunk getChunkAtMainThread(int x, int z) {
        LevelChunk ret = this.getChunkAtIfLoadedMainThread(x, z);
        if (ret != null) {
            return ret;
        }
        return (LevelChunk)this.getChunk(x, z, ChunkStatus.FULL, true);
    }

    long chunkFutureAwaitCounter; // Paper - private -> package private

    public void getEntityTickingChunkAsync(int x, int z, java.util.function.Consumer<LevelChunk> onLoad) {
        io.papermc.paper.chunk.system.ChunkSystem.scheduleTickingState(
            this.level, x, z, ChunkHolder.FullChunkStatus.ENTITY_TICKING, true,
            ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.NORMAL, onLoad
        );
    }

    public void getTickingChunkAsync(int x, int z, java.util.function.Consumer<LevelChunk> onLoad) {
        io.papermc.paper.chunk.system.ChunkSystem.scheduleTickingState(
            this.level, x, z, ChunkHolder.FullChunkStatus.TICKING, true,
            ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.NORMAL, onLoad
        );
    }

    public void getFullChunkAsync(int x, int z, java.util.function.Consumer<LevelChunk> onLoad) {
        io.papermc.paper.chunk.system.ChunkSystem.scheduleTickingState(
            this.level, x, z, ChunkHolder.FullChunkStatus.BORDER, true,
            ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.NORMAL, onLoad
        );
    }

    void chunkLoadAccept(int chunkX, int chunkZ, ChunkAccess chunk, java.util.function.Consumer<ChunkAccess> consumer) {
        try {
            consumer.accept(chunk);
        } catch (Throwable throwable) {
            if (throwable instanceof ThreadDeath) {
                throw (ThreadDeath)throwable;
            }
            LOGGER.error("Load callback for chunk " + chunkX + "," + chunkZ + " in world '" + this.level.getWorld().getName() + "' threw an exception", throwable);
        }
    }

    void getChunkAtAsynchronously(int chunkX, int chunkZ, int ticketLevel,
                                  java.util.function.Consumer<ChunkAccess> consumer) {
        if (ticketLevel <= 33) {
            this.getFullChunkAsync(chunkX, chunkZ, (java.util.function.Consumer)consumer);
            return;
        }

        io.papermc.paper.chunk.system.ChunkSystem.scheduleChunkLoad(
            this.level, chunkX, chunkZ, ChunkHolder.getStatus(ticketLevel), true,
            ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.NORMAL, consumer
        );
    }


    public final void getChunkAtAsynchronously(int chunkX, int chunkZ, ChunkStatus status, boolean gen, boolean allowSubTicketLevel, java.util.function.Consumer<ChunkAccess> onLoad) {
        // try to fire sync
        int chunkStatusTicketLevel = 33 + ChunkStatus.getDistance(status);
        ChunkHolder playerChunk = this.chunkMap.getUpdatingChunkIfPresent(io.papermc.paper.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
        if (playerChunk != null) {
            ChunkStatus holderStatus = playerChunk.getChunkHolderStatus();
            ChunkAccess immediate = playerChunk.getAvailableChunkNow();
            if (immediate != null) {
                if (allowSubTicketLevel ? immediate.getStatus().isOrAfter(status) : (playerChunk.getTicketLevel() <= chunkStatusTicketLevel && holderStatus != null && holderStatus.isOrAfter(status))) {
                    this.chunkLoadAccept(chunkX, chunkZ, immediate, onLoad);
                    return;
                } else {
                    if (gen || (!allowSubTicketLevel && immediate.getStatus().isOrAfter(status))) {
                        this.getChunkAtAsynchronously(chunkX, chunkZ, chunkStatusTicketLevel, onLoad);
                        return;
                    } else {
                        this.chunkLoadAccept(chunkX, chunkZ, null, onLoad);
                        return;
                    }
                }
            }
        }

        // need to fire async

        if (gen && !allowSubTicketLevel) {
            this.getChunkAtAsynchronously(chunkX, chunkZ, chunkStatusTicketLevel, onLoad);
            return;
        }

        this.getChunkAtAsynchronously(chunkX, chunkZ, io.papermc.paper.util.MCUtil.getTicketLevelFor(ChunkStatus.EMPTY), (ChunkAccess chunk) -> {
            if (chunk == null) {
                throw new IllegalStateException("Chunk cannot be null");
            }

            if (!chunk.getStatus().isOrAfter(status)) {
                if (gen) {
                    this.getChunkAtAsynchronously(chunkX, chunkZ, chunkStatusTicketLevel, onLoad);
                    return;
                } else {
                    ServerChunkCache.this.chunkLoadAccept(chunkX, chunkZ, null, onLoad);
                    return;
                }
            } else {
                if (allowSubTicketLevel) {
                    ServerChunkCache.this.chunkLoadAccept(chunkX, chunkZ, chunk, onLoad);
                    return;
                } else {
                    this.getChunkAtAsynchronously(chunkX, chunkZ, chunkStatusTicketLevel, onLoad);
                    return;
                }
            }
        });
    }
    // Paper end

    // Paper start
    @Nullable
    public ChunkAccess getChunkAtImmediately(int x, int z) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) {
            return null;
        }

        return holder.getLastAvailable();
    }

    // this will try to avoid chunk neighbours for lighting
    public final ChunkAccess getFullStatusChunkAt(int chunkX, int chunkZ) {
        LevelChunk ifLoaded = this.getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        if (ifLoaded != null) {
            return ifLoaded;
        }

        ChunkAccess empty = this.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, true);
        if (empty != null && empty.getStatus().isOrAfter(ChunkStatus.FULL)) {
            return empty;
        }
        return this.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
    }

    public final ChunkAccess getFullStatusChunkAtIfLoaded(int chunkX, int chunkZ) {
        LevelChunk ifLoaded = this.getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        if (ifLoaded != null) {
            return ifLoaded;
        }

        ChunkAccess ret = this.getChunkAtImmediately(chunkX, chunkZ);
        if (ret != null && ret.getStatus().isOrAfter(ChunkStatus.FULL)) {
            return ret;
        } else {
            return null;
        }
    }

    public <T> void addTicketAtLevel(TicketType<T> ticketType, ChunkPos chunkPos, int ticketLevel, T identifier) {
        this.distanceManager.addTicket(ticketType, chunkPos, ticketLevel, identifier);
    }

    public <T> void removeTicketAtLevel(TicketType<T> ticketType, ChunkPos chunkPos, int ticketLevel, T identifier) {
        this.distanceManager.removeTicket(ticketType, chunkPos, ticketLevel, identifier);
    }

    public final io.papermc.paper.util.maplist.IteratorSafeOrderedReferenceSet<LevelChunk> tickingChunks = new io.papermc.paper.util.maplist.IteratorSafeOrderedReferenceSet<>(4096, 0.75f, 4096, 0.15, true);
    public final io.papermc.paper.util.maplist.IteratorSafeOrderedReferenceSet<LevelChunk> entityTickingChunks = new io.papermc.paper.util.maplist.IteratorSafeOrderedReferenceSet<>(4096, 0.75f, 4096, 0.15, true);
    // Paper end

    public ServerChunkCache(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor workerExecutor, ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance, boolean dsync, ChunkProgressListener worldGenerationProgressListener, ChunkStatusUpdateListener chunkStatusChangeListener, Supplier<DimensionDataStorage> persistentStateManagerFactory) {
        this.level = world;
        this.mainThreadProcessor = new ServerChunkCache.MainThreadExecutor(world);
        this.mainThread = Thread.currentThread();
        File file = session.getDimensionPath(world.dimension()).resolve("data").toFile();

        file.mkdirs();
        this.dataStorage = new DimensionDataStorage(file, dataFixer);
        this.chunkMap = new ChunkMap(world, session, dataFixer, structureTemplateManager, workerExecutor, this.mainThreadProcessor, this, chunkGenerator, worldGenerationProgressListener, chunkStatusChangeListener, persistentStateManagerFactory, viewDistance, dsync);
        this.lightEngine = this.chunkMap.getLightEngine();
        this.distanceManager = this.chunkMap.getDistanceManager();
        this.distanceManager.updateSimulationDistance(simulationDistance);
        this.clearCache();
    }

    // CraftBukkit start - properly implement isChunkLoaded
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ChunkHolder chunk = this.chunkMap.getUpdatingChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (chunk == null) {
            return false;
        }
        return chunk.getFullChunkNow() != null;
    }
    // CraftBukkit end

    @Override
    public ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    @Nullable
    private ChunkHolder getVisibleChunkIfPresent(long pos) {
        return this.chunkMap.getVisibleChunkIfPresent(pos);
    }

    public int getTickingGenerated() {
        return this.chunkMap.getTickingGenerated();
    }

    private void storeInCache(long pos, ChunkAccess chunk, ChunkStatus status) {
        for (int j = 3; j > 0; --j) {
            this.lastChunkPos[j] = this.lastChunkPos[j - 1];
            this.lastChunkStatus[j] = this.lastChunkStatus[j - 1];
            this.lastChunk[j] = this.lastChunk[j - 1];
        }

        this.lastChunkPos[0] = pos;
        this.lastChunkStatus[0] = status;
        this.lastChunk[0] = chunk;
    }

    // Paper start - "real" get chunk if loaded
    // Note: Partially copied from the getChunkAt method below
    @Nullable
    public LevelChunk getChunkAtIfCachedImmediately(int x, int z) {
        long k = ChunkPos.asLong(x, z);

        // Note: Bypass cache since we need to check ticket level, and to make this MT-Safe

        ChunkHolder playerChunk = this.getVisibleChunkIfPresent(k);
        if (playerChunk == null) {
            return null;
        }

        return playerChunk.getFullChunkNowUnchecked();
    }

    @Nullable
    public LevelChunk getChunkAtIfLoadedImmediately(int x, int z) {
        long k = ChunkPos.asLong(x, z);

        if (io.papermc.paper.util.TickThread.isTickThread()) { // Paper - rewrite chunk system
            return this.getChunkAtIfLoadedMainThread(x, z);
        }

        LevelChunk ret = null;
        long readlock;
        do {
            readlock = this.loadedChunkMapSeqLock.acquireRead();
            try {
                ret = this.loadedChunkMap.get(k);
            } catch (Throwable thr) {
                if (thr instanceof ThreadDeath) {
                    throw (ThreadDeath)thr;
                }
                // re-try, this means a CME occurred...
                continue;
            }
        } while (!this.loadedChunkMapSeqLock.tryReleaseRead(readlock));

        return ret;
    }
    // Paper end
    // Paper start - async chunk io
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getChunkAtAsynchronously(int x, int z, boolean gen, boolean isUrgent) {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> ret = new CompletableFuture<>();

        ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority priority;
        if (isUrgent) {
            priority = ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.HIGHER;
        } else {
            priority = ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.NORMAL;
        }

        io.papermc.paper.chunk.system.ChunkSystem.scheduleChunkLoad(this.level, x, z, gen, ChunkStatus.FULL, true, priority, (chunk) -> {
            if (chunk == null) {
                ret.complete(ChunkHolder.UNLOADED_CHUNK);
            } else {
                ret.complete(Either.left(chunk));
            }
        });

        return ret;
    }
    // Paper end - async chunk io

    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus leastStatus, boolean create) {
        final int x1 = x; final int z1 = z; // Paper - conflict on variable change
        if (!io.papermc.paper.util.TickThread.isTickThread()) { // Paper - rewrite chunk system
            return (ChunkAccess) CompletableFuture.supplyAsync(() -> {
                return this.getChunk(x, z, leastStatus, create);
            }, this.mainThreadProcessor).join();
        } else {
            // Paper start - optimise for loaded chunks
            LevelChunk ifLoaded = this.getChunkAtIfLoadedMainThread(x, z);
            if (ifLoaded != null) {
                return ifLoaded;
            }
            // Paper end
            ProfilerFiller gameprofilerfiller = this.level.getProfiler();

            gameprofilerfiller.incrementCounter("getChunk");
            long k = ChunkPos.asLong(x, z);

            ChunkAccess ichunkaccess;

            // Paper - rewrite chunk system - there are no correct callbacks to remove items from cache in the new chunk system

            gameprofilerfiller.incrementCounter("getChunkCacheMiss");
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getChunkFutureMainThread(x, z, leastStatus, create, true); // Paper
            ServerChunkCache.MainThreadExecutor chunkproviderserver_b = this.mainThreadProcessor;

            Objects.requireNonNull(completablefuture);
            if (!completablefuture.isDone()) { // Paper
                // Paper start - async chunk io/loading
                io.papermc.paper.chunk.system.scheduling.ChunkTaskScheduler.pushChunkWait(this.level, x1, z1); // Paper - rewrite chunk system
                // Paper end
                com.destroystokyo.paper.io.SyncLoadFinder.logSyncLoad(this.level, x1, z1); // Paper - sync load info
                this.level.timings.syncChunkLoad.startTiming(); // Paper
            chunkproviderserver_b.managedBlock(completablefuture::isDone);
                io.papermc.paper.chunk.system.scheduling.ChunkTaskScheduler.popChunkWait(); // Paper - async chunk debug  // Paper - rewrite chunk system
                this.level.timings.syncChunkLoad.stopTiming(); // Paper
            } // Paper
            ichunkaccess = (ChunkAccess) ((Either) completablefuture.join()).map((ichunkaccess1) -> {
                return ichunkaccess1;
            }, (playerchunk_failure) -> {
                if (create) {
                    throw (IllegalStateException) Util.pauseInIde(new IllegalStateException("Chunk not there when requested: " + playerchunk_failure));
                } else {
                    return null;
                }
            });
            this.storeInCache(k, ichunkaccess, leastStatus);
            return ichunkaccess;
        }
    }

    @Nullable
    @Override
    public LevelChunk getChunkNow(int chunkX, int chunkZ) {
        if (!io.papermc.paper.util.TickThread.isTickThread()) { // Paper - rewrite chunk system
            return null;
        } else {
            return this.getChunkAtIfLoadedMainThread(chunkX, chunkZ); // Paper - optimise for loaded chunks
        }
    }

    private void clearCache() {
        Arrays.fill(this.lastChunkPos, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(this.lastChunkStatus, (Object) null);
        Arrays.fill(this.lastChunk, (Object) null);
    }

    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getChunkFuture(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        boolean flag1 = io.papermc.paper.util.TickThread.isTickThread(); // Paper - rewrite chunk system
        CompletableFuture completablefuture;

        if (flag1) {
            completablefuture = this.getChunkFutureMainThread(chunkX, chunkZ, leastStatus, create);
            ServerChunkCache.MainThreadExecutor chunkproviderserver_b = this.mainThreadProcessor;

            Objects.requireNonNull(completablefuture);
            chunkproviderserver_b.managedBlock(completablefuture::isDone);
        } else {
            completablefuture = CompletableFuture.supplyAsync(() -> {
                return this.getChunkFutureMainThread(chunkX, chunkZ, leastStatus, create);
            }, this.mainThreadProcessor).thenCompose((completablefuture1) -> {
                return completablefuture1;
            });
        }

        return completablefuture;
    }

    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getChunkFutureMainThread(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        // Paper start - add isUrgent - old sig left in place for dirty nms plugins
        return getChunkFutureMainThread(chunkX, chunkZ, leastStatus, create, false);
    }
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getChunkFutureMainThread(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create, boolean isUrgent) {
        // Paper start - rewrite chunk system
        io.papermc.paper.util.TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Scheduling chunk load off-main");
        int minLevel = 33 + ChunkStatus.getDistance(leastStatus);
        io.papermc.paper.chunk.system.scheduling.NewChunkHolder chunkHolder = this.level.chunkTaskScheduler.chunkHolderManager.getChunkHolder(chunkX, chunkZ);

        boolean needsFullScheduling = leastStatus == ChunkStatus.FULL && (chunkHolder == null || !chunkHolder.getChunkStatus().isOrAfter(ChunkHolder.FullChunkStatus.BORDER));

        if ((chunkHolder == null || chunkHolder.getTicketLevel() > minLevel || needsFullScheduling) && !create) {
            return ChunkHolder.UNLOADED_CHUNK_FUTURE;
        }

        io.papermc.paper.chunk.system.scheduling.NewChunkHolder.ChunkCompletion chunkCompletion = chunkHolder == null ? null : chunkHolder.getLastChunkCompletion();
        if (needsFullScheduling || chunkCompletion == null || !chunkCompletion.genStatus().isOrAfter(leastStatus)) {
            // schedule
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> ret = new CompletableFuture<>();
            Consumer<ChunkAccess> complete = (ChunkAccess chunk) -> {
                if (chunk == null) {
                    ret.complete(Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED));
                } else {
                    ret.complete(Either.left(chunk));
                }
            };

            this.level.chunkTaskScheduler.scheduleChunkLoad(
                chunkX, chunkZ, leastStatus, true,
                isUrgent ? ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.BLOCKING : ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor.Priority.NORMAL,
                complete
            );

            return ret;
        } else {
            // can return now
            return CompletableFuture.completedFuture(Either.left(chunkCompletion.chunk()));
        }
        // Paper end - rewrite chunk system
    }

    // Paper - rewrite chunk system

    @Override
    public boolean hasChunk(int x, int z) {
        return this.getChunkAtIfLoadedImmediately(x, z) != null; // Paper - rewrite chunk system
    }

    @Override
    public BlockGetter getChunkForLighting(int chunkX, int chunkZ) {
        long k = ChunkPos.asLong(chunkX, chunkZ);
        ChunkHolder playerchunk = this.getVisibleChunkIfPresent(k);

        if (playerchunk == null) {
            return null;
        } else {
            // Paper start - rewrite chunk system
            ChunkStatus status = playerchunk.getChunkHolderStatus();
            if (status != null && !status.isOrAfter(ChunkStatus.LIGHT.getParent())) {
                return null;
            }
            return playerchunk.getAvailableChunkNow();
            // Paper end - rewrite chunk system
        }
    }

    @Override
    public Level getLevel() {
        return this.level;
    }

    public boolean pollTask() {
        return this.mainThreadProcessor.pollTask();
    }

    boolean runDistanceManagerUpdates() {
        return this.level.chunkTaskScheduler.chunkHolderManager.processTicketUpdates(); // Paper - rewrite chunk system
    }

    // Paper start
    public boolean isPositionTicking(Entity entity) {
        return this.isPositionTicking(ChunkPos.asLong(net.minecraft.util.Mth.floor(entity.getX()) >> 4, net.minecraft.util.Mth.floor(entity.getZ()) >> 4));
    }
    // Paper end

    public boolean isPositionTicking(long pos) {
        // Paper start - replace player chunk loader system
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.isTickingReady();
        // Paper end - replace player chunk loader system
    }

    public void save(boolean flush) {
        this.runDistanceManagerUpdates();
        try (co.aikar.timings.Timing timed = level.timings.chunkSaveData.startTiming()) { // Paper - Timings
        this.chunkMap.saveAllChunks(flush);
        } // Paper - Timings
    }

    // Paper start - duplicate save, but call incremental
    public void saveIncrementally() {
        this.runDistanceManagerUpdates();
        try (co.aikar.timings.Timing timed = level.timings.chunkSaveData.startTiming()) { // Paper - Timings
            this.chunkMap.saveIncrementally();
        } // Paper - Timings
    }
    // Paper end

    @Override
    public void close() throws IOException {
        // CraftBukkit start
        this.close(true);
    }

    public void close(boolean save) { // Paper - rewrite chunk system
        this.level.chunkTaskScheduler.chunkHolderManager.close(save, true); // Paper - rewrite chunk system
    }

    // CraftBukkit start - modelled on below
    public void purgeUnload() {
        if (true) return; // Paper - tickets will be removed later, this behavior isn't really well accounted for by the chunk system
        this.level.getProfiler().push("purge");
        this.distanceManager.purgeStaleTickets();
        this.runDistanceManagerUpdates();
        this.level.getProfiler().popPush("unload");
        this.chunkMap.tick(() -> true);
        this.level.getProfiler().pop();
        this.clearCache();
    }
    // CraftBukkit end

    @Override
    public void tick(BooleanSupplier shouldKeepTicking, boolean tickChunks) {
        this.level.getProfiler().push("purge");
        this.level.timings.doChunkMap.startTiming(); // Spigot
        this.distanceManager.purgeStaleTickets();
        this.runDistanceManagerUpdates();
        this.level.timings.doChunkMap.stopTiming(); // Spigot
        this.level.getProfiler().popPush("chunks");
        if (tickChunks) {
            this.level.timings.chunks.startTiming(); // Paper - timings
            this.chunkMap.playerChunkManager.tick(); // Paper - this is mostly is to account for view distance changes
            this.tickChunks();
            this.level.timings.chunks.stopTiming(); // Paper - timings
        }

        this.level.timings.doChunkUnload.startTiming(); // Spigot
        this.level.getProfiler().popPush("unload");
        this.chunkMap.tick(shouldKeepTicking);
        this.level.timings.doChunkUnload.stopTiming(); // Spigot
        this.level.getProfiler().pop();
        this.clearCache();
    }

    private void tickChunks() {
        long i = this.level.getGameTime();
        long j = i - this.lastInhabitedUpdate;

        this.lastInhabitedUpdate = i;
        boolean flag = this.level.isDebug();

        if (flag) {
            this.chunkMap.tick();
        } else {
            // Paper start - optimize isOutisdeRange
            ChunkMap playerChunkMap = this.chunkMap;
            for (ServerPlayer player : this.level.players) {
                if (!player.affectsSpawning || player.isSpectator()) {
                    playerChunkMap.playerMobSpawnMap.remove(player);
                    continue;
                }

                int viewDistance = this.chunkMap.getEffectiveViewDistance();

                // copied and modified from isOutisdeRange
                int chunkRange = level.spigotConfig.mobSpawnRange;
                chunkRange = (chunkRange > viewDistance) ? (byte)viewDistance : chunkRange;
                chunkRange = (chunkRange > DistanceManager.MOB_SPAWN_RANGE) ? DistanceManager.MOB_SPAWN_RANGE : chunkRange;

                com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event = new com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent(player.getBukkitEntity(), (byte)chunkRange);
                event.callEvent();
                if (event.isCancelled() || event.getSpawnRadius() < 0 || playerChunkMap.playerChunkTickRangeMap.getLastViewDistance(player) == -1) {
                    playerChunkMap.playerMobSpawnMap.remove(player);
                    continue;
                }

                int range = Math.min(event.getSpawnRadius(), 32); // limit to max view distance
                int chunkX = io.papermc.paper.util.MCUtil.getChunkCoordinate(player.getX());
                int chunkZ = io.papermc.paper.util.MCUtil.getChunkCoordinate(player.getZ());

                playerChunkMap.playerMobSpawnMap.addOrUpdate(player, chunkX, chunkZ, range);
                player.lastEntitySpawnRadiusSquared = (double)((range << 4) * (range << 4)); // used in anyPlayerCloseEnoughForSpawning
                player.playerNaturallySpawnedEvent = event;
            }
            // Paper end - optimize isOutisdeRange
            LevelData worlddata = this.level.getLevelData();
            ProfilerFiller gameprofilerfiller = this.level.getProfiler();

            gameprofilerfiller.push("pollingChunks");
            int k = this.level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
            boolean flag1 = level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) != 0L && worlddata.getGameTime() % level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) == 0L; // CraftBukkit

            gameprofilerfiller.push("naturalSpawnCount");
            this.level.timings.countNaturalMobs.startTiming(); // Paper - timings
            int l = this.distanceManager.getNaturalSpawnChunkCount();
            // Paper start - per player mob spawning
            NaturalSpawner.SpawnState spawnercreature_d; // moved down
            if ((this.spawnFriendlies || this.spawnEnemies) && this.chunkMap.playerMobDistanceMap != null) { // don't count mobs when animals and monsters are disabled
                // re-set mob counts
                for (ServerPlayer player : this.level.players) {
                    Arrays.fill(player.mobCounts, 0);
                }
                spawnercreature_d = NaturalSpawner.createState(l, this.level.getAllEntities(), this::getFullChunk, null, true);
            } else {
                spawnercreature_d = NaturalSpawner.createState(l, this.level.getAllEntities(), this::getFullChunk, this.chunkMap.playerMobDistanceMap == null ? new LocalMobCapCalculator(this.chunkMap) : null, false);
            }
            // Paper end
            this.level.timings.countNaturalMobs.stopTiming(); // Paper - timings

            this.lastSpawnState = spawnercreature_d;
            gameprofilerfiller.popPush("filteringLoadedChunks");
            List<ServerChunkCache.ChunkAndHolder> list = Lists.newArrayListWithCapacity(l);
            Iterator iterator = this.chunkMap.getChunks().iterator();
            this.level.timings.chunkTicks.startTiming(); // Paper

            while (iterator.hasNext()) {
                ChunkHolder playerchunk = (ChunkHolder) iterator.next();
                LevelChunk chunk = playerchunk.getTickingChunk();

                if (chunk != null) {
                    list.add(new ServerChunkCache.ChunkAndHolder(chunk, playerchunk));
                }
            }

            gameprofilerfiller.popPush("spawnAndTick");
            boolean flag2 = this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) && !this.level.players().isEmpty(); // CraftBukkit

            Collections.shuffle(list);
            // Paper - moved natural spawn event up
            Iterator iterator1 = list.iterator();

            while (iterator1.hasNext()) {
                ServerChunkCache.ChunkAndHolder chunkproviderserver_a = (ServerChunkCache.ChunkAndHolder) iterator1.next();
                LevelChunk chunk1 = chunkproviderserver_a.chunk;
                ChunkPos chunkcoordintpair = chunk1.getPos();

                if (this.level.isNaturalSpawningAllowed(chunkcoordintpair) && this.chunkMap.anyPlayerCloseEnoughForSpawning(chunkproviderserver_a.holder, chunkcoordintpair, false)) { // Paper - optimise anyPlayerCloseEnoughForSpawning
                    chunk1.incrementInhabitedTime(j);
                    if (flag2 && (this.spawnEnemies || this.spawnFriendlies) && this.level.getWorldBorder().isWithinBounds(chunkcoordintpair) && this.chunkMap.anyPlayerCloseEnoughForSpawning(chunkproviderserver_a.holder, chunkcoordintpair, true)) { // Spigot // Paper - optimise anyPlayerCloseEnoughForSpawning
                        NaturalSpawner.spawnForChunk(this.level, chunk1, spawnercreature_d, this.spawnFriendlies, this.spawnEnemies, flag1);
                    }

                    if (this.level.shouldTickBlocksAt(chunkcoordintpair.toLong())) {
                        this.level.tickChunk(chunk1, k);
                    }
                }
            }
            this.level.timings.chunkTicks.stopTiming(); // Paper
            gameprofilerfiller.popPush("customSpawners");
            if (flag2) {
                try (co.aikar.timings.Timing ignored = this.level.timings.miscMobSpawning.startTiming()) { // Paper - timings
                this.level.tickCustomSpawners(this.spawnEnemies, this.spawnFriendlies);
                } // Paper - timings
            }

            gameprofilerfiller.popPush("broadcast");
            list.forEach((chunkproviderserver_a1) -> {
                this.level.timings.broadcastChunkUpdates.startTiming(); // Paper - timing
                chunkproviderserver_a1.holder.broadcastChanges(chunkproviderserver_a1.chunk);
                this.level.timings.broadcastChunkUpdates.stopTiming(); // Paper - timing
            });
            gameprofilerfiller.pop();
            gameprofilerfiller.pop();
            this.chunkMap.tick();
        }
    }

    private void getFullChunk(long pos, Consumer<LevelChunk> chunkConsumer) {
        ChunkHolder playerchunk = this.getVisibleChunkIfPresent(pos);

        if (playerchunk != null) {
            // Paper start - rewrite chunk system
            LevelChunk chunk = playerchunk.getFullChunk();
            if (chunk != null) {
                chunkConsumer.accept(chunk);
            }
            // Paper end - rewrite chunk system
        }

    }

    @Override
    public String gatherStats() {
        return Integer.toString(this.getLoadedChunksCount());
    }

    @VisibleForTesting
    public int getPendingTasksCount() {
        return this.mainThreadProcessor.getPendingTasksCount();
    }

    public ChunkGenerator getGenerator() {
        return this.chunkMap.generator();
    }

    public ChunkGeneratorStructureState getGeneratorState() {
        return this.chunkMap.generatorState();
    }

    public RandomState randomState() {
        return this.chunkMap.randomState();
    }

    @Override
    public int getLoadedChunksCount() {
        return this.chunkMap.size();
    }

    public void blockChanged(BlockPos pos) {
        int i = SectionPos.blockToSectionCoord(pos.getX());
        int j = SectionPos.blockToSectionCoord(pos.getZ());
        ChunkHolder playerchunk = this.getVisibleChunkIfPresent(ChunkPos.asLong(i, j));

        if (playerchunk != null) {
            playerchunk.blockChanged(pos);
        }

    }

    @Override
    public void onLightUpdate(LightLayer type, SectionPos pos) {
        this.mainThreadProcessor.execute(() -> {
            ChunkHolder playerchunk = this.getVisibleChunkIfPresent(pos.chunk().toLong());

            if (playerchunk != null) {
                playerchunk.sectionLightChanged(type, pos.y());
            }

        });
    }

    public <T> void addRegionTicket(TicketType<T> ticketType, ChunkPos pos, int radius, T argument) {
        this.distanceManager.addRegionTicket(ticketType, pos, radius, argument);
    }

    public <T> void removeRegionTicket(TicketType<T> ticketType, ChunkPos pos, int radius, T argument) {
        this.distanceManager.removeRegionTicket(ticketType, pos, radius, argument);
    }

    @Override
    public void updateChunkForced(ChunkPos pos, boolean forced) {
        this.distanceManager.updateChunkForced(pos, forced);
    }

    public void move(ServerPlayer player) {
        if (!player.isRemoved()) {
            this.chunkMap.move(player);
        }

    }

    public void removeEntity(Entity entity) {
        this.chunkMap.removeEntity(entity);
    }

    public void addEntity(Entity entity) {
        this.chunkMap.addEntity(entity);
    }

    public void broadcastAndSend(Entity entity, Packet<?> packet) {
        this.chunkMap.broadcastAndSend(entity, packet);
    }

    public void broadcast(Entity entity, Packet<?> packet) {
        this.chunkMap.broadcast(entity, packet);
    }

    public void setViewDistance(int watchDistance) {
        this.chunkMap.setViewDistance(watchDistance);
    }

    public void setSimulationDistance(int simulationDistance) {
        this.distanceManager.updateSimulationDistance(simulationDistance);
    }

    @Override
    public void setSpawnSettings(boolean spawnMonsters, boolean spawnAnimals) {
        this.spawnEnemies = spawnMonsters;
        this.spawnFriendlies = spawnAnimals;
    }

    public String getChunkDebugData(ChunkPos pos) {
        return this.chunkMap.getChunkDebugData(pos);
    }

    public DimensionDataStorage getDataStorage() {
        return this.dataStorage;
    }

    public PoiManager getPoiManager() {
        return this.chunkMap.getPoiManager();
    }

    public ChunkScanAccess chunkScanner() {
        return this.chunkMap.chunkScanner();
    }

    @Nullable
    @VisibleForDebug
    public NaturalSpawner.SpawnState getLastSpawnState() {
        return this.lastSpawnState;
    }

    public void removeTicketsOnClosing() {
        this.distanceManager.removeTicketsOnClosing();
    }

    public final class MainThreadExecutor extends BlockableEventLoop<Runnable> {

        MainThreadExecutor(Level world) {
            super("Chunk source main thread executor for " + world.dimension().location());
        }

        @Override
        protected Runnable wrapRunnable(Runnable runnable) {
            return runnable;
        }

        @Override
        protected boolean shouldRun(Runnable task) {
            return true;
        }

        @Override
        protected boolean scheduleExecutables() {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return ServerChunkCache.this.mainThread;
        }

        @Override
        protected void doRunTask(Runnable task) {
            ServerChunkCache.this.level.getProfiler().incrementCounter("runTask");
            super.doRunTask(task);
        }

        @Override
        // CraftBukkit start - process pending Chunk loadCallback() and unloadCallback() after each run task
        public boolean pollTask() {
            ServerChunkCache.this.chunkMap.playerChunkManager.tickMidTick();
            if (ServerChunkCache.this.runDistanceManagerUpdates()) {
                return true;
            }
            return super.pollTask() | ServerChunkCache.this.level.chunkTaskScheduler.executeMainThreadTask(); // Paper - rewrite chunk system
        }
    }

    private static record ChunkAndHolder(LevelChunk chunk, ChunkHolder holder) {

    }
}
