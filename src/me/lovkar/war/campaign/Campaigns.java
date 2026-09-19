package me.lovkar.war.campaign;

import com.minecolonies.api.colony.ColonyState;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.managers.interfaces.ITravellingManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.events.raid.barbarianEvent.Horde;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import com.minecolonies.core.entity.pathfinding.pathjobs.PathJobRaiderPathing;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.BuildingWarRoom;
import me.lovkar.war.colony.Standing;
import me.lovkar.war.compat.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import com.minecolonies.api.entity.citizen.Skill;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The warbands that are out, and everything that happens to them.
 *
 * <p>A campaign is the thief's expedition with an army in it. The War Room's guards leave through
 * MineColonies' own {@code TravellingManager} - the mechanism the Nether Worker uses, which means
 * no invisible guard stands in a corner, no AI state has to be owned, and the colony itself knows
 * not to respawn a man who is away. A clock says when they arrive; the battle is either a real raid
 * at the defender's town or, if nobody is there to see it, a number; the clock says when they are
 * home; and the outcome is applied when they walk back in: plunder in the warehouse, or wounds.</p>
 *
 * <p>Everything here is one SavedData on the overworld, like {@link Standing}: the campaigns, the
 * war log, and each colony's weariness.</p>
 */
public class Campaigns extends SavedData {
    private static final String NAME = "colonies_at_war_campaigns";
    private static final String TAG_CAMPAIGNS = "campaigns";
    private static final String TAG_NEXT = "next";
    private static final String TAG_WEARY = "weariness";
    private static final String TAG_WEARY_DAY = "wearinessDay";

    /** Campaigns already over are kept this long for {@code /war campaigns}, then dropped. */
    private static final int KEEP_DONE = 6;
    /** How often the clock is looked at, in ticks. */
    private static final int EVERY = 20;
    /** A siege that has not reported by then is called off as a number. */
    private static final long SIEGE_GRACE = 24000L;
    /** Weariness above which the War Room will not muster. */
    public static final int TOO_WEARY = 60;
    private static final int WEARY_MARCHED = 15;
    private static final int WEARY_ATTACKED = 10;
    private static final int WEARY_COOLS = 3;
    public static final String EXPEDITION = "expedition";
    public static final String CONVOY = "convoy";
    /** What one sellsword is worth in a fight: a seasoned man, paid to be there. */
    public static final int SELLSWORD_WORTH = 6;

    public enum Phase {
        MARCHING, BESIEGING, RETURNING, DONE
    }

    /** One warband, out. */
    public static final class Campaign {
        public long id;
        public int attacker;
        /** The colony marched on, or 0 when the target is a place and not a colony. */
        public int defender;
        /** "colony" or "kingdom" - what {@link #target} is. */
        public String kind = "colony";
        /** Where the target stands: the colony's or the kingdom's centre. */
        public BlockPos target = BlockPos.ZERO;
        public String attackerName = "";
        public String defenderName = "";
        public String dimension = Level.OVERWORLD.location().toString();
        public Phase phase = Phase.MARCHING;
        public int[] guards = new int[0];
        public BlockPos warRoom = BlockPos.ZERO;
        public int attack;
        public boolean hungry;
        public String goal = "";
        public long departed;
        public long arrive;
        public long home;
        public int eventId;
        public long siegeUntil;
        public BlockPos approach = BlockPos.ZERO;
        /** Whether it came to a fight at all: a recalled or turned-back warband is not wounded. */
        public boolean fought;
        public boolean won;
        public float margin;
        public String report = "";
        public List<ItemStack> plunder = new ArrayList<>();
        // an expedition: the biome it went to, its family, how hard, who led it, what they killed
        public String biome = "";
        public String family = "";
        public int difficulty;
        public int explorer;
        public String bestiary = "";
        // sellswords hired for this campaign: not citizens, counted in the attack, gone afterwards
        public int mercenaries;
        // a convoy: the courier who drives it (0 when the escort carries it), and the goods in `plunder`
        public int courier;

        public boolean onKingdom() {
            return "kingdom".equals(kind);
        }

        /** An expedition: a march with a biome at the far end instead of a gate. */
        public boolean onExpedition() {
            return EXPEDITION.equals(kind);
        }

        /** A convoy: goods on the road to a friend, with an escort. */
        public boolean onConvoy() {
            return CONVOY.equals(kind);
        }

        /** Everyone who went: the guards, and the explorer or the courier when there is one. */
        public int[] everyone() {
            final int extra = explorer > 0 ? explorer : courier > 0 ? courier : 0;
            if (extra <= 0) {
                return guards;
            }
            final int[] all = java.util.Arrays.copyOf(guards, guards.length + 1);
            all[guards.length] = extra;
            return all;
        }

        /** Guards and sellswords together: what stands on the field. */
        public int soldiers() {
            return guards.length + mercenaries;
        }

        /** A camp or a village: a place that is nobody's, changed for good or only robbed. */
        public boolean onPlace() {
            return Places.CAMP.equals(kind) || Places.VILLAGE.equals(kind);
        }

        public String describe() {
            final String men = guards.length + (guards.length == 1 ? " guard" : " guards")
                    + (mercenaries > 0 ? " and " + mercenaries + (mercenaries == 1 ? " sellsword" : " sellswords") : "");
            if (onConvoy()) {
                return switch (phase) {
                    case MARCHING -> attackerName + "'s convoy is on the road to " + defenderName + " with " + men;
                    case BESIEGING, RETURNING -> attackerName + "'s convoy is coming home from " + defenderName;
                    case DONE -> attackerName + "'s convoy to " + defenderName + ": " + report;
                };
            }
            if (onExpedition()) {
                final String party = guards.length == 0 ? "alone" : "with " + men;
                return switch (phase) {
                    case MARCHING -> attackerName + "'s explorer sets out for " + defenderName + " " + party
                            + " (" + Expeditions.difficultyName(difficulty) + ")";
                    case BESIEGING -> attackerName + "'s explorer is out in " + defenderName + " " + party;
                    case RETURNING -> attackerName + "'s expedition " + (won ? "comes home from " : "limps home from ") + defenderName;
                    case DONE -> attackerName + "'s expedition to " + defenderName + ": " + report;
                };
            }
            return switch (phase) {
                case MARCHING -> attackerName + " marches on " + defenderName + " with " + men
                        + (hungry ? " (hungry)" : "");
                case BESIEGING -> attackerName + " is at the walls of " + defenderName + " with " + men;
                case RETURNING -> attackerName + "'s warband " + (won ? "comes home victorious from " : "limps home from ")
                        + defenderName;
                case DONE -> attackerName + " against " + defenderName + ": " + report;
            };
        }
    }

    /**
     * A kingdom's answer: its men at the colony's gate, some days after the colony's were at its.
     *
     * <p>A Waking World kingdom has no War Room and no clock of its own, so this is the mod's:
     * a debt written down when a warband attacks a kingdom, paid as a real raid at the colony the
     * next time somebody is there to fight it, forgotten if nobody is for a week.</p>
     */
    public static final class Reprisal {
        public long id;
        public BlockPos kingdom = BlockPos.ZERO;
        public String kingdomName = "";
        public int colony;
        public String colonyName = "";
        public String dimension = Level.OVERWORLD.location().toString();
        public long due;
        public long expires;
        public int size;
        public int strength;
        public int eventId;
        public boolean sacked;

        public String describe() {
            return eventId != 0 ? kingdomName + "'s men are at the walls of " + colonyName + " - " + size + " soldiers"
                    : kingdomName + " is coming for " + colonyName + (sacked ? " - the sack will be answered" : "")
                    + " - " + size + " soldiers";
        }
    }

    /**
     * A man taken at the end of a lost fight. Nobody dies at skirmish depth; the men who fell are
     * held, by the colony or the kingdom that beat them, until a ransom is paid or they are let go.
     */
    public static final class Captive {
        public long id;
        public int colony;
        public String colonyName = "";
        public int guard;
        public String guardName = "";
        /** The colony holding him, or 0 for a kingdom. */
        public int holder;
        public String holderName = "";
        public BlockPos holderAt = BlockPos.ZERO;
        public String dimension = Level.OVERWORLD.location().toString();
        public long until;

        public String describe() {
            return guardName + " held by " + holderName;
        }
    }

    /** Sellswords waiting at a War Room for the next warband. */
    public static final class Sellswords {
        public int count;
        public long until;
    }

    /** A town overrun with the war goal 'conquer': it pays its lord out of its warehouse every day. */
    public static final class Vassalage {
        public int lord;
        public int vassal;
        public String lordName = "";
        public String vassalName = "";
        public String dimension = Level.OVERWORLD.location().toString();
        public long until;
        public long lastDay;

        public String describe(final long now) {
            final long days = Math.max(0, (until - now + 23999) / 24000L);
            return vassalName + " is the vassal of " + lordName + " - " + days + (days == 1 ? " day" : " days") + " to go";
        }
    }

    private final List<Campaign> campaigns = new ArrayList<>();
    private final List<Reprisal> reprisals = new ArrayList<>();
    private final List<Captive> captives = new ArrayList<>();
    private final Map<Integer, Sellswords> sellswords = new HashMap<>();
    private final List<Vassalage> vassalages = new ArrayList<>();
    private long next = 1;
    private final Map<Integer, Integer> weariness = new HashMap<>();
    private long wearinessDay;
    private final WarLog log = new WarLog();

    public static final SavedData.Factory<Campaigns> FACTORY = new SavedData.Factory<>(Campaigns::new, Campaigns::read);

    public static Campaigns of(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static Campaigns of(final ServerLevel level) {
        return of(level.getServer());
    }

    // ------------------------------------------------------------------ reading

    public static List<Campaign> involving(final ServerLevel level, final int colony) {
        final List<Campaign> out = new ArrayList<>();
        for (final Campaign c : of(level).campaigns) {
            if (c.attacker == colony || c.defender == colony) {
                out.add(c);
            }
        }
        return out;
    }

    /** The one warband this colony has out, or null. One at a time: a War Room plans one war. */
    public static Campaign outFrom(final ServerLevel level, final int colony) {
        for (final Campaign c : of(level).campaigns) {
            if (c.attacker == colony && c.phase != Phase.DONE && !c.onExpedition()) {
                return c;
            }
        }
        return null;
    }

    /** The expedition this colony has out, or null. One explorer, one trip at a time. */
    public static Campaign expeditionOut(final ServerLevel level, final int colony) {
        for (final Campaign c : of(level).campaigns) {
            if (c.attacker == colony && c.phase != Phase.DONE && c.onExpedition()) {
                return c;
            }
        }
        return null;
    }

    /** The convoy this colony has on the road, or null. One at a time. */
    public static Campaign convoyOut(final ServerLevel level, final int colony) {
        for (final Campaign c : of(level).campaigns) {
            if (c.attacker == colony && c.phase != Phase.DONE && c.onConvoy()) {
                return c;
            }
        }
        return null;
    }

    /** Sellswords waiting at this colony's War Room. */
    public static int sellswordsWaiting(final ServerLevel level, final int colony) {
        final Sellswords s = of(level).sellswords.get(colony);
        return s == null || level.getServer().overworld().getGameTime() > s.until ? 0 : s.count;
    }

    /** How many sellswords a War Room may keep, by its level. */
    public static int sellswordCap(final IColony mine) {
        final IBuilding room = warRoom(mine);
        return room == null ? 0 : room.getBuildingLevel() * WarConfig.mercenariesPerLevel();
    }

    /** Every vassalage this colony is part of, as lord or vassal. */
    public static List<Vassalage> vassalagesOf(final ServerLevel level, final int colony) {
        final List<Vassalage> out = new ArrayList<>();
        for (final Vassalage v : of(level).vassalages) {
            if (v.lord == colony || v.vassal == colony) {
                out.add(v);
            }
        }
        return out;
    }

    /** This colony's men held by somebody, oldest first. */
    public static List<Captive> captivesOf(final ServerLevel level, final int colony) {
        final List<Captive> out = new ArrayList<>();
        for (final Captive k : of(level).captives) {
            if (k.colony == colony) {
                out.add(k);
            }
        }
        return out;
    }

    /** Whether this guard of this colony is being held. */
    private boolean held(final int colony, final int guard) {
        for (final Captive k : captives) {
            if (k.colony == colony && k.guard == guard) {
                return true;
            }
        }
        return false;
    }

    /** The kingdoms' answers still owed to this colony, or on their way. */
    public static List<Reprisal> reprisalsFor(final ServerLevel level, final int colony) {
        final List<Reprisal> out = new ArrayList<>();
        for (final Reprisal r : of(level).reprisals) {
            if (r.colony == colony) {
                out.add(r);
            }
        }
        return out;
    }

    public static int weariness(final ServerLevel level, final int colony) {
        return of(level).weariness.getOrDefault(colony, 0);
    }

    public static WarLog log(final ServerLevel level) {
        return of(level).log;
    }

    public static int siegeNights() {
        return WarConfig.siegeNights();
    }

    // ------------------------------------------------------------------ mustering

    /** Why a warband cannot go, or null when it can. */
    public static String cannotMarch(final ServerLevel level, final IColony mine, final int theirs) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (theirs == mine.getID()) {
            return "not against yourselves";
        }
        if (Standing.lordOf(mine) == theirs) {
            return "the men will not march on their own lord";
        }
        if (!Standing.atWar(mine, theirs)) {
            return "not at war with colony " + theirs + " - declare it first, by hand";
        }
        final IColony them = IColonyManager.getInstance().getColonyByDimension(theirs, mine.getDimension());
        if (them == null) {
            return "colony " + theirs + " is not in this world";
        }
        if (warRoom(mine) == null) {
            return "no War Room standing - the men have nobody to muster them";
        }
        if (outFrom(level, mine.getID()) != null) {
            return "a warband is already out";
        }
        if (weariness(level, mine.getID()) >= TOO_WEARY) {
            return "the men will not march again so soon (weariness " + weariness(level, mine.getID()) + ")";
        }
        return null;
    }

    public static IBuilding warRoom(final IColony colony) {
        try {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                if (building instanceof BuildingWarRoom && building.getBuildingLevel() > 0) {
                    return building;
                }
            }
        } catch (final Throwable ignored) {
            // no buildings to read
        }
        return null;
    }

    /** Why a warband cannot march on a kingdom, or null when it can. No declaration: a kingdom is nobody's. */
    public static String cannotMarchOn(final ServerLevel level, final IColony mine, final Object kingdom) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!WarConfig.marchOnKingdoms()) {
            return "marching on kingdoms is off in the config";
        }
        if (kingdom == null) {
            return "no kingdom stands there";
        }
        if (warRoom(mine) == null) {
            return "no War Room standing - the men have nobody to muster them";
        }
        if (outFrom(level, mine.getID()) != null) {
            return "a warband is already out";
        }
        if (weariness(level, mine.getID()) >= TOO_WEARY) {
            return "the men will not march again so soon (weariness " + weariness(level, mine.getID()) + ")";
        }
        return null;
    }

    /** Why a warband cannot march on a camp or a village, or null when it can. */
    public static String cannotMarchOnPlace(final ServerLevel level, final IColony mine, final Places.Place place) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!WarConfig.marchOnCamps()) {
            return "marching on camps and villages is off in the config";
        }
        if (place == null) {
            return "no camp or village stands there";
        }
        if (warRoom(mine) == null) {
            return "no War Room standing - the men have nobody to muster them";
        }
        if (outFrom(level, mine.getID()) != null) {
            return "a warband is already out";
        }
        if (weariness(level, mine.getID()) >= TOO_WEARY) {
            return "the men will not march again so soon (weariness " + weariness(level, mine.getID()) + ")";
        }
        return null;
    }

    /** The warband leaves for a raider camp or a village. */
    public static Campaign marchOnPlace(final ServerLevel level, final IColony mine, final Places.Place place, final int wanted,
                                        final StringBuilder why) {
        final String no = cannotMarchOnPlace(level, mine, place);
        if (no != null) {
            why.append(no);
            return null;
        }
        return depart(level, mine, wanted, why, place.kind(), 0, place.title(), place.pos(), "plunder");
    }

    /**
     * The warband leaves for another colony.
     *
     * @param wanted how many guards, or 0 for everyone at home
     * @return the campaign, or null with the reason in {@code why}
     */
    public static Campaign march(final ServerLevel level, final IColony mine, final int theirs, final int wanted,
                                 final StringBuilder why) {
        final String no = cannotMarch(level, mine, theirs);
        if (no != null) {
            why.append(no);
            return null;
        }
        final IColony them = IColonyManager.getInstance().getColonyByDimension(theirs, mine.getDimension());
        return depart(level, mine, wanted, why, "colony", theirs, them.getName(), them.getCenter(), Standing.goal(mine, theirs));
    }

    /** The warband leaves for a Waking World kingdom (an opaque kingdom from {@link Kingdoms}). */
    public static Campaign marchOn(final ServerLevel level, final IColony mine, final Object kingdom, final int wanted,
                                   final StringBuilder why) {
        final String no = cannotMarchOn(level, mine, kingdom);
        if (no != null) {
            why.append(no);
            return null;
        }
        return depart(level, mine, wanted, why, "kingdom", 0, "the kingdom of " + Kingdoms.name(kingdom),
                Kingdoms.centre(kingdom), "plunder");
    }

    /** Whether this citizen is at home, on his feet and in loaded ground - fit to leave. */
    private static boolean fit(final ICitizenData citizen, final ITravellingManager travel) {
        if (citizen == null || travel.isTravelling(citizen) || Standing.onCampaign(citizen)) {
            return false;
        }
        final AbstractEntityCitizen entity = citizen.getEntity().orElse(null);
        return entity != null && entity.isAlive() && WorldUtil.isEntityBlockLoaded(entity.level(), entity.blockPosition());
    }

    /** The War Room's guards who are fit to leave, up to {@code wanted} (0 = all of them). */
    private static List<ICitizenData> fitGuards(final IBuilding room, final ITravellingManager travel, final int wanted) {
        final List<ICitizenData> warband = new ArrayList<>();
        for (final ICitizenData guard : ((BuildingWarRoom) room).getAllAssignedCitizen()) {
            if (!Standing.isGuard(guard) || !fit(guard, travel)) {
                continue;
            }
            warband.add(guard);
            if (wanted > 0 && warband.size() >= wanted) {
                break;
            }
        }
        return warband;
    }

    /** How many of the War Room's guards could leave right now. */
    public static int guardsAtHome(final IColony mine) {
        final IBuilding room = warRoom(mine);
        if (room == null) {
            return 0;
        }
        try {
            return fitGuards(room, mine.getTravellingManager(), 0).size();
        } catch (final Throwable t) {
            return 0;
        }
    }

    /** Out of the world, the Nether Worker's way, for at most this long before MineColonies brings them back itself. */
    private static void sendOff(final IColony mine, final IBuilding room, final List<ICitizenData> party, final int forTicks) {
        final ITravellingManager travel = mine.getTravellingManager();
        for (final ICitizenData one : party) {
            Standing.setOnCampaign(one, true);
            travel.startTravellingTo(one, room.getPosition(), forTicks);
            one.getEntity().ifPresent(e -> e.remove(Entity.RemovalReason.DISCARDED));
        }
    }

    private static Campaign depart(final ServerLevel level, final IColony mine, final int wanted, final StringBuilder why,
                                   final String kind, final int defender, final String defenderName, final BlockPos target,
                                   final String goal) {
        final IBuilding room = warRoom(mine);
        final ITravellingManager travel = mine.getTravellingManager();

        // who is at home, on their feet and loaded
        final List<ICitizenData> warband;
        try {
            warband = fitGuards(room, travel, wanted);
        } catch (final Throwable t) {
            why.append("could not read the War Room's guards: ").append(t);
            return null;
        }
        if (warband.isEmpty()) {
            why.append("nobody at the War Room is fit to march");
            return null;
        }

        // supply: two rations a man, out of the warehouse, or they go hungry
        final int rations = warband.size() * WarConfig.rationsPerGuard();
        final int fed = rations <= 0 ? rations : takeFood(mine, rations);
        final boolean hungry = fed < rations;

        final Campaigns data = of(level);
        final Campaign c = new Campaign();
        c.id = data.next++;
        c.attacker = mine.getID();
        c.defender = defender;
        c.kind = kind;
        c.target = target;
        c.attackerName = mine.getName();
        c.defenderName = defenderName;
        c.dimension = mine.getDimension().location().toString();
        c.warRoom = room.getPosition();
        c.hungry = hungry;
        c.attack = Battle.attack(warband, room, hungry);
        c.goal = goal == null ? "" : goal;
        final long now = level.getServer().overworld().getGameTime();
        // the sellswords waiting at the War Room go too, and are paid off by going
        final Sellswords hired = data.sellswords.remove(mine.getID());
        if (hired != null && now <= hired.until && hired.count > 0) {
            c.mercenaries = hired.count;
            c.attack += hired.count * SELLSWORD_WORTH;
        }
        final int travelTicks = travelTicks(mine.getCenter(), target);
        c.departed = now;
        c.arrive = now + travelTicks;
        c.home = 0;
        c.guards = new int[warband.size()];
        for (int i = 0; i < warband.size(); i++) {
            c.guards[i] = warband.get(i).getId();
        }
        // longer than the campaign can possibly take: we bring them back ourselves, and if this
        // data is ever lost MineColonies brings them back on its own after this many ticks
        sendOff(mine, room, warband, travelTicks * 2 + siegeNights() * 24000 + 48000);
        data.campaigns.add(c);
        data.weary(mine.getID(), WEARY_MARCHED);
        data.setDirty();
        touch(mine);

        final String swords = c.mercenaries > 0 ? " and " + c.mercenaries + (c.mercenaries == 1 ? " sellsword" : " sellswords") : "";
        final String line = mine.getName() + " marched on " + defenderName + " with " + warband.size()
                + (warband.size() == 1 ? " guard" : " guards") + swords + (hungry ? ", short of food" : "")
                + " - strength " + c.attack + (c.goal.isEmpty() ? "" : ", goal " + c.goal);
        data.log.add(mine.getWorld(), c.attacker, c.defender, line);
        tell(mine, "The warband has marched on " + defenderName + ": " + warband.size()
                + (warband.size() == 1 ? " guard" : " guards") + swords + (hungry ? ", hungry" : "") + ". They will be there in about "
                + minutes(travelTicks) + ".", MessageUtils.MessagePriority.IMPORTANT);
        Warfare.LOGGER.info("[campaign] {}", line);
        return c;
    }

    // ------------------------------------------------------------------ expeditions

    /** Why the War Room cannot send an expedition, or null when it can. */
    public static String cannotExplore(final ServerLevel level, final IColony mine) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!WarConfig.expeditions()) {
            return "expeditions are off in this world's settings";
        }
        final IBuilding room = warRoom(mine);
        if (room == null) {
            return "no War Room standing - there is no table to plan it at";
        }
        final ICitizenData explorer = BuildingWarRoom.explorer(room);
        if (explorer == null) {
            return "the War Room has no Explorer - hire one";
        }
        if (expeditionOut(level, mine.getID()) != null) {
            return "the Explorer is already out";
        }
        if (!fit(explorer, mine.getTravellingManager())) {
            return "the Explorer is not at home";
        }
        return null;
    }

    /**
     * The Explorer sets out for a biome with an escort.
     *
     * @param what       a family ("jungle") or an exact biome ("minecraft:cherry_grove")
     * @param difficulty 1..4
     * @param wanted     guards to take along; 0 for none
     */
    public static Campaign explore(final ServerLevel level, final IColony mine, final String what, final int difficulty,
                                   final int wanted, final StringBuilder why) {
        final String no = cannotExplore(level, mine);
        if (no != null) {
            why.append(no);
            return null;
        }
        final Expeditions.Destination where = Expeditions.find(level, mine.getCenter(), what);
        if (where == null) {
            why.append("no ").append(what).append(" within ").append(WarConfig.expeditionRange()).append(" blocks");
            return null;
        }
        final IBuilding room = warRoom(mine);
        final ICitizenData explorer = BuildingWarRoom.explorer(room);
        final List<ICitizenData> party = new ArrayList<>();
        try {
            if (wanted > 0) {
                party.addAll(fitGuards(room, mine.getTravellingManager(), wanted));
            }
        } catch (final Throwable t) {
            why.append("could not read the War Room's guards: ").append(t);
            return null;
        }
        final int d = Math.max(1, Math.min(4, difficulty));
        final int rations = (party.size() + 1) * WarConfig.rationsPerGuard();
        final int fed = rations <= 0 ? rations : takeFood(mine, rations);
        final boolean hungry = fed < rations;

        final Campaigns data = of(level);
        final Campaign c = new Campaign();
        c.id = data.next++;
        c.attacker = mine.getID();
        c.defender = 0;
        c.kind = EXPEDITION;
        c.target = where.pos();
        c.biome = where.biome().toString();
        c.family = where.family().id();
        c.difficulty = d;
        c.explorer = explorer.getId();
        c.attackerName = mine.getName();
        c.defenderName = where.title();
        c.dimension = mine.getDimension().location().toString();
        c.warRoom = room.getPosition();
        c.hungry = hungry;
        c.attack = Battle.attack(party, room, hungry);
        c.goal = Expeditions.difficultyName(d);
        final long now = level.getServer().overworld().getGameTime();
        final int travelTicks = travelTicks(mine.getCenter(), where.pos());
        c.departed = now;
        c.arrive = now + travelTicks;
        c.guards = new int[party.size()];
        for (int i = 0; i < party.size(); i++) {
            c.guards[i] = party.get(i).getId();
        }
        final List<ICitizenData> everyone = new ArrayList<>(party);
        everyone.add(explorer);
        sendOff(mine, room, everyone, travelTicks * 2 + Expeditions.exploreTicks(d) + 48000);
        data.campaigns.add(c);
        data.setDirty();
        touch(mine);

        final String escort = party.isEmpty() ? "alone" : "with " + party.size() + (party.size() == 1 ? " guard" : " guards");
        final String line = mine.getName() + "'s explorer " + explorer.getName() + " set out for " + where.describe() + " "
                + escort + " (" + Expeditions.difficultyName(d) + ")" + (hungry ? ", short of food" : "");
        data.log.add(mine.getWorld(), c.attacker, 0, line);
        tell(mine, explorer.getName() + " has set out for " + where.title() + " " + escort + (hungry ? ", hungry" : "")
                + ". About " + minutes(travelTicks) + " there, " + minutes(Expeditions.exploreTicks(d)) + " exploring, and "
                + minutes(travelTicks) + " back.", MessageUtils.MessagePriority.IMPORTANT);
        Warfare.LOGGER.info("[expedition] {}", line);
        return c;
    }

    /** They are there: the time out in the country starts. */
    private void arriveAtBiome(final ServerLevel level, final Campaign c, final long now) {
        c.phase = Phase.BESIEGING;
        c.siegeUntil = now + Expeditions.exploreTicks(c.difficulty);
        log.add(level, c.attacker, 0, c.attackerName + "'s expedition reached " + c.defenderName);
        tell(colony(level, c.attacker), "The expedition has reached " + c.defenderName + " and is looking around.",
                MessageUtils.MessagePriority.IMPORTANT);
    }

    /** The time is up: the country is rolled, the beasts are counted, and they turn for home. */
    private void conclude(final ServerLevel level, final Campaign c, final long now) {
        final IColony mine = colony(level, c.attacker);
        int skill = 0;
        if (mine != null) {
            final ICitizenData explorer = mine.getCitizenManager().getCivilian(c.explorer);
            if (explorer != null) {
                try {
                    skill = explorer.getCitizenSkillHandler().getLevel(Skill.Adaptability);
                } catch (final Throwable ignored) {
                    // a recruit
                }
            }
        }
        final Expeditions.Family family = Expeditions.family(c.family) == null ? Expeditions.WILDS : Expeditions.family(c.family);
        final ResourceLocation biome = ResourceLocation.tryParse(c.biome) == null
                ? ResourceLocation.withDefaultNamespace("plains") : ResourceLocation.parse(c.biome);
        final BlockPos from = mine == null ? c.warRoom : mine.getCenter();
        final Expeditions.Destination where = new Expeditions.Destination(family, biome, c.target,
                (int) Math.sqrt(from.distSqr(c.target)), Expeditions.direction(from, c.target));
        final Expeditions.Haul haul = Expeditions.roll(level, where, c.difficulty, c.guards.length, skill, level.getRandom());
        c.fought = true;
        c.won = haul.success();
        c.margin = haul.danger() <= 0 ? 1f : (haul.strength() + haul.luck() - haul.danger()) / (float) haul.danger();
        c.report = haul.report();
        c.bestiary = haul.bestiary();
        c.plunder = new ArrayList<>(haul.items());
        log.add(level, c.attacker, 0, c.attackerName + "'s expedition " + c.report
                + (c.plunder.isEmpty() ? "" : "; brings " + Plunder.describe(c.plunder)));
        Warfare.LOGGER.info("[expedition] {}'s expedition {}", c.attackerName, c.report);
        turnHome(level, c, now, c.target, c.warRoom);
    }

    /** Take up to {@code wanted} food items out of the colony's warehouses. */
    private static int takeFood(final IColony colony, final int wanted) {
        int taken = 0;
        try {
            for (final IWareHouse warehouse : colony.getServerBuildingManager().getWareHouses()) {
                for (final IItemHandler handler : InventoryUtils.getItemHandlersFromProvider(warehouse)) {
                    for (int slot = 0; slot < handler.getSlots() && taken < wanted; slot++) {
                        final ItemStack stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty() || stack.getFoodProperties(null) == null) {
                            continue;
                        }
                        final ItemStack out = handler.extractItem(slot, wanted - taken, false);
                        taken += out.getCount();
                    }
                    if (taken >= wanted) {
                        return taken;
                    }
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] could not count rations: {}", t.toString());
        }
        return taken;
    }

    /** A minute per 200 blocks, three minutes floor - the thief's rule, so the two mods agree. */
    public static int travelTicks(final BlockPos from, final BlockPos to) {
        final double blocks = Math.sqrt(from.distSqr(to));
        final double minutes = Math.max(WarConfig.marchMinimumMinutes(), blocks / 200.0 * WarConfig.marchMinutesPer200());
        return (int) Math.round(minutes * 1200);
    }

    private static String minutes(final int ticks) {
        final int m = Math.max(1, Math.round(ticks / 1200f));
        return m + (m == 1 ? " minute" : " minutes");
    }

    // ------------------------------------------------------------------ the clock

    private static int ticks = 0;

    public static void onServerTick(final ServerTickEvent.Post event) {
        if (++ticks % EVERY != 0) {
            return;
        }
        final MinecraftServer server = event.getServer();
        final Campaigns data;
        try {
            data = of(server);
        } catch (final Throwable t) {
            return;
        }
        try {
            data.tick(server);
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[campaign] clock failed: {}", t.toString(), t);
        }
        if (ticks % (EVERY * 30) == 0) {
            for (final ServerLevel level : server.getAllLevels()) {
                sweep(level);
            }
        }
    }

    private void tick(final MinecraftServer server) {
        final long now = server.overworld().getGameTime();
        coolWeariness(now);
        boolean changed = false;
        int done = 0;
        for (int i = campaigns.size() - 1; i >= 0; i--) {
            final Campaign c = campaigns.get(i);
            final ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.dimension)));
            if (level == null) {
                continue;
            }
            final Phase before = c.phase;
            switch (c.phase) {
                case MARCHING -> {
                    if (now >= c.arrive) {
                        arrive(level, c, now);
                        changed = true;
                    }
                }
                case BESIEGING -> {
                    if (c.onExpedition()) {
                        if (now >= c.siegeUntil) {
                            conclude(level, c, now);
                            changed = true;
                        }
                    } else if (siegeGone(level, c) || now >= c.siegeUntil) {
                        // the raid never started, was cancelled, or has run past any sensible night
                        // (the colony went quiet mid-siege): a number, and the raid is told it is
                        // over so it cleans up rather than restarting when somebody comes back
                        final IColony them = colony(level, c.defender);
                        closeSiege(them, c.eventId);
                        Warfare.LOGGER.info("[campaign] the siege of {} ended without a report - resolved as a number", c.defenderName);
                        resolve(level, c, Battle.roll(c.attack, Battle.defence(them), level.getRandom()), now);
                        changed = true;
                    }
                }
                case RETURNING -> {
                    if (now >= c.home) {
                        arriveHome(level, c);
                        changed = true;
                    }
                }
                case DONE -> {
                    if (++done > KEEP_DONE) {
                        campaigns.remove(i);
                        changed = true;
                    }
                }
            }
            if (c.phase != before) {
                touch(colony(level, c.attacker));
            }
        }
        // sellswords who waited long enough take their pay and go
        for (final Map.Entry<Integer, Sellswords> e : new ArrayList<>(sellswords.entrySet())) {
            if (now > e.getValue().until) {
                sellswords.remove(e.getKey());
                changed = true;
                final IColony colony = colony(server.overworld(), e.getKey());
                if (colony != null) {
                    log.add(server.overworld(), e.getKey(), 0, e.getValue().count + " sellswords left " + colony.getName() + " unemployed");
                    tell(colony, "The sellswords have waited long enough: " + e.getValue().count + " of them have left with their pay.",
                            MessageUtils.MessagePriority.IMPORTANT);
                    touch(colony);
                }
            }
        }
        // vassals pay their lords once a day, and are freed when their time is up
        for (int i = vassalages.size() - 1; i >= 0; i--) {
            final Vassalage v = vassalages.get(i);
            final ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(v.dimension)));
            if (level == null) {
                continue;
            }
            final IColony lord = colony(level, v.lord);
            final IColony vassal = colony(level, v.vassal);
            if (lord == null || vassal == null || now > v.until) {
                vassalages.remove(i);
                changed = true;
                if (lord != null) {
                    Standing.freeVassal(lord, v.vassal);
                }
                log.add(level, v.lord, v.vassal, v.vassalName + " is free of " + v.lordName);
                tell(lord, v.vassalName + " has served its time and is no longer your vassal.", MessageUtils.MessagePriority.IMPORTANT);
                tell(vassal, "The vassalage under " + v.lordName + " is over. " + v.vassalName + " is its own again.", MessageUtils.MessagePriority.IMPORTANT);
                touch(lord);
                touch(vassal);
                continue;
            }
            final long day = now / 24000L;
            if (day > v.lastDay && WarConfig.tributeStacks() > 0) {
                v.lastDay = day;
                changed = true;
                final List<ItemStack> tribute = takeGoods(vassal, WarConfig.tributeStacks());
                if (tribute.isEmpty()) {
                    log.add(level, v.lord, v.vassal, v.vassalName + " had nothing to pay " + v.lordName + " today");
                    continue;
                }
                Plunder.deliver(level, lord, warRoom(lord), tribute);
                final String line = v.vassalName + " paid " + v.lordName + " its tribute: " + Plunder.describe(tribute);
                log.add(level, v.lord, v.vassal, line);
                tell(lord, "Tribute from " + v.vassalName + ": " + Plunder.describe(tribute) + ".", MessageUtils.MessagePriority.IMPORTANT);
                tell(vassal, "The tribute to " + v.lordName + " has gone out of the warehouse: " + Plunder.describe(tribute) + ".",
                        MessageUtils.MessagePriority.IMPORTANT);
                Warfare.LOGGER.info("[tribute] {}", line);
            }
        }
        final List<Captive> letGo = new ArrayList<>();
        for (final Captive k : captives) {
            if (now >= k.until) {
                letGo.add(k);
            }
        }
        if (!letGo.isEmpty()) {
            final ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(letGo.get(0).dimension)));
            if (level != null) {
                for (final Captive k : letGo) {
                    tell(colony(level, k.colony), k.holderName + " has let " + k.guardName + " go. He is home.", MessageUtils.MessagePriority.IMPORTANT);
                }
                free(level, letGo, "let go");
                changed = true;
            }
        }
        for (int i = reprisals.size() - 1; i >= 0; i--) {
            final Reprisal r = reprisals.get(i);
            final ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(r.dimension)));
            if (level == null || now < r.due) {
                continue;
            }
            final IColony colony = colony(level, r.colony);
            if (colony == null) {
                reprisals.remove(i);
                changed = true;
                continue;
            }
            if (r.eventId != 0) {
                // at the walls: gone or cancelled means it is over; past its time means it stood in a
                // quiet town too long and is called off
                final IColonyEvent event = colony.getEventManager().getEventByID(r.eventId);
                if (event == null || event.getStatus() == EventStatus.CANCELED) {
                    log.add(level, r.colony, 0, "the kingdom of " + r.kingdomName + "'s men found no ground at " + r.colonyName + " and went home");
                    reprisals.remove(i);
                    changed = true;
                } else if (now >= r.expires) {
                    closeSiege(colony, r.eventId);
                    log.add(level, r.colony, 0, "the kingdom of " + r.kingdomName + "'s men waited at " + r.colonyName + " and went home");
                    reprisals.remove(i);
                    changed = true;
                }
                continue;
            }
            if (now >= r.expires) {
                log.add(level, r.colony, 0, "the kingdom of " + r.kingdomName + " never came for " + r.colonyName);
                reprisals.remove(i);
                changed = true;
                continue;
            }
            // only when somebody is there to fight it: a raid nobody sees is not revenge
            if (WarConfig.siegeInWorld() && colony.getState() == ColonyState.ACTIVE && WorldUtil.isBlockLoaded(level, colony.getCenter())) {
                if (strike(level, r, colony, now)) {
                    changed = true;
                } else {
                    // no ground today; try again tomorrow
                    r.due = now + 24000L;
                    changed = true;
                }
            }
        }
        if (changed) {
            setDirty();
        }
    }

    private static IColony colony(final ServerLevel level, final int id) {
        return IColonyManager.getInstance().getColonyByDimension(id, level.dimension());
    }

    /** The table has changed: mark the War Room dirty so every open window is sent the new state. */
    static void touch(final IColony colony) {
        try {
            final IBuilding room = colony == null ? null : warRoom(colony);
            if (room != null) {
                room.markDirty();
            }
        } catch (final Throwable ignored) {
            // a window that is a moment stale is no harm
        }
    }

    /** The raid, if it is still standing in the Town Hall, is marked over: its next tick cleans up. */
    private static void closeSiege(final IColony them, final int eventId) {
        if (them == null || eventId == 0) {
            return;
        }
        try {
            final IColonyEvent event = them.getEventManager().getEventByID(eventId);
            if (event != null && event.getStatus() != EventStatus.DONE && event.getStatus() != EventStatus.CANCELED) {
                event.setStatus(EventStatus.DONE);
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] could not close the siege: {}", t.toString());
        }
    }

    /** True once the defender's event manager no longer has our raid. */
    private static boolean siegeGone(final ServerLevel level, final Campaign c) {
        final IColony them = colony(level, c.defender);
        if (them == null) {
            return true;
        }
        try {
            final IColonyEvent event = them.getEventManager().getEventByID(c.eventId);
            return event == null || event.getStatus() == EventStatus.CANCELED;
        } catch (final Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ arrival

    private void arrive(final ServerLevel level, final Campaign c, final long now) {
        if (c.onExpedition()) {
            arriveAtBiome(level, c, now);
            return;
        }
        if (c.onConvoy()) {
            arriveWithConvoy(level, c, now);
            return;
        }
        if (c.onKingdom()) {
            arriveAtKingdom(level, c, now);
            return;
        }
        if (c.onPlace()) {
            arriveAtPlace(level, c, now);
            return;
        }
        final IColony them = colony(level, c.defender);
        final IColony mine = colony(level, c.attacker);
        if (them == null) {
            c.won = false;
            c.margin = 0;
            c.report = "found no colony where " + c.defenderName + " stood";
            log.add(level, c.attacker, c.defender, c.attackerName + "'s warband " + c.report);
            turnHome(level, c, now, mine == null ? c.warRoom : mine.getCenter(), c.warRoom);
            return;
        }
        weary(c.defender, WEARY_ATTACKED);
        if (mine == null || !Standing.atWar(mine, c.defender)) {
            // peace was made while they marched: they turn round at the gate
            c.won = false;
            c.report = "turned back at the gate - peace had been made";
            log.add(level, c.attacker, c.defender, c.attackerName + "'s warband " + c.report);
            turnHome(level, c, now, them.getCenter(), c.warRoom);
            return;
        }
        // is anybody home to fight it? ACTIVE, not isActive(): a colony whose owner is online but
        // far away is UNLOADED, which MineColonies also calls active - and which ticks no events,
        // so a siege laid there would stand frozen until somebody walked up to it
        if (c.approach.equals(BlockPos.ZERO)) {
            c.approach = mine != null ? mine.getCenter() : them.getCenter();
        }
        final boolean watched = WarConfig.siegeInWorld() && them.getState() == ColonyState.ACTIVE
                && WorldUtil.isBlockLoaded(level, them.getCenter());
        if (watched && besiege(level, c, them, now)) {
            return;
        }
        resolve(level, c, Battle.roll(c.attack, Battle.defence(them), level.getRandom()), now);
    }

    /**
     * At a kingdom's gate there is nobody to fight it but the kingdom, so it is always a number:
     * the warband against the keep's garrison, engines and wall arcs as Waking World keeps them.
     * Won or lost, the kingdom now hates the colony's owner - its guards turn on him, its traders
     * refuse him, its king will not see him - and its standing with him falls; a sack costs more.
     */
    private void arriveAtKingdom(final ServerLevel level, final Campaign c, final long now) {
        final IColony mine = colony(level, c.attacker);
        final Object kingdom = Kingdoms.at(level, c.target);
        if (kingdom == null || mine == null) {
            c.won = false;
            c.report = kingdom == null ? "found no kingdom where " + c.defenderName + " stood" : "came home to no colony";
            log.add(level, c.attacker, 0, c.attackerName + "'s warband " + c.report);
            turnHome(level, c, now, c.target, c.warRoom);
            return;
        }
        if (c.approach.equals(BlockPos.ZERO)) {
            c.approach = mine.getCenter();
        }
        final Battle battle = Battle.roll(c.attack, Kingdoms.defence(kingdom), level.getRandom());
        c.fought = true;
        c.won = battle.won();
        c.margin = battle.margin();
        c.report = battle.report();
        final java.util.UUID owner = mine.getPermissions() == null ? null : mine.getPermissions().getOwner();
        final StringBuilder line = new StringBuilder(c.attackerName + "'s warband " + c.report + " at " + c.defenderName);
        Kingdoms.favour(level, kingdom, -WarConfig.kingdomStandingCost(), "war");
        Kingdoms.anger(level, kingdom, owner, 24000);
        if (c.won) {
            c.plunder = Plunder.roll(level, Plunder.KINGDOM_TABLE, Kingdoms.wealth(kingdom), c.target, c.margin, true, level.getRandom());
            line.append("; carries off ").append(Plunder.describe(c.plunder));
            Kingdoms.favour(level, kingdom, -WarConfig.kingdomStandingCost(), "sacked");
            Kingdoms.anger(level, kingdom, owner, 24000 * 3);
            tell(mine, "Victory at " + c.defenderName + ": " + c.report + ". The men are on their way home with "
                    + Plunder.describe(c.plunder) + ". The kingdom will not forget it.", MessageUtils.MessagePriority.IMPORTANT);
        } else {
            final int taken = take(level, c, mine, 0, c.defenderName, c.target, 0, now);
            if (taken > 0) {
                line.append("; ").append(taken).append(taken == 1 ? " man taken" : " men taken");
            }
            tell(mine, "Defeat at " + c.defenderName + ": " + c.report + ". The men are coming home hurt, and the kingdom is angry."
                    + ransomNote(taken), MessageUtils.MessagePriority.DANGER);
        }
        line.append(" - the kingdom's standing is now ").append(Kingdoms.standing(kingdom));
        log.add(level, c.attacker, 0, line.toString());
        Warfare.LOGGER.info("[campaign] {}", line);
        owe(level, mine, kingdom, c.won, now);
        turnHome(level, c, now, c.target, c.warRoom);
    }

    /**
     * At a camp or a village: a number against what stands there. A camp overrun is cleared - its
     * spawners smashed, which is the one thing a warband changes in the world for good; a village
     * overrun only loses what the men carry off. Nobody holds either against anybody.
     */
    private void arriveAtPlace(final ServerLevel level, final Campaign c, final long now) {
        final IColony mine = colony(level, c.attacker);
        final Places.Place place = Places.at(level, c.target);
        if (place == null || mine == null) {
            c.won = false;
            c.report = place == null ? "found nothing where " + c.defenderName + " stood" : "came home to no colony";
            log.add(level, c.attacker, 0, c.attackerName + "'s warband " + c.report);
            turnHome(level, c, now, c.target, c.warRoom);
            return;
        }
        if (c.approach.equals(BlockPos.ZERO)) {
            c.approach = mine.getCenter();
        }
        final Battle battle = Battle.roll(c.attack, Places.defence(level, place), level.getRandom());
        c.fought = true;
        c.won = battle.won();
        c.margin = battle.margin();
        c.report = battle.report(place.isCamp() ? "the camp" : "the village");
        final StringBuilder line = new StringBuilder(c.attackerName + "'s warband " + c.report + " at " + c.defenderName);
        if (c.won) {
            c.plunder = Plunder.roll(level, Places.plunderTable(place), Places.wealth(place), c.target, c.margin, true, level.getRandom());
            line.append("; carries off ").append(Plunder.describe(c.plunder));
            final int smashed = Places.clear(level, place);
            if (smashed > 0) {
                line.append("; the camp is cleared - ").append(smashed).append(smashed == 1 ? " spawner" : " spawners").append(" smashed");
            }
            tell(mine, (place.isCamp() ? "The camp is cleared" : "Victory at " + c.defenderName) + ": " + c.report
                    + ". The men are on their way home with " + Plunder.describe(c.plunder)
                    + (smashed > 0 ? ", and " + smashed + (smashed == 1 ? " spawner" : " spawners") + " will not spawn again." : "."),
                    MessageUtils.MessagePriority.IMPORTANT);
        } else {
            tell(mine, "Defeat at " + c.defenderName + ": " + c.report + ". The men are coming home hurt.",
                    MessageUtils.MessagePriority.DANGER);
        }
        log.add(level, c.attacker, 0, line.toString());
        Warfare.LOGGER.info("[campaign] {}", line);
        turnHome(level, c, now, c.target, c.warRoom);
    }

    /** The kingdom writes the colony down: its men will come, sooner and more of them after a sack. */
    private void owe(final ServerLevel level, final IColony mine, final Object kingdom, final boolean sacked, final long now) {
        if (!WarConfig.kingdomsStrikeBack()) {
            return;
        }
        final int tier = Kingdoms.tier(kingdom);
        final int garrison = 2 + tier * 2 + Kingdoms.catapults(kingdom);
        final Reprisal r = new Reprisal();
        r.id = next++;
        r.kingdom = Kingdoms.centre(kingdom);
        r.kingdomName = Kingdoms.name(kingdom);
        r.colony = mine.getID();
        r.colonyName = mine.getName();
        r.dimension = mine.getDimension().location().toString();
        r.sacked = sacked;
        r.size = Math.max(3, Math.min(16, sacked ? garrison + tier * 2 : garrison));
        // the kingdom's men are worth what its defence is worth, spread over the men it sends
        r.strength = Math.max(r.size * 4, Kingdoms.defence(kingdom) + (sacked ? tier * 4 : 0));
        final int days = Math.max(1, sacked ? WarConfig.reprisalDays() / 2 : WarConfig.reprisalDays());
        r.due = now + days * 24000L;
        r.expires = r.due + 7 * 24000L;
        // one debt per kingdom and colony: a second attack makes it bigger, not a second raid
        for (final Reprisal old : new ArrayList<>(reprisals)) {
            if (old.colony == r.colony && old.kingdom.equals(r.kingdom) && old.eventId == 0) {
                r.size = Math.min(16, Math.max(r.size, old.size + tier));
                r.strength = Math.max(r.strength, old.strength + tier * 4);
                r.due = Math.min(r.due, old.due);
                reprisals.remove(old);
            }
        }
        reprisals.add(r);
        setDirty();
        tell(mine, "The kingdom of " + r.kingdomName + " will not let this stand. Expect its men at your walls within "
                + days + (days == 1 ? " day." : " days."), MessageUtils.MessagePriority.DANGER);
        Warfare.LOGGER.info("[campaign] {} owes {} a reprisal: {} soldiers, strength {}, due in {} day(s)",
                r.colonyName, r.kingdomName, r.size, r.strength, days);
    }

    /** The kingdom's men arrive: a raid at the colony, fought by its guards like any other. */
    private boolean strike(final ServerLevel level, final Reprisal r, final IColony colony, final long now) {
        final BlockPos spawn = spawnAt(level, colony);
        if (spawn == null) {
            return false;
        }
        final double perMan = Math.max(4, r.strength / (double) r.size);
        final ArmyRaidEvent event = new ArmyRaidEvent(colony).warband(0, r.kingdomName, -r.id, r.size,
                16 + perMan * 2.0, 2 + perMan / 3.0, 1 + perMan / 6.0, WarConfig.armyLook());
        event.setSpawnPoint(spawn);
        event.setHorde(new Horde(r.size));
        try {
            final BlockPos closest = colony.getServerBuildingManager().getBestBuilding(spawn, IBuilding.class);
            final PathJobRaiderPathing job = new PathJobRaiderPathing(
                    new ArrayList<>(colony.getServerBuildingManager().getBuildings().values()), level,
                    closest == null ? colony.getCenter() : closest, spawn);
            job.getResult().startJob(Pathfinding.getExecutor());
            event.setSpawnPath(job.getResult());
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] no spawn path for the kingdom's men: {}", t.toString());
        }
        colony.getEventManager().addEvent(event);
        r.eventId = event.getID();
        r.expires = now + siegeNights() * 24000L + SIEGE_GRACE;
        log.add(level, r.colony, 0, "the kingdom of " + r.kingdomName + " marched on " + r.colonyName + " - " + r.size + " soldiers");
        tell(colony, "The kingdom of " + r.kingdomName + " has come for what you did: " + r.size + " soldiers at your walls.",
                MessageUtils.MessagePriority.DANGER);
        Warfare.LOGGER.info("[campaign] the kingdom of {} strikes {} - {} soldiers, event {}", r.kingdomName, r.colonyName, r.size, r.eventId);
        return true;
    }

    /** The kingdom's raid is over. Its men still standing at the end of the night have had their way. */
    private void reprisalEnded(final ServerLevel level, final Reprisal r, final IColony colony, final int alive, final int marched) {
        final Battle battle = Battle.fought(r.strength, Battle.defence(colony), alive, marched);
        // "the men were thrown back", not "was": the report is written for a warband
        final String said = battle.report().replace("was thrown back", "were thrown back");
        final StringBuilder line = new StringBuilder("the kingdom of " + r.kingdomName + "'s men " + said + " at " + r.colonyName);
        if (battle.won()) {
            final List<ItemStack> taken = takeGoods(colony, WarConfig.plunderRolls() * 2);
            line.append("; carried off ").append(Plunder.describe(taken));
            final int broke = Breach.make(level, colony, r.kingdom);
            if (broke > 0) {
                line.append("; breached the wall (").append(broke).append(" blocks)");
            }
            tell(colony, "The kingdom of " + r.kingdomName + " has had its revenge: " + said + ", and its men carried off "
                    + Plunder.describe(taken) + (broke > 0 ? " and broke the wall." : "."), MessageUtils.MessagePriority.DANGER);
        } else {
            tell(colony, "The kingdom of " + r.kingdomName + "'s men were thrown back from " + r.colonyName + ".",
                    MessageUtils.MessagePriority.IMPORTANT);
        }
        log.add(level, r.colony, 0, line.toString());
        Warfare.LOGGER.info("[campaign] {}", line);
        reprisals.remove(r);
        setDirty();
    }

    /** Up to {@code stacks} stacks out of the colony's warehouses, a bruise and not a ruin. */
    private static List<ItemStack> takeGoods(final IColony colony, final int stacks) {
        final List<ItemStack> out = new ArrayList<>();
        try {
            final List<IItemHandler> handlers = new ArrayList<>();
            for (final IWareHouse warehouse : colony.getServerBuildingManager().getWareHouses()) {
                handlers.addAll(InventoryUtils.getItemHandlersFromProvider(warehouse));
            }
            final List<int[]> slots = new ArrayList<>();
            for (int h = 0; h < handlers.size(); h++) {
                for (int slot = 0; slot < handlers.get(h).getSlots(); slot++) {
                    if (!handlers.get(h).getStackInSlot(slot).isEmpty()) {
                        slots.add(new int[] {h, slot});
                    }
                }
            }
            java.util.Collections.shuffle(slots);
            for (int i = 0; i < slots.size() && out.size() < stacks; i++) {
                final IItemHandler handler = handlers.get(slots.get(i)[0]);
                final ItemStack taken = handler.extractItem(slots.get(i)[1], 16, false);
                if (!taken.isEmpty()) {
                    out.add(taken);
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] could not carry off the goods: {}", t.toString());
        }
        return out;
    }

    /** The real thing: a raid at their gate. False when there is no ground to spawn it on. */
    private boolean besiege(final ServerLevel level, final Campaign c, final IColony them, final long now) {
        final BlockPos spawn = spawnAt(level, them);
        if (spawn == null) {
            return false;
        }
        final int size = c.soldiers();
        final double perMan = size == 0 ? 4 : Math.max(4, c.attack / (double) size);
        final ArmyRaidEvent event = new ArmyRaidEvent(them).warband(c.attacker, c.attackerName, c.id, size,
                16 + perMan * 2.0, 2 + perMan / 3.0, 1 + perMan / 6.0, WarConfig.armyLook());
        event.setSpawnPoint(spawn);
        event.setHorde(new Horde(size));
        try {
            final BlockPos closest = them.getServerBuildingManager().getBestBuilding(spawn, IBuilding.class);
            final PathJobRaiderPathing job = new PathJobRaiderPathing(
                    new ArrayList<>(them.getServerBuildingManager().getBuildings().values()), level,
                    closest == null ? them.getCenter() : closest, spawn);
            job.getResult().startJob(Pathfinding.getExecutor());
            event.setSpawnPath(job.getResult());
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] no spawn path for the warband: {}", t.toString());
        }
        them.getEventManager().addEvent(event);
        c.eventId = event.getID();
        c.approach = spawn;
        c.phase = Phase.BESIEGING;
        c.siegeUntil = now + siegeNights() * 24000L + SIEGE_GRACE;
        log.add(level, c.attacker, c.defender, c.attackerName + "'s warband is at the walls of " + c.defenderName
                + " - " + size + " soldiers");
        tell(colony(level, c.attacker), "Your warband has reached " + c.defenderName + " and the fight is on.",
                MessageUtils.MessagePriority.IMPORTANT);
        Warfare.LOGGER.info("[campaign] {} besieges {} - {} soldiers, event {}", c.attackerName, c.defenderName, size, c.eventId);
        return true;
    }

    /** The raid has found its ground: where the warband actually stands, for the breach and the log. */
    public static void siegeStood(final IColony defender, final long campaignId, final BlockPos where) {
        if (defender == null || where == null || !(defender.getWorld() instanceof ServerLevel level)) {
            return;
        }
        final Campaigns data = of(level);
        for (final Campaign c : data.campaigns) {
            if (c.id == campaignId && c.phase == Phase.BESIEGING) {
                c.approach = where;
                data.setDirty();
                return;
            }
        }
    }

    /**
     * Somewhere for an army to appear round this town, or null.
     *
     * <p>MineColonies picks a random direction and walks out from the nearest hut until it is far
     * enough and the chunks are loaded; standing at the edge of a big town, half the directions
     * run into unloaded country and it answers nothing. Its own raids ask up to ten times before
     * giving up for the night, and so does this.</p>
     */
    private static BlockPos spawnAt(final ServerLevel level, final IColony colony) {
        for (int tries = 0; tries < 10; tries++) {
            final BlockPos spawn;
            try {
                spawn = colony.getRaiderManager().calculateSpawnLocation();
            } catch (final Throwable t) {
                Warfare.LOGGER.debug("[campaign] no spawn point at {}: {}", colony.getName(), t.toString());
                return null;
            }
            if (spawn != null && !spawn.equals(colony.getCenter()) && level.getWorldBorder().isWithinBounds(spawn)) {
                return spawn;
            }
        }
        return null;
    }

    /** The raid at their gate is over. Called from the event's own finish, on the server thread. */
    public static void siegeEnded(final IColony defender, final long campaignId, final int alive, final int marched) {
        if (defender == null || !(defender.getWorld() instanceof ServerLevel level)) {
            return;
        }
        final Campaigns data = of(level);
        if (campaignId < 0) {
            for (final Reprisal r : new ArrayList<>(data.reprisals)) {
                if (r.id == -campaignId && r.eventId != 0) {
                    data.reprisalEnded(level, r, defender, alive, marched);
                    return;
                }
            }
            return;
        }
        for (final Campaign c : data.campaigns) {
            if (c.id == campaignId && c.phase == Phase.BESIEGING) {
                final long now = level.getServer().overworld().getGameTime();
                data.resolve(level, c, Battle.fought(c.attack, Battle.defence(defender), alive, marched), now, Math.max(0, marched - alive));
                data.setDirty();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ the outcome

    private void resolve(final ServerLevel level, final Campaign c, final Battle battle, final long now) {
        resolve(level, c, battle, now, 0);
    }

    /** @param fallen how many soldiers fell in a raid that was fought in the world, 0 for a number */
    private void resolve(final ServerLevel level, final Campaign c, final Battle battle, final long now, final int fallen) {
        final IColony them = colony(level, c.defender);
        final IColony mine = colony(level, c.attacker);
        c.fought = true;
        c.won = battle.won();
        c.margin = battle.margin();
        c.report = battle.report();
        final StringBuilder line = new StringBuilder(c.attackerName + "'s warband " + c.report);
        if (c.won && them != null) {
            final boolean raze = "raze".equalsIgnoreCase(c.goal) && WarConfig.depth() >= 1;
            final boolean conquer = "conquer".equalsIgnoreCase(c.goal) && WarConfig.depth() >= 2;
            c.plunder = Plunder.roll(level, them, c.margin, "plunder".equalsIgnoreCase(c.goal), level.getRandom());
            final List<ItemStack> share = sellswordsShare(c);
            line.append("; carries off ").append(Plunder.describe(c.plunder));
            if (!share.isEmpty()) {
                line.append("; the sellswords took ").append(Plunder.describe(share));
            }
            if (raze) {
                final int broke = Breach.make(level, them, c.approach.equals(BlockPos.ZERO) ? c.warRoom : c.approach);
                if (broke > 0) {
                    line.append("; breached the wall (").append(broke).append(" blocks)");
                }
            }
            Standing.offend(them, c.attacker, 10, "sacked");
            if (conquer && mine != null) {
                line.append("; ").append(them.getName()).append(" is now ").append(mine.getName()).append("'s vassal");
                subjugate(level, mine, them, now);
            }
            tell(them, "The warband of " + c.attackerName + " overran " + them.getName()
                    + " and carried off " + Plunder.describe(c.plunder) + (conquer ? ". " + them.getName() + " is theirs now: tribute out of the warehouse every day for "
                    + WarConfig.vassalDays() + " days." : "."), MessageUtils.MessagePriority.DANGER);
            tell(mine, "Victory at " + c.defenderName + ": " + c.report + ". The men are on their way home with "
                    + Plunder.describe(c.plunder) + (share.isEmpty() ? "." : " - the sellswords kept " + Plunder.describe(share) + ".")
                    + (conquer ? " " + c.defenderName + " is your vassal for " + WarConfig.vassalDays() + " days." : ""),
                    MessageUtils.MessagePriority.IMPORTANT);
        } else {
            final int taken = them == null || mine == null ? 0
                    : take(level, c, mine, c.defender, them.getName(), them.getCenter(), fallen, now);
            if (taken > 0) {
                line.append("; ").append(taken).append(taken == 1 ? " man taken" : " men taken");
            }
            tell(them, "The warband of " + c.attackerName + " was thrown back from " + c.defenderName + "."
                    + (taken > 0 ? " You hold " + taken + " of their men; their ransom will come to your warehouse." : ""),
                    MessageUtils.MessagePriority.IMPORTANT);
            tell(mine, "Defeat at " + c.defenderName + ": " + c.report + ". The men are coming home hurt."
                    + ransomNote(taken), MessageUtils.MessagePriority.DANGER);
        }
        log.add(level, c.attacker, c.defender, line.toString());
        Warfare.LOGGER.info("[campaign] {}", line);
        turnHome(level, c, now, them == null ? c.warRoom : them.getCenter(), c.warRoom);
    }

    private static String ransomNote(final int taken) {
        if (taken <= 0) {
            return "";
        }
        return " " + taken + (taken == 1 ? " man was" : " men were") + " taken - " + WarConfig.ransomGold()
                + " gold a head to buy them back (/war ransom), or they are let go in " + WarConfig.ransomDays()
                + (WarConfig.ransomDays() == 1 ? " day." : " days.");
    }

    /**
     * The men who fell are held. In a raid fought in the world they are the soldiers who went
     * down, never more than half the warband; in a number, a share of the warband that grows with
     * how badly it went. They stay away - the same travelling as the march, with a longer clock -
     * until a ransom is paid or the holder lets them go.
     */
    private int take(final ServerLevel level, final Campaign c, final IColony mine, final int holder, final String holderName,
                     final BlockPos holderAt, final int fallen, final long now) {
        if (!WarConfig.captives() || c.guards.length == 0) {
            return 0;
        }
        final int marched = c.guards.length;
        int count = fallen > 0 ? Math.min(fallen, marched / 2)
                : Math.round(marched * Math.max(0f, Math.min(0.5f, -c.margin)));
        if (count <= 0) {
            return 0;
        }
        int taken = 0;
        for (int i = marched - 1; i >= 0 && taken < count; i--) {
            final ICitizenData guard = mine.getCitizenManager().getCivilian(c.guards[i]);
            if (guard == null || held(c.attacker, c.guards[i])) {
                continue;
            }
            final Captive k = new Captive();
            k.id = next++;
            k.colony = c.attacker;
            k.colonyName = c.attackerName;
            k.guard = guard.getId();
            k.guardName = guard.getName();
            k.holder = holder;
            k.holderName = holderName;
            k.holderAt = holderAt;
            k.dimension = c.dimension;
            k.until = now + WarConfig.ransomDays() * 24000L;
            captives.add(k);
            // a fresh clock on his travelling, long enough that MineColonies never brings him back first
            try {
                mine.getTravellingManager().startTravellingTo(guard, c.warRoom, (int) (WarConfig.ransomDays() * 24000L + 48000L));
            } catch (final Throwable t) {
                Warfare.LOGGER.debug("[campaign] could not keep {} away: {}", guard.getName(), t.toString());
            }
            taken++;
        }
        if (taken > 0) {
            log.add(level, c.attacker, holder, holderName + " holds " + taken + " of " + c.attackerName + "'s men");
            setDirty();
        }
        return taken;
    }

    /** The men come home: paid for, or let go. */
    private void free(final ServerLevel level, final List<Captive> held, final String how) {
        for (final Captive k : held) {
            final IColony mine = colony(level, k.colony);
            captives.remove(k);
            if (mine == null) {
                continue;
            }
            final ICitizenData guard = mine.getCitizenManager().getCivilian(k.guard);
            if (guard == null) {
                continue;
            }
            final IBuilding room = warRoom(mine);
            final BlockPos door = room == null ? mine.getCenter() : room.getPosition();
            Standing.setOnCampaign(guard, false);
            try {
                mine.getTravellingManager().finishTravellingFor(guard);
                guard.setNextRespawnPosition(EntityUtils.getSpawnPoint(level, door));
                guard.updateEntityIfNecessary();
                guard.getEntity().ifPresent(e -> {
                    e.setInvisible(false);
                    e.setHealth(Math.max(1f, e.getMaxHealth() * 0.5f));
                });
            } catch (final Throwable t) {
                Warfare.LOGGER.warn("[campaign] could not bring {} home from captivity: {}", k.guardName, t.toString());
            }
            log.add(level, k.colony, k.holder, k.guardName + " is home from " + k.holderName + " - " + how);
        }
        setDirty();
    }

    /**
     * {@code /war ransom}: buy every held man back at once, in gold out of the warehouse. A colony
     * that holds them gets the gold in its own warehouse; a kingdom is simply paid, and thinks a
     * little better of a man who pays his debts.
     */
    public static String ransom(final ServerLevel level, final IColony mine) {
        final Campaigns data = of(level);
        final List<Captive> held = captivesOf(level, mine.getID());
        if (held.isEmpty()) {
            return "none of your men are held";
        }
        final int price = held.size() * WarConfig.ransomGold();
        final List<ItemStack> gold = takeItems(mine, Items.GOLD_INGOT, price);
        int paid = 0;
        for (final ItemStack stack : gold) {
            paid += stack.getCount();
        }
        if (paid < price) {
            // not enough: put it back and say so
            for (final ItemStack stack : gold) {
                Plunder.deliver(level, mine, warRoom(mine), List.of(stack));
            }
            return "the ransom is " + price + " gold and the warehouse holds " + paid;
        }
        final Map<Integer, List<Captive>> byHolder = new HashMap<>();
        for (final Captive k : held) {
            byHolder.computeIfAbsent(k.holder, h -> new ArrayList<>()).add(k);
        }
        for (final Map.Entry<Integer, List<Captive>> e : byHolder.entrySet()) {
            final Captive first = e.getValue().get(0);
            final int share = e.getValue().size() * WarConfig.ransomGold();
            if (first.holder != 0) {
                final IColony holder = colony(level, first.holder);
                if (holder != null) {
                    Plunder.deliver(level, holder, warRoom(holder), List.of(new ItemStack(Items.GOLD_INGOT, share)));
                    tell(holder, mine.getName() + " has paid " + share + " gold for " + e.getValue().size()
                            + (e.getValue().size() == 1 ? " man" : " men") + " you held.", MessageUtils.MessagePriority.IMPORTANT);
                }
            } else {
                final Object kingdom = Kingdoms.at(level, first.holderAt);
                Kingdoms.favour(level, kingdom, 5, "ransom");
            }
        }
        data.free(level, held, "ransomed for " + price + " gold");
        data.log.add(level, mine.getID(), 0, mine.getName() + " paid " + price + " gold in ransom for " + held.size()
                + (held.size() == 1 ? " man" : " men"));
        tell(mine, "The ransom is paid: " + price + " gold, and " + held.size() + (held.size() == 1 ? " man is" : " men are")
                + " home.", MessageUtils.MessagePriority.IMPORTANT);
        touch(mine);
        return null;
    }

    /** Take up to {@code wanted} of an item out of the colony's warehouses. */
    private static List<ItemStack> takeItems(final IColony colony, final net.minecraft.world.item.Item item, final int wanted) {
        final List<ItemStack> out = new ArrayList<>();
        int taken = 0;
        try {
            for (final IWareHouse warehouse : colony.getServerBuildingManager().getWareHouses()) {
                for (final IItemHandler handler : InventoryUtils.getItemHandlersFromProvider(warehouse)) {
                    for (int slot = 0; slot < handler.getSlots() && taken < wanted; slot++) {
                        if (!handler.getStackInSlot(slot).is(item)) {
                            continue;
                        }
                        final ItemStack got = handler.extractItem(slot, wanted - taken, false);
                        if (!got.isEmpty()) {
                            out.add(got);
                            taken += got.getCount();
                        }
                    }
                    if (taken >= wanted) {
                        return out;
                    }
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] could not count the gold: {}", t.toString());
        }
        return out;
    }

    private void turnHome(final ServerLevel level, final Campaign c, final long now, final BlockPos from, final BlockPos to) {
        c.phase = Phase.RETURNING;
        c.home = now + travelTicks(from, to);
    }

    /** Sellswords take a stack for every two of them, one at the least, out of the plunder. */
    private static List<ItemStack> sellswordsShare(final Campaign c) {
        final List<ItemStack> share = new ArrayList<>();
        if (c.mercenaries <= 0 || c.plunder.isEmpty()) {
            return share;
        }
        final int cut = Math.min(c.plunder.size() - 1, Math.max(1, c.mercenaries / 2));
        for (int i = 0; i < cut && !c.plunder.isEmpty(); i++) {
            share.add(c.plunder.remove(c.plunder.size() - 1));
        }
        return share;
    }

    private void arriveHome(final ServerLevel level, final Campaign c) {
        final IColony mine = colony(level, c.attacker);
        c.phase = Phase.DONE;
        if (mine == null) {
            Warfare.LOGGER.warn("[campaign] {}'s warband came home to no colony", c.attackerName);
            return;
        }
        final IBuilding room = warRoom(mine);
        final BlockPos door = room == null ? mine.getCenter() : room.getPosition();
        int back = 0;
        int away = 0;
        for (final int id : c.everyone()) {
            final ICitizenData guard = mine.getCitizenManager().getCivilian(id);
            if (guard == null) {
                continue;
            }
            if (held(c.attacker, id)) {
                away++;
                continue;
            }
            Standing.setOnCampaign(guard, false);
            try {
                mine.getTravellingManager().finishTravellingFor(guard);
                guard.setNextRespawnPosition(EntityUtils.getSpawnPoint(level, door));
                guard.updateEntityIfNecessary();
                guard.getEntity().ifPresent(e -> {
                    e.setInvisible(false);
                    if (c.fought) {
                        // a fight lost hurts; an expedition wears the men down by how hard it was
                        final float share = c.onExpedition() ? (c.won ? 1f - 0.1f * c.difficulty : 0.35f) : c.won ? 0.8f : 0.35f;
                        e.setHealth(Math.max(1f, e.getMaxHealth() * share));
                        // veterans: a fight teaches, a fight won teaches more - through MineColonies'
                        // own experience handler, so it lands on the guard job's own skills
                        final double xp = WarConfig.campaignExperience() * (c.won ? 1.5 : 1.0);
                        if (xp > 0) {
                            try {
                                e.getCitizenExperienceHandler().addExperience(xp);
                            } catch (final Throwable t) {
                                Warfare.LOGGER.debug("[campaign] {} learned nothing: {}", guard.getName(), t.toString());
                            }
                        }
                    }
                });
                back++;
            } catch (final Throwable t) {
                Warfare.LOGGER.warn("[campaign] could not bring guard {} home: {}", id, t.toString());
            }
        }
        if (c.onConvoy()) {
            // whatever was not delivered (nobody there) comes back to the warehouse
            final int returned = c.plunder.isEmpty() ? 0 : Plunder.deliver(level, mine, room, c.plunder);
            final String line = c.attackerName + "'s convoy is home from " + c.defenderName + " - " + back + " of "
                    + c.everyone().length + " back" + (returned > 0 ? ", the goods brought back" : "");
            log.add(level, c.attacker, c.defender, line);
            tell(mine, "The convoy is home from " + c.defenderName + (returned > 0 ? " with the goods it could not deliver." : "."),
                    MessageUtils.MessagePriority.IMPORTANT);
            Warfare.LOGGER.info("[convoy] {}", line);
            c.plunder = new ArrayList<>();
            return;
        }
        final int stored = c.plunder.isEmpty() ? 0 : Plunder.deliver(level, mine, room, c.plunder);
        if (c.onExpedition()) {
            final String line = c.attackerName + "'s expedition is home from " + c.defenderName + " - " + back + " of "
                    + c.everyone().length + " back" + (c.plunder.isEmpty() ? " with nothing" : " with " + Plunder.describe(c.plunder))
                    + (c.bestiary.isEmpty() || "nothing".equals(c.bestiary) ? "" : "; killed " + c.bestiary);
            log.add(level, c.attacker, 0, line);
            tell(mine, "The expedition is home from " + c.defenderName
                    + (c.plunder.isEmpty() ? " with nothing to show for it." : " with " + Plunder.describe(c.plunder)
                    + (stored < c.plunder.size() ? " (some of it dropped at the War Room - no room in the warehouse)" : "") + ".")
                    + (c.bestiary.isEmpty() || "nothing".equals(c.bestiary) ? "" : " They killed " + c.bestiary + ".")
                    + (c.won ? "" : " They were driven out and are the worse for it."), MessageUtils.MessagePriority.IMPORTANT);
            Warfare.LOGGER.info("[expedition] {}", line);
            c.plunder = new ArrayList<>();
            return;
        }
        final String line = c.attackerName + "'s warband is home - " + back + " of " + c.guards.length
                + (c.won ? " back with " + Plunder.describe(c.plunder) : c.fought ? " back, hurt" : " back")
                + (away > 0 ? ", " + away + " held" : "");
        log.add(level, c.attacker, c.defender, line);
        tell(mine, "The warband is home" + (c.won ? " with " + Plunder.describe(c.plunder)
                + (stored < c.plunder.size() ? " (some of it dropped at the War Room - no room in the warehouse)" : "")
                : c.fought ? ". The wounded will need a few days." : ".")
                + (c.fought && WarConfig.campaignExperience() > 0 ? " The men are harder for it." : ""), MessageUtils.MessagePriority.IMPORTANT);
        Warfare.LOGGER.info("[campaign] {}", line);
        c.plunder = new ArrayList<>();
    }

    /** A warband still on the road turns round. On the field it cannot: the men are fighting. */
    public static boolean recall(final ServerLevel level, final IColony mine, final StringBuilder why) {
        final Campaign c = outFrom(level, mine.getID());
        if (c == null) {
            why.append("no warband is out");
            return false;
        }
        if (c.phase != Phase.MARCHING) {
            why.append("the warband is " + (c.phase == Phase.BESIEGING ? "fighting" : "already coming home"));
            return false;
        }
        final Campaigns data = of(level);
        final long now = level.getServer().overworld().getGameTime();
        c.won = false;
        c.report = "was recalled";
        // as far as they got is as far as they have to come back
        c.phase = Phase.RETURNING;
        c.home = now + Math.max(EVERY, now - c.departed);
        data.log.add(level, c.attacker, c.defender, c.attackerName + " recalled the warband");
        data.setDirty();
        touch(mine);
        return true;
    }

    /** The Explorer still on his way out turns round; out in the country he cannot be reached. */
    public static boolean recallExpedition(final ServerLevel level, final IColony mine, final StringBuilder why) {
        final Campaign c = expeditionOut(level, mine.getID());
        if (c == null) {
            why.append("no expedition is out");
            return false;
        }
        if (c.phase != Phase.MARCHING) {
            why.append("the explorer is " + (c.phase == Phase.BESIEGING ? "out in the country and cannot be reached" : "already coming home"));
            return false;
        }
        final Campaigns data = of(level);
        final long now = level.getServer().overworld().getGameTime();
        c.won = false;
        c.report = "was recalled";
        c.phase = Phase.RETURNING;
        c.home = now + Math.max(EVERY, now - c.departed);
        data.log.add(level, c.attacker, 0, c.attackerName + " recalled the expedition");
        data.setDirty();
        touch(mine);
        return true;
    }

    // ------------------------------------------------------------------ weariness

    private void weary(final int colony, final int amount) {
        weariness.merge(colony, amount, Integer::sum);
        weariness.computeIfPresent(colony, (k, v) -> Math.min(100, v));
    }

    private void coolWeariness(final long now) {
        final long day = now / 24000L;
        if (day == wearinessDay) {
            return;
        }
        final long days = wearinessDay == 0 ? 1 : Math.max(1, day - wearinessDay);
        wearinessDay = day;
        boolean changed = false;
        for (final Map.Entry<Integer, Integer> e : weariness.entrySet()) {
            final int was = e.getValue();
            final int is = (int) Math.max(0, was - WEARY_COOLS * days);
            if (is != was) {
                e.setValue(is);
                changed = true;
            }
        }
        weariness.values().removeIf(v -> v <= 0);
        if (changed) {
            setDirty();
        }
    }

    /** Peace clears the weariness on both sides: it was the point of making it. */
    public static void peaceMade(final ServerLevel level, final int a, final int b) {
        final Campaigns data = of(level);
        data.weariness.remove(a);
        data.weariness.remove(b);
        data.setDirty();
    }

    /** At a peace the men each side holds of the other go home: a prisoner exchange. */
    public static int exchangeCaptives(final ServerLevel level, final int a, final int b) {
        final Campaigns data = of(level);
        final List<Captive> held = new ArrayList<>();
        for (final Captive k : data.captives) {
            if ((k.colony == a && k.holder == b) || (k.colony == b && k.holder == a)) {
                held.add(k);
            }
        }
        if (!held.isEmpty()) {
            for (final Captive k : held) {
                tell(colony(level, k.colony), k.guardName + " is home from " + k.holderName + " - exchanged at the peace.",
                        MessageUtils.MessagePriority.IMPORTANT);
            }
            data.free(level, held, "exchanged at the peace");
        }
        return held.size();
    }

    /** The loser is the winner's vassal: written down here, and in the standing between them. */
    public static void subjugate(final ServerLevel level, final IColony lord, final IColony vassal, final long now) {
        final Campaigns data = of(level);
        final long until = now + WarConfig.vassalDays() * 24000L;
        for (final Vassalage old : new ArrayList<>(data.vassalages)) {
            if (old.vassal == vassal.getID() || (old.lord == vassal.getID() && old.vassal == lord.getID())) {
                data.vassalages.remove(old);   // a vassal has one lord; a lord conquered by its vassal is freed
                final IColony oldLord = colony(level, old.lord);
                if (oldLord != null) {
                    Standing.freeVassal(oldLord, old.vassal);
                }
            }
        }
        final Vassalage v = new Vassalage();
        v.lord = lord.getID();
        v.vassal = vassal.getID();
        v.lordName = lord.getName();
        v.vassalName = vassal.getName();
        v.dimension = lord.getDimension().location().toString();
        v.until = until;
        v.lastDay = now / 24000L;
        data.vassalages.add(v);
        Standing.subjugate(lord, vassal.getID(), until);
        data.weariness.remove(lord.getID());
        data.weariness.remove(vassal.getID());
        exchangeCaptives(level, lord.getID(), vassal.getID());
        data.log.add(level, lord.getID(), vassal.getID(), vassal.getName() + " is now the vassal of " + lord.getName()
                + " for " + WarConfig.vassalDays() + " days");
        data.setDirty();
        touch(lord);
        touch(vassal);
    }

    /** The lord lets a vassal go before its time. */
    public static boolean release(final ServerLevel level, final IColony lord, final int vassal) {
        final Campaigns data = of(level);
        for (final Vassalage v : new ArrayList<>(data.vassalages)) {
            if (v.lord == lord.getID() && v.vassal == vassal) {
                data.vassalages.remove(v);
                Standing.freeVassal(lord, vassal);
                data.log.add(level, v.lord, v.vassal, v.lordName + " released " + v.vassalName + " from its vassalage");
                tell(colony(level, vassal), v.lordName + " has released " + v.vassalName + " from its vassalage.", MessageUtils.MessagePriority.IMPORTANT);
                data.setDirty();
                touch(lord);
                touch(colony(level, vassal));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ sellswords

    /** Why sellswords cannot be hired, or null when they can. */
    public static String cannotHire(final ServerLevel level, final IColony mine, final int count) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (WarConfig.depth() < 1) {
            return "sellswords are not for hire at skirmish depth (warLevel)";
        }
        final IBuilding room = warRoom(mine);
        if (room == null) {
            return "no War Room standing - nobody to hire them";
        }
        final int waiting = sellswordsWaiting(level, mine.getID());
        final int cap = sellswordCap(mine);
        if (waiting + count > cap) {
            return "the War Room can keep " + cap + (cap == 1 ? " sellsword" : " sellswords") + " waiting and has " + waiting;
        }
        return null;
    }

    /**
     * {@code /war hire}: sellswords, paid in gold out of the warehouse, who wait at the War Room
     * and go with the next warband. Returns the reason it failed, or null.
     */
    public static String hire(final ServerLevel level, final IColony mine, final int count) {
        final String no = cannotHire(level, mine, count);
        if (no != null) {
            return no;
        }
        final int price = count * WarConfig.mercenaryGold();
        final List<ItemStack> gold = takeItems(mine, Items.GOLD_INGOT, price);
        int paid = 0;
        for (final ItemStack stack : gold) {
            paid += stack.getCount();
        }
        if (paid < price) {
            for (final ItemStack stack : gold) {
                Plunder.deliver(level, mine, warRoom(mine), List.of(stack));
            }
            return count + (count == 1 ? " sellsword costs " : " sellswords cost ") + price + " gold and the warehouse holds " + paid;
        }
        final Campaigns data = of(level);
        final long now = level.getServer().overworld().getGameTime();
        final Sellswords s = data.sellswords.computeIfAbsent(mine.getID(), k -> new Sellswords());
        if (now > s.until) {
            s.count = 0;
        }
        s.count += count;
        s.until = now + WarConfig.mercenaryDays() * 24000L;
        data.log.add(level, mine.getID(), 0, mine.getName() + " hired " + count + (count == 1 ? " sellsword" : " sellswords") + " for " + price + " gold");
        data.setDirty();
        tell(mine, count + (count == 1 ? " sellsword waits" : " sellswords wait") + " at the War Room for the next warband - "
                + s.count + " in all, for " + WarConfig.mercenaryDays() + " days.", MessageUtils.MessagePriority.IMPORTANT);
        touch(mine);
        return null;
    }

    // ------------------------------------------------------------------ convoys

    /** Why a convoy cannot leave for another colony, or null. */
    public static String cannotConvoy(final ServerLevel level, final IColony mine, final int theirs) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!WarConfig.convoys()) {
            return "convoys are off in this world's settings";
        }
        if (theirs == mine.getID()) {
            return "not to yourselves";
        }
        final IColony them = IColonyManager.getInstance().getColonyByDimension(theirs, mine.getDimension());
        if (them == null) {
            return "colony " + theirs + " is not in this world";
        }
        if (Standing.atWar(mine, theirs)) {
            return "at war - the road is closed";
        }
        return cannotConvoyAtAll(level, mine, String.valueOf(theirs));
    }

    /** Why a convoy cannot leave for a kingdom, or null. */
    public static String cannotConvoyTo(final ServerLevel level, final IColony mine, final Object kingdom) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!WarConfig.convoys()) {
            return "convoys are off in this world's settings";
        }
        if (kingdom == null) {
            return "no kingdom stands there";
        }
        return cannotConvoyAtAll(level, mine, "k" + Kingdoms.centre(kingdom).getX() + "," + Kingdoms.centre(kingdom).getZ());
    }

    private static String cannotConvoyAtAll(final ServerLevel level, final IColony mine, final String partner) {
        if (warRoom(mine) == null) {
            return "no War Room standing - nobody to muster the escort";
        }
        if (convoyOut(level, mine.getID()) != null) {
            return "a convoy is already on the road";
        }
        final int sent = Standing.convoysThisWeek(level, String.valueOf(mine.getID()), partner);
        if (sent >= WarConfig.convoysPerWeek()) {
            return "enough convoys that way this week (" + sent + " of " + WarConfig.convoysPerWeek() + ")";
        }
        return null;
    }

    /** A convoy to another colony: goods out of the warehouse, an escort, and standing at the far end. */
    public static Campaign convoy(final ServerLevel level, final IColony mine, final int theirs, final int wanted, final StringBuilder why) {
        final String no = cannotConvoy(level, mine, theirs);
        if (no != null) {
            why.append(no);
            return null;
        }
        final IColony them = IColonyManager.getInstance().getColonyByDimension(theirs, mine.getDimension());
        final int stacks = WarConfig.convoyStacks() * (Standing.allied(mine, theirs) ? 3 : 1);
        return sendConvoy(level, mine, wanted, why, theirs, them.getName(), them.getCenter(), stacks, String.valueOf(theirs));
    }

    /** A convoy to a Waking World kingdom: a gift, and a debt paid in goods. */
    public static Campaign convoyTo(final ServerLevel level, final IColony mine, final Object kingdom, final int wanted, final StringBuilder why) {
        final String no = cannotConvoyTo(level, mine, kingdom);
        if (no != null) {
            why.append(no);
            return null;
        }
        final BlockPos centre = Kingdoms.centre(kingdom);
        return sendConvoy(level, mine, wanted, why, 0, "the kingdom of " + Kingdoms.name(kingdom), centre, WarConfig.convoyStacks(),
                "k" + centre.getX() + "," + centre.getZ());
    }

    private static Campaign sendConvoy(final ServerLevel level, final IColony mine, final int wanted, final StringBuilder why,
                                       final int partner, final String partnerName, final BlockPos target, final int stacks,
                                       final String partnerKey) {
        final IBuilding room = warRoom(mine);
        final ITravellingManager travel = mine.getTravellingManager();
        final List<ICitizenData> escort;
        try {
            escort = fitGuards(room, travel, wanted);
        } catch (final Throwable t) {
            why.append("could not read the War Room's guards: ").append(t);
            return null;
        }
        if (escort.isEmpty()) {
            why.append("nobody at the War Room is fit to escort it");
            return null;
        }
        final ICitizenData courier = courier(mine, travel);
        // what the convoy carries is what was put on the manifest and has reached the room - the
        // knights' gear in the same racks and the warehouse's shelves are not the convoy's
        final me.lovkar.war.colony.ConvoyModule bay = me.lovkar.war.colony.ConvoyModule.of(room);
        final List<ItemStack> goods = bay == null ? new ArrayList<>() : bay.take(stacks);
        if (goods.isEmpty()) {
            why.append(bay == null || bay.manifest().isEmpty()
                    ? "nothing loaded at the War Room - put goods on the manifest first (the convoy tab, or /war load)"
                    : "nothing on the manifest has reached the War Room yet - the couriers are still bringing it");
            return null;
        }
        final Campaigns data = of(level);
        final Campaign c = new Campaign();
        c.id = data.next++;
        c.attacker = mine.getID();
        c.defender = partner;
        c.kind = CONVOY;
        c.target = target;
        c.attackerName = mine.getName();
        c.defenderName = partnerName;
        c.dimension = mine.getDimension().location().toString();
        c.warRoom = room.getPosition();
        c.attack = Battle.attack(escort, room, false);
        c.plunder = goods;
        final long now = level.getServer().overworld().getGameTime();
        final int travelTicks = travelTicks(mine.getCenter(), target);
        c.departed = now;
        c.arrive = now + travelTicks;
        c.guards = new int[escort.size()];
        for (int i = 0; i < escort.size(); i++) {
            c.guards[i] = escort.get(i).getId();
        }
        final List<ICitizenData> party = new ArrayList<>(escort);
        if (courier != null) {
            c.courier = courier.getId();
            party.add(courier);
        }
        sendOff(mine, room, party, travelTicks * 2 + 48000);
        data.campaigns.add(c);
        Standing.noteConvoy(level, String.valueOf(mine.getID()), partnerKey);
        data.setDirty();
        touch(mine);
        final String line = mine.getName() + " sent a convoy to " + partnerName + " - " + Plunder.describe(goods) + ", escorted by "
                + escort.size() + (escort.size() == 1 ? " guard" : " guards") + (courier != null ? ", driven by " + courier.getName() : "");
        data.log.add(level, c.attacker, c.defender, line);
        tell(mine, "The convoy has left for " + partnerName + " with " + Plunder.describe(goods) + ". About " + minutes(travelTicks)
                + " there.", MessageUtils.MessagePriority.IMPORTANT);
        Warfare.LOGGER.info("[convoy] {}", line);
        return c;
    }

    /** A courier from the warehouse, borrowed for the trip - or null, and the escort carries it. */
    private static ICitizenData courier(final IColony mine, final ITravellingManager travel) {
        try {
            for (final ICitizenData citizen : mine.getCitizenManager().getCitizens()) {
                if (citizen.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman && fit(citizen, travel)) {
                    return citizen;
                }
            }
        } catch (final Throwable ignored) {
            // no couriers to read
        }
        return null;
    }

    /** The convoy arrives: the goods go into the partner's warehouse, and the partner thinks better of the sender. */
    private void arriveWithConvoy(final ServerLevel level, final Campaign c, final long now) {
        final IColony mine = colony(level, c.attacker);
        final StringBuilder line = new StringBuilder(c.attackerName + "'s convoy reached " + c.defenderName);
        if (c.defender != 0) {
            final IColony them = colony(level, c.defender);
            if (them == null || mine == null) {
                c.report = "found nobody at " + c.defenderName;
                line.append(" and found nobody");
            } else {
                final String goods = Plunder.describe(c.plunder);
                Plunder.deliver(level, them, warRoom(them), c.plunder);
                line.append(" with ").append(goods);
                c.plunder = new ArrayList<>();
                Standing.mend(mine, c.defender, WarConfig.convoyStanding());
                line.append(" - standing now ").append(Standing.between(mine, c.defender));
                c.report = "delivered";
                c.won = true;
                tell(them, "A convoy from " + c.attackerName + " has arrived with " + goods + ".", MessageUtils.MessagePriority.IMPORTANT);
                tell(mine, "The convoy has reached " + c.defenderName + " and the goods are in their warehouse. Standing is now "
                        + Standing.between(mine, c.defender) + ".", MessageUtils.MessagePriority.IMPORTANT);
            }
        } else {
            final Object kingdom = Kingdoms.at(level, c.target);
            if (kingdom == null || mine == null) {
                c.report = "found no kingdom where " + c.defenderName + " stood";
                line.append(" and found nobody");
            } else {
                line.append(" with ").append(Plunder.describe(c.plunder));
                c.plunder = new ArrayList<>();
                Kingdoms.favour(level, kingdom, WarConfig.convoyStanding(), "convoy");
                int forgiven = 0;
                for (final Reprisal r : new ArrayList<>(reprisals)) {
                    if (r.colony == c.attacker && r.kingdom.equals(Kingdoms.centre(kingdom)) && r.eventId == 0) {
                        reprisals.remove(r);
                        forgiven++;
                    }
                }
                line.append(" - the kingdom's standing is now ").append(Kingdoms.standing(kingdom));
                if (forgiven > 0) {
                    line.append("; the debt is forgiven");
                }
                c.report = "delivered";
                c.won = true;
                tell(mine, "The convoy has reached " + c.defenderName + ". Its standing is now " + Kingdoms.standing(kingdom)
                        + (forgiven > 0 ? ", and its men will not come for you after all." : "."), MessageUtils.MessagePriority.IMPORTANT);
            }
        }
        log.add(level, c.attacker, c.defender, line.toString());
        Warfare.LOGGER.info("[convoy] {}", line);
        turnHome(level, c, now, c.target, c.warRoom);
    }

    // ------------------------------------------------------------------ the safety net

    /**
     * A guard who came back invisible.
     *
     * <p>MineColonies respawns a traveller invisible and leaves it to the job that sent him to
     * turn him visible again - the Nether Worker does. If this data were ever lost mid-campaign
     * the timer would bring the men back that way and nothing would fix them; so once in a while
     * every War Room guard who is invisible and in no campaign is made visible.</p>
     */
    public static void sweep(final ServerLevel level) {
        final Campaigns data = of(level);
        try {
            for (final IColony colony : IColonyManager.getInstance().getColonies(level)) {
                final IBuilding room = warRoom(colony);
                if (room == null) {
                    continue;
                }
                for (final ICitizenData guard : ((BuildingWarRoom) room).getAllAssignedCitizen()) {
                    if (guard == null || data.inCampaign(colony.getID(), guard.getId()) || data.held(colony.getID(), guard.getId())) {
                        continue;
                    }
                    guard.getEntity().ifPresent(e -> {
                        if (e.isInvisible() && !colony.getTravellingManager().isTravelling(guard)) {
                            e.setInvisible(false);
                            Standing.setOnCampaign(guard, false);
                            Warfare.LOGGER.info("[campaign] {} of {} came back invisible - fixed", guard.getName(), colony.getName());
                        }
                    });
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] sweep: {}", t.toString());
        }
    }

    private boolean inCampaign(final int colony, final int guard) {
        for (final Campaign c : campaigns) {
            if (c.attacker == colony && c.phase != Phase.DONE) {
                for (final int id : c.everyone()) {
                    if (id == guard) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ messages

    private static void tell(final IColony colony, final String text, final MessageUtils.MessagePriority priority) {
        if (colony == null) {
            return;
        }
        try {
            MessageUtils.format(Component.literal(text)).withPriority(priority).sendTo(colony).forManagers();
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[campaign] could not message {}: {}", colony.getName(), t.toString());
        }
    }

    // ------------------------------------------------------------------ saving

    private static Campaigns read(final CompoundTag tag, final HolderLookup.Provider provider) {
        final Campaigns out = new Campaigns();
        out.next = Math.max(1, tag.getLong(TAG_NEXT));
        for (final Tag t : tag.getList(TAG_CAMPAIGNS, Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) t;
            final Campaign c = new Campaign();
            c.id = one.getLong("id");
            c.attacker = one.getInt("attacker");
            c.defender = one.getInt("defender");
            c.kind = one.contains("kind") ? one.getString("kind") : "colony";
            c.target = BlockPos.of(one.getLong("target"));
            c.attackerName = one.getString("attackerName");
            c.defenderName = one.getString("defenderName");
            c.dimension = one.contains("dimension") ? one.getString("dimension") : c.dimension;
            try {
                c.phase = Phase.valueOf(one.getString("phase"));
            } catch (final IllegalArgumentException e) {
                c.phase = Phase.DONE;
            }
            c.guards = one.getIntArray("guards");
            c.warRoom = BlockPos.of(one.getLong("warRoom"));
            c.attack = one.getInt("attack");
            c.hungry = one.getBoolean("hungry");
            c.goal = one.getString("goal");
            c.departed = one.getLong("departed");
            c.arrive = one.getLong("arrive");
            c.home = one.getLong("home");
            c.eventId = one.getInt("eventId");
            c.siegeUntil = one.getLong("siegeUntil");
            c.approach = BlockPos.of(one.getLong("approach"));
            c.fought = one.getBoolean("fought");
            c.won = one.getBoolean("won");
            c.margin = one.getFloat("margin");
            c.report = one.getString("report");
            c.biome = one.getString("biome");
            c.family = one.getString("family");
            c.difficulty = one.getInt("difficulty");
            c.explorer = one.getInt("explorer");
            c.bestiary = one.getString("bestiary");
            c.mercenaries = one.getInt("mercenaries");
            c.courier = one.getInt("courier");
            for (final Tag s : one.getList("plunder", Tag.TAG_COMPOUND)) {
                final ItemStack stack = ItemStack.parseOptional(provider, (CompoundTag) s);
                if (!stack.isEmpty()) {
                    c.plunder.add(stack);
                }
            }
            out.campaigns.add(c);
        }
        for (final Tag t : tag.getList("reprisals", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) t;
            final Reprisal r = new Reprisal();
            r.id = one.getLong("id");
            r.kingdom = BlockPos.of(one.getLong("kingdom"));
            r.kingdomName = one.getString("kingdomName");
            r.colony = one.getInt("colony");
            r.colonyName = one.getString("colonyName");
            r.dimension = one.contains("dimension") ? one.getString("dimension") : r.dimension;
            r.due = one.getLong("due");
            r.expires = one.getLong("expires");
            r.size = one.getInt("size");
            r.strength = one.getInt("strength");
            r.eventId = one.getInt("eventId");
            r.sacked = one.getBoolean("sacked");
            out.reprisals.add(r);
        }
        for (final Tag t : tag.getList("captives", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) t;
            final Captive k = new Captive();
            k.id = one.getLong("id");
            k.colony = one.getInt("colony");
            k.colonyName = one.getString("colonyName");
            k.guard = one.getInt("guard");
            k.guardName = one.getString("guardName");
            k.holder = one.getInt("holder");
            k.holderName = one.getString("holderName");
            k.holderAt = BlockPos.of(one.getLong("holderAt"));
            k.dimension = one.contains("dimension") ? one.getString("dimension") : k.dimension;
            k.until = one.getLong("until");
            out.captives.add(k);
        }
        final CompoundTag swords = tag.getCompound("sellswords");
        for (final String key : swords.getAllKeys()) {
            try {
                final Sellswords s = new Sellswords();
                s.count = swords.getCompound(key).getInt("count");
                s.until = swords.getCompound(key).getLong("until");
                out.sellswords.put(Integer.parseInt(key), s);
            } catch (final NumberFormatException ignored) {
                // not a colony id
            }
        }
        for (final Tag t : tag.getList("vassalages", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) t;
            final Vassalage v = new Vassalage();
            v.lord = one.getInt("lord");
            v.vassal = one.getInt("vassal");
            v.lordName = one.getString("lordName");
            v.vassalName = one.getString("vassalName");
            v.dimension = one.contains("dimension") ? one.getString("dimension") : v.dimension;
            v.until = one.getLong("until");
            v.lastDay = one.getLong("lastDay");
            out.vassalages.add(v);
        }
        final CompoundTag weary = tag.getCompound(TAG_WEARY);
        for (final String key : weary.getAllKeys()) {
            try {
                out.weariness.put(Integer.parseInt(key), weary.getInt(key));
            } catch (final NumberFormatException ignored) {
                // not a colony id
            }
        }
        out.wearinessDay = tag.getLong(TAG_WEARY_DAY);
        out.log.read(tag);
        return out;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        tag.putLong(TAG_NEXT, next);
        final ListTag list = new ListTag();
        for (final Campaign c : campaigns) {
            final CompoundTag one = new CompoundTag();
            one.putLong("id", c.id);
            one.putInt("attacker", c.attacker);
            one.putInt("defender", c.defender);
            one.putString("kind", c.kind);
            one.putLong("target", c.target.asLong());
            one.putString("attackerName", c.attackerName);
            one.putString("defenderName", c.defenderName);
            one.putString("dimension", c.dimension);
            one.putString("phase", c.phase.name());
            one.putIntArray("guards", c.guards);
            one.putLong("warRoom", c.warRoom.asLong());
            one.putInt("attack", c.attack);
            one.putBoolean("hungry", c.hungry);
            one.putString("goal", c.goal);
            one.putLong("departed", c.departed);
            one.putLong("arrive", c.arrive);
            one.putLong("home", c.home);
            one.putInt("eventId", c.eventId);
            one.putLong("siegeUntil", c.siegeUntil);
            one.putLong("approach", c.approach.asLong());
            one.putBoolean("fought", c.fought);
            one.putBoolean("won", c.won);
            one.putFloat("margin", c.margin);
            one.putString("report", c.report);
            one.putString("biome", c.biome);
            one.putString("family", c.family);
            one.putInt("difficulty", c.difficulty);
            one.putInt("explorer", c.explorer);
            one.putString("bestiary", c.bestiary);
            one.putInt("mercenaries", c.mercenaries);
            one.putInt("courier", c.courier);
            final ListTag goods = new ListTag();
            for (final ItemStack stack : c.plunder) {
                if (!stack.isEmpty()) {
                    goods.add(stack.save(provider));
                }
            }
            one.put("plunder", goods);
            list.add(one);
        }
        tag.put(TAG_CAMPAIGNS, list);
        final ListTag debts = new ListTag();
        for (final Reprisal r : reprisals) {
            final CompoundTag one = new CompoundTag();
            one.putLong("id", r.id);
            one.putLong("kingdom", r.kingdom.asLong());
            one.putString("kingdomName", r.kingdomName);
            one.putInt("colony", r.colony);
            one.putString("colonyName", r.colonyName);
            one.putString("dimension", r.dimension);
            one.putLong("due", r.due);
            one.putLong("expires", r.expires);
            one.putInt("size", r.size);
            one.putInt("strength", r.strength);
            one.putInt("eventId", r.eventId);
            one.putBoolean("sacked", r.sacked);
            debts.add(one);
        }
        tag.put("reprisals", debts);
        final ListTag held = new ListTag();
        for (final Captive k : captives) {
            final CompoundTag one = new CompoundTag();
            one.putLong("id", k.id);
            one.putInt("colony", k.colony);
            one.putString("colonyName", k.colonyName);
            one.putInt("guard", k.guard);
            one.putString("guardName", k.guardName);
            one.putInt("holder", k.holder);
            one.putString("holderName", k.holderName);
            one.putLong("holderAt", k.holderAt.asLong());
            one.putString("dimension", k.dimension);
            one.putLong("until", k.until);
            held.add(one);
        }
        tag.put("captives", held);
        final CompoundTag swords = new CompoundTag();
        for (final Map.Entry<Integer, Sellswords> e : sellswords.entrySet()) {
            final CompoundTag one = new CompoundTag();
            one.putInt("count", e.getValue().count);
            one.putLong("until", e.getValue().until);
            swords.put(String.valueOf(e.getKey()), one);
        }
        tag.put("sellswords", swords);
        final ListTag vassals = new ListTag();
        for (final Vassalage v : vassalages) {
            final CompoundTag one = new CompoundTag();
            one.putInt("lord", v.lord);
            one.putInt("vassal", v.vassal);
            one.putString("lordName", v.lordName);
            one.putString("vassalName", v.vassalName);
            one.putString("dimension", v.dimension);
            one.putLong("until", v.until);
            one.putLong("lastDay", v.lastDay);
            vassals.add(one);
        }
        tag.put("vassalages", vassals);
        final CompoundTag weary = new CompoundTag();
        for (final Map.Entry<Integer, Integer> e : weariness.entrySet()) {
            weary.putInt(String.valueOf(e.getKey()), e.getValue());
        }
        tag.put(TAG_WEARY, weary);
        tag.putLong(TAG_WEARY_DAY, wearinessDay);
        log.write(tag);
        return tag;
    }
}
