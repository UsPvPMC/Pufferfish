package net.minecraft.server.level;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;

// Paper start
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import io.papermc.paper.util.CoordinateUtils;
import java.util.function.Supplier;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.world.level.chunk.ChunkStatus;
// Paper end

public class ThreadedLevelLightEngine extends LevelLightEngine implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ProcessorMailbox<Runnable> taskMailbox;
    private final ObjectList<Pair<ThreadedLevelLightEngine.TaskType, Runnable>> lightTasks = new ObjectArrayList<>();
    private final ChunkMap chunkMap;
    private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox;
    private volatile int taskPerBatch = 5;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    // Paper start - replace light engine impl
    protected final ca.spottedleaf.starlight.common.light.StarLightInterface theLightEngine;
    public final boolean hasBlockLight;
    public final boolean hasSkyLight;
    // Paper end - replace light engine impl

    public ThreadedLevelLightEngine(LightChunkGetter chunkProvider, ChunkMap chunkStorage, boolean hasBlockLight, ProcessorMailbox<Runnable> processor, ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> executor) {
        super(chunkProvider, false, false); // Paper - destroy vanilla light engine state
        this.chunkMap = chunkStorage;
        this.sorterMailbox = executor;
        this.taskMailbox = processor;
        // Paper start - replace light engine impl
        this.hasBlockLight = true;
        this.hasSkyLight = hasBlockLight; // Nice variable name.
        this.theLightEngine = new ca.spottedleaf.starlight.common.light.StarLightInterface(chunkProvider, this.hasSkyLight, this.hasBlockLight, this);
        // Paper end - replace light engine impl
    }

    // Paper start - replace light engine impl
    protected final ChunkAccess getChunk(final int chunkX, final int chunkZ) {
        return ((ServerLevel)this.theLightEngine.getWorld()).getChunkSource().getChunkAtImmediately(chunkX, chunkZ);
    }

    protected long relightCounter;

    public int relight(java.util.Set<ChunkPos> chunks_param,
                        java.util.function.Consumer<ChunkPos> chunkLightCallback,
                        java.util.function.IntConsumer onComplete) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Must only be called on the main thread");
        }

        java.util.Set<ChunkPos> chunks = new java.util.LinkedHashSet<>(chunks_param);
        // add tickets
        java.util.Map<ChunkPos, Long> ticketIds = new java.util.HashMap<>();
        int totalChunks = 0;
        for (java.util.Iterator<ChunkPos> iterator = chunks.iterator(); iterator.hasNext();) {
            final ChunkPos chunkPos = iterator.next();

            final ChunkAccess chunk = (ChunkAccess)((ServerLevel)this.theLightEngine.getWorld()).getChunkSource().getChunkForLighting(chunkPos.x, chunkPos.z);
            if (chunk == null || !chunk.isLightCorrect() || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
                // cannot relight this chunk
                iterator.remove();
                continue;
            }

            final Long id = Long.valueOf(this.relightCounter++);

            ((ServerLevel)this.theLightEngine.getWorld()).getChunkSource().addTicketAtLevel(TicketType.CHUNK_RELIGHT, chunkPos, io.papermc.paper.util.MCUtil.getTicketLevelFor(ChunkStatus.LIGHT), id);
            ticketIds.put(chunkPos, id);

            ++totalChunks;
        }

        this.taskMailbox.tell(() -> {
            this.theLightEngine.relightChunks(chunks, (ChunkPos chunkPos) -> {
                chunkLightCallback.accept(chunkPos);
                ((java.util.concurrent.Executor)((ServerLevel)this.theLightEngine.getWorld()).getChunkSource().mainThreadProcessor).execute(() -> {
                    ((ServerLevel)this.theLightEngine.getWorld()).getChunkSource().chunkMap.getUpdatingChunkIfPresent(chunkPos.toLong()).broadcast(new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(chunkPos, ThreadedLevelLightEngine.this, null, null, true), false);
                    ((ServerLevel)this.theLightEngine.getWorld()).getChunkSource().removeTicketAtLevel(TicketType.CHUNK_RELIGHT, chunkPos, io.papermc.paper.util.MCUtil.getTicketLevelFor(ChunkStatus.LIGHT), ticketIds.get(chunkPos));
                });
            }, onComplete);
        });
        this.tryScheduleUpdate();

        return totalChunks;
    }

    private final Long2IntOpenHashMap chunksBeingWorkedOn = new Long2IntOpenHashMap();

    private void queueTaskForSection(final int chunkX, final int chunkY, final int chunkZ, final Supplier<CompletableFuture<Void>> runnable) {
        final ServerLevel world = (ServerLevel)this.theLightEngine.getWorld();

        final ChunkAccess center = this.theLightEngine.getAnyChunkNow(chunkX, chunkZ);
        if (center == null || !center.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            // do not accept updates in unlit chunks, unless we might be generating a chunk. thanks to the amazing
            // chunk scheduling, we could be lighting and generating a chunk at the same time
            return;
        }

        if (center.getStatus() != ChunkStatus.FULL) {
            // do not keep chunk loaded, we are probably in a gen thread
            // if we proceed to add a ticket the chunk will be loaded, which is not what we want (avoid cascading gen)
            runnable.get();
            return;
        }

        if (!world.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            // ticket logic is not safe to run off-main, re-schedule
            world.getChunkSource().chunkMap.mainThreadExecutor.execute(() -> {
                this.queueTaskForSection(chunkX, chunkY, chunkZ, runnable);
            });
            return;
        }

        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        final CompletableFuture<Void> updateFuture = runnable.get();

        if (updateFuture == null) {
            // not scheduled
            return;
        }

        final int references = this.chunksBeingWorkedOn.addTo(key, 1);
        if (references == 0) {
            final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            world.getChunkSource().addRegionTicket(ca.spottedleaf.starlight.common.light.StarLightInterface.CHUNK_WORK_TICKET, pos, 0, pos);
        }

        updateFuture.thenAcceptAsync((final Void ignore) -> {
            final int newReferences = this.chunksBeingWorkedOn.get(key);
            if (newReferences == 1) {
                this.chunksBeingWorkedOn.remove(key);
                final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                world.getChunkSource().removeRegionTicket(ca.spottedleaf.starlight.common.light.StarLightInterface.CHUNK_WORK_TICKET, pos, 0, pos);
            } else {
                this.chunksBeingWorkedOn.put(key, newReferences - 1);
            }
        }, world.getChunkSource().chunkMap.mainThreadExecutor).whenComplete((final Void ignore, final Throwable thr) -> {
            if (thr != null) {
                LOGGER.error("Failed to remove ticket level for post chunk task " + new ChunkPos(chunkX, chunkZ), thr);
            }
        });
    }

    @Override
    public boolean hasLightWork() {
        // route to new light engine
        return this.theLightEngine.hasUpdates();
    }

    @Override
    public LayerLightEventListener getLayerListener(final LightLayer lightType) {
        return lightType == LightLayer.BLOCK ? this.theLightEngine.getBlockReader() : this.theLightEngine.getSkyReader();
    }

    @Override
    public int getRawBrightness(final BlockPos pos, final int ambientDarkness) {
        // need to use new light hooks for this
        final int sky = this.theLightEngine.getSkyReader().getLightValue(pos) - ambientDarkness;
        // Don't fetch the block light level if the skylight level is 15, since the value will never be higher.
        if (sky == 15) return 15;
        final int block = this.theLightEngine.getBlockReader().getLightValue(pos);
        return Math.max(sky, block);
    }
    // Paper end - replace light engine imp

    @Override
    public void close() {
    }

    @Override
    public int runUpdates(int i, boolean doSkylight, boolean skipEdgeLightPropagation) {
        throw (UnsupportedOperationException)Util.pauseInIde(new UnsupportedOperationException("Ran automatically on a different thread!"));
    }

    @Override
    public void onBlockEmissionIncrease(BlockPos pos, int level) {
        throw (UnsupportedOperationException)Util.pauseInIde(new UnsupportedOperationException("Ran automatically on a different thread!"));
    }

    @Override
    public void checkBlock(BlockPos pos) {
        // Paper start - replace light engine impl
        final BlockPos posCopy = pos.immutable();
        this.queueTaskForSection(posCopy.getX() >> 4, posCopy.getY() >> 4, posCopy.getZ() >> 4, () -> {
            return this.theLightEngine.blockChange(posCopy);
        });
        // Paper end - replace light engine impl
    }

    protected void updateChunkStatus(ChunkPos pos) {
        if (true) return; // Paper - replace light engine impl
        this.addTask(pos.x, pos.z, () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.retainData(pos, false);
            super.enableLightSources(pos, false);

            for(int i = this.getMinLightSection(); i < this.getMaxLightSection(); ++i) {
                super.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, i), (DataLayer)null, true);
                super.queueSectionData(LightLayer.SKY, SectionPos.of(pos, i), (DataLayer)null, true);
            }

            for(int j = this.levelHeightAccessor.getMinSection(); j < this.levelHeightAccessor.getMaxSection(); ++j) {
                super.updateSectionStatus(SectionPos.of(pos, j), true);
            }

        }, () -> {
            return "updateChunkStatus " + pos + " true";
        }));
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean notReady) {
        // Paper start - replace light engine impl
        this.queueTaskForSection(pos.getX(), pos.getY(), pos.getZ(), () -> {
            return this.theLightEngine.sectionChange(pos, notReady);
        });
        // Paper end - replace light engine impl
    }

    @Override
    public void enableLightSources(ChunkPos pos, boolean retainData) {
        if (true) return; // Paper - replace light engine impl
        this.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.enableLightSources(pos, retainData);
        }, () -> {
            return "enableLight " + pos + " " + retainData;
        }));
    }

    @Override
    public void queueSectionData(LightLayer lightType, SectionPos pos, @Nullable DataLayer nibbles, boolean nonEdge) {
        if (true) return; // Paper - replace light engine impl
        this.addTask(pos.x(), pos.z(), () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.queueSectionData(lightType, pos, nibbles, nonEdge);
        }, () -> {
            return "queueData " + pos;
        }));
    }

    private void addTask(int x, int z, ThreadedLevelLightEngine.TaskType stage, Runnable task) {
        this.addTask(x, z, this.chunkMap.getChunkQueueLevel(ChunkPos.asLong(x, z)), stage, task);
    }

    private void addTask(int x, int z, IntSupplier completedLevelSupplier, ThreadedLevelLightEngine.TaskType stage, Runnable task) {
        this.sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            this.lightTasks.add(Pair.of(stage, task));
            if (this.lightTasks.size() >= this.taskPerBatch) {
                this.runUpdate();
            }

        }, ChunkPos.asLong(x, z), completedLevelSupplier));
    }

    @Override
    public void retainData(ChunkPos pos, boolean retainData) {
        if (true) return; // Paper - replace light engine impl
        this.addTask(pos.x, pos.z, () -> {
            return 0;
        }, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            super.retainData(pos, retainData);
        }, () -> {
            return "retainData " + pos;
        }));
    }

    public CompletableFuture<ChunkAccess> retainData(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        return CompletableFuture.supplyAsync(Util.name(() -> {
            super.retainData(chunkPos, true);
            return chunk;
        }, () -> {
            return "retainData: " + chunkPos;
        }), (task) -> {
            this.addTask(chunkPos.x, chunkPos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, task);
        });
    }

    public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean excludeBlocks) {
        // Paper start - replace light engine impl
        if (true) {
            boolean lit = excludeBlocks;
            final ChunkPos chunkPos = chunk.getPos();

            return CompletableFuture.supplyAsync(() -> {
                final Boolean[] emptySections = StarLightEngine.getEmptySectionsForChunk(chunk);
                if (!lit) {
                    chunk.setLightCorrect(false);
                    this.theLightEngine.lightChunk(chunk, emptySections);
                    chunk.setLightCorrect(true);
                } else {
                    this.theLightEngine.forceLoadInChunk(chunk, emptySections);
                    // can't really force the chunk to be edged checked, as we need neighbouring chunks - but we don't have
                    // them, so if it's not loaded then i guess we can't do edge checks. later loads of the chunk should
                    // catch what we miss here.
                    this.theLightEngine.checkChunkEdges(chunkPos.x, chunkPos.z);
                }

                this.chunkMap.releaseLightTicket(chunkPos);
                return chunk;
            }, (runnable) -> {
                this.theLightEngine.scheduleChunkLight(chunkPos, runnable);
                this.tryScheduleUpdate();
            }).whenComplete((final ChunkAccess c, final Throwable throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Failed to light chunk " + chunkPos, throwable);
                }
            });
        }
        // Paper end - replace light engine impl
        ChunkPos chunkPos = chunk.getPos();
        chunk.setLightCorrect(false);
        this.addTask(chunkPos.x, chunkPos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, Util.name(() -> {
            LevelChunkSection[] levelChunkSections = chunk.getSections();

            for(int i = 0; i < chunk.getSectionsCount(); ++i) {
                LevelChunkSection levelChunkSection = levelChunkSections[i];
                if (!levelChunkSection.hasOnlyAir()) {
                    int j = this.levelHeightAccessor.getSectionYFromSectionIndex(i);
                    super.updateSectionStatus(SectionPos.of(chunkPos, j), false);
                }
            }

            super.enableLightSources(chunkPos, true);
            if (!excludeBlocks) {
                chunk.getLights().forEach((pos) -> {
                    super.onBlockEmissionIncrease(pos, chunk.getLightEmission(pos));
                });
            }

        }, () -> {
            return "lightChunk " + chunkPos + " " + excludeBlocks;
        }));
        return CompletableFuture.supplyAsync(() -> {
            chunk.setLightCorrect(true);
            super.retainData(chunkPos, false);
            this.chunkMap.releaseLightTicket(chunkPos);
            return chunk;
        }, (runnable) -> {
            this.addTask(chunkPos.x, chunkPos.z, ThreadedLevelLightEngine.TaskType.POST_UPDATE, runnable);
        });
    }

    public void tryScheduleUpdate() {
        if (this.hasLightWork() && this.scheduled.compareAndSet(false, true)) { // Paper  // Paper - rewrite light engine
            this.taskMailbox.tell(() -> {
                this.runUpdate();
                this.scheduled.set(false);
            });
        }

    }

    private void runUpdate() {
        int i = Math.min(this.lightTasks.size(), this.taskPerBatch);
        ObjectListIterator<Pair<ThreadedLevelLightEngine.TaskType, Runnable>> objectListIterator = this.lightTasks.iterator();

        int j;
        for(j = 0; objectListIterator.hasNext() && j < i; ++j) {
            Pair<ThreadedLevelLightEngine.TaskType, Runnable> pair = objectListIterator.next();
            if (pair.getFirst() == ThreadedLevelLightEngine.TaskType.PRE_UPDATE) {
                pair.getSecond().run();
            }
        }

        objectListIterator.back(j);
        this.theLightEngine.propagateChanges(); // Paper - rewrite light engine

        for(int var5 = 0; objectListIterator.hasNext() && var5 < i; ++var5) {
            Pair<ThreadedLevelLightEngine.TaskType, Runnable> pair2 = objectListIterator.next();
            if (pair2.getFirst() == ThreadedLevelLightEngine.TaskType.POST_UPDATE) {
                pair2.getSecond().run();
            }

            objectListIterator.remove();
        }

    }

    public void setTaskPerBatch(int taskBatchSize) {
        this.taskPerBatch = taskBatchSize;
    }

    static enum TaskType {
        PRE_UPDATE,
        POST_UPDATE;
    }
}
