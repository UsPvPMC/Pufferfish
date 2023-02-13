package io.papermc.paper.testing;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.inventory.CraftItemFactory;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.support.AbstractTestingBase;
import org.mockito.Mockito;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class DummyServer {

    @SuppressWarnings({"deprecation", "removal"})
    public static void setup() {
        //noinspection ConstantValue
        if (Bukkit.getServer() != null) {
            return;
        }

        final Server dummyServer = mock(Server.class, Mockito.withSettings().stubOnly());

        final Logger logger = Logger.getLogger(DummyServer.class.getCanonicalName());
        when(dummyServer.getLogger()).thenReturn(logger);
        when(dummyServer.getName()).thenReturn(DummyServer.class.getSimpleName());
        when(dummyServer.getVersion()).thenReturn("Version_" + DummyServer.class.getPackage().getImplementationVersion());
        when(dummyServer.getBukkitVersion()).thenReturn("BukkitVersion_" + DummyServer.class.getPackage().getImplementationVersion());

        final Thread currentThread = Thread.currentThread();
        when(dummyServer.isPrimaryThread()).thenAnswer(ignored -> Thread.currentThread().equals(currentThread));

        when(dummyServer.getItemFactory()).thenReturn(CraftItemFactory.instance());

        when(dummyServer.getUnsafe()).thenAnswer(ignored -> CraftMagicNumbers.INSTANCE); // lambda for lazy load

        when(dummyServer.createBlockData(any(Material.class))).thenAnswer(invocation -> {
            return CraftBlockData.newData(invocation.getArgument(0, Material.class), null);
        });

        when(dummyServer.getLootTable(any(NamespacedKey.class))).thenAnswer(invocation -> {
            final NamespacedKey key = invocation.getArgument(0, NamespacedKey.class);
            return new org.bukkit.craftbukkit.CraftLootTable(key, AbstractTestingBase.DATA_PACK.getLootTables().get(CraftNamespacedKey.toMinecraft(key)));
        });

        when(dummyServer.getRegistry(any())).thenAnswer(invocation -> {
            // LazyRegistry because the vanilla data hasn't been bootstrapped yet.
            return new LazyRegistry(() -> CraftRegistry.createRegistry(invocation.getArgument(0, Class.class), AbstractTestingBase.REGISTRY_CUSTOM));
        });

        final PluginManager pluginManager = new SimplePluginManager(dummyServer, new SimpleCommandMap(dummyServer));
        when(dummyServer.getPluginManager()).thenReturn(pluginManager);

        Bukkit.setServer(dummyServer);

    }
}
