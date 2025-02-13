package com.lothrazar.heartbalance;

import com.lothrazar.heartbalance.item.ItemHeart;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import top.theillusivec4.curios.api.CuriosApi;

public class HeartEvents {

  public static final UUID ID = UUID.fromString("55550aa2-eff2-4a81-b92b-a1cb95f15555");

  private static void forceHearts(PlayerEntity player) {
    ModifiableAttributeInstance healthAttribute = player.getAttribute(Attributes.MAX_HEALTH);
    AttributeModifier oldHealthModifier = healthAttribute.getModifier(ID);
    if (oldHealthModifier != null) {
      //delete and replace
      healthAttribute.removeModifier(oldHealthModifier);
    }
    //always apply to player if they do not have
    int h = 2 * ConfigManager.INIT_HEARTS.get();
    AttributeModifier healthModifier = new AttributeModifier(ID, ModMain.MODID, h, AttributeModifier.Operation.ADDITION);
    healthAttribute.applyPersistentModifier(healthModifier);
  }

  public static ItemStack getValidCurio(PlayerEntity player, Item remote) {
    if (isValid(player.getHeldItemOffhand(), remote)) {
      return player.getHeldItemOffhand();
    }
    if (isValid(player.getHeldItemMainhand(), remote)) {
      return player.getHeldItemMainhand();
    }
    if (ModList.get().isLoaded("curios")) {
      //check curios slots
      final ImmutableTriple<String, Integer, ItemStack> equipped = CuriosApi.getCuriosHelper().findEquippedCurio(remote, player).orElse(null);
      if (equipped != null && isValid(equipped.right, remote)) {
        //success: try to insert items to network thru this remote 
        return equipped.right;
      }
    }
    //    for (int i = 0; i < player.getInventoryEnderChest().getSizeInventory(); i++) {
    //      ItemStack temp = player.getInventoryEnderChest().getStackInSlot(i);
    //      if (isValid(temp, remote)) {
    //        return temp;
    //      }
    //    }
    for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
      ItemStack temp = player.inventory.getStackInSlot(i);
      if (isValid(temp, remote)) {
        return temp;
      }
    }
    return ItemStack.EMPTY;
  }

  public static boolean isValid(ItemStack right, Item remote) {
    return right.getItem() == remote
        //if its not isDamageable, we dont care, otherwise it is, so return true if we still have some juice left, havent taken max
        && (!remote.isDamageable() || right.getDamage() < right.getMaxDamage());
  }
  //  @SubscribeEvent
  //  public void onFallDamageEvent(LivingDamageEvent event) {
  //    if (event.getEntityLiving() instanceof PlayerEntity == false) {
  //      return;
  //    }
  //    PlayerEntity player = (PlayerEntity) event.getEntityLiving();
  //    if (event.getSource() == DamageSource.FALL) {
  //      ItemStack match = getValidCurio(player, ModRegistry.RING_FALL);
  //      if (!match.isEmpty()) {
  //        event.setAmount(0);
  //      }
  //    }
  //  }
  //
  //  @SubscribeEvent
  //  public void onLivingKnockBackEvent(LivingKnockBackEvent event) {
  //    if (event.getEntityLiving() instanceof PlayerEntity == false) {
  //      return;
  //    }
  //    PlayerEntity player = (PlayerEntity) event.getEntityLiving();
  //    ItemStack match = getValidCurio(player, ModRegistry.RING_KNOCKBACK);
  //    if (!match.isEmpty()) {
  //      match.damageItem(1, player, p -> {});
  //      // 
  //      event.setStrength(0);
  //      ModMain.LOGGER.info("knockback on  str");
  //    }
  //  }

  @SubscribeEvent
  public void onEntityJoinWorld(EntityJoinWorldEvent event) {
    if (event.getEntity() instanceof PlayerEntity) {
      forceHearts((PlayerEntity) event.getEntity());
    }
  }

  @SubscribeEvent
  public void onPlayerCloneDeath(PlayerEvent.Clone event) {
    forceHearts(event.getPlayer());
  }

  @SubscribeEvent
  public void onPlayerPickup(EntityItemPickupEvent event) {
    if (event.getEntityLiving() instanceof PlayerEntity) {
      PlayerEntity player = (PlayerEntity) event.getEntityLiving();
      ItemEntity itemEntity = event.getItem();
      ItemStack resultStack = itemEntity.getItem();
      if (!resultStack.isEmpty() && resultStack.getItem() instanceof ItemHeart) {
        ItemHeart heart = (ItemHeart) resultStack.getItem();
        //try to heal one by one
        boolean healed = false;
        while (!resultStack.isEmpty() && player.shouldHeal()) {
          player.heal(heart.getHealing());
          resultStack.shrink(1);
          itemEntity.setItem(resultStack);
          healed = true;
        }
        if (healed && ConfigManager.DO_SOUND_PICKUP.get()) {
          ModRegistry.playSoundFromServer((ServerPlayerEntity) player, ModRegistry.HEART_GET, 0.3F, 0.95F);
        }
        //all done. so EITHER player is fully healed
        // OR we ran out of items... so do we cancel?
        //dont cancel if healed = true, there might be more remaining
        if (itemEntity.getItem().isEmpty()) {
          itemEntity.remove();
          //cancel to block the pickup 
          event.setCanceled(true);
        }
      }
    }
  }

  @SubscribeEvent
  public void onLivingDeathEvent(LivingDeathEvent event) {
    World world = event.getEntity().world;
    if (world.isRemote || event.getSource() == null
        || world.rand.nextDouble() >= ConfigManager.CHANCE.get()) {
      return;
    }
    //if config is at 10, and you roll in 10-100 you were cancelled,
    //else here we continue so our roll was < 10 so the percentage worked
    Entity trueSource = event.getSource().getTrueSource();
    if (trueSource instanceof PlayerEntity && !(trueSource instanceof FakePlayer)) {
      //killed by me  
      if (event.getEntityLiving().getType().getClassification() == EntityClassification.MONSTER) {
        //drop
        BlockPos pos = event.getEntity().getPosition();
        world.addEntity(new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
            new ItemStack(ModRegistry.HALF_HEART)));
      }
    }
  }
}
