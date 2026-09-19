package me.lovkar.war.compat;

import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Waking World bridge - the only class here that knows that mod exists.
 *
 * <p>Reflection rather than a compile-time link, the same rule as Colonist Thieves' bridge and
 * Waking World's own {@code compat/Colonies}: this mod must build and run in a pack that has never
 * heard of The Waking World, and without it a warband simply has no kingdom to march on. Everything
 * read here is public in Waking World's {@code KingdomData}: a kingdom's centre, tier, standing with
 * the player, the wall arcs it has raised, its catapults and the houses outside its moat - the whole
 * assessment, no scanning, because the kingdom already knows. Two things are written: standing,
 * through Waking World's own {@code KingdomGrowth.favour}, and anger, into the same map its guards
 * read.</p>
 *
 * <p>If any lookup fails once, this class goes quiet for the rest of the run rather than throwing
 * on every march.</p>
 */
public final class Kingdoms {
    private Kingdoms() {
    }

    private static final boolean PRESENT = lookFor();
    private static boolean broken = false;
    private static java.lang.reflect.Method dataGet;
    private static java.lang.reflect.Method allKingdoms;
    private static java.lang.reflect.Method kingdomAt;
    private static java.lang.reflect.Method kingdomName;
    private static java.lang.reflect.Method favour;
    private static Class<?> kingdomClass;

    private static boolean lookFor() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("wakingworld");
        } catch (final Throwable t) {
            return false;
        }
    }

    public static boolean present() {
        return PRESENT && !broken;
    }

    private static void link() throws Exception {
        if (dataGet != null) {
            return;
        }
        final Class<?> data = Class.forName("me.lovkar.wakingworld.kingdom.KingdomData");
        kingdomClass = Class.forName("me.lovkar.wakingworld.kingdom.KingdomData$Kingdom");
        dataGet = data.getMethod("get", ServerLevel.class);
        kingdomAt = data.getMethod("kingdomAt", BlockPos.class);
        kingdomName = Class.forName("me.lovkar.wakingworld.kingdom.Kingdoms").getMethod("name", BlockPos.class);
        favour = Class.forName("me.lovkar.wakingworld.kingdom.KingdomGrowth")
                .getMethod("favour", ServerLevel.class, BlockPos.class, int.class, String.class);
    }

    // ------------------------------------------------------------------ reading

    /** Every kingdom this world has raised, or an empty list. */
    @SuppressWarnings("unchecked")
    public static Collection<Object> all(final ServerLevel level) {
        if (!present()) {
            return List.of();
        }
        try {
            link();
            final Object data = dataGet.invoke(null, level);
            if (allKingdoms == null) {
                allKingdoms = data.getClass().getMethod("all");
            }
            final Object list = allKingdoms.invoke(data);
            return list instanceof Collection<?> c ? new ArrayList<>((Collection<Object>) c) : List.of();
        } catch (final Throwable t) {
            quiet(t);
            return List.of();
        }
    }

    /** The kingdom whose land this is (within 120 blocks of its keep), or null. Opaque on purpose. */
    public static Object at(final ServerLevel level, final BlockPos pos) {
        if (!present()) {
            return null;
        }
        try {
            link();
            return kingdomAt.invoke(dataGet.invoke(null, level), pos);
        } catch (final Throwable t) {
            quiet(t);
            return null;
        }
    }

    public static String name(final Object kingdom) {
        try {
            return (String) kingdomName.invoke(null, centre(kingdom));
        } catch (final Throwable t) {
            return "a kingdom";
        }
    }

    public static BlockPos centre(final Object kingdom) {
        return (BlockPos) field(kingdom, "center", BlockPos.ZERO);
    }

    /** How the kingdom feels about the player: -100..100. */
    public static int standing(final Object kingdom) {
        return (Integer) field(kingdom, "standing", 0);
    }

    /** 1 hamlet .. 4 city. Everything about a kingdom's strength scales off it. */
    public static int tier(final Object kingdom) {
        return (Integer) field(kingdom, "tier", 1);
    }

    /** How many arcs of wall the town has raised - a wall score that needed no measuring. */
    public static int wallArcs(final Object kingdom) {
        return (Integer) field(kingdom, "wallArcs", 0);
    }

    /** Engines on the walls. */
    public static int catapults(final Object kingdom) {
        final Object set = field(kingdom, "catapults", null);
        return set instanceof Collection<?> c ? c.size() : 0;
    }

    /** Houses raised outside the moat: the closest thing a kingdom has to a population. */
    public static int houses(final Object kingdom) {
        final Object set = field(kingdom, "houses", null);
        return set instanceof Collection<?> c ? c.size() : 0;
    }

    /**
     * What stands against a warband - the same figures Colonist Thieves shows for a kingdom, so
     * "an army against 72" reads the same in both mods: a garrison of {@code 2 + tier*2 +
     * catapults} at four apiece, the keep at {@code tier*2} at two, and twelve for every arc of wall.
     */
    public static int defence(final Object kingdom) {
        final int tier = tier(kingdom);
        final int garrison = 2 + tier * 2 + catapults(kingdom);
        final int walls = Math.min(100, wallArcs(kingdom) * 12);
        return garrison * 4 + tier * 4 + walls;
    }

    /** A kingdom's wealth in the thief's units: {@code 25 + tier*12 + houses}, capped at 100. */
    public static int wealth(final Object kingdom) {
        return Math.min(100, 25 + tier(kingdom) * 12 + houses(kingdom));
    }

    // ------------------------------------------------------------------ writing

    /** Move the kingdom's standing with the player, through Waking World's own door. */
    public static void favour(final ServerLevel level, final Object kingdom, final int by, final String why) {
        if (!present() || kingdom == null || by == 0) {
            return;
        }
        try {
            link();
            favour.invoke(null, level, centre(kingdom), by, why);
        } catch (final Throwable t) {
            quiet(t);
        }
    }

    /**
     * The kingdom is angry with this player for a while: its guards turn on him within reach, its
     * traders refuse him, its king will not see him. Written into the map Waking World's guards read.
     */
    @SuppressWarnings("unchecked")
    public static void anger(final ServerLevel level, final Object kingdom, final UUID player, final int ticks) {
        if (!present() || kingdom == null || player == null || ticks <= 0) {
            return;
        }
        try {
            link();
            final Object map = kingdomClass.getField("angryUntil").get(kingdom);
            if (map instanceof Map<?, ?>) {
                ((Map<UUID, Long>) map).merge(player, level.getGameTime() + ticks, Math::max);
            }
            final Object permitted = kingdomClass.getField("permitted").get(kingdom);
            if (permitted instanceof Collection<?> c) {
                c.remove(player);
            }
            final Object data = dataGet.invoke(null, level);
            data.getClass().getMethod("setDirty").invoke(data);
        } catch (final Throwable t) {
            quiet(t);
        }
    }

    /** Whether the kingdom is angry with this player right now. */
    @SuppressWarnings("unchecked")
    public static boolean isAngry(final ServerLevel level, final Object kingdom, final UUID player) {
        if (!present() || kingdom == null || player == null) {
            return false;
        }
        try {
            link();
            final Object map = kingdomClass.getField("angryUntil").get(kingdom);
            if (map instanceof Map<?, ?> m) {
                final Object until = ((Map<UUID, Long>) m).get(player);
                return until instanceof Long l && l >= level.getGameTime();
            }
        } catch (final Throwable t) {
            quiet(t);
        }
        return false;
    }

    // ------------------------------------------------------------------ plumbing

    private static Object field(final Object kingdom, final String name, final Object fallback) {
        if (kingdom == null || kingdomClass == null) {
            return fallback;
        }
        try {
            return kingdomClass.getField(name).get(kingdom);
        } catch (final Throwable t) {
            return fallback;
        }
    }

    private static void quiet(final Throwable t) {
        if (!broken) {
            broken = true;
            Warfare.LOGGER.warn("[kingdoms] The Waking World is here but cannot be read - no kingdom will be marched on this session: {}", t.toString());
        }
    }
}
