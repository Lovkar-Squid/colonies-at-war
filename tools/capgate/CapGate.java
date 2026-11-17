package me.lovkar.capgate;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.colony.managers.interfaces.IRaiderManager;
import com.minecolonies.api.entity.mobs.RaiderMobUtils;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.events.raid.HordeRaidEvent;
import com.minecolonies.core.colony.events.raid.barbarianEvent.BarbarianRaidEvent;
import com.minecolonies.core.colony.events.raid.barbarianEvent.Horde;
import me.lovkar.war.WarConfig;
import me.lovkar.war.campaign.ArmyRaidEvent;
import me.lovkar.war.campaign.Captains;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rig gate for the named captains (C1).
 *
 * <p>A colony on a flat world, and three raids on it at once: MineColonies' own barbarian raid,
 * started through its raid manager; a warband of "Ironhold"; and a warband of "Stonebrook" that
 * brought no chief, so one of its men has to be promoted - and is then sent a chief late, who must
 * not become a second captain. For each raid the gate checks one named captain, his gear and
 * health, his bar; then a fake player hurts him (the bar follows) and kills him, and the gate
 * checks the one trophy in his drops, nothing of his gear, the bar gone, no trophy from a second
 * death, and - a while later - nobody promoted in his place. The gate sets {@code captainTrophy}
 * itself before each kill: the two chiefs leave banners (the default), Stonebrook's captain his
 * weapon, so one run proves both kinds.</p>
 *
 * <p>MineColonies treats a fake player's damage like the weather: it may wound a raider but never
 * take him below a fifth or more of his health. So the killing blow is {@code die} with the fake
 * player's attack as the source, which is everything a real killing blow ends in - the death
 * event, the drops and the raid being told.</p>
 */
@Mod("capgate")
public final class CapGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("capgate");
    private static final int SETUP = 40;
    private static final int RAIDS = 100;
    private static final int TIMEOUT = 2400;
    private static final String KEY_OF = "com.colonies_at_war.captain.of";
    private static final String KEY_BANNER = "com.colonies_at_war.captain.banner";
    private static final String KEY_WEAPON = "com.colonies_at_war.captain.weapon";
    private static final String KEY_TROPHY_RAID = "com.colonies_at_war.captain.trophy.raid";
    private static final String KEY_TROPHY_DAY = "com.colonies_at_war.captain.trophy.day";
    private static final String BARBARIAN_NAME = "com.colonies_at_war.captain.name.barbarian.";
    private static final String ARMY_NAME = "com.colonies_at_war.captain.name.army.";

    /** One raid being watched, and how far the gate has got with it. */
    private static final class Watched {
        final String label;
        final int event;
        final boolean wantChief;
        /** The warband's attacker, or null for MineColonies' raid. */
        final String attacker;
        final BlockPos spawn;
        /** The trophy his death is to leave: WarConfig.TROPHY_BANNER or TROPHY_WEAPON. */
        final String trophy;
        LivingEntity captain;
        Component title;
        boolean checked;
        int checkedAt;
        LivingEntity lateChief;
        int lateChiefAt;
        boolean lateChecked;
        boolean hurt;
        int hurtAt;
        float before;
        boolean killed;
        /** The armour he wore, which must not be in his drops. */
        final List<Item> gear = new ArrayList<>();

        Watched(final String label, final int event, final boolean wantChief, final String attacker, final BlockPos spawn,
                final String trophy) {
            this.label = label;
            this.event = event;
            this.wantChief = wantChief;
            this.attacker = attacker;
            this.spawn = spawn;
            this.trophy = trophy;
        }
    }

    private int tick;
    private MinecraftServer server;
    private ServerLevel level;
    private IColony colony;
    private BlockPos center;
    private FakePlayer fake;
    private final List<Watched> watched = new ArrayList<>();
    /** What every captain dropped, as the last listener sees it. */
    private final Map<UUID, List<ItemEntity>> drops = new HashMap<>();
    private final Map<UUID, Integer> dropEvents = new HashMap<>();
    private int fails;
    private int checks;
    private int endAt = -1;
    private boolean finished;

    public CapGate(final IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onDrops);
    }

    private void check(final String what, final boolean ok, final Object detail) {
        checks++;
        if (!ok) {
            fails++;
        }
        LOGGER.info("[capgate] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
    }

    private void onDrops(final LivingDropsEvent event) {
        if (Captains.isCaptain(event.getEntity())) {
            drops.computeIfAbsent(event.getEntity().getUUID(), k -> new ArrayList<>()).addAll(event.getDrops());
            dropEvents.merge(event.getEntity().getUUID(), 1, Integer::sum);
        }
    }

    private void onTick(final ServerTickEvent.Post e) {
        if (finished) {
            return;
        }
        tick++;
        server = e.getServer();
        try {
            if (tick == SETUP) {
                setUp();
            } else if (tick == RAIDS) {
                startRaids();
            } else if (tick > RAIDS && tick % 5 == 0) {
                step();
            }
            if (!finished && tick >= TIMEOUT) {
                for (final Watched w : watched) {
                    check("raid " + w.label + " ran to the end", false,
                            "checked " + w.checked + ", late chief " + w.lateChecked + ", killed " + w.killed);
                }
                finish();
            }
        } catch (final Throwable t) {
            LOGGER.error("[capgate] the probe broke at tick {}", tick, t);
            check("probe runs", false, t.toString());
            finish();
        }
    }

    private void finish() {
        finished = true;
        LOGGER.info("[capgate] {} checks, {} failed", checks, fails);
        LOGGER.info("[capgate] RESULT {}", fails == 0 && checks > 0 ? "PASS" : "FAIL");
        server.halt(false);
    }

    // ------------------------------------------------------------------ the colony and the raids

    private void setUp() {
        level = server.overworld();
        // not at 0,0: MineColonies takes a building at the zero position for a missing one
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
        center = new BlockPos(8, y, 8);
        for (int cx = -6; cx <= 6; cx++) {
            for (int cz = -6; cz <= 6; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        // the rig's world is peaceful, and MineColonies' raiders leave a peaceful world the tick they
        // arrive; daylight held and no mobs of the world's own: a raid ends at nightfall, and a zombie
        // is not a raider
        server.setDifficulty(Difficulty.NORMAL, true);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(1000L);
        fake = FakePlayerFactory.getMinecraft(level);
        fake.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(level, center, fake, "Capgate", "Medieval Oak");
        check("colony created", colony != null, colony == null ? "none" : "id " + colony.getID() + " at " + center.toShortString());
        final Block townHall = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall"));
        level.setBlock(center, townHall.defaultBlockState(), 3);
        final BlockEntity te = level.getBlockEntity(center);
        // a Town Hall of level 1: a raid finds its way in by the nearest building with a level
        final IBuilding hall = te instanceof AbstractTileEntityColonyBuilding hut
                ? colony.getServerBuildingManager().addNewBuilding(hut, level) : null;
        if (hall != null) {
            hall.setBuildingLevel(1);
        }
        check("the colony has a Town Hall", hall != null && hall.getBuildingLevel() == 1,
                hall == null ? "none" : "level " + hall.getBuildingLevel());
        // MineColonies runs a colony (and its raids) only while somebody is close. It refuses a fake
        // player through addCloseSubscriber, so the fake owner goes into the live set by hand, where it
        // stays while it stands on a chunk of the colony (the Voyager expedition probe's recipe).
        colony.getPackageManager().getCloseSubscribers().add(fake);
        LOGGER.info("[capgate] colony {} ready, extra health {}%, bar {}, trophy {}", colony.getName(),
                WarConfig.captainExtraHealth(), WarConfig.captainBossBar(), WarConfig.captainTrophy());
    }

    private BlockPos surface(final int dx, final int dz) {
        final int x = center.getX() + dx;
        final int z = center.getZ() + dz;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    private void startRaids() {
        final Set<Integer> before = new HashSet<>(colony.getEventManager().getEvents().keySet());
        final BlockPos a = surface(40, 0);
        final IRaiderManager.RaidSpawnResult result = colony.getRaiderManager().raiderEvent(new IRaiderManager.RaidSettings(
                true, BarbarianRaidEvent.BARBARIAN_RAID_EVENT_TYPE_ID.getPath(), false, 8, a));
        int barbarians = 0;
        for (final IColonyEvent event : new ArrayList<>(colony.getEventManager().getEvents().values())) {
            if (!before.contains(event.getID()) && event instanceof BarbarianRaidEvent) {
                watched.add(new Watched("barbarian raid", event.getID(), true, null, a, WarConfig.TROPHY_BANNER));
                barbarians++;
            }
        }
        check("MineColonies starts its own barbarian raid", result == IRaiderManager.RaidSpawnResult.SUCCESS && barbarians == 1,
                result + ", " + barbarians + " event(s)");

        final BlockPos b = surface(-40, 0);
        final ArmyRaidEvent ironhold = new ArmyRaidEvent(colony).warband(4242, "Ironhold", 9001L, 6, 20, 3, 1, "barbarian");
        ironhold.setSpawnPoint(b);
        ironhold.setHorde(new Horde(6));
        colony.getEventManager().addEvent(ironhold);
        watched.add(new Watched("warband of Ironhold", ironhold.getID(), true, "Ironhold", b, WarConfig.TROPHY_BANNER));

        final BlockPos c = surface(0, 40);
        final ArmyRaidEvent stonebrook = new ArmyRaidEvent(colony).warband(4243, "Stonebrook", 9002L, 4, 20, 3, 1, "barbarian");
        final Horde chiefless = new Horde(4);
        chiefless.numberOfBosses = 0;
        chiefless.hordeSize = chiefless.numberOfArchers + chiefless.numberOfRaiders;
        stonebrook.setSpawnPoint(c);
        stonebrook.setHorde(chiefless);
        colony.getEventManager().addEvent(stonebrook);
        watched.add(new Watched("chiefless warband of Stonebrook", stonebrook.getID(), false, "Stonebrook", c,
                WarConfig.TROPHY_WEAPON));
        LOGGER.info("[capgate] raids started: {}", watched.stream().map(w -> w.label + " = event " + w.event).toList());
    }

    private IColonyRaidEvent raid(final Watched w) {
        final IColonyEvent event = colony.getEventManager().getEventByID(w.event);
        return event instanceof IColonyRaidEvent raid ? raid : null;
    }

    private static List<LivingEntity> men(final IColonyRaidEvent raid) {
        final List<LivingEntity> out = new ArrayList<>();
        for (final Entity e : new ArrayList<>(raid.getEntities())) {
            if (e instanceof LivingEntity man && man.isAlive()) {
                out.add(man);
            }
        }
        return out;
    }

    private static List<LivingEntity> captains(final IColonyRaidEvent raid) {
        final List<LivingEntity> out = new ArrayList<>();
        for (final LivingEntity man : men(raid)) {
            if (Captains.isCaptain(man)) {
                out.add(man);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the gate

    private void step() throws ReflectiveOperationException {
        if (tick % 100 == 0) {
            final StringBuilder line = new StringBuilder();
            for (final Watched w : watched) {
                final IColonyRaidEvent raid = raid(w);
                line.append(w.label).append(": ").append(raid == null ? "gone" : raid.getStatus() + " " + men(raid).size()
                        + " men " + captains(raid).size() + " captain(s)").append("; ");
            }
            LOGGER.info("[capgate] t={} colony {} ({} close), {}", tick, colony.getState(),
                    colony.getPackageManager().getCloseSubscribers().size(), line);
        }
        boolean ready = true;
        for (final Watched w : watched) {
            if (!w.checked) {
                look(w);
            } else if (!w.wantChief && w.lateChief == null && tick >= w.checkedAt + 10) {
                sendLateChief(w);
            } else if (w.lateChief != null && !w.lateChecked && tick >= w.lateChiefAt + 40) {
                checkLateChief(w);
            }
            ready &= w.checked && (w.wantChief || w.lateChecked);
        }
        if (!ready || watched.isEmpty()) {
            return;
        }
        boolean allKilled = true;
        for (final Watched w : watched) {
            if (!w.hurt) {
                hurt(w);
            } else if (!w.killed && tick >= w.hurtAt + 25) {
                kill(w);
            }
            allKilled &= w.killed;
        }
        if (allKilled && endAt < 0) {
            endAt = tick + 100;
        }
        if (endAt >= 0 && tick >= endAt) {
            for (final Watched w : watched) {
                final IColonyRaidEvent raid = raid(w);
                check("no second captain in the " + w.label + " after the first fell",
                        raid != null && captains(raid).isEmpty() && Captains.recorded(colony, w.event),
                        raid == null ? "the raid is over" : captains(raid).size() + " marked man/men standing, "
                                + men(raid).size() + " men left, recorded " + Captains.recorded(colony, w.event));
            }
            finish();
        }
    }

    /** The raid's captain, once he is there: who, his name, his gear, his health and his bar. */
    private void look(final Watched w) {
        final IColonyRaidEvent raid = raid(w);
        if (raid == null) {
            return;
        }
        final List<LivingEntity> marked = captains(raid);
        if (marked.isEmpty()) {
            return;
        }
        final List<LivingEntity> men = men(raid);
        final LivingEntity captain = marked.get(0);
        w.captain = captain;
        w.title = captain.getCustomName();
        w.checked = true;
        w.checkedAt = tick;
        check("one captain in the " + w.label, marked.size() == 1, marked.size() + " of " + men.size() + " men");

        // his name
        final Component title = Captains.title(captain);
        final String mark = captain.getPersistentData().getCompound(Captains.TAG).getString("name");
        boolean named = w.title != null && w.title.equals(title) && !mark.isEmpty();
        String how = w.title == null ? "no name" : w.title.getString();
        if (named && w.title.getContents() instanceof TranslatableContents tc) {
            if (w.attacker == null) {
                named = tc.getKey().startsWith(BARBARIAN_NAME);
            } else {
                final Object[] args = tc.getArgs();
                named = KEY_OF.equals(tc.getKey()) && args.length == 2 && w.attacker.equals(args[1])
                        && args[0] instanceof Component given && given.getContents() instanceof TranslatableContents g
                        && g.getKey().startsWith(ARMY_NAME);
            }
            how += " (" + tc.getKey() + ", mark " + mark + ")";
        } else {
            named = false;
        }
        check("the captain of the " + w.label + " has a name", named, how);

        // who he is
        if (w.wantChief) {
            check("the " + w.label + " is led by its chief", captain.getType() == raid.getBossRaiderType(),
                    captain.getType().getDescriptionId());
        } else {
            int first = Integer.MAX_VALUE;
            for (final LivingEntity man : men) {
                if (man.getType() == raid.getNormalRaiderType()) {
                    first = Math.min(first, man.getId());
                }
            }
            check("the " + w.label + " is led by its first fighting man", captain.getType() == raid.getNormalRaiderType()
                    && captain.getId() == first, captain.getType().getDescriptionId() + " id " + captain.getId() + ", first " + first);
        }

        // his gear: a full set and a weapon a tier above his men's best, none of it dropped
        int best = -1;
        for (final LivingEntity man : men) {
            if (man != captain) {
                for (final EquipmentSlot slot : EquipmentSlot.values()) {
                    best = Math.max(best, tier(man.getItemBySlot(slot)));
                }
            }
        }
        final int want = Math.min(4, Math.max(best, 1) + 1);
        boolean armoured = true;
        final StringBuilder gear = new StringBuilder();
        for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            final ItemStack piece = captain.getItemBySlot(slot);
            armoured &= piece.getItem() instanceof ArmorItem && tier(piece) == want;
            gear.append(BuiltInRegistries.ITEM.getKey(piece.getItem()).getPath()).append(' ');
            w.gear.add(piece.getItem());
        }
        // his weapon is not on the list: a chief's own loot table may drop a sword of the same kind,
        // which is MineColonies' loot and not his; the drop chances below cover the one in his hand
        final ItemStack weapon = captain.getMainHandItem();
        final boolean ranged = weapon.getItem() instanceof ProjectileWeaponItem || weapon.getItem() instanceof TridentItem;
        final boolean armed = tier(weapon) >= want || (ranged && weapon.isEnchanted());
        gear.append("+ ").append(BuiltInRegistries.ITEM.getKey(weapon.getItem()).getPath());
        final CompoundTag saved = captain.saveWithoutId(new CompoundTag());
        final ListTag armourChances = saved.getList("ArmorDropChances", Tag.TAG_FLOAT);
        final ListTag handChances = saved.getList("HandDropChances", Tag.TAG_FLOAT);
        boolean kept = armourChances.size() == 4 && handChances.size() >= 1 && handChances.getFloat(0) == 0.0f;
        for (int i = 0; i < armourChances.size(); i++) {
            kept &= armourChances.getFloat(i) == 0.0f;
        }
        check("the captain of the " + w.label + " wears a full set a tier above his men", armoured && armed,
                gear + " (men's best tier " + best + ", wanted " + want + ")");
        check("the captain of the " + w.label + " cannot be farmed for his gear", kept,
                "armour " + armourChances + ", hands " + handChances);

        // his health
        final AttributeInstance health = captain.getAttribute(Attributes.MAX_HEALTH);
        final AttributeModifier bonus = health == null ? null : health.getModifier(Captains.HEALTH_BONUS_ID);
        final double extra = WarConfig.captainExtraHealth() / 100.0;
        boolean stronger = health != null && bonus != null && Math.abs(bonus.amount() - extra) < 1e-6
                && captain.getMaxHealth() >= health.getBaseValue() * (1.0 + extra) - 0.01
                && captain.getHealth() >= captain.getMaxHealth() - 0.01;
        String than = "";
        for (final LivingEntity man : men) {
            if (man != captain && man.getType() == captain.getType()) {
                stronger &= captain.getMaxHealth() >= man.getMaxHealth() * (1.0 + extra) - 0.01;
                than = ", a man of his kind has " + man.getMaxHealth();
                break;
            }
        }
        check("the captain of the " + w.label + " has the health bonus", stronger,
                captain.getHealth() + "/" + captain.getMaxHealth() + " on a base of "
                        + (health == null ? "?" : health.getBaseValue()) + ", modifier " + (bonus == null ? "none" : bonus.amount()) + than);

        // his bar
        final ServerBossEvent bar = Captains.bar(captain.getUUID());
        check("the captain of the " + w.label + " has his bar", bar != null && bar.isVisible()
                        && bar.getColor() == BossEvent.BossBarColor.RED && bar.getProgress() > 0.99f && title.equals(bar.getName()),
                bar == null ? "none" : bar.getColor() + " " + bar.getProgress() + " " + bar.getName().getString());
    }

    /** A chief sent after the captain was made: he must stay a soldier. */
    private void sendLateChief(final Watched w) throws ReflectiveOperationException {
        final IColonyRaidEvent raid = raid(w);
        if (!(raid instanceof HordeRaidEvent horde)) {
            check("a late chief reaches the " + w.label, false, "no horde raid");
            w.lateChecked = true;
            w.lateChief = w.captain;
            return;
        }
        final java.lang.reflect.Field field = HordeRaidEvent.class.getDeclaredField("horde");
        field.setAccessible(true);
        final Horde counts = (Horde) field.get(horde);
        counts.numberOfBosses = 1;
        counts.hordeSize++;
        final Set<Integer> before = new HashSet<>();
        for (final LivingEntity man : men(raid)) {
            before.add(man.getId());
        }
        RaiderMobUtils.spawn(raid.getBossRaiderType(), 1, w.spawn, level, colony, w.event);
        for (final LivingEntity man : men(raid)) {
            if (!before.contains(man.getId()) && man.getType() == raid.getBossRaiderType()) {
                w.lateChief = man;
            }
        }
        w.lateChiefAt = tick;
        check("a late chief reaches the " + w.label, w.lateChief != null, w.lateChief == null ? "none registered"
                : w.lateChief.getType().getDescriptionId() + " id " + w.lateChief.getId());
        if (w.lateChief == null) {
            w.lateChief = w.captain;
            w.lateChecked = true;
        }
    }

    private void checkLateChief(final Watched w) {
        w.lateChecked = true;
        final IColonyRaidEvent raid = raid(w);
        final List<LivingEntity> marked = raid == null ? List.of() : captains(raid);
        check("a late chief does not make a second captain in the " + w.label,
                !Captains.isCaptain(w.lateChief) && marked.size() == 1 && marked.get(0) == w.captain,
                "late chief marked " + Captains.isCaptain(w.lateChief) + ", " + marked.size() + " captain(s)");
    }

    /**
     * He is wounded, and the bar must follow. The wound is given by hand: a fake player's blow is the
     * weather to MineColonies (see the class comment), and a raider at his camp fire is spared that.
     */
    private void hurt(final Watched w) {
        w.hurt = true;
        w.hurtAt = tick;
        w.before = w.captain.getHealth();
        w.captain.setHealth(w.captain.getMaxHealth() * 0.5f);
    }

    private void kill(final Watched w) throws ReflectiveOperationException {
        w.killed = true;
        final LivingEntity captain = w.captain;
        final UUID id = captain.getUUID();
        final ServerBossEvent bar = Captains.bar(id);
        check("the bar of the captain of the " + w.label + " follows his health",
                captain.getHealth() < w.before && bar != null && bar.getProgress() < 0.999f
                        && Math.abs(bar.getProgress() - captain.getHealth() / captain.getMaxHealth()) < 0.01f,
                w.before + " -> " + captain.getHealth() + ", bar " + (bar == null ? "none" : bar.getProgress()));

        // the trophy is set by the gate for each raid - banners for the first two, the weapon for the
        // third - so every run proves both kinds, whatever a rig config file left by another run says
        setTrophy(w.trophy);
        final Item held = captain.getMainHandItem().getItem();
        final DamageSource blow = level.damageSources().playerAttack(fake);
        captain.setHealth(0.0f);
        captain.die(blow);
        final List<ItemEntity> got = drops.getOrDefault(id, List.of());
        final List<ItemStack> trophies = trophies(got);
        boolean good = trophies.size() == 1;
        String what = trophies.size() + " trophies in " + got.size() + " drops, wanted a " + w.trophy;
        if (good) {
            final ItemStack trophy = trophies.get(0);
            final Component name = trophy.get(DataComponents.CUSTOM_NAME);
            final ItemLore lore = trophy.get(DataComponents.LORE);
            final BannerPatternLayers layers = trophy.get(DataComponents.BANNER_PATTERNS);
            final boolean kind = WarConfig.TROPHY_WEAPON.equals(w.trophy) ? trophy.getItem() == held
                    : trophy.getItem() instanceof BannerItem && layers != null && !layers.layers().isEmpty();
            good = kind && name != null && name.getContents() instanceof TranslatableContents tc
                    && (WarConfig.TROPHY_WEAPON.equals(w.trophy) ? KEY_WEAPON : KEY_BANNER).equals(tc.getKey())
                    && tc.getArgs().length == 1 && w.title.equals(tc.getArgs()[0]) && lore != null && lore.lines().size() == 2
                    // which raid on which colony, and the colony's day he fell
                    && lore.lines().get(0).getContents() instanceof TranslatableContents raidLine
                    && KEY_TROPHY_RAID.equals(raidLine.getKey()) && raidLine.getArgs().length == 2
                    && colony.getName().equals(raidLine.getArgs()[1])
                    && lore.lines().get(1).getContents() instanceof TranslatableContents dayLine
                    && KEY_TROPHY_DAY.equals(dayLine.getKey()) && dayLine.getArgs().length == 1
                    && Integer.valueOf(colony.getDay()).equals(dayLine.getArgs()[0]);
            what += ": " + BuiltInRegistries.ITEM.getKey(trophy.getItem()).getPath() + " named " + (name == null ? "nothing" : name.getString())
                    + ", lore " + (lore == null ? "none" : lore.lines().stream().map(Component::getString).toList());
        }
        check("the captain of the " + w.label + " drops one named trophy with lore", good, what);

        boolean clean = true;
        for (final ItemEntity drop : got) {
            final Item item = drop.getItem().getItem();
            if (trophies.contains(drop.getItem())) {
                continue;
            }
            clean &= !w.gear.contains(item);
        }
        check("the captain of the " + w.label + " drops none of his armour", clean,
                got.stream().map(d -> BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).getPath()).toList());
        check("the bar of the captain of the " + w.label + " is gone", Captains.bar(id) == null, "bar " + Captains.bar(id));

        // a second death event, and the drops hook called again by hand: nothing more
        final int events = dropEvents.getOrDefault(id, 0);
        captain.die(blow);
        final List<ItemEntity> again = new ArrayList<>();
        CommonHooks.onLivingDrops(captain, blow, again, true);
        check("the captain of the " + w.label + " never drops a second trophy",
                trophies(again).isEmpty() && dropEvents.getOrDefault(id, 0) == events + 1 && trophies(drops.get(id)).size() == 1,
                trophies(again).size() + " in a second drop, " + (dropEvents.getOrDefault(id, 0) - events - 1)
                        + " extra drop event(s) from a second death");
    }

    /** captainTrophy, in the running server's config: its value is private to WarConfig, so by reflection. */
    @SuppressWarnings("unchecked")
    private static void setTrophy(final String kind) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = WarConfig.class.getDeclaredField("CAPTAIN_TROPHY");
        field.setAccessible(true);
        ((ModConfigSpec.ConfigValue<String>) field.get(null)).set(kind);
        if (!kind.equals(WarConfig.captainTrophy())) {
            throw new IllegalStateException("captainTrophy did not take " + kind);
        }
    }

    private static List<ItemStack> trophies(final List<ItemEntity> drops) {
        final List<ItemStack> out = new ArrayList<>();
        if (drops == null) {
            return out;
        }
        for (final ItemEntity drop : drops) {
            final Component name = drop.getItem().get(DataComponents.CUSTOM_NAME);
            if (name != null && name.getContents() instanceof TranslatableContents tc
                    && (KEY_BANNER.equals(tc.getKey()) || KEY_WEAPON.equals(tc.getKey()))) {
                out.add(drop.getItem());
            }
        }
        return out;
    }

    /** The tier ladder, read off the spec: wood/leather 0, stone/chainmail 1, iron 2, diamond 3, netherite 4. */
    private static int tier(final ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        if (stack.getItem() instanceof TieredItem tiered) {
            return Math.min(4, Math.max(0, (int) tiered.getTier().getAttackDamageBonus()));
        }
        if (stack.getItem() instanceof ArmorItem armour) {
            final ArmorMaterial m = armour.getMaterial().value();
            if (m == ArmorMaterials.LEATHER.value()) {
                return 0;
            }
            if (m == ArmorMaterials.CHAIN.value() || m == ArmorMaterials.GOLD.value()) {
                return 1;
            }
            if (m == ArmorMaterials.IRON.value() || m == ArmorMaterials.TURTLE.value()) {
                return 2;
            }
            if (m == ArmorMaterials.DIAMOND.value()) {
                return 3;
            }
            return m == ArmorMaterials.NETHERITE.value() ? 4 : 1;
        }
        return -1;
    }
}
