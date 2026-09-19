package me.lovkar.war.campaign;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.war.colony.Standing;
import me.lovkar.war.compat.Kingdoms;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the War Room's table shows and does - the same things {@code /war targets}, {@code /war
 * march} and {@code /war explore} do, gathered once so the window and the commands cannot drift.
 *
 * <p>Everything here runs on the server. The window is handed a list of targets it may only
 * point at; what it asks for comes back through {@code WarTableMessage} and is re-resolved and
 * re-checked here, so a client that invents a target gets nothing.</p>
 */
public final class WarTable {
    private WarTable() {
    }

    /**
     * Somewhere a warband could march: what it is, where, what it is worth, and why not - and
     * what else stands between us: the state of the pair, an offer on the table, whether a convoy
     * could go, a vassalage either way.
     *
     * @param state  peace, tense, war, truce, warm, allied - or "" for what has no standing with us
     * @param offer  the offer on the table, described from our side, or ""
     * @param ours   whether that offer is ours (waiting for their answer) rather than theirs
     * @param convoy why a convoy cannot go, or "" when it can
     * @param bond   "their vassal for N days" / "our lord for N days" / ""
     */
    public record Target(String kind, int id, BlockPos pos, String name, int distance, String direction, int strength,
                         int standing, boolean atWar, String reason, String state, String offer, boolean ours, String convoy,
                         String bond) {
    }

    private record Cached(long at, List<Places.Place> places) {
    }

    private static final Map<Integer, Cached> nearby = new HashMap<>();
    private static final long KEEP = 6000L;

    /** The nearest camp and village, remembered five minutes: finding a structure is not free. */
    private static synchronized List<Places.Place> places(final ServerLevel level, final IColony mine) {
        final Cached cached = nearby.get(mine.getID());
        final long now = level.getGameTime();
        if (cached != null && now - cached.at() < KEEP) {
            return cached.places();
        }
        final List<Places.Place> found = new ArrayList<>();
        for (final String kind : new String[] {Places.CAMP, Places.VILLAGE}) {
            final Places.Place place = Places.nearest(level, mine.getCenter(), kind);
            if (place != null) {
                found.add(place);
            }
        }
        nearby.put(mine.getID(), new Cached(now, found));
        return found;
    }

    /** Everything a warband could march on from this colony, nearest first. */
    public static List<Target> targets(final ServerLevel level, final IColony mine) {
        final List<Target> out = new ArrayList<>();
        final BlockPos home = mine.getCenter();
        for (final IColony colony : IColonyManager.getInstance().getColonies(level)) {
            if (colony.getID() == mine.getID()) {
                continue;
            }
            final String no = Campaigns.cannotMarch(level, mine, colony.getID());
            final Standing.Offer offer = Standing.offerBetween(mine, colony.getID());
            final boolean ours = offer != null && offer.from() == mine.getID();
            final String convoy = Campaigns.cannotConvoy(level, mine, colony.getID());
            out.add(new Target("colony", colony.getID(), colony.getCenter(), colony.getName(),
                    (int) Math.sqrt(colony.getCenter().distSqr(home)), Expeditions.direction(home, colony.getCenter()),
                    Battle.defence(colony), Standing.between(mine, colony.getID()), Standing.atWar(mine, colony.getID()),
                    brief(no), state(mine, colony.getID()), offer == null ? "" : offer.describe(ours), ours,
                    convoy == null ? "" : brief(convoy), bond(level, mine, colony)));
        }
        for (final Object kingdom : Kingdoms.all(level)) {
            final BlockPos centre = Kingdoms.centre(kingdom);
            final String no = Campaigns.cannotMarchOn(level, mine, kingdom);
            final String convoy = Campaigns.cannotConvoyTo(level, mine, kingdom);
            out.add(new Target("kingdom", 0, centre, Kingdoms.name(kingdom) + " (kingdom)",
                    (int) Math.sqrt(centre.distSqr(home)), Expeditions.direction(home, centre), Kingdoms.defence(kingdom),
                    Kingdoms.standing(kingdom), false, brief(no), "", "", false, convoy == null ? "" : brief(convoy), ""));
        }
        for (final Places.Place place : places(level, mine)) {
            final String no = Campaigns.cannotMarchOnPlace(level, mine, place);
            out.add(new Target(place.kind(), 0, place.pos(), place.name(), (int) Math.sqrt(place.pos().distSqr(home)),
                    Expeditions.direction(home, place.pos()), Places.defence(level, place), 0, false, brief(no), "", "", false,
                    "no road for a convoy", ""));
        }
        out.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        return out;
    }

    /**
     * March on whatever stands at a position - a colony, a kingdom, a camp, a village - the way
     * {@code /war march at} does. Returns the campaign or null with {@code why} filled in.
     */
    public static Campaigns.Campaign marchAt(final ServerLevel level, final IColony mine, final BlockPos at, final int guards,
                                             final StringBuilder why) {
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, at);
        if (colony != null) {
            final String no = Campaigns.cannotMarch(level, mine, colony.getID());
            if (no != null) {
                why.append(no);
                return null;
            }
            return Campaigns.march(level, mine, colony.getID(), guards, why);
        }
        final Object kingdom = Kingdoms.at(level, at);
        if (kingdom != null) {
            return Campaigns.marchOn(level, mine, kingdom, guards, why);
        }
        final Places.Place place = Places.at(level, at);
        if (place == null) {
            why.append("nothing to march on at ").append(at.getX()).append(", ").append(at.getZ())
                    .append(" - no colony, no camp, no village").append(Kingdoms.present() ? ", no kingdom" : "");
            return null;
        }
        return Campaigns.marchOnPlace(level, mine, place, guards, why);
    }

    /** A vassalage between the two, as a short line, or "". */
    private static String bond(final ServerLevel level, final IColony mine, final IColony other) {
        final long now = level.getGameTime();
        final long until = Standing.vassalUntil(mine, other.getID());
        if (until <= 0) {
            return "";
        }
        final long days = Math.max(0, (until - now + 23999) / 24000L);
        if (Standing.isVassalOf(mine, other.getID())) {
            return "their vassal for " + days + (days == 1 ? " day" : " days");
        }
        return "your lord for " + days + (days == 1 ? " day" : " days");
    }

    /** The reason a march is refused, short enough for a line on the table. */
    public static String brief(final String reason) {
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        if (reason.startsWith("not at war")) {
            return "not at war - declare it first";
        }
        if (reason.startsWith("the men will not march on their own lord")) {
            return "they are your lord";
        }
        if (reason.startsWith("at war - the road")) {
            return "at war - the road is closed";
        }
        if (reason.startsWith("enough convoys")) {
            return "enough convoys this week";
        }
        if (reason.startsWith("a convoy is already")) {
            return "a convoy is already out";
        }
        if (reason.startsWith("nothing loaded at the War Room")) {
            return "nothing loaded - see the convoy tab";
        }
        if (reason.startsWith("nothing on the manifest has reached")) {
            return "the couriers are still bringing it";
        }
        if (reason.startsWith("nobody at the War Room is fit to escort")) {
            return "nobody fit to escort it";
        }
        if (reason.startsWith("the men will not march")) {
            return "the men are too weary";
        }
        if (reason.startsWith("no War Room")) {
            return "no War Room standing";
        }
        if (reason.startsWith("nobody at the War Room")) {
            return "nobody fit to march";
        }
        final int dash = reason.indexOf(" - ");
        return dash > 0 ? reason.substring(0, dash) : reason;
    }

    /** "at war" / "peace" / "truce" - what the standing means, for a line on the table. */
    public static String state(final IColony mine, final int theirs) {
        try {
            return Standing.state(mine, theirs).name().toLowerCase(Locale.ROOT);
        } catch (final Throwable t) {
            return "";
        }
    }
}
