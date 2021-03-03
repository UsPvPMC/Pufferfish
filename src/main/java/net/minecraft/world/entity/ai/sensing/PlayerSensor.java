package net.minecraft.world.entity.ai.sensing;

import com.google.common.collect.ImmutableSet;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;

public class PlayerSensor extends Sensor<LivingEntity> {
    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(MemoryModuleType.NEAREST_PLAYERS, MemoryModuleType.NEAREST_VISIBLE_PLAYER, MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER);
    }

    @Override
    protected void doTick(ServerLevel world, LivingEntity entity) {
        List<Player> players = new java.util.ArrayList<>(world.players());
        players.removeIf(player -> !EntitySelector.NO_SPECTATORS.test(player) || !entity.closerThan(player, 16.0D));
        players.sort(Comparator.comparingDouble(entity::distanceTo));
        Brain<?> brain = entity.getBrain();

        brain.setMemory(MemoryModuleType.NEAREST_PLAYERS, players);

        Player nearest = null, nearestTargetable = null;
        for (Player player : players) {
            if (Sensor.isEntityTargetable(entity, player)) {
                if (nearest == null) nearest = player;
                if (Sensor.isEntityAttackable(entity, player)) {
                    nearestTargetable = player;
                    break; // Both variables are assigned, no reason to loop further
                }
            }
        }
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER, nearest);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER, nearestTargetable);
        // Paper end
    }
}
