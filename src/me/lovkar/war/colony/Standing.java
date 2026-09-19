package me.lovkar.war.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How two colonies feel about each other, and whether they are at war - or allied, or one the
 * other's vassal.
 *
 * <p>Three rules are built into this class rather than written down somewhere and hoped for.</p>
 *
 * <p><b>Standing falls only when somebody is caught.</b> There is no method here that lowers
 * standing on its own, no tick that drifts it, and no event that reads "they are near us, so they
 * must hate us". The only way down is {@link #offend}, and the only caller is a mod telling this
 * one that something was done and the victim knows who did it.</p>
 *
 * <p><b>War is never declared by the game.</b> {@link #mayDeclare} answers whether the button is
 * unlocked; {@link #declare} is what a colony owner presses. Nothing in this mod calls
 * {@code declare} on its own, ever. Low standing is permission, not a trigger - and a
 * {@link #truce} is a door that has to stay shut for a while after a peace.</p>
 *
 * <p><b>Neither is peace, nor an alliance.</b> Both are an {@link Offer} one side makes and the
 * other accepts, by hand; an offer nobody answers lapses. High standing unlocks the offer of an
 * alliance the way low standing unlocks the declaration of war, and no more.</p>
 */
public class Standing extends SavedData {
    private static final String NAME = "colonies_at_war_standing";
    private static final String TAG_PAIRS = "pairs";
    private static final String TAG_STANDING = "standing";
    private static final String TAG_STATE = "state";
    private static final String TAG_UNTIL = "until";
    private static final String TAG_GOAL = "goal";
    private static final String TAG_CAMPAIGN = "campaign";

    /** Standing has to be at or below this before the declare button unlocks (the config may move it). */
    public static final int WAR_THRESHOLD = -40;
    /** A peace holds for this long before anybody may declare again: seven days (the config may change it). */
    public static final long TRUCE_TICKS = 24000L * 7;

    public enum State {
        /** Nothing between them. */
        PEACE,
        /** Bad blood. War may be declared, by hand, by a person. */
        TENSE,
        /** Openly at war. */
        WAR,
        /** A peace was made and neither may declare again until it runs out. */
        TRUCE,
        /** Good blood. An alliance may be offered, by hand, by a person. */
        WARM,
        /** Sworn allies: convoys between them are fat, and neither may declare on the other. */
        ALLIED
    }

    /** Something one side has put on the table and the other has not answered yet. */
    public record Offer(int from, String kind, String terms, int gold, long until) {
        public static final String PEACE = "peace";
        public static final String ALLIANCE = "alliance";

        public boolean isPeace() {
            return PEACE.equals(kind);
        }

        /** "a white peace", "20 gold from them", "20 gold to them", "their vassalage", "an alliance". */
        public String describe(final boolean fromOurSide) {
            if (!isPeace()) {
                return "an alliance";
            }
            return switch (terms) {
                case "tribute" -> gold + " gold " + (fromOurSide ? "from them" : "from you");
                case "pay" -> gold + " gold " + (fromOurSide ? "to them" : "to you");
                case "vassal" -> fromOurSide ? "their vassalage" : "your vassalage";
                default -> "a white peace";
            };
        }
    }

    /** What stands between two colonies. */
    public static final class Relation {
        public int standing;
        public State state = State.PEACE;
        public long until;
        public String goal = "";
        /** An offer on the table, or null. */
        public Offer offer;
        /** While one is the other's vassal: who the lord is, and until when. */
        public int lord;
        public int vassal;
        public long vassalUntil;

        Relation() {
        }
    }

    private final Map<String, Relation> pairs = new HashMap<>();
    /** Citizens who have marched out. Kept by colony so a reload does not strand a warband. */
    private final Set<Integer> campaigning = new HashSet<>();
    /** Days on which a convoy left, per "from->to" (colony ids, or a kingdom's centre as "kx,z"). */
    private final Map<String, List<Long>> convoys = new HashMap<>();

    public static final SavedData.Factory<Standing> FACTORY = new SavedData.Factory<>(Standing::new, Standing::read);

    private static Standing of(final ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    private static String key(final int a, final int b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }

    private Relation relation(final int a, final int b) {
        return pairs.computeIfAbsent(key(a, b), k -> new Relation());
    }

    private static ServerLevel level(final IColony mine) {
        return mine != null && mine.getWorld() instanceof ServerLevel level ? level : null;
    }

    private static int threshold() {
        return WarConfig.threshold();
    }

    // ------------------------------------------------------------------ reading

    public static int between(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return 0;
        }
        return of(level).relation(mine.getID(), theirs).standing;
    }

    /**
     * The state, with the clocks read: a truce that has run out, an offer that has lapsed, a
     * vassalage that is over - and the three loose states told apart by the standing alone, so
     * that standing which has risen past the ally threshold reads as WARM without anybody having
     * pressed anything.
     */
    public static State state(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return State.PEACE;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        final long now = level.getGameTime();
        boolean changed = false;
        if (r.state == State.TRUCE && now > r.until) {
            r.state = State.PEACE;
            changed = true;
        }
        if (r.offer != null && now > r.offer.until()) {
            r.offer = null;
            changed = true;
        }
        if (r.lord != 0 && now > r.vassalUntil) {
            r.lord = 0;
            r.vassal = 0;
            changed = true;
        }
        if (r.state == State.PEACE || r.state == State.TENSE || r.state == State.WARM) {
            final State loose = r.standing <= threshold() ? State.TENSE
                    : r.standing >= WarConfig.allyThreshold() ? State.WARM : State.PEACE;
            if (loose != r.state) {
                r.state = loose;
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
        return r.state;
    }

    public static boolean atWar(final IColony mine, final int theirs) {
        return state(mine, theirs) == State.WAR;
    }

    public static boolean allied(final IColony mine, final int theirs) {
        return state(mine, theirs) == State.ALLIED;
    }

    /** What the war was declared for - plunder, raze, conquer - or an empty string. */
    public static String goal(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return "";
        }
        final String goal = of(level).relation(mine.getID(), theirs).goal;
        return goal == null ? "" : goal.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the declare button is unlocked. Note what this is not: it is not a decision, and
     * nothing acts on it. A person reads it and chooses. Allies cannot declare on each other, and
     * a vassal cannot declare on its lord (nor the lord on a vassal: the war is over).
     */
    public static boolean mayDeclare(final IColony mine, final int theirs) {
        final State now = state(mine, theirs);
        if (now == State.WAR || now == State.TRUCE || now == State.ALLIED) {
            return false;
        }
        if (lordOf(mine) == theirs || isVassalOf(mine, theirs)) {
            return false;
        }
        return between(mine, theirs) <= threshold();
    }

    /** Whether an alliance may be offered: standing high enough, and nothing else in the way. */
    public static boolean mayAlly(final IColony mine, final int theirs) {
        return state(mine, theirs) == State.WARM && lordOf(mine) != theirs && !isVassalOf(mine, theirs);
    }

    /** The offer on the table between these two, or null. */
    public static Offer offerBetween(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return null;
        }
        state(mine, theirs);                        // reads the clocks
        return of(level).relation(mine.getID(), theirs).offer;
    }

    /** The colony this one is a vassal of, or 0. */
    public static int lordOf(final IColony mine) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return 0;
        }
        final Standing data = of(level);
        final long now = level.getGameTime();
        for (final Relation r : data.pairs.values()) {
            if (r.vassal == mine.getID() && r.lord != 0 && now <= r.vassalUntil) {
                return r.lord;
            }
        }
        return 0;
    }

    /** Whether {@code theirs} is this colony's vassal. */
    public static boolean isVassalOf(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return false;
        }
        final Relation r = of(level).relation(mine.getID(), theirs);
        return r.lord == mine.getID() && r.vassal == theirs && level.getGameTime() <= r.vassalUntil;
    }

    /** When the vassalage of {@code theirs} under {@code mine} ends, or 0. */
    public static long vassalUntil(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return 0;
        }
        final Relation r = of(level).relation(mine.getID(), theirs);
        return r.lord != 0 && level.getGameTime() <= r.vassalUntil ? r.vassalUntil : 0;
    }

    /** Every colony that is this one's vassal right now. */
    public static List<Integer> vassalsOf(final IColony mine) {
        final List<Integer> out = new ArrayList<>();
        final ServerLevel level = level(mine);
        if (level == null) {
            return out;
        }
        final long now = level.getGameTime();
        for (final Relation r : of(level).pairs.values()) {
            if (r.lord == mine.getID() && r.vassal != 0 && now <= r.vassalUntil) {
                out.add(r.vassal);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ changing

    /**
     * They were wronged, and they know by whom. The <b>only</b> way standing goes down.
     *
     * <p>It does not declare anything. At worst it moves the pair to {@link State#TENSE}, which
     * means a person may now choose war - not that the game has chosen it for them.</p>
     */
    public static void offend(final IColony mine, final int theirs, final int amount, final String why) {
        final ServerLevel level = level(mine);
        if (level == null || amount <= 0) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.standing = Math.max(-100, r.standing - amount);
        if (r.state == State.PEACE || r.state == State.WARM) {
            r.state = r.standing <= threshold() ? State.TENSE : r.standing >= WarConfig.allyThreshold() ? State.WARM : State.PEACE;
        }
        data.setDirty();
        Warfare.LOGGER.info("[standing] {} lost {} with colony {} ({}) - now {} and {}",
                mine.getName(), amount, theirs, why, r.standing, r.state);
    }

    /** Something was made right - a tribute paid, a prisoner returned, a treaty honoured, a convoy delivered. */
    public static void mend(final IColony mine, final int theirs, final int amount) {
        final ServerLevel level = level(mine);
        if (level == null || amount <= 0) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.standing = Math.min(100, r.standing + amount);
        if (r.state == State.PEACE || r.state == State.TENSE) {
            r.state = r.standing <= threshold() ? State.TENSE : r.standing >= WarConfig.allyThreshold() ? State.WARM : State.PEACE;
        }
        data.setDirty();
    }

    /** War, declared by a person, by hand, with a goal that decides what the victor may take. */
    public static boolean declare(final IColony mine, final int theirs, final String goal) {
        final ServerLevel level = level(mine);
        if (!mayDeclare(mine, theirs) || level == null) {
            return false;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.state = State.WAR;
        r.goal = goal == null ? "" : goal;
        r.until = 0;
        r.offer = null;
        data.setDirty();
        Warfare.LOGGER.info("[war] {} declares war on colony {} - goal '{}'", mine.getName(), theirs, r.goal);
        return true;
    }

    /** A peace, and the truce that follows it: nobody may declare again for some days. */
    public static void peace(final IColony mine, final int theirs, final int mended) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.state = State.TRUCE;
        r.until = level.getGameTime() + WarConfig.truceDays() * 24000L;
        r.goal = "";
        r.offer = null;
        r.standing = Math.min(100, r.standing + Math.max(0, mended));
        data.setDirty();
        Warfare.LOGGER.info("[war] {} and colony {} are at peace - truce for {} days", mine.getName(), theirs, WarConfig.truceDays());
    }

    /** One side puts something on the table. Replaces whatever was there. */
    public static void offer(final IColony mine, final int theirs, final String kind, final String terms, final int gold) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.offer = new Offer(mine.getID(), kind, terms == null ? "white" : terms, Math.max(0, gold),
                level.getGameTime() + WarConfig.offerDays() * 24000L);
        data.setDirty();
        Warfare.LOGGER.info("[war] {} offers colony {} {}: {} {}", mine.getName(), theirs, kind, r.offer.terms(), r.offer.gold());
    }

    /** The offer is taken off the table - refused, accepted, or overtaken by events. */
    public static void clearOffer(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        if (r.offer != null) {
            r.offer = null;
            data.setDirty();
        }
    }

    /** Sworn, by both: the offer was made and accepted. */
    public static boolean ally(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return false;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        if (r.state == State.WAR) {
            return false;
        }
        r.state = State.ALLIED;
        r.offer = null;
        r.goal = "";
        r.until = 0;
        r.standing = Math.max(r.standing, WarConfig.allyThreshold());
        data.setDirty();
        Warfare.LOGGER.info("[war] {} and colony {} are allied", mine.getName(), theirs);
        return true;
    }

    /**
     * Walked out of. A pact you can leave for free is a coupon, so leaving costs the standing it
     * was built on: the pair is back at zero, and it is a season's work to get back up.
     */
    public static boolean breakAlliance(final IColony mine, final int theirs) {
        final ServerLevel level = level(mine);
        if (level == null) {
            return false;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        if (r.state != State.ALLIED) {
            return false;
        }
        r.state = State.PEACE;
        r.standing = 0;
        r.offer = null;
        data.setDirty();
        Warfare.LOGGER.info("[war] {} has broken its alliance with colony {}", mine.getName(), theirs);
        return true;
    }

    /**
     * Conquest: the loser is the winner's vassal until {@code until}, the war is over, and the
     * truce runs until the vassalage is over and then some. The winner's standing with its new
     * vassal is what it is; being conquered is not a friendship.
     */
    public static void subjugate(final IColony lord, final int vassal, final long until) {
        final ServerLevel level = level(lord);
        if (level == null) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(lord.getID(), vassal);
        r.state = State.TRUCE;
        r.goal = "";
        r.offer = null;
        r.lord = lord.getID();
        r.vassal = vassal;
        r.vassalUntil = until;
        r.until = until + WarConfig.truceDays() * 24000L;
        data.setDirty();
        Warfare.LOGGER.info("[war] colony {} is now the vassal of {} until tick {}", vassal, lord.getName(), until);
    }

    /** The vassalage is over early - the lord let it go, or the pair made a peace that says so. */
    public static void freeVassal(final IColony lord, final int vassal) {
        final ServerLevel level = level(lord);
        if (level == null) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(lord.getID(), vassal);
        if (r.lord != 0) {
            r.lord = 0;
            r.vassal = 0;
            r.vassalUntil = 0;
            data.setDirty();
        }
    }

    // ------------------------------------------------------------------ convoys

    /** How many convoys have left {@code from} for {@code to} in the last seven days. */
    public static int convoysThisWeek(final ServerLevel level, final String from, final String to) {
        final List<Long> days = of(level).convoys.get(from + "->" + to);
        if (days == null) {
            return 0;
        }
        final long today = level.getGameTime() / 24000L;
        int n = 0;
        for (final long day : days) {
            if (today - day < 7) {
                n++;
            }
        }
        return n;
    }

    /** One more convoy left today; days older than a week are forgotten. */
    public static void noteConvoy(final ServerLevel level, final String from, final String to) {
        final Standing data = of(level);
        final long today = level.getGameTime() / 24000L;
        final List<Long> days = data.convoys.computeIfAbsent(from + "->" + to, k -> new ArrayList<>());
        days.removeIf(day -> today - day >= 7);
        days.add(today);
        data.setDirty();
    }

    // ------------------------------------------------------------------ the warband

    /** Mark a guard as marched out. A colony whose army is away is soft, and visibly so. */
    public static void setOnCampaign(final ICitizenData citizen, final boolean away) {
        if (citizen == null || citizen.getColony() == null
                || !(citizen.getColony().getWorld() instanceof ServerLevel level)) {
            return;
        }
        final Standing data = of(level);
        final int id = citizen.getColony().getID() * 100000 + citizen.getId();
        if (away ? data.campaigning.add(id) : data.campaigning.remove(id)) {
            data.setDirty();
        }
    }

    /** Whether this citizen fights for a living: a knight, ranger, druid - not the explorer. */
    public static boolean isGuard(final ICitizenData citizen) {
        try {
            return citizen != null && citizen.getJob(com.minecolonies.core.colony.jobs.AbstractJobGuard.class) != null;
        } catch (final Throwable t) {
            return false;
        }
    }

    public static boolean onCampaign(final ICitizenData citizen) {
        if (citizen == null || citizen.getColony() == null
                || !(citizen.getColony().getWorld() instanceof ServerLevel level)) {
            return false;
        }
        return of(level).campaigning.contains(citizen.getColony().getID() * 100000 + citizen.getId());
    }

    // ------------------------------------------------------------------ saving

    private static Standing read(final CompoundTag tag, final HolderLookup.Provider provider) {
        final Standing out = new Standing();
        final CompoundTag pairs = tag.getCompound(TAG_PAIRS);
        for (final String key : pairs.getAllKeys()) {
            final CompoundTag one = pairs.getCompound(key);
            final Relation r = new Relation();
            r.standing = one.getInt(TAG_STANDING);
            try {
                r.state = State.valueOf(one.getString(TAG_STATE));
            } catch (final IllegalArgumentException e) {
                r.state = State.PEACE;
            }
            r.until = one.getLong(TAG_UNTIL);
            r.goal = one.getString(TAG_GOAL);
            if (one.contains("offerFrom")) {
                r.offer = new Offer(one.getInt("offerFrom"), one.getString("offerKind"), one.getString("offerTerms"),
                        one.getInt("offerGold"), one.getLong("offerUntil"));
            }
            r.lord = one.getInt("lord");
            r.vassal = one.getInt("vassal");
            r.vassalUntil = one.getLong("vassalUntil");
            out.pairs.put(key, r);
        }
        for (final int id : tag.getIntArray(TAG_CAMPAIGN)) {
            out.campaigning.add(id);
        }
        final CompoundTag convoys = tag.getCompound("convoys");
        for (final String key : convoys.getAllKeys()) {
            final List<Long> days = new ArrayList<>();
            for (final long day : convoys.getLongArray(key)) {
                days.add(day);
            }
            out.convoys.put(key, days);
        }
        return out;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        final CompoundTag pairs = new CompoundTag();
        for (final Map.Entry<String, Relation> e : this.pairs.entrySet()) {
            final Relation r = e.getValue();
            final CompoundTag one = new CompoundTag();
            one.putInt(TAG_STANDING, r.standing);
            one.putString(TAG_STATE, r.state.name());
            one.putLong(TAG_UNTIL, r.until);
            one.putString(TAG_GOAL, r.goal);
            if (r.offer != null) {
                one.putInt("offerFrom", r.offer.from());
                one.putString("offerKind", r.offer.kind());
                one.putString("offerTerms", r.offer.terms());
                one.putInt("offerGold", r.offer.gold());
                one.putLong("offerUntil", r.offer.until());
            }
            one.putInt("lord", r.lord);
            one.putInt("vassal", r.vassal);
            one.putLong("vassalUntil", r.vassalUntil);
            pairs.put(e.getKey(), one);
        }
        tag.put(TAG_PAIRS, pairs);
        final int[] away = new int[campaigning.size()];
        int i = 0;
        for (final Integer id : campaigning) {
            away[i++] = id;
        }
        tag.putIntArray(TAG_CAMPAIGN, away);
        final CompoundTag convoys = new CompoundTag();
        for (final Map.Entry<String, List<Long>> e : this.convoys.entrySet()) {
            final long[] days = new long[e.getValue().size()];
            for (int d = 0; d < days.length; d++) {
                days[d] = e.getValue().get(d);
            }
            convoys.putLongArray(e.getKey(), days);
        }
        tag.put("convoys", convoys);
        return tag;
    }
}
