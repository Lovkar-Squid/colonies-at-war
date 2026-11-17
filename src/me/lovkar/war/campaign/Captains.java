package me.lovkar.war.campaign;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import com.minecolonies.api.entity.mobs.IArcherMobEntity;
import com.minecolonies.api.entity.mobs.IChiefMobEntity;
import com.minecolonies.api.entity.mobs.IMeleeMobEntity;
import com.minecolonies.api.entity.mobs.RaiderType;
import com.minecolonies.api.util.DamageSourceKeys;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Every raid on a colony is led by somebody.
 *
 * <p>MineColonies' raids - barbarians, pirates, Egyptians, Norsemen, Amazons, the drowned - and this
 * mod's own warbands and kingdom reprisals all get one captain per raid: the raid's chief when it
 * brought one, otherwise its first ordinary fighting man, promoted. He has a name, better gear than
 * his men, more health, a red bar over the screen of anybody within 64 blocks of him, and when a
 * player or a colony guard kills him he leaves something behind to hang on a wall.</p>
 *
 * <p>Nothing here hooks into a raid. MineColonies' raid events are theirs, and a mixin into every one
 * of them would be a mixin into code that changes every month; so the captain is found from the
 * outside, by looking at each colony's raids twice a second, the way {@link me.lovkar.war.ai.RaidAlarm}
 * finds the raiders. That makes the warband and every raid MineColonies or anybody else adds the same
 * case: an {@code IColonyRaidEvent} with raiders in it.</p>
 *
 * <p>Two places remember a captain, and both are needed. <b>The man himself</b> carries a mark in his
 * entity's persistent data - his name, his raid, whether he has fallen and whether his trophy has
 * dropped - so he comes back from a restart as himself, with his bar. <b>This saved data</b> carries
 * one line per raid (colony and event id) saying it has had its captain, because once he is dead the
 * man is gone, and a raid that has lost its captain must not promote another.</p>
 */
public class Captains extends SavedData {
    private static final String NAME = "colonies_at_war_captains";
    private static final String TAG_RECORDS = "records";

    /** The mark in a captain's persistent data. */
    public static final String TAG = "colonies_at_war_captain";
    private static final String TAG_COLONY = "colony";
    private static final String TAG_EVENT = "event";
    private static final String TAG_NAME = "name";
    private static final String TAG_OF = "of";
    private static final String TAG_ATTACKER = "attacker";
    private static final String TAG_RAID = "raid";
    private static final String TAG_FELL = "fell";
    private static final String TAG_TROPHY = "trophy";
    private static final String TAG_UUID = "uuid";

    /** His extra health, one modifier with one id, so it is never stacked twice and survives a reload. */
    public static final ResourceLocation HEALTH_BONUS_ID = ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "captain_health");

    /** Who sees his bar, in blocks. */
    public static final double BAR_RANGE = 64.0;
    /** How often the raids are looked at and the bars brought up to date, in ticks. */
    private static final int EVERY = 10;
    /**
     * How long a raid without a chief waits before it promotes one of its men, in ticks. A horde
     * spawns all at once and needs no wait; a ship lands its crew a few at a time, and its captain
     * may still be below deck.
     */
    private static final int GRACE = 100;

    private static final String KEY_OF = "com.colonies_at_war.captain.of";
    private static final String KEY_PLAIN = "com.colonies_at_war.captain.plain";
    private static final String KEY_LEADS = "com.colonies_at_war.captain.leads";
    private static final String KEY_FELL = "com.colonies_at_war.captain.fell";
    private static final String KEY_BANNER = "com.colonies_at_war.captain.banner";
    private static final String KEY_WEAPON = "com.colonies_at_war.captain.weapon";
    private static final String KEY_TROPHY_RAID = "com.colonies_at_war.captain.trophy.raid";
    private static final String KEY_TROPHY_DAY = "com.colonies_at_war.captain.trophy.day";

    /**
     * What kind of raid a captain leads: which name list he is named from, what the chat calls his
     * raid, and the colours of his banner.
     */
    public enum Raid {
        BARBARIAN("barbarian"),
        PIRATE("pirate"),
        DROWNED("drowned_pirate"),
        EGYPTIAN("egyptian"),
        NORSE("norse"),
        AMAZON("amazon"),
        /** A colony's warband. */
        WARBAND("warband"),
        /** A kingdom's men (a reprisal), or anybody else this mod sends who is not a colony. */
        ARMY("army"),
        /** A raid nobody here has heard of, with raiders nobody here recognises. */
        OTHER("other");

        private final String id;

        Raid(final String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Raid of(final String id) {
            for (final Raid raid : values()) {
                if (raid.id.equals(id)) {
                    return raid;
                }
            }
            return OTHER;
        }

        /** Somebody else's raid, by MineColonies' own event id, or by what its men are. */
        static Raid of(final IColonyRaidEvent event, final LivingEntity man) {
            if (event instanceof ArmyRaidEvent army) {
                return army.campaign() < 0 || army.attacker() == 0 ? ARMY : WARBAND;
            }
            final String path;
            try {
                path = event.getEventTypeID().getPath();
            } catch (final Throwable t) {
                return byRaider(man);
            }
            return switch (path) {
                case "barbarian_raid" -> BARBARIAN;
                case "pirate_raid", "pirate_ground_raid" -> PIRATE;
                case "drowned_pirate_raid" -> DROWNED;
                case "egyptian_raid" -> EGYPTIAN;
                case "norsemen_raid", "norsemen_ship_raid" -> NORSE;
                case "amazon_raid" -> AMAZON;
                default -> byRaider(man);
            };
        }

        private static Raid byRaider(final LivingEntity man) {
            if (!(man instanceof AbstractEntityMinecoloniesRaider raider)) {
                return OTHER;
            }
            final RaiderType type;
            try {
                type = raider.getRaiderType();
            } catch (final Throwable t) {
                return OTHER;
            }
            if (type == null) {
                return OTHER;
            }
            return switch (type) {
                case BARBARIAN -> BARBARIAN;
                case PIRATE -> PIRATE;
                case EGYPTIAN -> EGYPTIAN;
                case NORSEMAN -> NORSE;
                case AMAZON -> AMAZON;
                default -> OTHER;
            };
        }

        /**
         * The names this raid's captains are given, as lang keys so a resource pack can rename them.
         * A warband or an army is a colony's or a kingdom's own people, so its captains have plain
         * names and the attacker's name after them.
         */
        String[] names() {
            return switch (this) {
                case BARBARIAN, OTHER -> BARBARIAN_NAMES;
                case PIRATE, DROWNED -> PIRATE_NAMES;
                case EGYPTIAN -> EGYPTIAN_NAMES;
                case NORSE -> NORSE_NAMES;
                case AMAZON -> AMAZON_NAMES;
                case WARBAND, ARMY -> ARMY_NAMES;
            };
        }

        /** "barbarian raid", "warband of Ironhold": what the chat and the trophy call the raid. */
        Component display(final String of) {
            return switch (this) {
                case BARBARIAN -> Component.translatable("com.colonies_at_war.captain.raid.barbarian");
                case PIRATE -> Component.translatable("com.colonies_at_war.captain.raid.pirate");
                case DROWNED -> Component.translatable("com.colonies_at_war.captain.raid.drowned_pirate");
                case EGYPTIAN -> Component.translatable("com.colonies_at_war.captain.raid.egyptian");
                case NORSE -> Component.translatable("com.colonies_at_war.captain.raid.norse");
                case AMAZON -> Component.translatable("com.colonies_at_war.captain.raid.amazon");
                case WARBAND -> of.isEmpty() ? Component.translatable("com.colonies_at_war.captain.raid.nameless")
                        : Component.translatable("com.colonies_at_war.captain.raid.warband", of);
                case ARMY -> of.isEmpty() ? Component.translatable("com.colonies_at_war.captain.raid.nameless")
                        : Component.translatable("com.colonies_at_war.captain.raid.army", of);
                case OTHER -> Component.translatable("com.colonies_at_war.captain.raid.other");
            };
        }
    }

    private static final String[] BARBARIAN_NAMES = {
            "com.colonies_at_war.captain.name.barbarian.ulgar",
            "com.colonies_at_war.captain.name.barbarian.hroth",
            "com.colonies_at_war.captain.name.barbarian.brakka",
            "com.colonies_at_war.captain.name.barbarian.kordak",
            "com.colonies_at_war.captain.name.barbarian.grimma",
            "com.colonies_at_war.captain.name.barbarian.thrain",
            "com.colonies_at_war.captain.name.barbarian.vashka",
            "com.colonies_at_war.captain.name.barbarian.morrok",
            "com.colonies_at_war.captain.name.barbarian.durga",
            "com.colonies_at_war.captain.name.barbarian.yorvik",
            "com.colonies_at_war.captain.name.barbarian.skarn",
            "com.colonies_at_war.captain.name.barbarian.ottar",
    };
    private static final String[] PIRATE_NAMES = {
            "com.colonies_at_war.captain.name.pirate.meg",
            "com.colonies_at_war.captain.name.pirate.bartholomew",
            "com.colonies_at_war.captain.name.pirate.nell",
            "com.colonies_at_war.captain.name.pirate.josiah",
            "com.colonies_at_war.captain.name.pirate.ned",
            "com.colonies_at_war.captain.name.pirate.harry",
            "com.colonies_at_war.captain.name.pirate.tom",
            "com.colonies_at_war.captain.name.pirate.isobel",
            "com.colonies_at_war.captain.name.pirate.mercy",
            "com.colonies_at_war.captain.name.pirate.saltbeard",
            "com.colonies_at_war.captain.name.pirate.kate",
            "com.colonies_at_war.captain.name.pirate.tobias",
    };
    private static final String[] EGYPTIAN_NAMES = {
            "com.colonies_at_war.captain.name.egyptian.khaemhet",
            "com.colonies_at_war.captain.name.egyptian.nebetah",
            "com.colonies_at_war.captain.name.egyptian.senusret",
            "com.colonies_at_war.captain.name.egyptian.merytra",
            "com.colonies_at_war.captain.name.egyptian.ahmose",
            "com.colonies_at_war.captain.name.egyptian.tawaret",
            "com.colonies_at_war.captain.name.egyptian.kheperu",
            "com.colonies_at_war.captain.name.egyptian.nefermaat",
            "com.colonies_at_war.captain.name.egyptian.iset",
            "com.colonies_at_war.captain.name.egyptian.rekhmire",
            "com.colonies_at_war.captain.name.egyptian.hapu",
            "com.colonies_at_war.captain.name.egyptian.sobekhotep",
    };
    private static final String[] NORSE_NAMES = {
            "com.colonies_at_war.captain.name.norse.hakon",
            "com.colonies_at_war.captain.name.norse.sigrun",
            "com.colonies_at_war.captain.name.norse.ulf",
            "com.colonies_at_war.captain.name.norse.asta",
            "com.colonies_at_war.captain.name.norse.einar",
            "com.colonies_at_war.captain.name.norse.thyra",
            "com.colonies_at_war.captain.name.norse.orm",
            "com.colonies_at_war.captain.name.norse.gunnhild",
            "com.colonies_at_war.captain.name.norse.halvar",
            "com.colonies_at_war.captain.name.norse.leif",
            "com.colonies_at_war.captain.name.norse.solveig",
            "com.colonies_at_war.captain.name.norse.bjarni",
    };
    private static final String[] AMAZON_NAMES = {
            "com.colonies_at_war.captain.name.amazon.antiope",
            "com.colonies_at_war.captain.name.amazon.thessa",
            "com.colonies_at_war.captain.name.amazon.kyra",
            "com.colonies_at_war.captain.name.amazon.melanippe",
            "com.colonies_at_war.captain.name.amazon.otrera",
            "com.colonies_at_war.captain.name.amazon.lysippe",
            "com.colonies_at_war.captain.name.amazon.ianthe",
            "com.colonies_at_war.captain.name.amazon.phoebe",
            "com.colonies_at_war.captain.name.amazon.alkaia",
            "com.colonies_at_war.captain.name.amazon.derinoe",
            "com.colonies_at_war.captain.name.amazon.myrina",
            "com.colonies_at_war.captain.name.amazon.xanthe",
    };
    private static final String[] ARMY_NAMES = {
            "com.colonies_at_war.captain.name.army.aldric",
            "com.colonies_at_war.captain.name.army.bertram",
            "com.colonies_at_war.captain.name.army.cedric",
            "com.colonies_at_war.captain.name.army.edmund",
            "com.colonies_at_war.captain.name.army.gilbert",
            "com.colonies_at_war.captain.name.army.hugh",
            "com.colonies_at_war.captain.name.army.osric",
            "com.colonies_at_war.captain.name.army.roland",
            "com.colonies_at_war.captain.name.army.walter",
            "com.colonies_at_war.captain.name.army.matilda",
            "com.colonies_at_war.captain.name.army.isolde",
            "com.colonies_at_war.captain.name.army.rowena",
    };

    // ------------------------------------------------------------------ what is remembered

    /** One line per raid that has had its captain: "dimension/colony/event" to who he was. */
    private final Map<String, CompoundTag> records = new HashMap<>();

    public static final SavedData.Factory<Captains> FACTORY = new SavedData.Factory<>(Captains::new, Captains::read);

    private static Captains of(final ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    private static String key(final ServerLevel level, final int colony, final int event) {
        return prefix(level, colony) + event;
    }

    /** Every line about one colony's raids starts with this. */
    private static String prefix(final ServerLevel level, final int colony) {
        return level.dimension().location() + "/" + colony + "/";
    }

    // ------------------------------------------------------------------ what is not

    /** A captain on the field and his bar. */
    private record Field(LivingEntity man, ServerBossEvent bar) {
    }

    private static final Map<UUID, Field> fields = new HashMap<>();
    /** When a raid with no chief was first seen with men in it, for the grace before a promotion. */
    private static final Map<String, Long> firstSeen = new HashMap<>();
    private static int ticks = 0;
    private static boolean warned = false;

    // ------------------------------------------------------------------ asking

    /** Whether this entity is a captain - alive, dead or anywhere between. */
    public static boolean isCaptain(final Entity entity) {
        return entity != null && entity.getPersistentData().contains(TAG);
    }

    /** His name as the world shows it: "Ulgar Bonebreaker", "Captain Aldric of Ironhold". */
    public static Component title(final Entity entity) {
        return title(entity.getPersistentData().getCompound(TAG));
    }

    private static Component title(final CompoundTag mark) {
        final Component given = Component.translatable(mark.getString(TAG_NAME));
        final Raid raid = Raid.of(mark.getString(TAG_RAID));
        if (raid == Raid.WARBAND || raid == Raid.ARMY) {
            final String of = mark.getString(TAG_OF);
            return of.isEmpty() ? Component.translatable(KEY_PLAIN, given) : Component.translatable(KEY_OF, given, of);
        }
        return given;
    }

    /** The bar over his head, while he has one. For the rig and for anybody who wants to know. */
    public static ServerBossEvent bar(final UUID captain) {
        final Field field = fields.get(captain);
        return field == null ? null : field.bar();
    }

    /** Whether this raid has had its captain, alive or dead. */
    public static boolean recorded(final IColony colony, final int event) {
        return colony.getWorld() instanceof ServerLevel level && of(level).records.containsKey(key(level, colony.getID(), event));
    }

    // ------------------------------------------------------------------ the tick

    public static void onServerTick(final ServerTickEvent.Post event) {
        if (++ticks % EVERY != 0) {
            return;
        }
        final long now = event.getServer().overworld().getGameTime();
        if (WarConfig.captains()) {
            for (final ServerLevel level : event.getServer().getAllLevels()) {
                final List<IColony> colonies;
                try {
                    colonies = new ArrayList<>(IColonyManager.getInstance().getColonies(level));
                } catch (final Throwable t) {
                    continue;
                }
                for (final IColony colony : colonies) {
                    try {
                        muster(level, colony, now);
                    } catch (final Throwable t) {
                        if (!warned) {
                            warned = true;
                            Warfare.LOGGER.warn("[captain] could not look at the raids on {}: {}", colony.getName(), t.toString(), t);
                        }
                    }
                }
            }
        }
        for (final Map.Entry<UUID, Field> entry : new ArrayList<>(fields.entrySet())) {
            try {
                watch(entry.getKey(), entry.getValue());
            } catch (final Throwable t) {
                drop(entry.getKey());
                Warfare.LOGGER.debug("[captain] lost the bar of a captain: {}", t.toString());
            }
        }
    }

    /** The server is gone: every bar with it. */
    public static void forget() {
        for (final UUID id : new ArrayList<>(fields.keySet())) {
            drop(id);
        }
        fields.clear();
        firstSeen.clear();
        ticks = 0;
        warned = false;
    }

    // ------------------------------------------------------------------ choosing him

    /**
     * Every raid on this colony that has men in the field and no captain yet gets one. Lines about
     * raids that are over are forgotten.
     */
    private static void muster(final ServerLevel level, final IColony colony, final long now) {
        final Captains book = of(level);
        // copied: a raid that starts while this runs adds to the map
        final List<IColonyEvent> events = new ArrayList<>(colony.getEventManager().getEvents().values());
        final Set<Integer> live = new HashSet<>();
        for (final IColonyEvent event : events) {
            live.add(event.getID());
            if (!(event instanceof IColonyRaidEvent raid)) {
                continue;
            }
            final EventStatus status = event.getStatus();
            if (status == EventStatus.DONE || status == EventStatus.CANCELED) {
                continue;
            }
            final String key = key(level, colony.getID(), event.getID());
            final List<LivingEntity> men = men(raid);
            if (book.records.containsKey(key)) {
                firstSeen.remove(key);
                // he has been chosen, and only ever once; if he is back in the world without his
                // bar (his chunk came in before the colony's raids did), it is given back here
                for (final LivingEntity man : men) {
                    if (isCaptain(man) && !man.getPersistentData().getCompound(TAG).getBoolean(TAG_FELL)) {
                        field(man);
                    }
                }
                continue;
            }
            if (men.isEmpty()) {
                continue;
            }
            final long seen = firstSeen.computeIfAbsent(key, k -> now);
            final LivingEntity chosen = choose(raid, men, now - seen >= GRACE);
            if (chosen != null) {
                firstSeen.remove(key);
                promote(level, colony, raid, chosen, men, book, key);
            }
        }
        final String prefix = prefix(level, colony.getID());
        if (book.records.keySet().removeIf(k -> k.startsWith(prefix) && !live.contains(eventOf(k)))) {
            book.setDirty();
        }
        firstSeen.keySet().removeIf(k -> k.startsWith(prefix) && !live.contains(eventOf(k)));
    }

    private static int eventOf(final String key) {
        try {
            return Integer.parseInt(key.substring(key.lastIndexOf('/') + 1));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** The raid's men standing in the world, a copy. */
    private static List<LivingEntity> men(final IColonyRaidEvent raid) {
        final List<LivingEntity> out = new ArrayList<>();
        for (final Entity e : new ArrayList<>(raid.getEntities())) {
            if (e instanceof AbstractEntityMinecoloniesRaider man && man.isAlive() && !man.isRemoved()) {
                out.add(man);
            }
        }
        return out;
    }

    /**
     * The raid's chief if it brought one; once the grace is up, its first ordinary fighting man,
     * and failing even him, its first man of any kind. "First" is the lowest entity id - the man who
     * came into the world first - so the choice does not depend on the order of a hash map.
     */
    private static LivingEntity choose(final IColonyRaidEvent raid, final List<LivingEntity> men, final boolean graceUp) {
        Object bossType = null;
        Object normalType = null;
        try {
            bossType = raid.getBossRaiderType();
            normalType = raid.getNormalRaiderType();
        } catch (final Throwable t) {
            // a raid that cannot say: the interfaces still can
        }
        LivingEntity boss = null;
        LivingEntity melee = null;
        LivingEntity any = null;
        for (final LivingEntity man : men) {
            if (man.getType() == bossType || man instanceof IChiefMobEntity) {
                boss = first(boss, man);
            } else if ((man.getType() == normalType || man instanceof IMeleeMobEntity) && !(man instanceof IArcherMobEntity)) {
                melee = first(melee, man);
            }
            any = first(any, man);
        }
        if (boss != null) {
            return boss;
        }
        if (!graceUp) {
            return null;
        }
        return melee != null ? melee : any;
    }

    private static LivingEntity first(final LivingEntity best, final LivingEntity man) {
        return best == null || man.getId() < best.getId() ? man : best;
    }

    /** He is the captain now: a name, his gear, his health, his bar, and the town is told. */
    private static void promote(final ServerLevel level, final IColony colony, final IColonyRaidEvent raid,
                                final LivingEntity man, final List<LivingEntity> men, final Captains book, final String key) {
        final Raid kind = Raid.of(raid, man);
        final String name = name(level, colony, raid.getID(), kind, book);
        final CompoundTag mark = new CompoundTag();
        mark.putInt(TAG_COLONY, colony.getID());
        mark.putInt(TAG_EVENT, raid.getID());
        mark.putString(TAG_NAME, name);
        mark.putString(TAG_RAID, kind.id());
        if (raid instanceof ArmyRaidEvent army) {
            mark.putString(TAG_OF, army.attackerName());
            mark.putInt(TAG_ATTACKER, army.attacker());
        }
        man.getPersistentData().put(TAG, mark);
        final Component title = title(mark);
        man.setCustomName(title);
        man.setCustomNameVisible(true);
        if (man instanceof Mob mob) {
            arm(level, mob, men);
        }
        fortify(man);

        final CompoundTag record = mark.copy();
        record.putUUID(TAG_UUID, man.getUUID());
        book.records.put(key, record);
        book.setDirty();
        field(man);
        tell(colony, Component.translatable(KEY_LEADS, title, kind.display(mark.getString(TAG_OF)), colony.getName()),
                MessageUtils.MessagePriority.DANGER);
        Warfare.LOGGER.info("[captain] {} leads the {} raid (event {}) against {} - {} at {}", title.getString(), kind.id(),
                raid.getID(), colony.getName(), man.getType().getDescription().getString(), man.blockPosition().toShortString());
    }

    /**
     * His name: picked from the raid's list by the raid itself (dimension, colony, event), so the
     * same raid always gets the same man - and stepped on past any name a captain of another raid on
     * the same colony already has, so two raids at one town never send two Hughs. Once given, the
     * name is kept on the man and in the saved data, and a reload reads it rather than picks again.
     */
    private static String name(final ServerLevel level, final IColony colony, final int event, final Raid kind, final Captains book) {
        final String[] names = kind.names();
        final Set<String> taken = new HashSet<>();
        final String prefix = prefix(level, colony.getID());
        for (final Map.Entry<String, CompoundTag> e : book.records.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                taken.add(e.getValue().getString(TAG_NAME));
            }
        }
        final int start = Math.floorMod(mix(Objects.hash(level.dimension().location().toString(), colony.getID(), event)), names.length);
        for (int i = 0; i < names.length; i++) {
            final String name = names[(start + i) % names.length];
            if (!taken.contains(name)) {
                return name;
            }
        }
        return names[start];
    }

    /** Spread the bits of a hash, so neighbouring event ids do not get neighbouring names. */
    private static int mix(final int seed) {
        int h = seed;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    // ------------------------------------------------------------------ his gear

    /**
     * A full set of armour and a weapon one tier above the best his men carry, none of it dropped.
     *
     * <p>Tiers run wood, stone, iron, diamond, netherite for weapons and leather, chainmail, iron,
     * diamond, netherite for armour, and a man's tier is the best of either he has on. Men who carry
     * nothing with a tier at all - spears, bows, bare hands - count as stone, so their captain is in
     * iron rather than leather. A weapon he already holds that is as good or better stays in his
     * hand: a barbarian chief's diamond-edged sword is not taken off him for an iron one. A bow, a
     * crossbow, a spear or a scepter stays too, and is enchanted instead, because a captain who
     * fights at range should go on doing so.</p>
     */
    private static void arm(final ServerLevel level, final Mob captain, final List<LivingEntity> men) {
        int best = -1;
        for (final LivingEntity man : men) {
            if (man == captain) {
                continue;
            }
            for (final EquipmentSlot slot : EquipmentSlot.values()) {
                best = Math.max(best, tier(man.getItemBySlot(slot)));
            }
        }
        final int tier = Math.min(4, Math.max(best, 1) + 1);
        captain.setItemSlot(EquipmentSlot.HEAD, new ItemStack(armour(tier, EquipmentSlot.HEAD)));
        captain.setItemSlot(EquipmentSlot.CHEST, new ItemStack(armour(tier, EquipmentSlot.CHEST)));
        captain.setItemSlot(EquipmentSlot.LEGS, new ItemStack(armour(tier, EquipmentSlot.LEGS)));
        captain.setItemSlot(EquipmentSlot.FEET, new ItemStack(armour(tier, EquipmentSlot.FEET)));

        final ItemStack held = captain.getMainHandItem();
        if (held.getItem() instanceof ProjectileWeaponItem || held.getItem() instanceof net.minecraft.world.item.TridentItem) {
            final ItemStack better = held.copy();
            try {
                better.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(
                        held.getItem() instanceof ProjectileWeaponItem ? Enchantments.POWER : Enchantments.SHARPNESS), 1);
            } catch (final Throwable t) {
                Warfare.LOGGER.debug("[captain] no enchantment for the captain's weapon: {}", t.toString());
            }
            captain.setItemSlot(EquipmentSlot.MAINHAND, better);
        } else if (tier(held) < tier) {
            captain.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(held.getItem() instanceof AxeItem ? axe(tier) : sword(tier)));
        }
        // the gear is his, not a prize: a captain killed a hundred times is not a hundred iron sets
        for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND}) {
            captain.setDropChance(slot, 0.0f);
        }
    }

    /** 0 wood or leather (or gold), 1 stone or chainmail, 2 iron, 3 diamond, 4 netherite; -1 nothing with a tier. */
    public static int tier(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        final Item item = stack.getItem();
        if (item instanceof TieredItem tiered) {
            // the vanilla tiers' damage bonus is their place on the ladder; a modded tier is placed by it too
            return Math.min(4, Math.max(0, (int) tiered.getTier().getAttackDamageBonus()));
        }
        if (item instanceof ArmorItem armour) {
            final ArmorMaterial material = armour.getMaterial().value();
            if (material == ArmorMaterials.LEATHER.value()) {
                return 0;
            }
            if (material == ArmorMaterials.CHAIN.value() || material == ArmorMaterials.GOLD.value()) {
                return 1;
            }
            if (material == ArmorMaterials.IRON.value() || material == ArmorMaterials.TURTLE.value()) {
                return 2;
            }
            if (material == ArmorMaterials.DIAMOND.value()) {
                return 3;
            }
            if (material == ArmorMaterials.NETHERITE.value()) {
                return 4;
            }
            return 1;
        }
        return -1;
    }

    private static Item armour(final int tier, final EquipmentSlot slot) {
        final Item[] set = switch (tier) {
            case 0 -> new Item[] {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
            case 1 -> new Item[] {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
            case 2 -> new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            case 3 -> new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
            default -> new Item[] {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
        };
        return switch (slot) {
            case HEAD -> set[0];
            case CHEST -> set[1];
            case LEGS -> set[2];
            default -> set[3];
        };
    }

    private static Item sword(final int tier) {
        return switch (tier) {
            case 0 -> Items.WOODEN_SWORD;
            case 1 -> Items.STONE_SWORD;
            case 2 -> Items.IRON_SWORD;
            case 3 -> Items.DIAMOND_SWORD;
            default -> Items.NETHERITE_SWORD;
        };
    }

    private static Item axe(final int tier) {
        return switch (tier) {
            case 0 -> Items.WOODEN_AXE;
            case 1 -> Items.STONE_AXE;
            case 2 -> Items.IRON_AXE;
            case 3 -> Items.DIAMOND_AXE;
            default -> Items.NETHERITE_AXE;
        };
    }

    /**
     * His extra health: a modifier on top of whatever the raid statted him at. The raid sets the
     * base - MineColonies again on every reload, the warband on every registration - and a modifier
     * rides on the base, so he keeps his margin over his men whoever statted them last.
     */
    private static void fortify(final LivingEntity captain) {
        final int extra = WarConfig.captainExtraHealth();
        final AttributeInstance health = captain.getAttribute(Attributes.MAX_HEALTH);
        if (health == null || extra <= 0) {
            return;
        }
        health.addOrReplacePermanentModifier(new AttributeModifier(HEALTH_BONUS_ID, extra / 100.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        captain.setHealth(captain.getMaxHealth());
    }

    // ------------------------------------------------------------------ his bar

    /** A bar for him, if the server wants bars and he does not have one yet. */
    private static void field(final LivingEntity captain) {
        if (!WarConfig.captainBossBar() || fields.containsKey(captain.getUUID())) {
            return;
        }
        final ServerBossEvent bar = new ServerBossEvent(title(captain), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(Math.max(0.0f, Math.min(1.0f, captain.getHealth() / captain.getMaxHealth())));
        fields.put(captain.getUUID(), new Field(captain, bar));
    }

    /** His bar, gone from every screen. */
    private static void drop(final UUID captain) {
        final Field field = fields.remove(captain);
        if (field != null) {
            field.bar().removeAllPlayers();
            field.bar().setVisible(false);
        }
    }

    /** One bar brought up to date: his health, and who is near enough to see it. */
    private static void watch(final UUID id, final Field field) {
        final LivingEntity man = field.man();
        if (!WarConfig.captainBossBar() || man.isRemoved() || !man.isAlive() || !(man.level() instanceof ServerLevel level)
                || !raidGoesOn(level, man)) {
            drop(id);
            return;
        }
        final ServerBossEvent bar = field.bar();
        bar.setProgress(Math.max(0.0f, Math.min(1.0f, man.getHealth() / man.getMaxHealth())));
        final double range = BAR_RANGE * BAR_RANGE;
        for (final ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            if (player.level() != level || player.distanceToSqr(man) > range) {
                bar.removePlayer(player);
            }
        }
        for (final ServerPlayer player : level.players()) {
            if (player.distanceToSqr(man) <= range) {
                bar.addPlayer(player);
            }
        }
    }

    /** Whether the raid he leads is still being fought. A captain whose raid is over has no bar. */
    private static boolean raidGoesOn(final ServerLevel level, final LivingEntity man) {
        final CompoundTag mark = man.getPersistentData().getCompound(TAG);
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(mark.getInt(TAG_COLONY), level);
        if (colony == null) {
            return false;
        }
        final IColonyEvent event = colony.getEventManager().getEventByID(mark.getInt(TAG_EVENT));
        return event != null && event.getStatus() != EventStatus.DONE && event.getStatus() != EventStatus.CANCELED;
    }

    // ------------------------------------------------------------------ the world's events

    /** A captain comes back with his chunk, or with the server: his bar comes back with him. */
    public static void onJoin(final EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity man) || !isCaptain(man)) {
            return;
        }
        if (man.isAlive() && !man.getPersistentData().getCompound(TAG).getBoolean(TAG_FELL) && WarConfig.captains()) {
            field(man);
        }
    }

    /** Unloaded, despawned, sent home at the end of the raid: his bar goes with him. */
    public static void onLeave(final EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && fields.containsKey(event.getEntity().getUUID())) {
            drop(event.getEntity().getUUID());
        }
    }

    /**
     * He is dead. The bar goes, and the town is told once - a second death event for the same man
     * (another mod calling {@code die} again) says nothing. A raider despawning after a long night
     * dies too, by MineColonies' own hand; that is not a fall and nobody is told.
     */
    public static void onDeath(final LivingDeathEvent event) {
        final LivingEntity man = event.getEntity();
        if (man.level().isClientSide() || !isCaptain(man)) {
            return;
        }
        drop(man.getUUID());
        final CompoundTag mark = man.getPersistentData().getCompound(TAG);
        if (mark.getBoolean(TAG_FELL) || event.getSource().is(DamageSourceKeys.DESPAWN)
                || !(man.level() instanceof ServerLevel level)) {
            return;
        }
        mark.putBoolean(TAG_FELL, true);
        final Captains book = of(level);
        final CompoundTag record = book.records.get(key(level, mark.getInt(TAG_COLONY), mark.getInt(TAG_EVENT)));
        if (record != null) {
            record.putBoolean(TAG_FELL, true);
            book.setDirty();
        }
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(mark.getInt(TAG_COLONY), level);
        if (colony != null) {
            tell(colony, Component.translatable(KEY_FELL, title(mark), Raid.of(mark.getString(TAG_RAID)).display(mark.getString(TAG_OF)),
                    colony.getName()), MessageUtils.MessagePriority.IMPORTANT);
        }
        final Entity killer = killer(man, event.getSource());
        Warfare.LOGGER.info("[captain] {} fell at {} ({})", title(mark).getString(), man.blockPosition().toShortString(),
                killer == null ? event.getSource().getMsgId() : killer.getName().getString());
    }

    /**
     * His trophy, into what he drops: once, and only to a player or a colony guard. The mark on the
     * man and the line in the saved data both say whether it has dropped; either is enough to stop a
     * second one.
     */
    public static void onDrops(final LivingDropsEvent event) {
        final LivingEntity man = event.getEntity();
        if (man.level().isClientSide() || !isCaptain(man) || !(man.level() instanceof ServerLevel level)) {
            return;
        }
        final DamageSource source = event.getSource();
        final CompoundTag mark = man.getPersistentData().getCompound(TAG);
        // a server that has turned mob loot off has turned this off too
        if (mark.getBoolean(TAG_TROPHY) || source.is(DamageSourceKeys.DESPAWN)
                || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) {
            return;
        }
        final Captains book = of(level);
        final CompoundTag record = book.records.get(key(level, mark.getInt(TAG_COLONY), mark.getInt(TAG_EVENT)));
        if (record != null && record.getBoolean(TAG_TROPHY)) {
            return;
        }
        final Entity killer = killer(man, source);
        if (!(killer instanceof Player) && !guard(killer)) {
            return;
        }
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(mark.getInt(TAG_COLONY), level);
        final ItemStack trophy = trophy(level, man, mark, colony);
        final ItemEntity drop = new ItemEntity(level, man.getX(), man.getY() + 0.5, man.getZ(), trophy);
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop);
        mark.putBoolean(TAG_TROPHY, true);
        if (record != null) {
            record.putBoolean(TAG_TROPHY, true);
            book.setDirty();
        }
        Warfare.LOGGER.info("[captain] {} dropped his trophy ({}) to {}", title(mark).getString(),
                WarConfig.captainTrophy(), killer.getName().getString());
    }

    /** Who killed him: the source's entity, or failing it whoever gets the credit. */
    private static Entity killer(final LivingEntity man, final DamageSource source) {
        final Entity direct = source.getEntity();
        return direct != null ? direct : man.getKillCredit();
    }

    /** A colony's guard - any colony's, this mod's War Room soldiers included. */
    private static boolean guard(final Entity entity) {
        if (!(entity instanceof AbstractEntityCitizen citizen)) {
            return false;
        }
        try {
            final IJob<?> job = citizen.getCitizenData() == null ? null : citizen.getCitizenData().getJob();
            return job != null && job.isGuard();
        } catch (final Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the trophy

    private static ItemStack trophy(final ServerLevel level, final LivingEntity man, final CompoundTag mark, final IColony colony) {
        final Raid raid = Raid.of(mark.getString(TAG_RAID));
        final Component title = title(mark);
        final ItemStack weapon = man.getMainHandItem();
        final ItemStack stack;
        if (WarConfig.TROPHY_WEAPON.equals(WarConfig.captainTrophy()) && !weapon.isEmpty()) {
            stack = weapon.copyWithCount(1);
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable(KEY_WEAPON, title)
                    .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GOLD)));
        } else {
            stack = banner(level, raid, mark);
            stack.set(DataComponents.CUSTOM_NAME, Component.translatable(KEY_BANNER, title)
                    .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GOLD)));
        }
        final String colonyName = colony == null ? "?" : colony.getName();
        final int day = colony == null ? 0 : colony.getDay();
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable(KEY_TROPHY_RAID, raid.display(mark.getString(TAG_OF)), colonyName)
                        .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)),
                Component.translatable(KEY_TROPHY_DAY, day)
                        .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)))));
        return stack;
    }

    /**
     * A banner in the raid's colours. A colony's warband carries its own colony's flag on its own
     * colour; MineColonies' raiders and a kingdom's men get a design each.
     */
    private static ItemStack banner(final ServerLevel level, final Raid raid, final CompoundTag mark) {
        final HolderGetter<BannerPattern> patterns = level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        final BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        DyeColor base;
        switch (raid) {
            case BARBARIAN -> {
                base = DyeColor.BROWN;
                layer(layers, patterns, BannerPatterns.CROSS, DyeColor.RED);
                layer(layers, patterns, BannerPatterns.BORDER, DyeColor.BLACK);
            }
            case PIRATE -> {
                base = DyeColor.BLACK;
                layer(layers, patterns, BannerPatterns.SKULL, DyeColor.WHITE);
            }
            case DROWNED -> {
                base = DyeColor.CYAN;
                layer(layers, patterns, BannerPatterns.GRADIENT_UP, DyeColor.BLUE);
                layer(layers, patterns, BannerPatterns.SKULL, DyeColor.BLACK);
            }
            case EGYPTIAN -> {
                base = DyeColor.YELLOW;
                layer(layers, patterns, BannerPatterns.STRIPE_BOTTOM, DyeColor.BLUE);
                layer(layers, patterns, BannerPatterns.RHOMBUS_MIDDLE, DyeColor.BLUE);
            }
            case NORSE -> {
                base = DyeColor.RED;
                layer(layers, patterns, BannerPatterns.RHOMBUS_MIDDLE, DyeColor.BLACK);
                layer(layers, patterns, BannerPatterns.BORDER, DyeColor.BLACK);
            }
            case AMAZON -> {
                base = DyeColor.GREEN;
                layer(layers, patterns, BannerPatterns.TRIANGLES_BOTTOM, DyeColor.LIME);
                layer(layers, patterns, BannerPatterns.CIRCLE_MIDDLE, DyeColor.YELLOW);
            }
            case WARBAND -> {
                final IColony attacker = attackerColony(level, mark.getInt(TAG_ATTACKER));
                base = attacker == null ? DyeColor.RED : dye(attacker.getTeamColonyColor());
                final BannerPatternLayers flag = attacker == null ? null : attacker.getColonyFlag();
                if (flag != null && !flag.layers().isEmpty()) {
                    layers.addAll(flag);
                } else {
                    layer(layers, patterns, BannerPatterns.BORDER, DyeColor.BLACK);
                }
            }
            case ARMY -> {
                base = DyeColor.PURPLE;
                layer(layers, patterns, BannerPatterns.BORDER, DyeColor.YELLOW);
                layer(layers, patterns, BannerPatterns.CIRCLE_MIDDLE, DyeColor.YELLOW);
            }
            default -> {
                base = DyeColor.GRAY;
                layer(layers, patterns, BannerPatterns.SKULL, DyeColor.BLACK);
            }
        }
        final ItemStack stack = new ItemStack(BannerBlock.byColor(base).asItem());
        stack.set(DataComponents.BANNER_PATTERNS, layers.build());
        return stack;
    }

    private static void layer(final BannerPatternLayers.Builder layers, final HolderGetter<BannerPattern> patterns,
                              final ResourceKey<BannerPattern> pattern, final DyeColor colour) {
        patterns.get(pattern).ifPresent(holder -> layers.add(holder, colour));
    }

    private static IColony attackerColony(final ServerLevel level, final int id) {
        if (id <= 0) {
            return null;
        }
        for (final ServerLevel each : level.getServer().getAllLevels()) {
            final IColony colony = IColonyManager.getInstance().getColonyByWorld(id, each);
            if (colony != null) {
                return colony;
            }
        }
        return null;
    }

    private static DyeColor dye(final ChatFormatting colour) {
        if (colour == null) {
            return DyeColor.WHITE;
        }
        return switch (colour) {
            case BLACK -> DyeColor.BLACK;
            case DARK_BLUE -> DyeColor.BLUE;
            case DARK_GREEN -> DyeColor.GREEN;
            case DARK_AQUA -> DyeColor.CYAN;
            case DARK_RED, RED -> DyeColor.RED;
            case DARK_PURPLE -> DyeColor.PURPLE;
            case GOLD -> DyeColor.ORANGE;
            case GRAY -> DyeColor.LIGHT_GRAY;
            case DARK_GRAY -> DyeColor.GRAY;
            case BLUE, AQUA -> DyeColor.LIGHT_BLUE;
            case GREEN -> DyeColor.LIME;
            case LIGHT_PURPLE -> DyeColor.MAGENTA;
            case YELLOW -> DyeColor.YELLOW;
            default -> DyeColor.WHITE;
        };
    }

    // ------------------------------------------------------------------ telling

    private static void tell(final IColony colony, final Component line, final MessageUtils.MessagePriority priority) {
        try {
            MessageUtils.format(line).withPriority(priority).sendTo(colony).forAllPlayers();
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[captain] could not tell {}: {}", colony.getName(), t.toString());
        }
    }

    // ------------------------------------------------------------------ saving

    private static Captains read(final CompoundTag tag, final HolderLookup.Provider provider) {
        final Captains out = new Captains();
        final CompoundTag records = tag.getCompound(TAG_RECORDS);
        for (final String key : records.getAllKeys()) {
            out.records.put(key, records.getCompound(key));
        }
        return out;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        final CompoundTag records = new CompoundTag();
        for (final Map.Entry<String, CompoundTag> e : this.records.entrySet()) {
            records.put(e.getKey(), e.getValue());
        }
        tag.put(TAG_RECORDS, records);
        return tag;
    }
}
