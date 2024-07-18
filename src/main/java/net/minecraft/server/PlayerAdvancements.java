package net.minecraft.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.internal.Streams;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.FileUtil;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;

public class PlayerAdvancements {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = (new GsonBuilder()).registerTypeAdapter(AdvancementProgress.class, new AdvancementProgress.Serializer()).registerTypeAdapter(ResourceLocation.class, new ResourceLocation.Serializer()).setPrettyPrinting().create();
    private static final TypeToken<Map<ResourceLocation, AdvancementProgress>> TYPE_TOKEN = new TypeToken<Map<ResourceLocation, AdvancementProgress>>() {
    };
    private final DataFixer dataFixer;
    private final PlayerList playerList;
    private final Path playerSavePath;
    private final Map<Advancement, AdvancementProgress> progress = new LinkedHashMap();
    private final Set<Advancement> visible = new HashSet();
    private final Set<Advancement> progressChanged = new HashSet();
    private final Set<Advancement> rootsToUpdate = new HashSet();
    private ServerPlayer player;
    @Nullable
    private Advancement lastSelectedTab;
    private boolean isFirstPacket = true;

    public PlayerAdvancements(DataFixer dataFixer, PlayerList playerManager, ServerAdvancementManager advancementLoader, Path filePath, ServerPlayer owner) {
        this.dataFixer = dataFixer;
        this.playerList = playerManager;
        this.playerSavePath = filePath;
        this.player = owner;
        this.load(advancementLoader);
    }

    public void setPlayer(ServerPlayer owner) {
        this.player = owner;
    }

    public void stopListening() {
        Iterator iterator = CriteriaTriggers.all().iterator();

        while (iterator.hasNext()) {
            CriterionTrigger<?> criteriontrigger = (CriterionTrigger) iterator.next();

            criteriontrigger.removePlayerListeners(this);
        }

    }

    public void reload(ServerAdvancementManager advancementLoader) {
        this.stopListening();
        this.progress.clear();
        this.visible.clear();
        this.rootsToUpdate.clear();
        this.progressChanged.clear();
        this.isFirstPacket = true;
        this.lastSelectedTab = null;
        this.load(advancementLoader);
    }

    private void registerListeners(ServerAdvancementManager advancementLoader) {
        Iterator iterator = advancementLoader.getAllAdvancements().iterator();

        while (iterator.hasNext()) {
            Advancement advancement = (Advancement) iterator.next();

            this.registerListeners(advancement);
        }

    }

    private void checkForAutomaticTriggers(ServerAdvancementManager advancementLoader) {
        Iterator iterator = advancementLoader.getAllAdvancements().iterator();

        while (iterator.hasNext()) {
            Advancement advancement = (Advancement) iterator.next();

            if (advancement.getCriteria().isEmpty()) {
                this.award(advancement, "");
                advancement.getRewards().grant(this.player);
            }
        }

    }

    private void load(ServerAdvancementManager advancementLoader) {
        if (Files.isRegularFile(this.playerSavePath, new LinkOption[0])) {
            try {
                JsonReader jsonreader = new JsonReader(Files.newBufferedReader(this.playerSavePath, StandardCharsets.UTF_8));

                try {
                    jsonreader.setLenient(false);
                    Dynamic<JsonElement> dynamic = new Dynamic(JsonOps.INSTANCE, Streams.parse(jsonreader));
                    int i = dynamic.get("DataVersion").asInt(1343);

                    dynamic = dynamic.remove("DataVersion");
                    dynamic = DataFixTypes.ADVANCEMENTS.updateToCurrentVersion(this.dataFixer, dynamic, i);
                    Map<ResourceLocation, AdvancementProgress> map = (Map) PlayerAdvancements.GSON.getAdapter(PlayerAdvancements.TYPE_TOKEN).fromJsonTree((JsonElement) dynamic.getValue());

                    if (map == null) {
                        throw new JsonParseException("Found null for advancements");
                    }

                    map.entrySet().stream().sorted(Entry.comparingByValue()).forEach((entry) -> {
                        Advancement advancement = advancementLoader.getAdvancement((ResourceLocation) entry.getKey());

                        if (advancement == null) {
                            // CraftBukkit start
                            if (entry.getKey().getNamespace().equals("minecraft")) {
                                PlayerAdvancements.LOGGER.warn("Ignored advancement '{}' in progress file {} - it doesn't exist anymore?", entry.getKey(), this.playerSavePath);
                            }
                            // CraftBukkit end
                        } else {
                            this.startProgress(advancement, (AdvancementProgress) entry.getValue());
                            this.progressChanged.add(advancement);
                            this.markForVisibilityUpdate(advancement);
                        }
                    });
                } catch (Throwable throwable) {
                    try {
                        jsonreader.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }

                    throw throwable;
                }

                jsonreader.close();
            } catch (JsonParseException jsonparseexception) {
                PlayerAdvancements.LOGGER.error("Couldn't parse player advancements in {}", this.playerSavePath, jsonparseexception);
            } catch (IOException ioexception) {
                PlayerAdvancements.LOGGER.error("Couldn't access player advancements in {}", this.playerSavePath, ioexception);
            }
        }

        this.checkForAutomaticTriggers(advancementLoader);
        this.registerListeners(advancementLoader);
    }

    public void save() {
        if (org.spigotmc.SpigotConfig.disableAdvancementSaving) return; // Spigot
        Map<ResourceLocation, AdvancementProgress> map = new LinkedHashMap();
        Iterator iterator = this.progress.entrySet().iterator();

        while (iterator.hasNext()) {
            Entry<Advancement, AdvancementProgress> entry = (Entry) iterator.next();
            AdvancementProgress advancementprogress = (AdvancementProgress) entry.getValue();

            if (advancementprogress.hasProgress()) {
                map.put(((Advancement) entry.getKey()).getId(), advancementprogress);
            }
        }

        JsonElement jsonelement = PlayerAdvancements.GSON.toJsonTree(map);

        jsonelement.getAsJsonObject().addProperty("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        try {
            FileUtil.createDirectoriesSafe(this.playerSavePath.getParent());
            BufferedWriter bufferedwriter = Files.newBufferedWriter(this.playerSavePath, StandardCharsets.UTF_8);

            try {
                PlayerAdvancements.GSON.toJson(jsonelement, bufferedwriter);
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
            PlayerAdvancements.LOGGER.error("Couldn't save player advancements to {}", this.playerSavePath, ioexception);
        }

    }

    public boolean award(Advancement advancement, String criterionName) {
        boolean flag = false;
        AdvancementProgress advancementprogress = this.getOrStartProgress(advancement);
        boolean flag1 = advancementprogress.isDone();

        if (advancementprogress.grantProgress(criterionName)) {
            this.unregisterListeners(advancement);
            this.progressChanged.add(advancement);
            flag = true;
            if (!flag1 && advancementprogress.isDone()) {
                this.player.level.getCraftServer().getPluginManager().callEvent(new org.bukkit.event.player.PlayerAdvancementDoneEvent(this.player.getBukkitEntity(), advancement.bukkit)); // CraftBukkit
                advancement.getRewards().grant(this.player);
                if (advancement.getDisplay() != null && advancement.getDisplay().shouldAnnounceChat() && this.player.level.getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS)) {
                    this.playerList.broadcastSystemMessage(Component.translatable("chat.type.advancement." + advancement.getDisplay().getFrame().getName(), this.player.getDisplayName(), advancement.getChatComponent()), false);
                }
            }
        }

        if (!flag1 && advancementprogress.isDone()) {
            this.markForVisibilityUpdate(advancement);
        }

        return flag;
    }

    public boolean revoke(Advancement advancement, String criterionName) {
        boolean flag = false;
        AdvancementProgress advancementprogress = this.getOrStartProgress(advancement);
        boolean flag1 = advancementprogress.isDone();

        if (advancementprogress.revokeProgress(criterionName)) {
            this.registerListeners(advancement);
            this.progressChanged.add(advancement);
            flag = true;
        }

        if (flag1 && !advancementprogress.isDone()) {
            this.markForVisibilityUpdate(advancement);
        }

        return flag;
    }

    private void markForVisibilityUpdate(Advancement advancement) {
        this.rootsToUpdate.add(advancement.getRoot());
    }

    private void registerListeners(Advancement advancement) {
        AdvancementProgress advancementprogress = this.getOrStartProgress(advancement);

        if (!advancementprogress.isDone()) {
            Iterator iterator = advancement.getCriteria().entrySet().iterator();

            while (iterator.hasNext()) {
                Entry<String, Criterion> entry = (Entry) iterator.next();
                CriterionProgress criterionprogress = advancementprogress.getCriterion((String) entry.getKey());

                if (criterionprogress != null && !criterionprogress.isDone()) {
                    CriterionTriggerInstance criterioninstance = ((Criterion) entry.getValue()).getTrigger();

                    if (criterioninstance != null) {
                        CriterionTrigger<CriterionTriggerInstance> criteriontrigger = CriteriaTriggers.getCriterion(criterioninstance.getCriterion());

                        if (criteriontrigger != null) {
                            criteriontrigger.addPlayerListener(this, new CriterionTrigger.Listener<>(criterioninstance, advancement, (String) entry.getKey()));
                        }
                    }
                }
            }

        }
    }

    private void unregisterListeners(Advancement advancement) {
        AdvancementProgress advancementprogress = this.getOrStartProgress(advancement);
        Iterator iterator = advancement.getCriteria().entrySet().iterator();

        while (iterator.hasNext()) {
            Entry<String, Criterion> entry = (Entry) iterator.next();
            CriterionProgress criterionprogress = advancementprogress.getCriterion((String) entry.getKey());

            if (criterionprogress != null && (criterionprogress.isDone() || advancementprogress.isDone())) {
                CriterionTriggerInstance criterioninstance = ((Criterion) entry.getValue()).getTrigger();

                if (criterioninstance != null) {
                    CriterionTrigger<CriterionTriggerInstance> criteriontrigger = CriteriaTriggers.getCriterion(criterioninstance.getCriterion());

                    if (criteriontrigger != null) {
                        criteriontrigger.removePlayerListener(this, new CriterionTrigger.Listener<>(criterioninstance, advancement, (String) entry.getKey()));
                    }
                }
            }
        }

    }

    public void flushDirty(ServerPlayer player) {
        if (this.isFirstPacket || !this.rootsToUpdate.isEmpty() || !this.progressChanged.isEmpty()) {
            Map<ResourceLocation, AdvancementProgress> map = new HashMap();
            Set<Advancement> set = new HashSet();
            Set<ResourceLocation> set1 = new HashSet();
            Iterator iterator = this.rootsToUpdate.iterator();

            Advancement advancement;

            while (iterator.hasNext()) {
                advancement = (Advancement) iterator.next();
                this.updateTreeVisibility(advancement, set, set1);
            }

            this.rootsToUpdate.clear();
            iterator = this.progressChanged.iterator();

            while (iterator.hasNext()) {
                advancement = (Advancement) iterator.next();
                if (this.visible.contains(advancement)) {
                    map.put(advancement.getId(), (AdvancementProgress) this.progress.get(advancement));
                }
            }

            this.progressChanged.clear();
            if (!map.isEmpty() || !set.isEmpty() || !set1.isEmpty()) {
                player.connection.send(new ClientboundUpdateAdvancementsPacket(this.isFirstPacket, set, set1, map));
            }
        }

        this.isFirstPacket = false;
    }

    public void setSelectedTab(@Nullable Advancement advancement) {
        Advancement advancement1 = this.lastSelectedTab;

        if (advancement != null && advancement.getParent() == null && advancement.getDisplay() != null) {
            this.lastSelectedTab = advancement;
        } else {
            this.lastSelectedTab = null;
        }

        if (advancement1 != this.lastSelectedTab) {
            this.player.connection.send(new ClientboundSelectAdvancementsTabPacket(this.lastSelectedTab == null ? null : this.lastSelectedTab.getId()));
        }

    }

    public AdvancementProgress getOrStartProgress(Advancement advancement) {
        AdvancementProgress advancementprogress = (AdvancementProgress) this.progress.get(advancement);

        if (advancementprogress == null) {
            advancementprogress = new AdvancementProgress();
            this.startProgress(advancement, advancementprogress);
        }

        return advancementprogress;
    }

    private void startProgress(Advancement advancement, AdvancementProgress progress) {
        progress.update(advancement.getCriteria(), advancement.getRequirements());
        this.progress.put(advancement, progress);
    }

    private void updateTreeVisibility(Advancement root, Set<Advancement> added, Set<ResourceLocation> removed) {
        AdvancementVisibilityEvaluator.evaluateVisibility(root, (advancement1) -> {
            return this.getOrStartProgress(advancement1).isDone();
        }, (advancement1, flag) -> {
            if (flag) {
                if (this.visible.add(advancement1)) {
                    added.add(advancement1);
                    if (this.progress.containsKey(advancement1)) {
                        this.progressChanged.add(advancement1);
                    }
                }
            } else if (this.visible.remove(advancement1)) {
                removed.add(advancement1.getId());
            }

        });
    }
}
