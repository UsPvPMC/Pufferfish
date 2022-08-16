package net.minecraft.world.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;

public class SuspiciousStewItem extends Item {
    public static final String EFFECTS_TAG = "Effects";
    public static final String EFFECT_ID_TAG = "EffectId";
    public static final String EFFECT_DURATION_TAG = "EffectDuration";
    public static final int DEFAULT_DURATION = 160;

    public SuspiciousStewItem(Item.Properties settings) {
        super(settings);
    }

    public static void saveMobEffect(ItemStack stew, MobEffect effect, int duration) {
        CompoundTag compoundTag = stew.getOrCreateTag();
        ListTag listTag = compoundTag.getList("Effects", 9);
        CompoundTag compoundTag2 = new CompoundTag();
        compoundTag2.putInt("EffectId", MobEffect.getId(effect));
        compoundTag2.putInt("EffectDuration", duration);
        listTag.add(compoundTag2);
        compoundTag.put("Effects", listTag);
    }

    private static void listPotionEffects(ItemStack stew, Consumer<MobEffectInstance> effectConsumer) {
        CompoundTag compoundTag = stew.getTag();
        if (compoundTag != null && compoundTag.contains("Effects", 9)) {
            ListTag listTag = compoundTag.getList("Effects", 10);

            for(int i = 0; i < listTag.size(); ++i) {
                CompoundTag compoundTag2 = listTag.getCompound(i);
                int j;
                if (compoundTag2.contains("EffectDuration", 3)) {
                    j = compoundTag2.getInt("EffectDuration");
                } else {
                    j = 160;
                }

                MobEffect mobEffect = MobEffect.byId(compoundTag2.getInt("EffectId"));
                if (mobEffect != null) {
                    effectConsumer.accept(new MobEffectInstance(mobEffect, j));
                }
            }
        }

    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level world, List<Component> tooltip, TooltipFlag context) {
        super.appendHoverText(stack, world, tooltip, context);
        if (context.isCreative()) {
            List<MobEffectInstance> list = new ArrayList<>();
            listPotionEffects(stack, list::add);
            PotionUtils.addPotionTooltip(list, tooltip, 1.0F);
        }

    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level world, LivingEntity user) {
        ItemStack itemStack = super.finishUsingItem(stack, world, user);
        listPotionEffects(itemStack, effect -> user.addEffect(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.FOOD)); // Paper
        return user instanceof Player && ((Player)user).getAbilities().instabuild ? itemStack : new ItemStack(Items.BOWL);
    }
}
