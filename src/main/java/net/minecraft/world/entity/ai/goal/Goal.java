package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import net.minecraft.util.Mth;

public abstract class Goal {
    private final EnumSet<Goal.Flag> flags = EnumSet.noneOf(Goal.Flag.class); // Paper unused, but dummy to prevent plugins from crashing as hard. Theyll need to support paper in a special case if this is super important, but really doesn't seem like it would be.
    private final com.destroystokyo.paper.util.set.OptimizedSmallEnumSet<net.minecraft.world.entity.ai.goal.Goal.Flag> goalTypes = new com.destroystokyo.paper.util.set.OptimizedSmallEnumSet<>(Goal.Flag.class); // Paper - remove streams from pathfindergoalselector

    public abstract boolean canUse();

    public boolean canContinueToUse() {
        return this.canUse();
    }

    public boolean isInterruptable() {
        return true;
    }

    public void start() {
    }

    public void stop() {
    }

    public boolean requiresUpdateEveryTick() {
        return false;
    }

    public void tick() {
    }

    public void setFlags(EnumSet<Goal.Flag> controls) {
        // Paper start - remove streams from pathfindergoalselector
        this.goalTypes.clear();
        this.goalTypes.addAllUnchecked(controls);
        // Paper end - remove streams from pathfindergoalselector
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    // Paper start - remove streams from pathfindergoalselector
    public com.destroystokyo.paper.util.set.OptimizedSmallEnumSet<Goal.Flag> getFlags() {
        return this.goalTypes;
        // Paper end - remove streams from pathfindergoalselector
    }

    protected int adjustedTickDelay(int ticks) {
        return this.requiresUpdateEveryTick() ? ticks : reducedTickDelay(ticks);
    }

    protected static int reducedTickDelay(int serverTicks) {
        return Mth.positiveCeilDiv(serverTicks, 2);
    }

    public static enum Flag {
        MOVE,
        LOOK,
        JUMP,
        TARGET;
    }
}
