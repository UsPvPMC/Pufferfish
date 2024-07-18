package net.minecraft.world.item;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.apache.commons.lang3.StringUtils;

public class PlayerHeadItem extends StandingAndWallBlockItem {

    public static final String TAG_SKULL_OWNER = "SkullOwner";

    public PlayerHeadItem(Block block, Block wallBlock, Item.Properties settings) {
        super(block, wallBlock, settings, Direction.DOWN);
    }

    @Override
    public Component getName(ItemStack stack) {
        if (stack.is(Items.PLAYER_HEAD) && stack.hasTag()) {
            String s = null;
            CompoundTag nbttagcompound = stack.getTag();

            if (nbttagcompound.contains("SkullOwner", 8)) {
                s = nbttagcompound.getString("SkullOwner");
            } else if (nbttagcompound.contains("SkullOwner", 10)) {
                CompoundTag nbttagcompound1 = nbttagcompound.getCompound("SkullOwner");

                if (nbttagcompound1.contains("Name", 8)) {
                    s = nbttagcompound1.getString("Name");
                }
            }

            if (s != null) {
                return Component.translatable(this.getDescriptionId() + ".named", s);
            }
        }

        return super.getName(stack);
    }

    @Override
    public void verifyTagAfterLoad(CompoundTag nbt) {
        super.verifyTagAfterLoad(nbt);
        if (nbt.contains("SkullOwner", 8) && !StringUtils.isBlank(nbt.getString("SkullOwner"))) {
            GameProfile gameprofile = new GameProfile((UUID) null, nbt.getString("SkullOwner"));

            SkullBlockEntity.updateGameprofile(gameprofile, (gameprofile1) -> {
                nbt.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), gameprofile1));
            });
            // CraftBukkit start
        } else {
            net.minecraft.nbt.ListTag textures = nbt.getCompound("SkullOwner").getCompound("Properties").getList("textures", 10); // Safe due to method contracts
            for (int i = 0; i < textures.size(); i++) {
                if (textures.get(i) instanceof CompoundTag && !((CompoundTag) textures.get(i)).contains("Signature", 8) && ((CompoundTag) textures.get(i)).getString("Value").trim().isEmpty()) {
                    nbt.remove("SkullOwner");
                    break;
                }
            }
            // CraftBukkit end
        }

    }
}
