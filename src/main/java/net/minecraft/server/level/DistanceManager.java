package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

public abstract class DistanceManager {

    static final Logger LOGGER = LogUtils.getLogger();
    private static final int ENTITY_TICKING_RANGE = 2;
    static final int PLAYER_TICKET_LEVEL = 33 + ChunkStatus.getDistance(ChunkStatus.FULL) - 2;
    private static final int INITIAL_TICKET_LIST_CAPACITY = 4;
    private static final int ENTITY_TICKING_LEVEL_THRESHOLD = 32;
    private static final int BLOCK_TICKING_LEVEL_THRESHOLD = 33;
    final Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk = new Long2ObjectOpenHashMap();
    public final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets = new Long2ObjectOpenHashMap();
    private final DistanceManager.ChunkTicketTracker ticketTracker = new DistanceManager.ChunkTicketTracker();
    private final DistanceManager.FixedPlayerDistanceChunkTracker naturalSpawnChunkCounter = new DistanceManager.FixedPlayerDistanceChunkTracker(8);
    private final TickingTracker tickingTicketsTracker = new TickingTracker();
    private final DistanceManager.PlayerTicketTracker playerTicketManager = new DistanceManager.PlayerTicketTracker(33);
    final Set<ChunkHolder> chunksToUpdateFutures = Sets.newHashSet();
    final ChunkTaskPriorityQueueSorter ticketThrottler;
    final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> ticketThrottlerInput;
    final ProcessorHandle<ChunkTaskPriorityQueueSorter.Release> ticketThrottlerReleaser;
    final LongSet ticketsToRelease = new LongOpenHashSet();
    final Executor mainThreadExecutor;
    private long ticketTickCounter;
    private int simulationDistance = 10;

    protected DistanceManager(Executor workerExecutor, Executor mainThreadExecutor) {
        Objects.requireNonNull(mainThreadExecutor);
        ProcessorHandle<Runnable> mailbox = ProcessorHandle.of("player ticket throttler", mainThreadExecutor::execute);
        ChunkTaskPriorityQueueSorter chunktaskqueuesorter = new ChunkTaskPriorityQueueSorter(ImmutableList.of(mailbox), workerExecutor, 4);

        this.ticketThrottler = chunktaskqueuesorter;
        this.ticketThrottlerInput = chunktaskqueuesorter.getProcessor(mailbox, true);
        this.ticketThrottlerReleaser = chunktaskqueuesorter.getReleaseProcessor(mailbox);
        this.mainThreadExecutor = mainThreadExecutor;
    }

    protected void purgeStaleTickets() {
        ++this.ticketTickCounter;
        ObjectIterator objectiterator = this.tickets.long2ObjectEntrySet().fastIterator();

        while (objectiterator.hasNext()) {
            Entry<SortedArraySet<Ticket<?>>> entry = (Entry) objectiterator.next();
            Iterator<Ticket<?>> iterator = ((SortedArraySet) entry.getValue()).iterator();
            boolean flag = false;

            while (iterator.hasNext()) {
                Ticket<?> ticket = (Ticket) iterator.next();

                if (ticket.timedOut(this.ticketTickCounter)) {
                    iterator.remove();
                    flag = true;
                    this.tickingTicketsTracker.removeTicket(entry.getLongKey(), ticket);
                }
            }

            if (flag) {
                this.ticketTracker.update(entry.getLongKey(), DistanceManager.getTicketLevelAt((SortedArraySet) entry.getValue()), false);
            }

            if (((SortedArraySet) entry.getValue()).isEmpty()) {
                objectiterator.remove();
            }
        }

    }

    private static int getTicketLevelAt(SortedArraySet<Ticket<?>> tickets) {
        return !tickets.isEmpty() ? ((Ticket) tickets.first()).getTicketLevel() : ChunkMap.MAX_CHUNK_DISTANCE + 1;
    }

    protected abstract boolean isChunkToRemove(long pos);

    @Nullable
    protected abstract ChunkHolder getChunk(long pos);

    @Nullable
    protected abstract ChunkHolder updateChunkScheduling(long pos, int level, @Nullable ChunkHolder holder, int k);

    public boolean runAllUpdates(ChunkMap chunkStorage) {
        this.naturalSpawnChunkCounter.runAllUpdates();
        this.tickingTicketsTracker.runAllUpdates();
        this.playerTicketManager.runAllUpdates();
        int i = Integer.MAX_VALUE - this.ticketTracker.runDistanceUpdates(Integer.MAX_VALUE);
        boolean flag = i != 0;

        if (flag) {
            ;
        }

        if (!this.chunksToUpdateFutures.isEmpty()) {
            // CraftBukkit start
            // Iterate pending chunk updates with protection against concurrent modification exceptions
            java.util.Iterator<ChunkHolder> iter = this.chunksToUpdateFutures.iterator();
            int expectedSize = this.chunksToUpdateFutures.size();
            do {
                ChunkHolder playerchunk = iter.next();
                iter.remove();
                expectedSize--;

                playerchunk.updateFutures(chunkStorage, this.mainThreadExecutor);

                // Reset iterator if set was modified using add()
                if (this.chunksToUpdateFutures.size() != expectedSize) {
                    expectedSize = this.chunksToUpdateFutures.size();
                    iter = this.chunksToUpdateFutures.iterator();
                }
            } while (iter.hasNext());
            // CraftBukkit end

            return true;
        } else {
            if (!this.ticketsToRelease.isEmpty()) {
                LongIterator longiterator = this.ticketsToRelease.iterator();

                while (longiterator.hasNext()) {
                    long j = longiterator.nextLong();

                    if (this.getTickets(j).stream().anyMatch((ticket) -> {
                        return ticket.getType() == TicketType.PLAYER;
                    })) {
                        ChunkHolder playerchunk = chunkStorage.getUpdatingChunkIfPresent(j);

                        if (playerchunk == null) {
                            throw new IllegalStateException();
                        }

                        CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> completablefuture = playerchunk.getEntityTickingChunkFuture();

                        completablefuture.thenAccept((either) -> {
                            this.mainThreadExecutor.execute(() -> {
                                this.ticketThrottlerReleaser.tell(ChunkTaskPriorityQueueSorter.release(() -> {
                                }, j, false));
                            });
                        });
                    }
                }

                this.ticketsToRelease.clear();
            }

            return flag;
        }
    }

    boolean addTicket(long i, Ticket<?> ticket) { // CraftBukkit - void -> boolean
        SortedArraySet<Ticket<?>> arraysetsorted = this.getTickets(i);
        int j = DistanceManager.getTicketLevelAt(arraysetsorted);
        Ticket<?> ticket1 = (Ticket) arraysetsorted.addOrGet(ticket);

        ticket1.setCreatedTick(this.ticketTickCounter);
        if (ticket.getTicketLevel() < j) {
            this.ticketTracker.update(i, ticket.getTicketLevel(), true);
        }

        return ticket == ticket1; // CraftBukkit
    }

    boolean removeTicket(long i, Ticket<?> ticket) { // CraftBukkit - void -> boolean
        SortedArraySet<Ticket<?>> arraysetsorted = this.getTickets(i);

        boolean removed = false; // CraftBukkit
        if (arraysetsorted.remove(ticket)) {
            removed = true; // CraftBukkit
        }

        if (arraysetsorted.isEmpty()) {
            this.tickets.remove(i);
        }

        this.ticketTracker.update(i, DistanceManager.getTicketLevelAt(arraysetsorted), false);
        return removed; // CraftBukkit
    }

    public <T> void addTicket(TicketType<T> type, ChunkPos pos, int level, T argument) {
        this.addTicket(pos.toLong(), new Ticket<>(type, level, argument));
    }

    public <T> void removeTicket(TicketType<T> type, ChunkPos pos, int level, T argument) {
        Ticket<T> ticket = new Ticket<>(type, level, argument);

        this.removeTicket(pos.toLong(), ticket);
    }

    public <T> void addRegionTicket(TicketType<T> type, ChunkPos pos, int radius, T argument) {
        // CraftBukkit start
        this.addRegionTicketAtDistance(type, pos, radius, argument);
    }

    public <T> boolean addRegionTicketAtDistance(TicketType<T> tickettype, ChunkPos chunkcoordintpair, int i, T t0) {
        // CraftBukkit end
        Ticket<T> ticket = new Ticket<>(tickettype, 33 - i, t0);
        long j = chunkcoordintpair.toLong();

        boolean added = this.addTicket(j, ticket); // CraftBukkit
        this.tickingTicketsTracker.addTicket(j, ticket);
        return added; // CraftBukkit
    }

    public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int radius, T argument) {
        // CraftBukkit start
        this.removeRegionTicketAtDistance(type, pos, radius, argument);
    }

    public <T> boolean removeRegionTicketAtDistance(TicketType<T> tickettype, ChunkPos chunkcoordintpair, int i, T t0) {
        // CraftBukkit end
        Ticket<T> ticket = new Ticket<>(tickettype, 33 - i, t0);
        long j = chunkcoordintpair.toLong();

        boolean removed = this.removeTicket(j, ticket); // CraftBukkit
        this.tickingTicketsTracker.removeTicket(j, ticket);
        return removed; // CraftBukkit
    }

    private SortedArraySet<Ticket<?>> getTickets(long position) {
        return (SortedArraySet) this.tickets.computeIfAbsent(position, (j) -> {
            return SortedArraySet.create(4);
        });
    }

    protected void updateChunkForced(ChunkPos pos, boolean forced) {
        Ticket<ChunkPos> ticket = new Ticket<>(TicketType.FORCED, 31, pos);
        long i = pos.toLong();

        if (forced) {
            this.addTicket(i, ticket);
            this.tickingTicketsTracker.addTicket(i, ticket);
        } else {
            this.removeTicket(i, ticket);
            this.tickingTicketsTracker.removeTicket(i, ticket);
        }

    }

    public void addPlayer(SectionPos pos, ServerPlayer player) {
        ChunkPos chunkcoordintpair = pos.chunk();
        long i = chunkcoordintpair.toLong();

        ((ObjectSet) this.playersPerChunk.computeIfAbsent(i, (j) -> {
            return new ObjectOpenHashSet();
        })).add(player);
        this.naturalSpawnChunkCounter.update(i, 0, true);
        this.playerTicketManager.update(i, 0, true);
        this.tickingTicketsTracker.addTicket(TicketType.PLAYER, chunkcoordintpair, this.getPlayerTicketLevel(), chunkcoordintpair);
    }

    public void removePlayer(SectionPos pos, ServerPlayer player) {
        ChunkPos chunkcoordintpair = pos.chunk();
        long i = chunkcoordintpair.toLong();
        ObjectSet<ServerPlayer> objectset = (ObjectSet) this.playersPerChunk.get(i);
        if (objectset == null) return; // CraftBukkit - SPIGOT-6208

        objectset.remove(player);
        if (objectset.isEmpty()) {
            this.playersPerChunk.remove(i);
            this.naturalSpawnChunkCounter.update(i, Integer.MAX_VALUE, false);
            this.playerTicketManager.update(i, Integer.MAX_VALUE, false);
            this.tickingTicketsTracker.removeTicket(TicketType.PLAYER, chunkcoordintpair, this.getPlayerTicketLevel(), chunkcoordintpair);
        }

    }

    private int getPlayerTicketLevel() {
        return Math.max(0, 31 - this.simulationDistance);
    }

    public boolean inEntityTickingRange(long chunkPos) {
        return this.tickingTicketsTracker.getLevel(chunkPos) < 32;
    }

    public boolean inBlockTickingRange(long chunkPos) {
        return this.tickingTicketsTracker.getLevel(chunkPos) < 33;
    }

    protected String getTicketDebugString(long pos) {
        SortedArraySet<Ticket<?>> arraysetsorted = (SortedArraySet) this.tickets.get(pos);

        return arraysetsorted != null && !arraysetsorted.isEmpty() ? ((Ticket) arraysetsorted.first()).toString() : "no_ticket";
    }

    protected void updatePlayerTickets(int viewDistance) {
        this.playerTicketManager.updateViewDistance(viewDistance);
    }

    public void updateSimulationDistance(int simulationDistance) {
        if (simulationDistance != this.simulationDistance) {
            this.simulationDistance = simulationDistance;
            this.tickingTicketsTracker.replacePlayerTicketsLevel(this.getPlayerTicketLevel());
        }

    }

    public int getNaturalSpawnChunkCount() {
        this.naturalSpawnChunkCounter.runAllUpdates();
        return this.naturalSpawnChunkCounter.chunks.size();
    }

    public boolean hasPlayersNearby(long chunkPos) {
        this.naturalSpawnChunkCounter.runAllUpdates();
        return this.naturalSpawnChunkCounter.chunks.containsKey(chunkPos);
    }

    public String getDebugStatus() {
        return this.ticketThrottler.getDebugStatus();
    }

    private void dumpTickets(String path) {
        try {
            FileOutputStream fileoutputstream = new FileOutputStream(new File(path));

            try {
                ObjectIterator objectiterator = this.tickets.long2ObjectEntrySet().iterator();

                while (objectiterator.hasNext()) {
                    Entry<SortedArraySet<Ticket<?>>> entry = (Entry) objectiterator.next();
                    ChunkPos chunkcoordintpair = new ChunkPos(entry.getLongKey());
                    Iterator iterator = ((SortedArraySet) entry.getValue()).iterator();

                    while (iterator.hasNext()) {
                        Ticket<?> ticket = (Ticket) iterator.next();

                        fileoutputstream.write((chunkcoordintpair.x + "\t" + chunkcoordintpair.z + "\t" + ticket.getType() + "\t" + ticket.getTicketLevel() + "\t\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Throwable throwable) {
                try {
                    fileoutputstream.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }

                throw throwable;
            }

            fileoutputstream.close();
        } catch (IOException ioexception) {
            DistanceManager.LOGGER.error("Failed to dump tickets to {}", path, ioexception);
        }

    }

    @VisibleForTesting
    TickingTracker tickingTracker() {
        return this.tickingTicketsTracker;
    }

    public void removeTicketsOnClosing() {
        ImmutableSet<TicketType<?>> immutableset = ImmutableSet.of(TicketType.UNKNOWN, TicketType.POST_TELEPORT, TicketType.LIGHT);
        ObjectIterator objectiterator = this.tickets.long2ObjectEntrySet().fastIterator();

        while (objectiterator.hasNext()) {
            Entry<SortedArraySet<Ticket<?>>> entry = (Entry) objectiterator.next();
            Iterator<Ticket<?>> iterator = ((SortedArraySet) entry.getValue()).iterator();
            boolean flag = false;

            while (iterator.hasNext()) {
                Ticket<?> ticket = (Ticket) iterator.next();

                if (!immutableset.contains(ticket.getType())) {
                    iterator.remove();
                    flag = true;
                    this.tickingTicketsTracker.removeTicket(entry.getLongKey(), ticket);
                }
            }

            if (flag) {
                this.ticketTracker.update(entry.getLongKey(), DistanceManager.getTicketLevelAt((SortedArraySet) entry.getValue()), false);
            }

            if (((SortedArraySet) entry.getValue()).isEmpty()) {
                objectiterator.remove();
            }
        }

    }

    public boolean hasTickets() {
        return !this.tickets.isEmpty();
    }

    // CraftBukkit start
    public <T> void removeAllTicketsFor(TicketType<T> ticketType, int ticketLevel, T ticketIdentifier) {
        Ticket<T> target = new Ticket<>(ticketType, ticketLevel, ticketIdentifier);

        for (java.util.Iterator<Entry<SortedArraySet<Ticket<?>>>> iterator = this.tickets.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
            Entry<SortedArraySet<Ticket<?>>> entry = iterator.next();
            SortedArraySet<Ticket<?>> tickets = entry.getValue();
            if (tickets.remove(target)) {
                // copied from removeTicket
                this.ticketTracker.update(entry.getLongKey(), DistanceManager.getTicketLevelAt(tickets), false);

                // can't use entry after it's removed
                if (tickets.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }
    // CraftBukkit end

    private class ChunkTicketTracker extends ChunkTracker {

        public ChunkTicketTracker() {
            super(ChunkMap.MAX_CHUNK_DISTANCE + 2, 16, 256);
        }

        @Override
        protected int getLevelFromSource(long id) {
            SortedArraySet<Ticket<?>> arraysetsorted = (SortedArraySet) DistanceManager.this.tickets.get(id);

            return arraysetsorted == null ? Integer.MAX_VALUE : (arraysetsorted.isEmpty() ? Integer.MAX_VALUE : ((Ticket) arraysetsorted.first()).getTicketLevel());
        }

        @Override
        protected int getLevel(long id) {
            if (!DistanceManager.this.isChunkToRemove(id)) {
                ChunkHolder playerchunk = DistanceManager.this.getChunk(id);

                if (playerchunk != null) {
                    return playerchunk.getTicketLevel();
                }
            }

            return ChunkMap.MAX_CHUNK_DISTANCE + 1;
        }

        @Override
        protected void setLevel(long id, int level) {
            ChunkHolder playerchunk = DistanceManager.this.getChunk(id);
            int k = playerchunk == null ? ChunkMap.MAX_CHUNK_DISTANCE + 1 : playerchunk.getTicketLevel();

            if (k != level) {
                playerchunk = DistanceManager.this.updateChunkScheduling(id, level, playerchunk, k);
                if (playerchunk != null) {
                    DistanceManager.this.chunksToUpdateFutures.add(playerchunk);
                }

            }
        }

        public int runDistanceUpdates(int distance) {
            return this.runUpdates(distance);
        }
    }

    private class FixedPlayerDistanceChunkTracker extends ChunkTracker {

        protected final Long2ByteMap chunks = new Long2ByteOpenHashMap();
        protected final int maxDistance;

        protected FixedPlayerDistanceChunkTracker(int i) {
            super(i + 2, 16, 256);
            this.maxDistance = i;
            this.chunks.defaultReturnValue((byte) (i + 2));
        }

        @Override
        protected int getLevel(long id) {
            return this.chunks.get(id);
        }

        @Override
        protected void setLevel(long id, int level) {
            byte b0;

            if (level > this.maxDistance) {
                b0 = this.chunks.remove(id);
            } else {
                b0 = this.chunks.put(id, (byte) level);
            }

            this.onLevelChange(id, b0, level);
        }

        protected void onLevelChange(long pos, int oldDistance, int distance) {}

        @Override
        protected int getLevelFromSource(long id) {
            return this.havePlayer(id) ? 0 : Integer.MAX_VALUE;
        }

        private boolean havePlayer(long chunkPos) {
            ObjectSet<ServerPlayer> objectset = (ObjectSet) DistanceManager.this.playersPerChunk.get(chunkPos);

            return objectset != null && !objectset.isEmpty();
        }

        public void runAllUpdates() {
            this.runUpdates(Integer.MAX_VALUE);
        }

        private void dumpChunks(String path) {
            try {
                FileOutputStream fileoutputstream = new FileOutputStream(new File(path));

                try {
                    ObjectIterator objectiterator = this.chunks.long2ByteEntrySet().iterator();

                    while (objectiterator.hasNext()) {
                        it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry it_unimi_dsi_fastutil_longs_long2bytemap_entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry) objectiterator.next();
                        ChunkPos chunkcoordintpair = new ChunkPos(it_unimi_dsi_fastutil_longs_long2bytemap_entry.getLongKey());
                        String s1 = Byte.toString(it_unimi_dsi_fastutil_longs_long2bytemap_entry.getByteValue());

                        fileoutputstream.write((chunkcoordintpair.x + "\t" + chunkcoordintpair.z + "\t" + s1 + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Throwable throwable) {
                    try {
                        fileoutputstream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }

                    throw throwable;
                }

                fileoutputstream.close();
            } catch (IOException ioexception) {
                DistanceManager.LOGGER.error("Failed to dump chunks to {}", path, ioexception);
            }

        }
    }

    private class PlayerTicketTracker extends DistanceManager.FixedPlayerDistanceChunkTracker {

        private int viewDistance = 0;
        private final Long2IntMap queueLevels = Long2IntMaps.synchronize(new Long2IntOpenHashMap());
        private final LongSet toUpdate = new LongOpenHashSet();

        protected PlayerTicketTracker(int i) {
            super(i);
            this.queueLevels.defaultReturnValue(i + 2);
        }

        @Override
        protected void onLevelChange(long pos, int oldDistance, int distance) {
            this.toUpdate.add(pos);
        }

        public void updateViewDistance(int watchDistance) {
            ObjectIterator objectiterator = this.chunks.long2ByteEntrySet().iterator();

            while (objectiterator.hasNext()) {
                it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry it_unimi_dsi_fastutil_longs_long2bytemap_entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry) objectiterator.next();
                byte b0 = it_unimi_dsi_fastutil_longs_long2bytemap_entry.getByteValue();
                long j = it_unimi_dsi_fastutil_longs_long2bytemap_entry.getLongKey();

                this.onLevelChange(j, b0, this.haveTicketFor(b0), b0 <= watchDistance - 2);
            }

            this.viewDistance = watchDistance;
        }

        private void onLevelChange(long pos, int distance, boolean oldWithinViewDistance, boolean withinViewDistance) {
            if (oldWithinViewDistance != withinViewDistance) {
                Ticket<?> ticket = new Ticket<>(TicketType.PLAYER, DistanceManager.PLAYER_TICKET_LEVEL, new ChunkPos(pos));

                if (withinViewDistance) {
                    DistanceManager.this.ticketThrottlerInput.tell(ChunkTaskPriorityQueueSorter.message(() -> {
                        DistanceManager.this.mainThreadExecutor.execute(() -> {
                            if (this.haveTicketFor(this.getLevel(pos))) {
                                DistanceManager.this.addTicket(pos, ticket);
                                DistanceManager.this.ticketsToRelease.add(pos);
                            } else {
                                DistanceManager.this.ticketThrottlerReleaser.tell(ChunkTaskPriorityQueueSorter.release(() -> {
                                }, pos, false));
                            }

                        });
                    }, pos, () -> {
                        return distance;
                    }));
                } else {
                    DistanceManager.this.ticketThrottlerReleaser.tell(ChunkTaskPriorityQueueSorter.release(() -> {
                        DistanceManager.this.mainThreadExecutor.execute(() -> {
                            DistanceManager.this.removeTicket(pos, ticket);
                        });
                    }, pos, true));
                }
            }

        }

        @Override
        public void runAllUpdates() {
            super.runAllUpdates();
            if (!this.toUpdate.isEmpty()) {
                LongIterator longiterator = this.toUpdate.iterator();

                while (longiterator.hasNext()) {
                    long i = longiterator.nextLong();
                    int j = this.queueLevels.get(i);
                    int k = this.getLevel(i);

                    if (j != k) {
                        DistanceManager.this.ticketThrottler.onLevelChange(new ChunkPos(i), () -> {
                            return this.queueLevels.get(i);
                        }, k, (l) -> {
                            if (l >= this.queueLevels.defaultReturnValue()) {
                                this.queueLevels.remove(i);
                            } else {
                                this.queueLevels.put(i, l);
                            }

                        });
                        this.onLevelChange(i, k, this.haveTicketFor(j), this.haveTicketFor(k));
                    }
                }

                this.toUpdate.clear();
            }

        }

        private boolean haveTicketFor(int distance) {
            return distance <= this.viewDistance - 2;
        }
    }
}
