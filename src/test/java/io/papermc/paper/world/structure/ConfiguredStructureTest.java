package io.papermc.paper.world.structure;

import io.papermc.paper.registry.Reference;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.support.AbstractTestingBase;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Deprecated(forRemoval = true)
public class ConfiguredStructureTest extends AbstractTestingBase {

    private static final Map<ResourceLocation, String> BUILT_IN_STRUCTURES = new LinkedHashMap<>();
    private static final Map<NamespacedKey, Reference<?>> DEFAULT_CONFIGURED_STRUCTURES = new LinkedHashMap<>();

    private static PrintStream out;

    @BeforeClass
    public static void collectStructures() throws ReflectiveOperationException {
        out = System.out;
        System.setOut(Bootstrap.STDOUT);
        for (Field field : BuiltinStructures.class.getDeclaredFields()) {
            if (field.getType().equals(ResourceKey.class) && Modifier.isStatic(field.getModifiers())) {
                BUILT_IN_STRUCTURES.put(((ResourceKey<?>) field.get(null)).location(), field.getName());
            }
        }
        for (Field field : ConfiguredStructure.class.getDeclaredFields()) {
            if (field.getType().equals(Reference.class) && Modifier.isStatic(field.getModifiers())) {
                final Reference<?> ref = (Reference<?>) field.get(null);
                DEFAULT_CONFIGURED_STRUCTURES.put(ref.getKey(), ref);
            }
        }
    }

    @Test
    public void testMinecraftToApi() {
        Registry<Structure> structureRegistry = AbstractTestingBase.REGISTRY_CUSTOM.registryOrThrow(Registries.STRUCTURE);
        assertEquals("configured structure maps should be the same size", BUILT_IN_STRUCTURES.size(), structureRegistry.size());

        Map<ResourceLocation, Structure> missing = new LinkedHashMap<>();
        for (Structure feature : structureRegistry) {
            final ResourceLocation key = structureRegistry.getKey(feature);
            assertNotNull("Missing built-in registry key", key);
            if (key.equals(BuiltinStructures.ANCIENT_CITY.location())) {
                continue; // TODO remove when upstream adds "jigsaw" StructureType
            }
            if (DEFAULT_CONFIGURED_STRUCTURES.get(CraftNamespacedKey.fromMinecraft(key)) == null) {
                missing.put(key, feature);
            }
        }

        assertTrue(printMissing(missing), missing.isEmpty());
    }

    @Test
    public void testApiToMinecraft() {
        Registry<Structure> structureRegistry = AbstractTestingBase.REGISTRY_CUSTOM.registryOrThrow(Registries.STRUCTURE);
        for (NamespacedKey apiKey : DEFAULT_CONFIGURED_STRUCTURES.keySet()) {
            assertTrue(apiKey + " does not have a minecraft counterpart", structureRegistry.containsKey(CraftNamespacedKey.toMinecraft(apiKey)));
        }
    }

    private static String printMissing(Map<ResourceLocation, Structure> missing) {
        final StringJoiner joiner = new StringJoiner("\n", "Missing: \n", "");

        missing.forEach((key, configuredFeature) -> {
            joiner.add("public static final Reference<ConfiguredStructure> " + BUILT_IN_STRUCTURES.get(key) + " = create(\"" + key.getPath() + "\");");
        });

        return joiner.toString();
    }

    @AfterClass
    public static void after() {
        System.setOut(out);
    }
}
