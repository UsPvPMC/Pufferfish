package net.minecraft.server.level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.Util;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.DebugPackets;
import io.papermc.paper.util.MCUtil;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.CsvOutput;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;
import org.bukkit.entity.Player;
// CraftBukkit end

public class ChunkMap extends ChunkStorage implements ChunkHolder.PlayerProvider {

    private static final byte CHUNK_TYPE_REPLACEABLE = -1;
    private static final byte CHUNK_TYPE_UNKNOWN = 0;
    private static final byte CHUNK_TYPE_FULL = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_SAVED_PER_TICK = 200;
    private static final int CHUNK_SAVED_EAGERLY_PER_TICK = 20;
    private static final int EAGER_CHUNK_SAVE_COOLDOWN_IN_MILLIS = 10000;
    private static final int MIN_VIEW_DISTANCE = 3;
    public static final int MAX_VIEW_DISTANCE = 33;
    public static final int MAX_CHUNK_DISTANCE = 33 + ChunkStatus.maxDistance();
    public static final int FORCED_TICKET_LEVEL = 31;
    public final Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap = new Long2ObjectLinkedOpenHashMap();
    public volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    private final Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    private final LongSet entitiesInLevel;
    public final ServerLevel level;
    private final ThreadedLevelLightEngine lightEngine;
    private final BlockableEventLoop<Runnable> mainThreadExecutor;
    public ChunkGenerator generator;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState chunkGeneratorState;
    public final Supplier<DimensionDataStorage> overworldDataStorage;
    private final PoiManager poiManager;
    public final LongSet toDrop;
    private boolean modified;
    private final ChunkTaskPriorityQueueSorter queueSorter;
    private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> worldgenMailbox;
    private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> mainThreadMailbox;
    public final ChunkProgressListener progressListener;
    private final ChunkStatusUpdateListener chunkStatusListener;
    public final ChunkMap.ChunkDistanceManager distanceManager;
    private final AtomicInteger tickingGenerated;
    private final StructureTemplateManager structureTemplateManager;
    private final String storageName;
    private final PlayerMap playerMap;
    public final Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;
    private final Long2ByteMap chunkTypeCache;
    private final Long2LongMap chunkSaveCooldowns;
    private final Queue<Runnable> unloadQueue;
    int viewDistance;

    // CraftBukkit start - recursion-safe executor for Chunk loadCallback() and unloadCallback()
    public final CallbackExecutor callbackExecutor = new CallbackExecutor();
    public static final class CallbackExecutor implements java.util.concurrent.Executor, Runnable {

        private final java.util.Queue<Runnable> queue = new java.util.ArrayDeque<>();

        @Override
        public void execute(Runnable runnable) {
            this.queue.add(runnable);
        }

        @Override
        public void run() {
            Runnable task;
            while ((task = this.queue.poll()) != null) {
                task.run();
            }
        }
    };
    // CraftBukkit end

    // Paper start - distance maps
    private final com.destroystokyo.paper.util.misc.PooledLinkedHashSets<ServerPlayer> pooledLinkedPlayerHashSets = new com.destroystokyo.paper.util.misc.PooledLinkedHashSets<>();

    void addPlayerToDistanceMaps(ServerPlayer player) {
        int chunkX = MCUtil.getChunkCoordinate(player.getX());
        int chunkZ = MCUtil.getChunkCoordinate(player.getZ());
        // Note: players need to be explicitly added to distance maps before they can be updated
    }

    void removePlayerFromDistanceMaps(ServerPlayer player) {

    }

    void updateMaps(ServerPlayer player) {
        int chunkX = MCUtil.getChunkCoordinate(player.getX());
        int chunkZ = MCUtil.getChunkCoordinate(player.getZ());
        // Note: players need to be explicitly added to distance maps before they can be updated
    }
    // Paper end
    // Paper start
    public final List<io.papermc.paper.chunk.SingleThreadChunkRegionManager> regionManagers = new java.util.ArrayList<>();
    public final io.papermc.paper.chunk.SingleThreadChunkRegionManager dataRegionManager;

    public static final class DataRegionData implements io.papermc.paper.chunk.SingleThreadChunkRegionManager.RegionData {
    }

    public static final class DataRegionSectionData implements io.papermc.paper.chunk.SingleThreadChunkRegionManager.RegionSectionData {

        @Override
        public void removeFromRegion(final io.papermc.paper.chunk.SingleThreadChunkRegionManager.RegionSection section,
                                     final io.papermc.paper.chunk.SingleThreadChunkRegionManager.Region from) {
            final DataRegionSectionData sectionData = (DataRegionSectionData)section.sectionData;
            final DataRegionData fromData = (DataRegionData)from.regionData;
        }

        @Override
        public void addToRegion(final io.papermc.paper.chunk.SingleThreadChunkRegionManager.RegionSection section,
                                final io.papermc.paper.chunk.SingleThreadChunkRegionManager.Region oldRegion,
                                final io.papermc.paper.chunk.SingleThreadChunkRegionManager.Region newRegion) {
            final DataRegionSectionData sectionData = (DataRegionSectionData)section.sectionData;
            final DataRegionData oldRegionData = oldRegion == null ? null : (DataRegionData)oldRegion.regionData;
            final DataRegionData newRegionData = (DataRegionData)newRegion.regionData;
        }
    }

    public final ChunkHolder getUnloadingChunkHolder(int chunkX, int chunkZ) {
        return this.pendingUnloads.get(io.papermc.paper.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }
    // Paper end

    public ChunkMap(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor executor, BlockableEventLoop<Runnable> mainThreadExecutor, LightChunkGetter chunkProvider, ChunkGenerator chunkGenerator, ChunkProgressListener worldGenerationProgressListener, ChunkStatusUpdateListener chunkStatusChangeListener, Supplier<DimensionDataStorage> persistentStateManagerFactory, int viewDistance, boolean dsync) {
        super(session.getDimensionPath(world.dimension()).resolve("region"), dataFixer, dsync);
        this.visibleChunkMap = this.updatingChunkMap.clone();
        this.pendingUnloads = new Long2ObjectLinkedOpenHashMap();
        this.entitiesInLevel = new LongOpenHashSet();
        this.toDrop = new LongOpenHashSet();
        this.tickingGenerated = new AtomicInteger();
        this.playerMap = new PlayerMap();
        this.entityMap = new Int2ObjectOpenHashMap();
        this.chunkTypeCache = new Long2ByteOpenHashMap();
        this.chunkSaveCooldowns = new Long2LongOpenHashMap();
        this.unloadQueue = Queues.newConcurrentLinkedQueue();
        this.structureTemplateManager = structureTemplateManager;
        Path path = session.getDimensionPath(world.dimension());

        this.storageName = path.getFileName().toString();
        this.level = world;
        this.generator = chunkGenerator;
        // CraftBukkit start - SPIGOT-7051: It's a rigged game! Use delegate for random state creation, otherwise it is not so random.
        if (chunkGenerator instanceof CustomChunkGenerator) {
            chunkGenerator = ((CustomChunkGenerator) chunkGenerator).getDelegate();
        }
        // CraftBukkit end
        RegistryAccess iregistrycustom = world.registryAccess();
        long j = world.getSeed();

        if (chunkGenerator instanceof NoiseBasedChunkGenerator) {
            NoiseBasedChunkGenerator chunkgeneratorabstract = (NoiseBasedChunkGenerator) chunkGenerator;

            this.randomState = RandomState.create((NoiseGeneratorSettings) chunkgeneratorabstract.generatorSettings().value(), (HolderGetter) iregistrycustom.lookupOrThrow(Registries.NOISE), j);
        } else {
            this.randomState = RandomState.create(NoiseGeneratorSettings.dummy(), (HolderGetter) iregistrycustom.lookupOrThrow(Registries.NOISE), j);
        }

        this.chunkGeneratorState = chunkGenerator.createState(iregistrycustom.lookupOrThrow(Registries.STRUCTURE_SET), this.randomState, j, world.spigotConfig); // Spigot
        this.mainThreadExecutor = mainThreadExecutor;
        ProcessorMailbox<Runnable> threadedmailbox = ProcessorMailbox.create(executor, "worldgen");

        Objects.requireNonNull(mainThreadExecutor);
        ProcessorHandle<Runnable> mailbox = ProcessorHandle.of("main", mainThreadExecutor::tell);

        this.progressListener = worldGenerationProgressListener;
        this.chunkStatusListener = chunkStatusChangeListener;
        ProcessorMailbox<Runnable> threadedmailbox1 = ProcessorMailbox.create(executor, "light");

        this.queueSorter = new ChunkTaskPriorityQueueSorter(ImmutableList.of(threadedmailbox, mailbox, threadedmailbox1), executor, Integer.MAX_VALUE);
        this.worldgenMailbox = this.queueSorter.getProcessor(threadedmailbox, false);
        this.mainThreadMailbox = this.queueSorter.getProcessor(mailbox, false);
        this.lightEngine = new ThreadedLevelLightEngine(chunkProvider, this, this.level.dimensionType().hasSkyLight(), threadedmailbox1, this.queueSorter.getProcessor(threadedmailbox1, false));
        this.distanceManager = new ChunkMap.ChunkDistanceManager(executor, mainThreadExecutor);
        this.overworldDataStorage = persistentStateManagerFactory;
        this.poiManager = new PoiManager(path.resolve("poi"), dataFixer, dsync, iregistrycustom, world);
        this.setViewDistance(viewDistance);
        // Paper start
        this.dataRegionManager = new io.papermc.paper.chunk.SingleThreadChunkRegionManager(this.level, 2, (1.0 / 3.0), 1, 6, "Data", DataRegionData::new, DataRegionSectionData::new);
        this.regionManagers.add(this.dataRegionManager);
        // Paper end
    }

    protected ChunkGenerator generator() {
        return this.generator;
    }

    protected ChunkGeneratorStructureState generatorState() {
        return this.chunkGeneratorState;
    }

    protected RandomState randomState() {
        return this.randomState;
    }

    public void debugReloadGenerator() {
        DataResult<JsonElement> dataresult = ChunkGenerator.CODEC.encodeStart(JsonOps.INSTANCE, this.generator);
        DataResult<ChunkGenerator> dataresult1 = dataresult.flatMap((jsonelement) -> {
            return ChunkGenerator.CODEC.parse(JsonOps.INSTANCE, jsonelement);
        });

        dataresult1.result().ifPresent((chunkgenerator) -> {
            this.generator = chunkgenerator;
        });
    }

    private static double euclideanDistanceSquared(ChunkPos pos, Entity entity) {
        double d0 = (double) SectionPos.sectionToBlockCoord(pos.x, 8);
        double d1 = (double) SectionPos.sectionToBlockCoord(pos.z, 8);
        double d2 = d0 - entity.getX();
        double d3 = d1 - entity.getZ();

        return d2 * d2 + d3 * d3;
    }

    public static boolean isChunkInRange(int x1, int z1, int x2, int z2, int distance) {
        int j1 = Math.max(0, Math.abs(x1 - x2) - 1);
        int k1 = Math.max(0, Math.abs(z1 - z2) - 1);
        long l1 = (long) Math.max(0, Math.max(j1, k1) - 1);
        long i2 = (long) Math.min(j1, k1);
        long j2 = i2 * i2 + l1 * l1;
        int k2 = distance - 1;
        int l2 = k2 * k2;

        return j2 <= (long) l2;
    }

    private static boolean isChunkOnRangeBorder(int x1, int z1, int x2, int z2, int distance) {
        return !ChunkMap.isChunkInRange(x1, z1, x2, z2, distance) ? false : (!ChunkMap.isChunkInRange(x1 + 1, z1, x2, z2, distance) ? true : (!ChunkMap.isChunkInRange(x1, z1 + 1, x2, z2, distance) ? true : (!ChunkMap.isChunkInRange(x1 - 1, z1, x2, z2, distance) ? true : !ChunkMap.isChunkInRange(x1, z1 - 1, x2, z2, distance))));
    }

    protected ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    @Nullable
    protected ChunkHolder getUpdatingChunkIfPresent(long pos) {
        return (ChunkHolder) this.updatingChunkMap.get(pos);
    }

    @Nullable
    public ChunkHolder getVisibleChunkIfPresent(long pos) {
        return (ChunkHolder) this.visibleChunkMap.get(pos);
    }

    protected IntSupplier getChunkQueueLevel(long pos) {
        return () -> {
            ChunkHolder playerchunk = this.getVisibleChunkIfPresent(pos);

            return playerchunk == null ? ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT - 1 : Math.min(playerchunk.getQueueLevel(), ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT - 1);
        };
    }

    public String getChunkDebugData(ChunkPos chunkPos) {
        ChunkHolder playerchunk = this.getVisibleChunkIfPresent(chunkPos.toLong());

        if (playerchunk == null) {
            return "null";
        } else {
            String s = playerchunk.getTicketLevel() + "\n";
            ChunkStatus chunkstatus = playerchunk.getLastAvailableStatus();
            ChunkAccess ichunkaccess = playerchunk.getLastAvailable();

            if (chunkstatus != null) {
                s = s + "St: \u00a7" + chunkstatus.getIndex() + chunkstatus + "\u00a7r\n";
            }

            if (ichunkaccess != null) {
                s = s + "Ch: \u00a7" + ichunkaccess.getStatus().getIndex() + ichunkaccess.getStatus() + "\u00a7r\n";
            }

            ChunkHolder.FullChunkStatus playerchunk_state = playerchunk.getFullStatus();

            s = s + "\u00a7" + playerchunk_state.ordinal() + playerchunk_state;
            return s + "\u00a7r";
        }
    }

    // Paper start
    public final int getEffectiveViewDistance() {
        // TODO this needs to be checked on update
        // Mojang currently sets it to +1 of the configured view distance. So subtract one to get the one we really want.
        return this.viewDistance - 1;
    }
    // Paper end

    private CompletableFuture<Either<List<ChunkAccess>, ChunkHolder.ChunkLoadingFailure>> getChunkRangeFuture(ChunkPos centerChunk, int margin, IntFunction<ChunkStatus> distanceToStatus) {
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> list = new ArrayList();
        List<ChunkHolder> list1 = new ArrayList();
        int j = centerChunk.x;
        int k = centerChunk.z;

        for (int l = -margin; l <= margin; ++l) {
            for (int i1 = -margin; i1 <= margin; ++i1) {
                int j1 = Math.max(Math.abs(i1), Math.abs(l));
                final ChunkPos chunkcoordintpair1 = new ChunkPos(j + i1, k + l);
                long k1 = chunkcoordintpair1.toLong();
                ChunkHolder playerchunk = this.getUpdatingChunkIfPresent(k1);

                if (playerchunk == null) {
                    return CompletableFuture.completedFuture(Either.right(new ChunkHolder.ChunkLoadingFailure() {
                        public String toString() {
                            return "Unloaded " + chunkcoordintpair1;
                        }
                    }));
                }

                ChunkStatus chunkstatus = (ChunkStatus) distanceToStatus.apply(j1);
                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = playerchunk.getOrScheduleFuture(chunkstatus, this);

                list1.add(playerchunk);
                list.add(completablefuture);
            }
        }

        CompletableFuture<List<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completablefuture1 = Util.sequence(list);
        CompletableFuture<Either<List<ChunkAccess>, ChunkHolder.ChunkLoadingFailure>> completablefuture2 = completablefuture1.thenApply((list2) -> {
            List<ChunkAccess> list3 = Lists.newArrayList();
            // CraftBukkit start - decompile error
            int cnt = 0;

            for (Iterator iterator = list2.iterator(); iterator.hasNext(); ++cnt) {
                final int l1 = cnt;
                // CraftBukkit end
                final Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = (Either) iterator.next();

                if (either == null) {
                    throw this.debugFuturesAndCreateReportedException(new IllegalStateException("At least one of the chunk futures were null"), "n/a");
                }

                Optional<ChunkAccess> optional = either.left();

                if (!optional.isPresent()) {
                    return Either.right(new ChunkHolder.ChunkLoadingFailure() {
                        public String toString() {
                            ChunkPos chunkcoordintpair2 = new ChunkPos(j + l1 % (margin * 2 + 1), k + l1 / (margin * 2 + 1));

                            return "Unloaded " + chunkcoordintpair2 + " " + either.right().get();
                        }
                    });
                }

                list3.add((ChunkAccess) optional.get());
            }

            return Either.left(list3);
        });
        Iterator iterator = list1.iterator();

        while (iterator.hasNext()) {
            ChunkHolder playerchunk1 = (ChunkHolder) iterator.next();

            playerchunk1.addSaveDependency("getChunkRangeFuture " + centerChunk + " " + margin, completablefuture2);
        }

        return completablefuture2;
    }

    public ReportedException debugFuturesAndCreateReportedException(IllegalStateException exception, String details) {
        StringBuilder stringbuilder = new StringBuilder();
        Consumer<ChunkHolder> consumer = (playerchunk) -> {
            playerchunk.getAllFutures().forEach((pair) -> {
                ChunkStatus chunkstatus = (ChunkStatus) pair.getFirst();
                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = (CompletableFuture) pair.getSecond();

                if (completablefuture != null && completablefuture.isDone() && completablefuture.join() == null) {
                    stringbuilder.append(playerchunk.getPos()).append(" - status: ").append(chunkstatus).append(" future: ").append(completablefuture).append(System.lineSeparator());
                }

            });
        };

        stringbuilder.append("Updating:").append(System.lineSeparator());
        io.papermc.paper.chunk.system.ChunkSystem.getUpdatingChunkHolders(this.level).forEach(consumer); // Paper
        stringbuilder.append("Visible:").append(System.lineSeparator());
        io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(this.level).forEach(consumer); // Paper
        CrashReport crashreport = CrashReport.forThrowable(exception, "Chunk loading");
        CrashReportCategory crashreportsystemdetails = crashreport.addCategory("Chunk loading");

        crashreportsystemdetails.setDetail("Details", (Object) details);
        crashreportsystemdetails.setDetail("Futures", (Object) stringbuilder);
        return new ReportedException(crashreport);
    }

    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> prepareEntityTickingChunk(ChunkPos pos) {
        return this.getChunkRangeFuture(pos, 2, (i) -> {
            return ChunkStatus.FULL;
        }).thenApplyAsync((either) -> {
            return either.mapLeft((list) -> {
                return (LevelChunk) list.get(list.size() / 2);
            });
        }, this.mainThreadExecutor);
    }

    @Nullable
    ChunkHolder updateChunkScheduling(long pos, int level, @Nullable ChunkHolder holder, int k) {
        if (k > ChunkMap.MAX_CHUNK_DISTANCE && level > ChunkMap.MAX_CHUNK_DISTANCE) {
            return holder;
        } else {
            if (holder != null) {
                holder.setTicketLevel(level);
            }

            if (holder != null) {
                if (level > ChunkMap.MAX_CHUNK_DISTANCE) {
                    this.toDrop.add(pos);
                } else {
                    this.toDrop.remove(pos);
                }
            }

            if (level <= ChunkMap.MAX_CHUNK_DISTANCE && holder == null) {
                holder = (ChunkHolder) this.pendingUnloads.remove(pos);
                if (holder != null) {
                    holder.setTicketLevel(level);
                } else {
                    holder = new ChunkHolder(new ChunkPos(pos), level, this.level, this.lightEngine, this.queueSorter, this);
                    // Paper start
                    io.papermc.paper.chunk.system.ChunkSystem.onChunkHolderCreate(this.level, holder);
                    // Paper end
                }

                // Paper start
                holder.onChunkAdd();
                // Paper end
                this.updatingChunkMap.put(pos, holder);
                this.modified = true;
            }

            return holder;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.queueSorter.close();
            this.poiManager.close();
        } finally {
            super.close();
        }

    }

    protected void saveAllChunks(boolean flush) {
        if (flush) {
            List<ChunkHolder> list = (List) io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(this.level).stream().filter(ChunkHolder::wasAccessibleSinceLastSave).peek(ChunkHolder::refreshAccessibility).collect(Collectors.toList()); // Paper
            MutableBoolean mutableboolean = new MutableBoolean();

            do {
                mutableboolean.setFalse();
                list.stream().map((playerchunk) -> {
                    CompletableFuture completablefuture;

                    do {
                        completablefuture = playerchunk.getChunkToSave();
                        BlockableEventLoop iasynctaskhandler = this.mainThreadExecutor;

                        Objects.requireNonNull(completablefuture);
                        iasynctaskhandler.managedBlock(completablefuture::isDone);
                    } while (completablefuture != playerchunk.getChunkToSave());

                    return (ChunkAccess) completablefuture.join();
                }).filter((ichunkaccess) -> {
                    return ichunkaccess instanceof ImposterProtoChunk || ichunkaccess instanceof LevelChunk;
                }).filter(this::save).forEach((ichunkaccess) -> {
                    mutableboolean.setTrue();
                });
            } while (mutableboolean.isTrue());

            this.processUnloads(() -> {
                return true;
            });
            this.flushWorker();
        } else {
            io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(this.level).forEach(this::saveChunkIfNeeded);
        }

    }

    protected void tick(BooleanSupplier shouldKeepTicking) {
        ProfilerFiller gameprofilerfiller = this.level.getProfiler();

        gameprofilerfiller.push("poi");
        this.poiManager.tick(shouldKeepTicking);
        gameprofilerfiller.popPush("chunk_unload");
        if (!this.level.noSave()) {
            this.processUnloads(shouldKeepTicking);
        }

        gameprofilerfiller.pop();
    }

    public boolean hasWork() {
        return this.lightEngine.hasLightWork() || !this.pendingUnloads.isEmpty() || io.papermc.paper.chunk.system.ChunkSystem.hasAnyChunkHolders(this.level) || this.poiManager.hasWork() || !this.toDrop.isEmpty() || !this.unloadQueue.isEmpty() || this.queueSorter.hasWork() || this.distanceManager.hasTickets(); // Paper
    }

    private void processUnloads(BooleanSupplier shouldKeepTicking) {
        LongIterator longiterator = this.toDrop.iterator();

        for (int i = 0; longiterator.hasNext() && (shouldKeepTicking.getAsBoolean() || i < 200 || this.toDrop.size() > 2000); longiterator.remove()) {
            long j = longiterator.nextLong();
            ChunkHolder playerchunk = (ChunkHolder) this.updatingChunkMap.remove(j);

            if (playerchunk != null) {
                playerchunk.onChunkRemove(); // Paper
                this.pendingUnloads.put(j, playerchunk);
                this.modified = true;
                ++i;
                this.scheduleUnload(j, playerchunk);
            }
        }

        int k = Math.max(0, this.unloadQueue.size() - 2000);

        Runnable runnable;

        while ((shouldKeepTicking.getAsBoolean() || k > 0) && (runnable = (Runnable) this.unloadQueue.poll()) != null) {
            --k;
            runnable.run();
        }

        int l = 0;
        Iterator objectiterator = io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(this.level).iterator(); // Paper

        while (l < 20 && shouldKeepTicking.getAsBoolean() && objectiterator.hasNext()) {
            if (this.saveChunkIfNeeded((ChunkHolder) objectiterator.next())) {
                ++l;
            }
        }

    }

    private void scheduleUnload(long pos, ChunkHolder holder) {
        CompletableFuture<ChunkAccess> completablefuture = holder.getChunkToSave();
        Consumer<ChunkAccess> consumer = (ichunkaccess) -> { // CraftBukkit - decompile error
            CompletableFuture<ChunkAccess> completablefuture1 = holder.getChunkToSave();

            if (completablefuture1 != completablefuture) {
                this.scheduleUnload(pos, holder);
            } else {
                // Paper start
                boolean removed;
                if ((removed = this.pendingUnloads.remove(pos, holder)) && ichunkaccess != null) {
                    io.papermc.paper.chunk.system.ChunkSystem.onChunkHolderDelete(this.level, holder);
                    // Paper end
                    if (ichunkaccess instanceof LevelChunk) {
                        ((LevelChunk) ichunkaccess).setLoaded(false);
                    }

                    this.save(ichunkaccess);
                    if (this.entitiesInLevel.remove(pos) && ichunkaccess instanceof LevelChunk) {
                        LevelChunk chunk = (LevelChunk) ichunkaccess;

                        this.level.unload(chunk);
                    }

                    this.lightEngine.updateChunkStatus(ichunkaccess.getPos());
                    this.lightEngine.tryScheduleUpdate();
                    this.progressListener.onStatusChange(ichunkaccess.getPos(), (ChunkStatus) null);
                    this.chunkSaveCooldowns.remove(ichunkaccess.getPos().toLong());
                } else if (removed) { // Paper start
                    io.papermc.paper.chunk.system.ChunkSystem.onChunkHolderDelete(this.level, holder);
                } // Paper end

            }
        };
        Queue queue = this.unloadQueue;

        Objects.requireNonNull(this.unloadQueue);
        completablefuture.thenAcceptAsync(consumer, queue::add).whenComplete((ovoid, throwable) -> {
            if (throwable != null) {
                ChunkMap.LOGGER.error("Failed to save chunk {}", holder.getPos(), throwable);
            }

        });
    }

    protected boolean promoteChunkMap() {
        if (!this.modified) {
            return false;
        } else {
            this.visibleChunkMap = this.updatingChunkMap.clone();
            this.modified = false;
            return true;
        }
    }

    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> schedule(ChunkHolder holder, ChunkStatus requiredStatus) {
        ChunkPos chunkcoordintpair = holder.getPos();

        if (requiredStatus == ChunkStatus.EMPTY) {
            return this.scheduleChunkLoad(chunkcoordintpair);
        } else {
            if (requiredStatus == ChunkStatus.LIGHT) {
                this.distanceManager.addTicket(TicketType.LIGHT, chunkcoordintpair, 33 + ChunkStatus.getDistance(ChunkStatus.LIGHT), chunkcoordintpair);
            }

            Optional<ChunkAccess> optional = ((Either) holder.getOrScheduleFuture(requiredStatus.getParent(), this).getNow(ChunkHolder.UNLOADED_CHUNK)).left();

            if (optional.isPresent() && ((ChunkAccess) optional.get()).getStatus().isOrAfter(requiredStatus)) {
                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = requiredStatus.load(this.level, this.structureTemplateManager, this.lightEngine, (ichunkaccess) -> {
                    return this.protoChunkToFullChunk(holder);
                }, (ChunkAccess) optional.get());

                this.progressListener.onStatusChange(chunkcoordintpair, requiredStatus);
                return completablefuture;
            } else {
                return this.scheduleChunkGeneration(holder, requiredStatus);
            }
        }
    }

    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkLoad(ChunkPos pos) {
        return this.readChunk(pos).thenApply((optional) -> {
            return optional.filter((nbttagcompound) -> {
                boolean flag = ChunkMap.isChunkDataValid(nbttagcompound);

                if (!flag) {
                    ChunkMap.LOGGER.error("Chunk file at {} is missing level data, skipping", pos);
                }

                return flag;
            });
        }).thenApplyAsync((optional) -> {
            this.level.getProfiler().incrementCounter("chunkLoad");
            if (optional.isPresent()) {
                ProtoChunk protochunk = ChunkSerializer.read(this.level, this.poiManager, pos, (CompoundTag) optional.get());

                this.markPosition(pos, protochunk.getStatus().getChunkType());
                return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(protochunk); // CraftBukkit - decompile error
            } else {
                return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(this.createEmptyChunk(pos)); // CraftBukkit - decompile error
            }
        }, this.mainThreadExecutor).exceptionallyAsync((throwable) -> {
            return this.handleChunkLoadFailure(throwable, pos);
        }, this.mainThreadExecutor);
    }

    private static boolean isChunkDataValid(CompoundTag nbt) {
        return nbt.contains("Status", 8);
    }

    private Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> handleChunkLoadFailure(Throwable throwable, ChunkPos chunkPos) {
        if (throwable instanceof ReportedException) {
            ReportedException reportedexception = (ReportedException) throwable;
            Throwable throwable1 = reportedexception.getCause();

            if (!(throwable1 instanceof IOException)) {
                this.markPositionReplaceable(chunkPos);
                throw reportedexception;
            }

            ChunkMap.LOGGER.error("Couldn't load chunk {}", chunkPos, throwable1);
        } else if (throwable instanceof IOException) {
            ChunkMap.LOGGER.error("Couldn't load chunk {}", chunkPos, throwable);
        }

        return Either.left(this.createEmptyChunk(chunkPos));
    }

    private ChunkAccess createEmptyChunk(ChunkPos chunkPos) {
        this.markPositionReplaceable(chunkPos);
        return new ProtoChunk(chunkPos, UpgradeData.EMPTY, this.level, this.level.registryAccess().registryOrThrow(Registries.BIOME), (BlendingData) null);
    }

    private void markPositionReplaceable(ChunkPos pos) {
        this.chunkTypeCache.put(pos.toLong(), (byte) -1);
    }

    private byte markPosition(ChunkPos pos, ChunkStatus.ChunkType type) {
        return this.chunkTypeCache.put(pos.toLong(), (byte) (type == ChunkStatus.ChunkType.PROTOCHUNK ? -1 : 1));
    }

    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkGeneration(ChunkHolder holder, ChunkStatus requiredStatus) {
        ChunkPos chunkcoordintpair = holder.getPos();
        CompletableFuture<Either<List<ChunkAccess>, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getChunkRangeFuture(chunkcoordintpair, requiredStatus.getRange(), (i) -> {
            return this.getDependencyStatus(requiredStatus, i);
        });

        this.level.getProfiler().incrementCounter(() -> {
            return "chunkGenerate " + requiredStatus.getName();
        });
        Executor executor = (runnable) -> {
            this.worldgenMailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable));
        };

        return completablefuture.thenComposeAsync((either) -> {
            return (CompletionStage) either.map((list) -> {
                try {
                    CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture1 = requiredStatus.generate(executor, this.level, this.generator, this.structureTemplateManager, this.lightEngine, (ichunkaccess) -> {
                        return this.protoChunkToFullChunk(holder);
                    }, list, false);

                    this.progressListener.onStatusChange(chunkcoordintpair, requiredStatus);
                    return completablefuture1;
                } catch (Exception exception) {
                    exception.getStackTrace();
                    CrashReport crashreport = CrashReport.forThrowable(exception, "Exception generating new chunk");
                    CrashReportCategory crashreportsystemdetails = crashreport.addCategory("Chunk to be generated");

                    crashreportsystemdetails.setDetail("Location", (Object) String.format(Locale.ROOT, "%d,%d", chunkcoordintpair.x, chunkcoordintpair.z));
                    crashreportsystemdetails.setDetail("Position hash", (Object) ChunkPos.asLong(chunkcoordintpair.x, chunkcoordintpair.z));
                    crashreportsystemdetails.setDetail("Generator", (Object) this.generator);
                    this.mainThreadExecutor.execute(() -> {
                        throw new ReportedException(crashreport);
                    });
                    throw new ReportedException(crashreport);
                }
            }, (playerchunk_failure) -> {
                this.releaseLightTicket(chunkcoordintpair);
                return CompletableFuture.completedFuture(Either.right(playerchunk_failure));
            });
        }, executor);
    }

    protected void releaseLightTicket(ChunkPos pos) {
        this.mainThreadExecutor.tell(Util.name(() -> {
            this.distanceManager.removeTicket(TicketType.LIGHT, pos, 33 + ChunkStatus.getDistance(ChunkStatus.LIGHT), pos);
        }, () -> {
            return "release light ticket " + pos;
        }));
    }

    private ChunkStatus getDependencyStatus(ChunkStatus centerChunkTargetStatus, int distance) {
        ChunkStatus chunkstatus1;

        if (distance == 0) {
            chunkstatus1 = centerChunkTargetStatus.getParent();
        } else {
            chunkstatus1 = ChunkStatus.getStatusAroundFullChunk(ChunkStatus.getDistance(centerChunkTargetStatus) + distance);
        }

        return chunkstatus1;
    }

    private static void postLoadProtoChunk(ServerLevel world, List<CompoundTag> nbt) {
        if (!nbt.isEmpty()) {
            // CraftBukkit start - these are spawned serialized (DefinedStructure) and we don't call an add event below at the moment due to ordering complexities
            world.addWorldGenChunkEntities(EntityType.loadEntitiesRecursive(nbt, world).filter((entity) -> {
                boolean needsRemoval = false;
                net.minecraft.server.dedicated.DedicatedServer server = world.getCraftServer().getServer();
                if (!server.areNpcsEnabled() && entity instanceof net.minecraft.world.entity.npc.Npc) {
                    entity.discard();
                    needsRemoval = true;
                }
                if (!server.isSpawningAnimals() && (entity instanceof net.minecraft.world.entity.animal.Animal || entity instanceof net.minecraft.world.entity.animal.WaterAnimal)) {
                    entity.discard();
                    needsRemoval = true;
                }
                return !needsRemoval;
            }));
            // CraftBukkit end
        }

    }

    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> protoChunkToFullChunk(ChunkHolder chunkHolder) {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completablefuture = chunkHolder.getFutureIfPresentUnchecked(ChunkStatus.FULL.getParent());

        return completablefuture.thenApplyAsync((either) -> {
            ChunkStatus chunkstatus = ChunkHolder.getStatus(chunkHolder.getTicketLevel());

            return !chunkstatus.isOrAfter(ChunkStatus.FULL) ? ChunkHolder.UNLOADED_CHUNK : either.mapLeft((ichunkaccess) -> {
                ChunkPos chunkcoordintpair = chunkHolder.getPos();
                ProtoChunk protochunk = (ProtoChunk) ichunkaccess;
                LevelChunk chunk;

                if (protochunk instanceof ImposterProtoChunk) {
                    chunk = ((ImposterProtoChunk) protochunk).getWrapped();
                } else {
                    chunk = new LevelChunk(this.level, protochunk, (chunk1) -> {
                        ChunkMap.postLoadProtoChunk(this.level, protochunk.getEntities());
                    });
                    chunkHolder.replaceProtoChunk(new ImposterProtoChunk(chunk, false));
                }

                chunk.setFullStatus(() -> {
                    return ChunkHolder.getFullChunkStatus(chunkHolder.getTicketLevel());
                });
                chunk.runPostLoad();
                if (this.entitiesInLevel.add(chunkcoordintpair.toLong())) {
                    chunk.setLoaded(true);
                    chunk.registerAllBlockEntitiesAfterLevelLoad();
                    chunk.registerTickContainerInLevel(this.level);
                }

                return chunk;
            });
        }, (runnable) -> {
            ProcessorHandle mailbox = this.mainThreadMailbox;
            long i = chunkHolder.getPos().toLong();

            Objects.requireNonNull(chunkHolder);
            mailbox.tell(ChunkTaskPriorityQueueSorter.message(runnable, i, chunkHolder::getTicketLevel));
        });
    }

    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> prepareTickingChunk(ChunkHolder holder) {
        ChunkPos chunkcoordintpair = holder.getPos();
        CompletableFuture<Either<List<ChunkAccess>, ChunkHolder.ChunkLoadingFailure>> completablefuture = this.getChunkRangeFuture(chunkcoordintpair, 1, (i) -> {
            return ChunkStatus.FULL;
        });
        CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> completablefuture1 = completablefuture.thenApplyAsync((either) -> {
            return either.mapLeft((list) -> {
                return (LevelChunk) list.get(list.size() / 2);
            });
        }, (runnable) -> {
            this.mainThreadMailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable));
        }).thenApplyAsync((either) -> {
            return either.ifLeft((chunk) -> {
                chunk.postProcessGeneration();
                this.level.startTickingChunk(chunk);
            });
        }, this.mainThreadExecutor);

        completablefuture1.thenAcceptAsync((either) -> {
            either.ifLeft((chunk) -> {
                this.tickingGenerated.getAndIncrement();
                MutableObject<ClientboundLevelChunkWithLightPacket> mutableobject = new MutableObject();

                this.getPlayers(chunkcoordintpair, false).forEach((entityplayer) -> {
                    this.playerLoadedChunk(entityplayer, mutableobject, chunk);
                });
            });
        }, (runnable) -> {
            this.mainThreadMailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable));
        });
        return completablefuture1;
    }

    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> prepareAccessibleChunk(ChunkHolder holder) {
        return this.getChunkRangeFuture(holder.getPos(), 1, ChunkStatus::getStatusAroundFullChunk).thenApplyAsync((either) -> {
            return either.mapLeft((list) -> {
                LevelChunk chunk = (LevelChunk) list.get(list.size() / 2);

                return chunk;
            });
        }, (runnable) -> {
            this.mainThreadMailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable));
        });
    }

    public int getTickingGenerated() {
        return this.tickingGenerated.get();
    }

    private boolean saveChunkIfNeeded(ChunkHolder chunkHolder) {
        if (!chunkHolder.wasAccessibleSinceLastSave()) {
            return false;
        } else {
            ChunkAccess ichunkaccess = (ChunkAccess) chunkHolder.getChunkToSave().getNow(null); // CraftBukkit - decompile error

            if (!(ichunkaccess instanceof ImposterProtoChunk) && !(ichunkaccess instanceof LevelChunk)) {
                return false;
            } else {
                long i = ichunkaccess.getPos().toLong();
                long j = this.chunkSaveCooldowns.getOrDefault(i, -1L);
                long k = System.currentTimeMillis();

                if (k < j) {
                    return false;
                } else {
                    boolean flag = this.save(ichunkaccess);

                    chunkHolder.refreshAccessibility();
                    if (flag) {
                        this.chunkSaveCooldowns.put(i, k + 10000L);
                    }

                    return flag;
                }
            }
        }
    }

    public boolean save(ChunkAccess chunk) {
        this.poiManager.flush(chunk.getPos());
        if (!chunk.isUnsaved()) {
            return false;
        } else {
            chunk.setUnsaved(false);
            ChunkPos chunkcoordintpair = chunk.getPos();

            try {
                ChunkStatus chunkstatus = chunk.getStatus();

                if (chunkstatus.getChunkType() != ChunkStatus.ChunkType.LEVELCHUNK) {
                    if (this.isExistingChunkFull(chunkcoordintpair)) {
                        return false;
                    }

                    if (chunkstatus == ChunkStatus.EMPTY && chunk.getAllStarts().values().stream().noneMatch(StructureStart::isValid)) {
                        return false;
                    }
                }

                this.level.getProfiler().incrementCounter("chunkSave");
                CompoundTag nbttagcompound = ChunkSerializer.write(this.level, chunk);

                this.write(chunkcoordintpair, nbttagcompound);
                this.markPosition(chunkcoordintpair, chunkstatus.getChunkType());
                return true;
            } catch (Exception exception) {
                ChunkMap.LOGGER.error("Failed to save chunk {},{}", new Object[]{chunkcoordintpair.x, chunkcoordintpair.z, exception});
                return false;
            }
        }
    }

    private boolean isExistingChunkFull(ChunkPos pos) {
        byte b0 = this.chunkTypeCache.get(pos.toLong());

        if (b0 != 0) {
            return b0 == 1;
        } else {
            CompoundTag nbttagcompound;

            try {
                nbttagcompound = (CompoundTag) ((Optional) this.readChunk(pos).join()).orElse((Object) null);
                if (nbttagcompound == null) {
                    this.markPositionReplaceable(pos);
                    return false;
                }
            } catch (Exception exception) {
                ChunkMap.LOGGER.error("Failed to read chunk {}", pos, exception);
                this.markPositionReplaceable(pos);
                return false;
            }

            ChunkStatus.ChunkType chunkstatus_type = ChunkSerializer.getChunkTypeFromTag(nbttagcompound);

            return this.markPosition(pos, chunkstatus_type) == 1;
        }
    }

    public void setViewDistance(int watchDistance) {
        int j = Mth.clamp(watchDistance + 1, 3, 33);

        if (j != this.viewDistance) {
            int k = this.viewDistance;

            this.viewDistance = j;
            this.distanceManager.updatePlayerTickets(this.viewDistance + 1);
            Iterator objectiterator = io.papermc.paper.chunk.system.ChunkSystem.getUpdatingChunkHolders(this.level).iterator(); // Paper

            while (objectiterator.hasNext()) {
                ChunkHolder playerchunk = (ChunkHolder) objectiterator.next();
                ChunkPos chunkcoordintpair = playerchunk.getPos();
                MutableObject<ClientboundLevelChunkWithLightPacket> mutableobject = new MutableObject();

                this.getPlayers(chunkcoordintpair, false).forEach((entityplayer) -> {
                    SectionPos sectionposition = entityplayer.getLastSectionPos();
                    boolean flag = ChunkMap.isChunkInRange(chunkcoordintpair.x, chunkcoordintpair.z, sectionposition.x(), sectionposition.z(), k);
                    boolean flag1 = ChunkMap.isChunkInRange(chunkcoordintpair.x, chunkcoordintpair.z, sectionposition.x(), sectionposition.z(), this.viewDistance);

                    this.updateChunkTracking(entityplayer, chunkcoordintpair, mutableobject, flag, flag1);
                });
            }
        }

    }

    protected void updateChunkTracking(ServerPlayer player, ChunkPos pos, MutableObject<ClientboundLevelChunkWithLightPacket> packet, boolean oldWithinViewDistance, boolean newWithinViewDistance) {
        if (player.level == this.level) {
            if (newWithinViewDistance && !oldWithinViewDistance) {
                ChunkHolder playerchunk = this.getVisibleChunkIfPresent(pos.toLong());

                if (playerchunk != null) {
                    LevelChunk chunk = playerchunk.getTickingChunk();

                    if (chunk != null) {
                        this.playerLoadedChunk(player, packet, chunk);
                    }

                    DebugPackets.sendPoiPacketsForChunk(this.level, pos);
                }
            }

            if (!newWithinViewDistance && oldWithinViewDistance) {
                player.untrackChunk(pos);
            }

        }
    }

    public int size() {
        return io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolderCount(this.level); // Paper
    }

    public DistanceManager getDistanceManager() {
        return this.distanceManager;
    }

    protected Iterable<ChunkHolder> getChunks() {
        return Iterables.unmodifiableIterable(io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(this.level)); // Paper
    }

    void dumpChunks(Writer writer) throws IOException {
        CsvOutput csvwriter = CsvOutput.builder().addColumn("x").addColumn("z").addColumn("level").addColumn("in_memory").addColumn("status").addColumn("full_status").addColumn("accessible_ready").addColumn("ticking_ready").addColumn("entity_ticking_ready").addColumn("ticket").addColumn("spawning").addColumn("block_entity_count").addColumn("ticking_ticket").addColumn("ticking_level").addColumn("block_ticks").addColumn("fluid_ticks").build(writer);
        TickingTracker tickingtracker = this.distanceManager.tickingTracker();
        Iterator<ChunkHolder> objectbidirectionaliterator = io.papermc.paper.chunk.system.ChunkSystem.getVisibleChunkHolders(this.level).iterator(); // Paper

        while (objectbidirectionaliterator.hasNext()) {
            ChunkHolder playerchunk = objectbidirectionaliterator.next(); // Paper
            long i = playerchunk.pos.toLong(); // Paper
            ChunkPos chunkcoordintpair = new ChunkPos(i);
            // Paper
            Optional<ChunkAccess> optional = Optional.ofNullable(playerchunk.getLastAvailable());
            Optional<LevelChunk> optional1 = optional.flatMap((ichunkaccess) -> {
                return ichunkaccess instanceof LevelChunk ? Optional.of((LevelChunk) ichunkaccess) : Optional.empty();
            });

            // CraftBukkit - decompile error
            csvwriter.writeRow(chunkcoordintpair.x, chunkcoordintpair.z, playerchunk.getTicketLevel(), optional.isPresent(), optional.map(ChunkAccess::getStatus).orElse(null), optional1.map(LevelChunk::getFullStatus).orElse(null), ChunkMap.printFuture(playerchunk.getFullChunkFuture()), ChunkMap.printFuture(playerchunk.getTickingChunkFuture()), ChunkMap.printFuture(playerchunk.getEntityTickingChunkFuture()), this.distanceManager.getTicketDebugString(i), this.anyPlayerCloseEnoughForSpawning(chunkcoordintpair), optional1.map((chunk) -> {
                return chunk.getBlockEntities().size();
            }).orElse(0), tickingtracker.getTicketDebugString(i), tickingtracker.getLevel(i), optional1.map((chunk) -> {
                return chunk.getBlockTicks().count();
            }).orElse(0), optional1.map((chunk) -> {
                return chunk.getFluidTicks().count();
            }).orElse(0));
        }

    }

    private static String printFuture(CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> future) {
        try {
            Either<LevelChunk, ChunkHolder.ChunkLoadingFailure> either = (Either) future.getNow(null); // CraftBukkit - decompile error

            return either != null ? (String) either.map((chunk) -> {
                return "done";
            }, (playerchunk_failure) -> {
                return "unloaded";
            }) : "not completed";
        } catch (CompletionException completionexception) {
            return "failed " + completionexception.getCause().getMessage();
        } catch (CancellationException cancellationexception) {
            return "cancelled";
        }
    }

    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos chunkPos) {
        return this.read(chunkPos).thenApplyAsync((optional) -> {
            return optional.map((nbttagcompound) -> this.upgradeChunkTag(nbttagcompound, chunkPos)); // CraftBukkit
        }, Util.backgroundExecutor());
    }

    // CraftBukkit start
    private CompoundTag upgradeChunkTag(CompoundTag nbttagcompound, ChunkPos chunkcoordintpair) {
        return this.upgradeChunkTag(this.level.getTypeKey(), this.overworldDataStorage, nbttagcompound, this.generator.getTypeNameForDataFixer(), chunkcoordintpair, level);
        // CraftBukkit end
    }

    boolean anyPlayerCloseEnoughForSpawning(ChunkPos pos) {
        // Spigot start
        return this.anyPlayerCloseEnoughForSpawning(pos, false);
    }

    boolean anyPlayerCloseEnoughForSpawning(ChunkPos chunkcoordintpair, boolean reducedRange) {
        int chunkRange = level.spigotConfig.mobSpawnRange;
        chunkRange = (chunkRange > level.spigotConfig.viewDistance) ? (byte) level.spigotConfig.viewDistance : chunkRange;
        chunkRange = (chunkRange > 8) ? 8 : chunkRange;

        double blockRange = (reducedRange) ? Math.pow(chunkRange << 4, 2) : 16384.0D;
        // Spigot end
        long i = chunkcoordintpair.toLong();

        if (!this.distanceManager.hasPlayersNearby(i)) {
            return false;
        } else {
            Iterator iterator = this.playerMap.getPlayers(i).iterator();

            ServerPlayer entityplayer;

            do {
                if (!iterator.hasNext()) {
                    return false;
                }

                entityplayer = (ServerPlayer) iterator.next();
            } while (!this.playerIsCloseEnoughForSpawning(entityplayer, chunkcoordintpair, blockRange)); // Spigot

            return true;
        }
    }

    public List<ServerPlayer> getPlayersCloseForSpawning(ChunkPos pos) {
        long i = pos.toLong();

        if (!this.distanceManager.hasPlayersNearby(i)) {
            return List.of();
        } else {
            Builder<ServerPlayer> builder = ImmutableList.builder();
            Iterator iterator = this.playerMap.getPlayers(i).iterator();

            while (iterator.hasNext()) {
                ServerPlayer entityplayer = (ServerPlayer) iterator.next();

                if (this.playerIsCloseEnoughForSpawning(entityplayer, pos, 16384.0D)) { // Spigot
                    builder.add(entityplayer);
                }
            }

            return builder.build();
        }
    }

    private boolean playerIsCloseEnoughForSpawning(ServerPlayer entityplayer, ChunkPos chunkcoordintpair, double range) { // Spigot
        if (entityplayer.isSpectator()) {
            return false;
        } else {
            double d0 = ChunkMap.euclideanDistanceSquared(chunkcoordintpair, entityplayer);

            return d0 < range; // Spigot
        }
    }

    private boolean skipPlayer(ServerPlayer player) {
        return player.isSpectator() && !this.level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
    }

    void updatePlayerStatus(ServerPlayer player, boolean added) {
        boolean flag1 = this.skipPlayer(player);
        boolean flag2 = this.playerMap.ignoredOrUnknown(player);
        int i = SectionPos.blockToSectionCoord(player.getBlockX());
        int j = SectionPos.blockToSectionCoord(player.getBlockZ());

        if (added) {
            this.playerMap.addPlayer(ChunkPos.asLong(i, j), player, flag1);
            this.updatePlayerPos(player);
            if (!flag1) {
                this.distanceManager.addPlayer(SectionPos.of((EntityAccess) player), player);
            }
            this.addPlayerToDistanceMaps(player); // Paper - distance maps
        } else {
            SectionPos sectionposition = player.getLastSectionPos();

            this.playerMap.removePlayer(sectionposition.chunk().toLong(), player);
            if (!flag2) {
                this.distanceManager.removePlayer(sectionposition, player);
            }
            this.removePlayerFromDistanceMaps(player); // Paper - distance maps
        }

        for (int k = i - this.viewDistance - 1; k <= i + this.viewDistance + 1; ++k) {
            for (int l = j - this.viewDistance - 1; l <= j + this.viewDistance + 1; ++l) {
                if (ChunkMap.isChunkInRange(k, l, i, j, this.viewDistance)) {
                    ChunkPos chunkcoordintpair = new ChunkPos(k, l);

                    this.updateChunkTracking(player, chunkcoordintpair, new MutableObject(), !added, added);
                }
            }
        }

    }

    private SectionPos updatePlayerPos(ServerPlayer player) {
        SectionPos sectionposition = SectionPos.of((EntityAccess) player);

        player.setLastSectionPos(sectionposition);
        player.connection.send(new ClientboundSetChunkCacheCenterPacket(sectionposition.x(), sectionposition.z()));
        return sectionposition;
    }

    public void move(ServerPlayer player) {
        ObjectIterator objectiterator = this.entityMap.values().iterator();

        while (objectiterator.hasNext()) {
            ChunkMap.TrackedEntity playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) objectiterator.next();

            if (playerchunkmap_entitytracker.entity == player) {
                playerchunkmap_entitytracker.updatePlayers(this.level.players());
            } else {
                playerchunkmap_entitytracker.updatePlayer(player);
            }
        }

        int i = SectionPos.blockToSectionCoord(player.getBlockX());
        int j = SectionPos.blockToSectionCoord(player.getBlockZ());
        SectionPos sectionposition = player.getLastSectionPos();
        SectionPos sectionposition1 = SectionPos.of((EntityAccess) player);
        long k = sectionposition.chunk().toLong();
        long l = sectionposition1.chunk().toLong();
        boolean flag = this.playerMap.ignored(player);
        boolean flag1 = this.skipPlayer(player);
        boolean flag2 = sectionposition.asLong() != sectionposition1.asLong();

        if (flag2 || flag != flag1) {
            this.updatePlayerPos(player);
            if (!flag) {
                this.distanceManager.removePlayer(sectionposition, player);
            }

            if (!flag1) {
                this.distanceManager.addPlayer(sectionposition1, player);
            }

            if (!flag && flag1) {
                this.playerMap.ignorePlayer(player);
            }

            if (flag && !flag1) {
                this.playerMap.unIgnorePlayer(player);
            }

            if (k != l) {
                this.playerMap.updatePlayer(k, l, player);
            }
        }

        int i1 = sectionposition.x();
        int j1 = sectionposition.z();
        int k1;
        int l1;

        if (Math.abs(i1 - i) <= this.viewDistance * 2 && Math.abs(j1 - j) <= this.viewDistance * 2) {
            k1 = Math.min(i, i1) - this.viewDistance - 1;
            l1 = Math.min(j, j1) - this.viewDistance - 1;
            int i2 = Math.max(i, i1) + this.viewDistance + 1;
            int j2 = Math.max(j, j1) + this.viewDistance + 1;

            for (int k2 = k1; k2 <= i2; ++k2) {
                for (int l2 = l1; l2 <= j2; ++l2) {
                    boolean flag3 = ChunkMap.isChunkInRange(k2, l2, i1, j1, this.viewDistance);
                    boolean flag4 = ChunkMap.isChunkInRange(k2, l2, i, j, this.viewDistance);

                    this.updateChunkTracking(player, new ChunkPos(k2, l2), new MutableObject(), flag3, flag4);
                }
            }
        } else {
            boolean flag5;
            boolean flag6;

            for (k1 = i1 - this.viewDistance - 1; k1 <= i1 + this.viewDistance + 1; ++k1) {
                for (l1 = j1 - this.viewDistance - 1; l1 <= j1 + this.viewDistance + 1; ++l1) {
                    if (ChunkMap.isChunkInRange(k1, l1, i1, j1, this.viewDistance)) {
                        flag5 = true;
                        flag6 = false;
                        this.updateChunkTracking(player, new ChunkPos(k1, l1), new MutableObject(), true, false);
                    }
                }
            }

            for (k1 = i - this.viewDistance - 1; k1 <= i + this.viewDistance + 1; ++k1) {
                for (l1 = j - this.viewDistance - 1; l1 <= j + this.viewDistance + 1; ++l1) {
                    if (ChunkMap.isChunkInRange(k1, l1, i, j, this.viewDistance)) {
                        flag5 = false;
                        flag6 = true;
                        this.updateChunkTracking(player, new ChunkPos(k1, l1), new MutableObject(), false, true);
                    }
                }
            }
        }

        this.updateMaps(player); // Paper - distance maps

    }

    @Override
    public List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge) {
        Set<ServerPlayer> set = this.playerMap.getPlayers(chunkPos.toLong());
        Builder<ServerPlayer> builder = ImmutableList.builder();
        Iterator iterator = set.iterator();

        while (iterator.hasNext()) {
            ServerPlayer entityplayer = (ServerPlayer) iterator.next();
            SectionPos sectionposition = entityplayer.getLastSectionPos();

            if (onlyOnWatchDistanceEdge && ChunkMap.isChunkOnRangeBorder(chunkPos.x, chunkPos.z, sectionposition.x(), sectionposition.z(), this.viewDistance) || !onlyOnWatchDistanceEdge && ChunkMap.isChunkInRange(chunkPos.x, chunkPos.z, sectionposition.x(), sectionposition.z(), this.viewDistance)) {
                builder.add(entityplayer);
            }
        }

        return builder.build();
    }

    public void addEntity(Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity track"); // Spigot
        if (!(entity instanceof EnderDragonPart)) {
            EntityType<?> entitytypes = entity.getType();
            int i = entitytypes.clientTrackingRange() * 16;
            i = org.spigotmc.TrackingRange.getEntityTrackingRange(entity, i); // Spigot

            if (i != 0) {
                int j = entitytypes.updateInterval();

                if (this.entityMap.containsKey(entity.getId())) {
                    throw (IllegalStateException) Util.pauseInIde(new IllegalStateException("Entity is already tracked!"));
                } else {
                    ChunkMap.TrackedEntity playerchunkmap_entitytracker = new ChunkMap.TrackedEntity(entity, i, j, entitytypes.trackDeltas());

                    this.entityMap.put(entity.getId(), playerchunkmap_entitytracker);
                    playerchunkmap_entitytracker.updatePlayers(this.level.players());
                    if (entity instanceof ServerPlayer) {
                        ServerPlayer entityplayer = (ServerPlayer) entity;

                        this.updatePlayerStatus(entityplayer, true);
                        ObjectIterator objectiterator = this.entityMap.values().iterator();

                        while (objectiterator.hasNext()) {
                            ChunkMap.TrackedEntity playerchunkmap_entitytracker1 = (ChunkMap.TrackedEntity) objectiterator.next();

                            if (playerchunkmap_entitytracker1.entity != entityplayer) {
                                playerchunkmap_entitytracker1.updatePlayer(entityplayer);
                            }
                        }
                    }

                }
            }
        }
    }

    protected void removeEntity(Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity untrack"); // Spigot
        if (entity instanceof ServerPlayer) {
            ServerPlayer entityplayer = (ServerPlayer) entity;

            this.updatePlayerStatus(entityplayer, false);
            ObjectIterator objectiterator = this.entityMap.values().iterator();

            while (objectiterator.hasNext()) {
                ChunkMap.TrackedEntity playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) objectiterator.next();

                playerchunkmap_entitytracker.removePlayer(entityplayer);
            }
        }

        ChunkMap.TrackedEntity playerchunkmap_entitytracker1 = (ChunkMap.TrackedEntity) this.entityMap.remove(entity.getId());

        if (playerchunkmap_entitytracker1 != null) {
            playerchunkmap_entitytracker1.broadcastRemoved();
        }

    }

    protected void tick() {
        List<ServerPlayer> list = Lists.newArrayList();
        List<ServerPlayer> list1 = this.level.players();
        ObjectIterator objectiterator = this.entityMap.values().iterator();

        ChunkMap.TrackedEntity playerchunkmap_entitytracker;

        while (objectiterator.hasNext()) {
            playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) objectiterator.next();
            SectionPos sectionposition = playerchunkmap_entitytracker.lastSectionPos;
            SectionPos sectionposition1 = SectionPos.of((EntityAccess) playerchunkmap_entitytracker.entity);
            boolean flag = !Objects.equals(sectionposition, sectionposition1);

            if (flag) {
                playerchunkmap_entitytracker.updatePlayers(list1);
                Entity entity = playerchunkmap_entitytracker.entity;

                if (entity instanceof ServerPlayer) {
                    list.add((ServerPlayer) entity);
                }

                playerchunkmap_entitytracker.lastSectionPos = sectionposition1;
            }

            if (flag || this.distanceManager.inEntityTickingRange(sectionposition1.chunk().toLong())) {
                playerchunkmap_entitytracker.serverEntity.sendChanges();
            }
        }

        if (!list.isEmpty()) {
            objectiterator = this.entityMap.values().iterator();

            while (objectiterator.hasNext()) {
                playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) objectiterator.next();
                playerchunkmap_entitytracker.updatePlayers(list);
            }
        }

    }

    public void broadcast(Entity entity, Packet<?> packet) {
        ChunkMap.TrackedEntity playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) this.entityMap.get(entity.getId());

        if (playerchunkmap_entitytracker != null) {
            playerchunkmap_entitytracker.broadcast(packet);
        }

    }

    protected void broadcastAndSend(Entity entity, Packet<?> packet) {
        ChunkMap.TrackedEntity playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) this.entityMap.get(entity.getId());

        if (playerchunkmap_entitytracker != null) {
            playerchunkmap_entitytracker.broadcastAndSend(packet);
        }

    }

    public void resendBiomesForChunks(List<ChunkAccess> chunks) {
        Map<ServerPlayer, List<LevelChunk>> map = new HashMap();
        Iterator iterator = chunks.iterator();

        while (iterator.hasNext()) {
            ChunkAccess ichunkaccess = (ChunkAccess) iterator.next();
            ChunkPos chunkcoordintpair = ichunkaccess.getPos();
            LevelChunk chunk;

            if (ichunkaccess instanceof LevelChunk) {
                LevelChunk chunk1 = (LevelChunk) ichunkaccess;

                chunk = chunk1;
            } else {
                chunk = this.level.getChunk(chunkcoordintpair.x, chunkcoordintpair.z);
            }

            Iterator iterator1 = this.getPlayers(chunkcoordintpair, false).iterator();

            while (iterator1.hasNext()) {
                ServerPlayer entityplayer = (ServerPlayer) iterator1.next();

                ((List) map.computeIfAbsent(entityplayer, (entityplayer1) -> {
                    return new ArrayList();
                })).add(chunk);
            }
        }

        map.forEach((entityplayer1, list1) -> {
            entityplayer1.connection.send(ClientboundChunksBiomesPacket.forChunks(list1));
        });
    }

    private void playerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> cachedDataPacket, LevelChunk chunk) {
        if (cachedDataPacket.getValue() == null) {
            cachedDataPacket.setValue(new ClientboundLevelChunkWithLightPacket(chunk, this.lightEngine, (BitSet) null, (BitSet) null, true));
        }

        player.trackChunk(chunk.getPos(), (Packet) cachedDataPacket.getValue());
        DebugPackets.sendPoiPacketsForChunk(this.level, chunk.getPos());
        List<Entity> list = Lists.newArrayList();
        List<Entity> list1 = Lists.newArrayList();
        ObjectIterator objectiterator = this.entityMap.values().iterator();

        while (objectiterator.hasNext()) {
            ChunkMap.TrackedEntity playerchunkmap_entitytracker = (ChunkMap.TrackedEntity) objectiterator.next();
            Entity entity = playerchunkmap_entitytracker.entity;

            if (entity != player && entity.chunkPosition().equals(chunk.getPos())) {
                playerchunkmap_entitytracker.updatePlayer(player);
                if (entity instanceof Mob && ((Mob) entity).getLeashHolder() != null) {
                    list.add(entity);
                }

                if (!entity.getPassengers().isEmpty()) {
                    list1.add(entity);
                }
            }
        }

        Iterator iterator;
        Entity entity1;

        if (!list.isEmpty()) {
            iterator = list.iterator();

            while (iterator.hasNext()) {
                entity1 = (Entity) iterator.next();
                player.connection.send(new ClientboundSetEntityLinkPacket(entity1, ((Mob) entity1).getLeashHolder()));
            }
        }

        if (!list1.isEmpty()) {
            iterator = list1.iterator();

            while (iterator.hasNext()) {
                entity1 = (Entity) iterator.next();
                player.connection.send(new ClientboundSetPassengersPacket(entity1));
            }
        }

    }

    public PoiManager getPoiManager() {
        return this.poiManager;
    }

    public String getStorageName() {
        return this.storageName;
    }

    void onFullChunkStatusChange(ChunkPos chunkPos, ChunkHolder.FullChunkStatus levelType) {
        this.chunkStatusListener.onChunkStatusChange(chunkPos, levelType);
    }

    private class ChunkDistanceManager extends DistanceManager {

        protected ChunkDistanceManager(Executor workerExecutor, Executor mainThreadExecutor) {
            super(workerExecutor, mainThreadExecutor, ChunkMap.this);
        }

        @Override
        protected boolean isChunkToRemove(long pos) {
            return ChunkMap.this.toDrop.contains(pos);
        }

        @Nullable
        @Override
        protected ChunkHolder getChunk(long pos) {
            return ChunkMap.this.getUpdatingChunkIfPresent(pos);
        }

        @Nullable
        @Override
        protected ChunkHolder updateChunkScheduling(long pos, int level, @Nullable ChunkHolder holder, int k) {
            return ChunkMap.this.updateChunkScheduling(pos, level, holder, k);
        }
    }

    public class TrackedEntity {

        public final ServerEntity serverEntity;
        final Entity entity;
        private final int range;
        SectionPos lastSectionPos;
        public final Set<ServerPlayerConnection> seenBy = Sets.newIdentityHashSet();

        public TrackedEntity(Entity entity, int i, int j, boolean flag) {
            this.serverEntity = new ServerEntity(ChunkMap.this.level, entity, j, flag, this::broadcast, this.seenBy); // CraftBukkit
            this.entity = entity;
            this.range = i;
            this.lastSectionPos = SectionPos.of((EntityAccess) entity);
        }

        public boolean equals(Object object) {
            return object instanceof ChunkMap.TrackedEntity ? ((ChunkMap.TrackedEntity) object).entity.getId() == this.entity.getId() : false;
        }

        public int hashCode() {
            return this.entity.getId();
        }

        public void broadcast(Packet<?> packet) {
            Iterator iterator = this.seenBy.iterator();

            while (iterator.hasNext()) {
                ServerPlayerConnection serverplayerconnection = (ServerPlayerConnection) iterator.next();

                serverplayerconnection.send(packet);
            }

        }

        public void broadcastAndSend(Packet<?> packet) {
            this.broadcast(packet);
            if (this.entity instanceof ServerPlayer) {
                ((ServerPlayer) this.entity).connection.send(packet);
            }

        }

        public void broadcastRemoved() {
            Iterator iterator = this.seenBy.iterator();

            while (iterator.hasNext()) {
                ServerPlayerConnection serverplayerconnection = (ServerPlayerConnection) iterator.next();

                this.serverEntity.removePairing(serverplayerconnection.getPlayer());
            }

        }

        public void removePlayer(ServerPlayer player) {
            org.spigotmc.AsyncCatcher.catchOp("player tracker clear"); // Spigot
            if (this.seenBy.remove(player.connection)) {
                this.serverEntity.removePairing(player);
            }

        }

        public void updatePlayer(ServerPlayer player) {
            org.spigotmc.AsyncCatcher.catchOp("player tracker update"); // Spigot
            if (player != this.entity) {
                Vec3 vec3d = player.position().subtract(this.entity.position());
                double d0 = (double) Math.min(this.getEffectiveRange(), (ChunkMap.this.viewDistance - 1) * 16);
                double d1 = vec3d.x * vec3d.x + vec3d.z * vec3d.z;
                double d2 = d0 * d0;
                boolean flag = d1 <= d2 && this.entity.broadcastToPlayer(player);

                // CraftBukkit start - respect vanish API
                if (!player.getBukkitEntity().canSee(this.entity.getBukkitEntity())) {
                    flag = false;
                }
                // CraftBukkit end
                if (flag) {
                    if (this.seenBy.add(player.connection)) {
                        this.serverEntity.addPairing(player);
                    }
                } else if (this.seenBy.remove(player.connection)) {
                    this.serverEntity.removePairing(player);
                }

            }
        }

        private int scaledRange(int initialDistance) {
            return ChunkMap.this.level.getServer().getScaledTrackingDistance(initialDistance);
        }

        private int getEffectiveRange() {
            int i = this.range;
            Iterator iterator = this.entity.getIndirectPassengers().iterator();

            while (iterator.hasNext()) {
                Entity entity = (Entity) iterator.next();
                int j = entity.getType().clientTrackingRange() * 16;

                if (j > i) {
                    i = j;
                }
            }

            return this.scaledRange(i);
        }

        public void updatePlayers(List<ServerPlayer> players) {
            Iterator iterator = players.iterator();

            while (iterator.hasNext()) {
                ServerPlayer entityplayer = (ServerPlayer) iterator.next();

                this.updatePlayer(entityplayer);
            }

        }
    }
}
