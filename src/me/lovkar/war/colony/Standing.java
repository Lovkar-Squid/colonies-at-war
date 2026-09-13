package me.lovkar.war.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.lovkar.war.Warfare;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * How two colonies feel about each other, and whether they are at war.
 *
 * <p>Two rules are built into this class rather than written down somewhere and hoped for.</p>
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
 */
public class Standing extends SavedData {
    private static final String NAME = "colonies_at_war_standing";
    private static final String TAG_PAIRS = "pairs";
    private static final String TAG_STANDING = "standing";
    private static final String TAG_STATE = "state";
    private static final String TAG_UNTIL = "until";
    private static final String TAG_GOAL = "goal";
    private static final String TAG_CAMPAIGN = "campaign";

    /** Standing has to be at or below this before the declare button unlocks. */
    public static final int WAR_THRESHOLD = -40;
    /** A peace holds for this long before anybody may declare again: seven days. */
    public static final long TRUCE_TICKS = 24000L * 7;

    public enum State {
        /** Nothing between them. */
        PEACE,
        /** Bad blood. War may be declared, by hand, by a person. */
        TENSE,
        /** Openly at war. */
        WAR,
        /** A peace was made and neither may declare again until it runs out. */
        TRUCE
    }

    /** What stands between two colonies. */
    public static final class Relation {
        public int standing;
        public State state = State.PEACE;
        public long until;
        public String goal = "";

        Relation() {
        }
    }

    private final Map<String, Relation> pairs = new HashMap<>();
    /** Citizens who have marched out. Kept by colony so a reload does not strand a warband. */
    private final Set<Integer> campaigning = new HashSet<>();

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

    // ------------------------------------------------------------------ reading

    public static int between(final IColony mine, final int theirs) {
        if (mine == null || !(mine.getWorld() instanceof ServerLevel level)) {
            return 0;
        }
        return of(level).relation(mine.getID(), theirs).standing;
    }

    public static State state(final IColony mine, final int theirs) {
        if (mine == null || !(mine.getWorld() instanceof ServerLevel level)) {
            return State.PEACE;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        if (r.state == State.TRUCE && level.getGameTime() > r.until) {
            r.state = r.standing <= WAR_THRESHOLD ? State.TENSE : State.PEACE;
            data.setDirty();
        }
        return r.state;
    }

    public static boolean atWar(final IColony mine, final int theirs) {
        return state(mine, theirs) == State.WAR;
    }

    /**
     * Whether the declare button is unlocked. Note what this is not: it is not a decision, and
     * nothing acts on it. A person reads it and chooses.
     */
    public static boolean mayDeclare(final IColony mine, final int theirs) {
        final State now = state(mine, theirs);
        if (now == State.WAR || now == State.TRUCE) {
            return false;
        }
        return between(mine, theirs) <= WAR_THRESHOLD;
    }

    // ------------------------------------------------------------------ changing

    /**
     * They were wronged, and they know by whom. The <b>only</b> way standing goes down.
     *
     * <p>It does not declare anything. At worst it moves the pair to {@link State#TENSE}, which
     * means a person may now choose war - not that the game has chosen it for them.</p>
     */
    public static void offend(final IColony mine, final int theirs, final int amount, final String why) {
        if (mine == null || !(mine.getWorld() instanceof ServerLevel level) || amount <= 0) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.standing = Math.max(-100, r.standing - amount);
        if (r.state == State.PEACE && r.standing <= WAR_THRESHOLD) {
            r.state = State.TENSE;
        }
        data.setDirty();
        Warfare.LOGGER.info("[standing] {} lost {} with colony {} ({}) - now {} and {}",
                mine.getName(), amount, theirs, why, r.standing, r.state);
    }

    /** Something was made right - a tribute paid, a prisoner returned, a treaty honoured. */
    public static void mend(final IColony mine, final int theirs, final int amount) {
        if (mine == null || !(mine.getWorld() instanceof ServerLevel level) || amount <= 0) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.standing = Math.min(100, r.standing + amount);
        data.setDirty();
    }

    /** War, declared by a person, by hand, with a goal that decides what the victor may take. */
    public static boolean declare(final IColony mine, final int theirs, final String goal) {
        if (!mayDeclare(mine, theirs) || !(mine.getWorld() instanceof ServerLevel level)) {
            return false;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.state = State.WAR;
        r.goal = goal == null ? "" : goal;
        r.until = 0;
        data.setDirty();
        Warfare.LOGGER.info("[war] {} declares war on colony {} - goal '{}'", mine.getName(), theirs, r.goal);
        return true;
    }

    /** A peace, and the truce that follows it: nobody may declare again for seven days. */
    public static void peace(final IColony mine, final int theirs, final int mended) {
        if (mine == null || !(mine.getWorld() instanceof ServerLevel level)) {
            return;
        }
        final Standing data = of(level);
        final Relation r = data.relation(mine.getID(), theirs);
        r.state = State.TRUCE;
        r.until = level.getGameTime() + TRUCE_TICKS;
        r.goal = "";
        r.standing = Math.min(100, r.standing + Math.max(0, mended));
        data.setDirty();
        Warfare.LOGGER.info("[war] {} and colony {} are at peace - truce for seven days", mine.getName(), theirs);
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
            r.state = State.valueOf(one.getString(TAG_STATE));
            r.until = one.getLong(TAG_UNTIL);
            r.goal = one.getString(TAG_GOAL);
            out.pairs.put(key, r);
        }
        for (final int id : tag.getIntArray(TAG_CAMPAIGN)) {
            out.campaigning.add(id);
        }
        return out;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        final CompoundTag pairs = new CompoundTag();
        for (final Map.Entry<String, Relation> e : this.pairs.entrySet()) {
            final CompoundTag one = new CompoundTag();
            one.putInt(TAG_STANDING, e.getValue().standing);
            one.putString(TAG_STATE, e.getValue().state.name());
            one.putLong(TAG_UNTIL, e.getValue().until);
            one.putString(TAG_GOAL, e.getValue().goal);
            pairs.put(e.getKey(), one);
        }
        tag.put(TAG_PAIRS, pairs);
        final int[] away = new int[campaigning.size()];
        int i = 0;
        for (final Integer id : campaigning) {
            away[i++] = id;
        }
        tag.putIntArray(TAG_CAMPAIGN, away);
        return tag;
    }
}
