package me.lovkar.war.campaign;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.colony.events.raid.HordeRaidEvent;
import com.minecolonies.core.colony.events.raid.RaidManager;
import me.lovkar.war.Warfare;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity.RemovalReason;
import org.jetbrains.annotations.NotNull;

/**
 * Somebody's army at the gate.
 *
 * <p>A MineColonies raid event, registered on MineColonies' own registry, so it sits in the Town
 * Hall beside a barbarian horde, is saved with the colony, raises the same boss bar and is fought by
 * the same guards. The difference is who it is: the soldiers are re-statted from the <b>attacking</b>
 * colony's warband, never from the defender's raid level - MineColonies scales a horde to the town
 * it attacks, which is the right answer for barbarians and the wrong one for a neighbour's twelve
 * knights - and when it ends it tells the campaign that sent it how the night went.</p>
 *
 * <p><b>The registry name is {@code minecolonies:caw_army}, and that is not a mistake.</b>
 * {@code EventManager.readFromNBT} builds the id it looks up as
 * {@code new ResourceLocation("minecolonies", name)} - only the path is saved - so an event
 * registered under this mod's own namespace is dropped on every reload with "Event is missing
 * registryEntry!". A siege that vanished when you quit to the title screen would be a worse bug
 * than a foreign prefix in somebody else's registry. The path is prefixed so it cannot collide with
 * anything MineColonies adds.</p>
 */
public class ArmyRaidEvent extends HordeRaidEvent {
    public static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("minecolonies", "caw_army");

    private static final String TAG_ATTACKER = "caw_attacker";
    private static final String TAG_ATTACKER_NAME = "caw_attacker_name";
    private static final String TAG_CAMPAIGN = "caw_campaign";
    private static final String TAG_NIGHTS = "caw_nights";
    private static final String TAG_HEALTH = "caw_health";
    private static final String TAG_DAMAGE = "caw_damage";
    private static final String TAG_ARMOUR = "caw_armour";
    private static final String TAG_LOOK = "caw_look";
    private static final String TAG_INITIAL = "caw_initial";

    private int attacker;
    private String attackerName = "";
    private long campaign;
    /** Nights the siege has lasted; a skirmish ends after the first. */
    private int nights;
    private double soldierHealth = 20;
    private double soldierDamage = 3;
    private double soldierArmour = 1;
    private String look = "barbarian";
    private int initialSize;

    public ArmyRaidEvent(final IColony colony) {
        super(colony);
    }

    /** Everything the campaign knows about the warband, so the soldiers are its soldiers. */
    public ArmyRaidEvent warband(final int attacker, final String attackerName, final long campaign,
                                 final int size, final double health, final double damage,
                                 final double armour, final String look) {
        this.attacker = attacker;
        this.attackerName = attackerName == null ? "" : attackerName;
        this.campaign = campaign;
        this.initialSize = size;
        this.soldierHealth = health;
        this.soldierDamage = damage;
        this.soldierArmour = armour;
        this.look = look == null ? "barbarian" : look;
        return this;
    }

    public int attacker() {
        return attacker;
    }

    public long campaign() {
        return campaign;
    }

    public int initialSize() {
        return initialSize;
    }

    /** How many soldiers still stand. */
    public int alive() {
        return horde == null ? 0 : horde.hordeSize;
    }

    @Override
    public ResourceLocation getEventTypeID() {
        return TYPE_ID;
    }

    @Override
    protected MutableComponent getDisplayName() {
        return Component.literal(attackerName.isEmpty() ? "An army" : "Warband of " + attackerName);
    }

    // ------------------------------------------------------------------ the soldiers

    @Override
    public EntityType<?> getNormalRaiderType() {
        return ArmyLook.of(look).normal();
    }

    @Override
    public EntityType<?> getArcherRaiderType() {
        return ArmyLook.of(look).archer();
    }

    @Override
    public EntityType<?> getBossRaiderType() {
        return ArmyLook.of(look).boss();
    }

    /**
     * A soldier has arrived. MineColonies has just statted him for the town he is attacking;
     * re-stat him for the army he marched with, and give him a name so a player knows whose he is.
     */
    @Override
    public void registerEntity(final Entity entity) {
        if (!(entity instanceof AbstractEntityMinecoloniesRaider raider) || !entity.isAlive()) {
            entity.remove(RemovalReason.DISCARDED);
            return;
        }
        final ArmyLook types = ArmyLook.of(look);
        final EntityType<?> type = entity.getType();
        if (type == types.boss() && boss.size() < horde.numberOfBosses) {
            boss.put(entity, entity.getUUID());
        } else if (type == types.archer() && archers.size() < horde.numberOfArchers) {
            archers.put(entity, entity.getUUID());
        } else if (type == types.normal() && normal.size() < horde.numberOfRaiders) {
            normal.put(entity, entity.getUUID());
        } else {
            entity.remove(RemovalReason.DISCARDED);
            return;
        }
        try {
            // the same numbers for everyone: a chief's own initStatsFor already gives him half
            // again the health, double the armour and a point of damage over the men
            raider.initStatsFor(soldierHealth, soldierArmour, soldierDamage);
            raider.setCustomName(Component.literal((type == types.boss() ? "Captain of " : "Soldier of ")
                    + (attackerName.isEmpty() ? "the army" : attackerName)));
            raider.setCustomNameVisible(false);
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[army] could not re-stat a soldier: {}", t.toString());
        }
    }

    @Override
    public void onEntityDeath(final LivingEntity entity) {
        super.onEntityDeath(entity);
        if (!(entity instanceof AbstractEntityMinecoloniesRaider)) {
            return;
        }
        final ArmyLook types = ArmyLook.of(look);
        final EntityType<?> type = entity.getType();
        if (type == types.boss()) {
            boss.remove(entity);
            horde.numberOfBosses--;
        } else if (type == types.archer()) {
            archers.remove(entity);
            horde.numberOfArchers--;
        } else if (type == types.normal()) {
            normal.remove(entity);
            horde.numberOfRaiders--;
        }
        horde.hordeSize--;
        if (horde.hordeSize <= 0) {
            status = EventStatus.DONE;
        }
        sendHordeMessage();
    }

    // ------------------------------------------------------------------ the night

    @Override
    public void onStart() {
        super.onStart();
        if (status == EventStatus.CANCELED) {
            Warfare.LOGGER.info("[army] no ground to stand on at {} - the warband of {} turns back",
                    getColony().getName(), attackerName);
            return;
        }
        openRaidHistory();
        Campaigns.siegeStood(getColony(), campaign, getSpawnPos());
        MessageUtils.format(Component.literal("The warband of " + attackerName + " is at the walls of "
                        + getColony().getName() + " - " + initialSize + " soldiers from the "
                        + BlockPosUtil.calcDirection(getColony().getCenter(), getSpawnPos()).getLongText().getString()))
                .withPriority(MessageUtils.MessagePriority.DANGER)
                .sendTo(getColony()).forManagers();
    }

    /** A skirmish is one night. When it ends with soldiers still standing, the town was overrun. */
    @Override
    public void onNightFall() {
        nights++;
        if (nights >= Campaigns.siegeNights()) {
            status = EventStatus.DONE;
        }
    }

    /**
     * A page in the town's raid history, because the soldiers cannot walk without one.
     *
     * <p>MineColonies' own raids write a {@code RaidHistory} line the moment they spawn, and
     * two things read the latest line without asking whether there is one: {@code
     * RaidManager.getRandomBuilding()}, which every raider calls to pick the hut it walks at once
     * the camp is struck, and {@code onRaidEventFinished}. On a colony that has never been raided
     * both throw - the first out of every soldier's AI, every tick, leaving a warband standing at
     * its camp fires for the whole night. So the warband writes its own line, which is also the
     * honest thing: it <i>is</i> a raid, the Town Hall's raid info shows it as one, citizens lost
     * to it weigh on the town's raid difficulty like any other, and its dead are counted. The list
     * is private; there is no other door.</p>
     */
    @SuppressWarnings("unchecked")
    private void openRaidHistory() {
        try {
            if (!(getColony().getRaiderManager() instanceof RaidManager manager)) {
                return;
            }
            final java.lang.reflect.Field field = RaidManager.class.getDeclaredField("raidHistories");
            field.setAccessible(true);
            final java.util.List<RaidManager.RaidHistory> histories = (java.util.List<RaidManager.RaidHistory>) field.get(manager);
            final RaidManager.RaidHistory page = new RaidManager.RaidHistory(initialSize, getColony().getWorld().getGameTime());
            page.spawnData.add(new RaidManager.RaidSpawnInfo(TYPE_ID, getSpawnPos()));
            page.difficulty = Math.round(soldierHealth / 20.0 * 100.0) / 100.0;
            histories.add(page);
            getColony().markDirty();
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[army] could not write the warband into {}'s raid history - its soldiers may stand at their fires all night: {}",
                    getColony().getName(), t.toString());
        }
    }

    /**
     * The night is over.
     *
     * <p>Everything {@code HordeRaidEvent.onFinish} does except {@code RaidManager.onRaidEventFinished},
     * which is never called: it would announce that the raiders were slain whatever happened, and
     * the campaign has its own words for how the night went. Then the campaign is told.</p>
     */
    @Override
    public void onFinish() {
        final int left = alive();
        try {
            finishByHand();
        } catch (final RuntimeException e) {
            Warfare.LOGGER.warn("[army] could not clean up after the warband at {}: {}", getColony().getName(), e.toString());
        }
        Campaigns.siegeEnded(getColony(), campaign, left, initialSize);
    }

    /** What HordeRaidEvent.onFinish does after the raid manager call, minus the raid manager. */
    @SuppressWarnings("unchecked")
    private void finishByHand() {
        for (final Entity entity : getEntities()) {
            entity.remove(RemovalReason.DISCARDED);
        }
        try {
            final java.lang.reflect.Field field = HordeRaidEvent.class.getDeclaredField("campFires");
            field.setAccessible(true);
            for (final net.minecraft.core.BlockPos pos : (java.util.List<net.minecraft.core.BlockPos>) field.get(this)) {
                if (getColony().getWorld().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) {
                    getColony().getWorld().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[army] the camp fires stay: {}", t.toString());
        }
        raidBar.setVisible(false);
        raidBar.removeAllPlayers();
    }

    // ------------------------------------------------------------------ saving

    @Override
    public CompoundTag serializeNBT(final @NotNull HolderLookup.Provider provider) {
        final CompoundTag tag = super.serializeNBT(provider);
        tag.putInt(TAG_ATTACKER, attacker);
        tag.putString(TAG_ATTACKER_NAME, attackerName);
        tag.putLong(TAG_CAMPAIGN, campaign);
        tag.putInt(TAG_NIGHTS, nights);
        tag.putDouble(TAG_HEALTH, soldierHealth);
        tag.putDouble(TAG_DAMAGE, soldierDamage);
        tag.putDouble(TAG_ARMOUR, soldierArmour);
        tag.putString(TAG_LOOK, look);
        tag.putInt(TAG_INITIAL, initialSize);
        return tag;
    }

    @Override
    public void deserializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        attacker = tag.getInt(TAG_ATTACKER);
        attackerName = tag.getString(TAG_ATTACKER_NAME);
        campaign = tag.getLong(TAG_CAMPAIGN);
        nights = tag.getInt(TAG_NIGHTS);
        soldierHealth = tag.getDouble(TAG_HEALTH);
        soldierDamage = tag.getDouble(TAG_DAMAGE);
        soldierArmour = tag.getDouble(TAG_ARMOUR);
        look = tag.getString(TAG_LOOK);
        initialSize = tag.getInt(TAG_INITIAL);
    }

    public static ArmyRaidEvent loadFromNBT(final IColony colony, final CompoundTag tag,
                                            final @NotNull HolderLookup.Provider provider) {
        final ArmyRaidEvent event = new ArmyRaidEvent(colony);
        event.deserializeNBT(provider, tag);
        return event;
    }
}
