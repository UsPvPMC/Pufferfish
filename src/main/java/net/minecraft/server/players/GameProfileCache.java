// mc-dev import
package net.minecraft.server.players;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.UUIDUtil;
import org.slf4j.Logger;

public class GameProfileCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GAMEPROFILES_MRU_LIMIT = 1000;
    private static final int GAMEPROFILES_EXPIRATION_MONTHS = 1;
    private static boolean usesAuthentication;
    private final Map<String, GameProfileCache.GameProfileInfo> profilesByName = Maps.newConcurrentMap();
    private final Map<UUID, GameProfileCache.GameProfileInfo> profilesByUUID = Maps.newConcurrentMap();
    private final Map<String, CompletableFuture<Optional<GameProfile>>> requests = Maps.newConcurrentMap();
    private final GameProfileRepository profileRepository;
    private final Gson gson = (new GsonBuilder()).create();
    private final File file;
    private final AtomicLong operationCount = new AtomicLong();
    @Nullable
    private Executor executor;

    public GameProfileCache(GameProfileRepository profileRepository, File cacheFile) {
        this.profileRepository = profileRepository;
        this.file = cacheFile;
        Lists.reverse(this.load()).forEach(this::safeAdd);
    }

    private void safeAdd(GameProfileCache.GameProfileInfo entry) {
        GameProfile gameprofile = entry.getProfile();

        entry.setLastAccess(this.getNextOperation());
        String s = gameprofile.getName();

        if (s != null) {
            this.profilesByName.put(s.toLowerCase(Locale.ROOT), entry);
        }

        UUID uuid = gameprofile.getId();

        if (uuid != null) {
            this.profilesByUUID.put(uuid, entry);
        }

    }

    private static Optional<GameProfile> lookupGameProfile(GameProfileRepository repository, String name) {
        final AtomicReference<GameProfile> atomicreference = new AtomicReference();
        ProfileLookupCallback profilelookupcallback = new ProfileLookupCallback() {
            public void onProfileLookupSucceeded(GameProfile gameprofile) {
                atomicreference.set(gameprofile);
            }

            public void onProfileLookupFailed(GameProfile gameprofile, Exception exception) {
                atomicreference.set(null); // CraftBukkit - decompile error
            }
        };

        repository.findProfilesByNames(new String[]{name}, Agent.MINECRAFT, profilelookupcallback);
        GameProfile gameprofile = (GameProfile) atomicreference.get();

        if (!GameProfileCache.usesAuthentication() && gameprofile == null) {
            UUID uuid = UUIDUtil.getOrCreatePlayerUUID(new GameProfile((UUID) null, name));

            return Optional.of(new GameProfile(uuid, name));
        } else {
            return Optional.ofNullable(gameprofile);
        }
    }

    public static void setUsesAuthentication(boolean value) {
        GameProfileCache.usesAuthentication = value;
    }

    private static boolean usesAuthentication() {
        return GameProfileCache.usesAuthentication;
    }

    public void add(GameProfile profile) {
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(new Date());
        calendar.add(2, 1);
        Date date = calendar.getTime();
        GameProfileCache.GameProfileInfo usercache_usercacheentry = new GameProfileCache.GameProfileInfo(profile, date);

        this.safeAdd(usercache_usercacheentry);
        if( !org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly ) this.save(true); // Spigot - skip saving if disabled // Paper - async
    }

    private long getNextOperation() {
        return this.operationCount.incrementAndGet();
    }

    public Optional<GameProfile> get(String name) {
        String s1 = name.toLowerCase(Locale.ROOT);
        GameProfileCache.GameProfileInfo usercache_usercacheentry = (GameProfileCache.GameProfileInfo) this.profilesByName.get(s1);
        boolean flag = false;

        if (usercache_usercacheentry != null && (new Date()).getTime() >= usercache_usercacheentry.expirationDate.getTime()) {
            this.profilesByUUID.remove(usercache_usercacheentry.getProfile().getId());
            this.profilesByName.remove(usercache_usercacheentry.getProfile().getName().toLowerCase(Locale.ROOT));
            flag = true;
            usercache_usercacheentry = null;
        }

        Optional optional;

        if (usercache_usercacheentry != null) {
            usercache_usercacheentry.setLastAccess(this.getNextOperation());
            optional = Optional.of(usercache_usercacheentry.getProfile());
        } else {
            optional = GameProfileCache.lookupGameProfile(this.profileRepository, name); // Spigot - use correct case for offline players
            if (optional.isPresent()) {
                this.add((GameProfile) optional.get());
                flag = false;
            }
        }

        if (flag && !org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly) { // Spigot - skip saving if disabled
            this.save(true); // Paper
        }

        return optional;
    }

    public void getAsync(String username, Consumer<Optional<GameProfile>> consumer) {
        if (this.executor == null) {
            throw new IllegalStateException("No executor");
        } else {
            CompletableFuture<Optional<GameProfile>> completablefuture = (CompletableFuture) this.requests.get(username);

            if (completablefuture != null) {
                this.requests.put(username, completablefuture.whenCompleteAsync((optional, throwable) -> {
                    consumer.accept(optional);
                }, this.executor));
            } else {
                this.requests.put(username, CompletableFuture.supplyAsync(() -> {
                    return this.get(username);
                }, Util.backgroundExecutor()).whenCompleteAsync((optional, throwable) -> {
                    this.requests.remove(username);
                }, this.executor).whenCompleteAsync((optional, throwable) -> {
                    consumer.accept(optional);
                }, this.executor));
            }

        }
    }

    public Optional<GameProfile> get(UUID uuid) {
        GameProfileCache.GameProfileInfo usercache_usercacheentry = (GameProfileCache.GameProfileInfo) this.profilesByUUID.get(uuid);

        if (usercache_usercacheentry == null) {
            return Optional.empty();
        } else {
            usercache_usercacheentry.setLastAccess(this.getNextOperation());
            return Optional.of(usercache_usercacheentry.getProfile());
        }
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void clearExecutor() {
        this.executor = null;
    }

    private static DateFormat createDateFormat() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
    }

    public List<GameProfileCache.GameProfileInfo> load() {
        ArrayList arraylist = Lists.newArrayList();

        try {
            BufferedReader bufferedreader = Files.newReader(this.file, StandardCharsets.UTF_8);

            label54:
            {
                ArrayList arraylist1;

                try {
                    JsonArray jsonarray = (JsonArray) this.gson.fromJson(bufferedreader, JsonArray.class);

                    if (jsonarray != null) {
                        DateFormat dateformat = GameProfileCache.createDateFormat();

                        jsonarray.forEach((jsonelement) -> {
                            Optional optional = GameProfileCache.readGameProfile(jsonelement, dateformat);

                            Objects.requireNonNull(arraylist);
                            optional.ifPresent(arraylist::add);
                        });
                        break label54;
                    }

                    arraylist1 = arraylist;
                } catch (Throwable throwable) {
                    if (bufferedreader != null) {
                        try {
                            bufferedreader.close();
                        } catch (Throwable throwable1) {
                            throwable.addSuppressed(throwable1);
                        }
                    }

                    throw throwable;
                }

                if (bufferedreader != null) {
                    bufferedreader.close();
                }

                return arraylist1;
            }

            if (bufferedreader != null) {
                bufferedreader.close();
            }
        } catch (FileNotFoundException filenotfoundexception) {
            ;
        // Spigot Start
        } catch (com.google.gson.JsonSyntaxException | NullPointerException ex) {
            GameProfileCache.LOGGER.warn( "Usercache.json is corrupted or has bad formatting. Deleting it to prevent further issues." );
            this.file.delete();
        // Spigot End
        } catch (JsonParseException | IOException ioexception) {
            GameProfileCache.LOGGER.warn("Failed to load profile cache {}", this.file, ioexception);
        }

        return arraylist;
    }

    public void save(boolean asyncSave) { // Paper
        JsonArray jsonarray = new JsonArray();
        DateFormat dateformat = GameProfileCache.createDateFormat();

        this.getTopMRUProfiles(org.spigotmc.SpigotConfig.userCacheCap).forEach((usercache_usercacheentry) -> { // Spigot
            jsonarray.add(GameProfileCache.writeGameProfile(usercache_usercacheentry, dateformat));
        });
        String s = this.gson.toJson(jsonarray);
        Runnable save = () -> { // Paper

        try {
            BufferedWriter bufferedwriter = Files.newWriter(this.file, StandardCharsets.UTF_8);

            try {
                bufferedwriter.write(s);
            } catch (Throwable throwable) {
                if (bufferedwriter != null) {
                    try {
                        bufferedwriter.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                }

                throw throwable;
            }

            if (bufferedwriter != null) {
                bufferedwriter.close();
            }
        } catch (IOException ioexception) {
            ;
        }
        // Paper start
        };
        if (asyncSave) {
            io.papermc.paper.util.MCUtil.scheduleAsyncTask(save);
        } else {
            save.run();
        }
        // Paper end

    }

    private Stream<GameProfileCache.GameProfileInfo> getTopMRUProfiles(int limit) {
        return ImmutableList.copyOf(this.profilesByUUID.values()).stream().sorted(Comparator.comparing(GameProfileCache.GameProfileInfo::getLastAccess).reversed()).limit((long) limit);
    }

    private static JsonElement writeGameProfile(GameProfileCache.GameProfileInfo entry, DateFormat dateFormat) {
        JsonObject jsonobject = new JsonObject();

        jsonobject.addProperty("name", entry.getProfile().getName());
        UUID uuid = entry.getProfile().getId();

        jsonobject.addProperty("uuid", uuid == null ? "" : uuid.toString());
        jsonobject.addProperty("expiresOn", dateFormat.format(entry.getExpirationDate()));
        return jsonobject;
    }

    private static Optional<GameProfileCache.GameProfileInfo> readGameProfile(JsonElement json, DateFormat dateFormat) {
        if (json.isJsonObject()) {
            JsonObject jsonobject = json.getAsJsonObject();
            JsonElement jsonelement1 = jsonobject.get("name");
            JsonElement jsonelement2 = jsonobject.get("uuid");
            JsonElement jsonelement3 = jsonobject.get("expiresOn");

            if (jsonelement1 != null && jsonelement2 != null) {
                String s = jsonelement2.getAsString();
                String s1 = jsonelement1.getAsString();
                Date date = null;

                if (jsonelement3 != null) {
                    try {
                        date = dateFormat.parse(jsonelement3.getAsString());
                    } catch (ParseException parseexception) {
                        ;
                    }
                }

                if (s1 != null && s != null && date != null) {
                    UUID uuid;

                    try {
                        uuid = UUID.fromString(s);
                    } catch (Throwable throwable) {
                        return Optional.empty();
                    }

                    return Optional.of(new GameProfileCache.GameProfileInfo(new GameProfile(uuid, s1), date));
                } else {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    private static class GameProfileInfo {

        private final GameProfile profile;
        final Date expirationDate;
        private volatile long lastAccess;

        GameProfileInfo(GameProfile profile, Date expirationDate) {
            this.profile = profile;
            this.expirationDate = expirationDate;
        }

        public GameProfile getProfile() {
            return this.profile;
        }

        public Date getExpirationDate() {
            return this.expirationDate;
        }

        public void setLastAccess(long lastAccessed) {
            this.lastAccess = lastAccessed;
        }

        public long getLastAccess() {
            return this.lastAccess;
        }
    }
}
