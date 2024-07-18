package org.bukkit.craftbukkit.inventory;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FactoryItemMaterialTest extends AbstractTestingBase {
    static final ItemFactory factory = CraftItemFactory.instance();
    static final StringBuilder buffer = new StringBuilder();
    static final Material[] materials;

    static {
        Material[] local_materials = Material.values();
        List<Material> list = new ArrayList<Material>(local_materials.length);
        for (Material material : local_materials) {
            if (INVALIDATED_MATERIALS.contains(material)) {
                continue;
            }

            list.add(material);
        }
        materials = list.toArray(new Material[list.size()]);
    }

    static String name(Enum<?> from, Enum<?> to) {
        if (from.getClass() == to.getClass()) {
            return FactoryItemMaterialTest.buffer.delete(0, Integer.MAX_VALUE).append(from.getClass().getName()).append(' ').append(from.name()).append(" to ").append(to.name()).toString();
        }
        return FactoryItemMaterialTest.buffer.delete(0, Integer.MAX_VALUE).append(from.getClass().getName()).append('(').append(from.name()).append(") to ").append(to.getClass().getName()).append('(').append(to.name()).append(')').toString();
    }

    @Parameters(name = "Material[{index}]:{0}")
    public static List<Object[]> data() {
        List<Object[]> list = new ArrayList<Object[]>();
        for (Material material : FactoryItemMaterialTest.materials) {
            list.add(new Object[] {material});
        }
        return list;
    }

    @Parameter(0) public Material material;

    @Test
    public void itemStack() {
        ItemStack bukkitStack = new ItemStack(this.material);
        CraftItemStack craftStack = CraftItemStack.asCraftCopy(bukkitStack);
        ItemMeta meta = FactoryItemMaterialTest.factory.getItemMeta(material);
        if (meta == null) {
            assertThat(this.material, is(Material.AIR));
        } else {
            assertTrue(FactoryItemMaterialTest.factory.isApplicable(meta, bukkitStack));
            assertTrue(FactoryItemMaterialTest.factory.isApplicable(meta, craftStack));
        }
    }

    @Test
    public void generalCase() {
        CraftMetaItem meta = (CraftMetaItem) FactoryItemMaterialTest.factory.getItemMeta(material);
        if (meta == null) {
            assertThat(this.material, is(Material.AIR));
        } else {
            assertTrue(FactoryItemMaterialTest.factory.isApplicable(meta, material));
            assertTrue(meta.applicableTo(material));

            meta = meta.clone();
            assertTrue(FactoryItemMaterialTest.factory.isApplicable(meta, material));
            assertTrue(meta.applicableTo(material));
        }
    }

    @Test
    public void asMetaFor() {
        final CraftMetaItem baseMeta = (CraftMetaItem) FactoryItemMaterialTest.factory.getItemMeta(material);
        if (baseMeta == null) {
            assertThat(this.material, is(Material.AIR));
            return;
        }

        for (Material other : FactoryItemMaterialTest.materials) {
            final ItemStack bukkitStack = new ItemStack(other);
            final CraftItemStack craftStack = CraftItemStack.asCraftCopy(bukkitStack);
            final CraftMetaItem otherMeta = (CraftMetaItem) FactoryItemMaterialTest.factory.asMetaFor(baseMeta, other);

            final String testName = FactoryItemMaterialTest.name(this.material, other);

            if (otherMeta == null) {
                assertThat(testName, other, is(Material.AIR));
                continue;
            }

            assertTrue(testName, FactoryItemMaterialTest.factory.isApplicable(otherMeta, craftStack));
            assertTrue(testName, FactoryItemMaterialTest.factory.isApplicable(otherMeta, bukkitStack));
            assertTrue(testName, FactoryItemMaterialTest.factory.isApplicable(otherMeta, other));
            assertTrue(testName, otherMeta.applicableTo(other));
        }
    }

    @Test
    public void blankEqualities() {
        if (this.material == Material.AIR) {
            return;
        }
        final CraftMetaItem baseMeta = (CraftMetaItem) FactoryItemMaterialTest.factory.getItemMeta(material);
        final CraftMetaItem baseMetaClone = baseMeta.clone();

        final ItemStack baseMetaStack = new ItemStack(this.material);
        baseMetaStack.setItemMeta(baseMeta);

        assertThat(baseMeta, is(not(sameInstance(baseMetaStack.getItemMeta()))));

        assertTrue(FactoryItemMaterialTest.factory.equals(baseMeta, null));
        assertTrue(FactoryItemMaterialTest.factory.equals(null, baseMeta));

        assertTrue(FactoryItemMaterialTest.factory.equals(baseMeta, baseMetaClone));
        assertTrue(FactoryItemMaterialTest.factory.equals(baseMetaClone, baseMeta));

        assertThat(baseMeta, is(not(sameInstance(baseMetaClone))));

        assertThat(baseMeta, is(baseMetaClone));
        assertThat(baseMetaClone, is(baseMeta));

        for (Material other : FactoryItemMaterialTest.materials) {
            final String testName = FactoryItemMaterialTest.name(this.material, other);

            final CraftMetaItem otherMeta = (CraftMetaItem) FactoryItemMaterialTest.factory.asMetaFor(baseMetaClone, other);

            if (otherMeta == null) {
                assertThat(testName, other, is(Material.AIR));
                continue;
            }

            assertTrue(testName, FactoryItemMaterialTest.factory.equals(baseMeta, otherMeta));
            assertTrue(testName, FactoryItemMaterialTest.factory.equals(otherMeta, baseMeta));

            assertThat(testName, baseMeta, is(otherMeta));
            assertThat(testName, otherMeta, is(baseMeta));

            assertThat(testName, baseMeta.hashCode(), is(otherMeta.hashCode()));
        }
    }
}
