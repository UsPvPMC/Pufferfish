package io.papermc.paper.testing;

import java.util.Iterator;
import java.util.function.Supplier;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record LazyRegistry(Supplier<Registry<Keyed>> supplier) implements Registry<Keyed> {

    @NotNull
    @Override
    public Iterator<Keyed> iterator() {
        return this.supplier().get().iterator();
    }

    @Override
    public @Nullable Keyed get(@NotNull final NamespacedKey key) {
        return this.supplier().get().get(key);
    }
}
