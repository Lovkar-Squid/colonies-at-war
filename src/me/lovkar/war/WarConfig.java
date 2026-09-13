package me.lovkar.war;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * How much war this server wants.
 *
 * <p>Marko's call, and the right one: <b>all three depths ship, and the server chooses which is
 * active.</b> A skirmish is a report and a bruise; a siege breaks walls and takes prisoners; a
 * conquest moves borders. One setting, and nobody has to install a different mod to play at a
 * different intensity.</p>
 */
public final class WarConfig {
    private WarConfig() {
    }

    /** Nothing is permanent: a battle resolves as a report, the losers go home hurt. */
    public static final String SKIRMISH = "skirmish";
    /** Engines, broken walls, wounded and captured citizens, a colony knocked down a level. */
    public static final String SIEGE = "siege";
    /** Territory, captured buildings, tribute, vassalage and terms. */
    public static final String CONQUEST = "conquest";

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<String> WAR_LEVEL;
    private static final ModConfigSpec.BooleanValue WALLS_BREAK;
    private static final ModConfigSpec.IntValue THRESHOLD;
    private static final ModConfigSpec.BooleanValue STYLE_PACK_WALLS;
    private static final ModConfigSpec.ConfigValue<String> EXTRA_WALL_FOLDERS;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Colonies at War").push("war");
        WAR_LEVEL = builder
                .comment("How far a war may go: skirmish, siege or conquest.",
                        "skirmish  - battles resolve as a report; nothing is destroyed and nobody is taken.",
                        "siege     - engines, broken walls, wounded and captured citizens.",
                        "conquest  - all of the above, plus territory, tribute and terms.")
                .define("warLevel", SKIRMISH, v -> SKIRMISH.equals(v) || SIEGE.equals(v) || CONQUEST.equals(v));
        WALLS_BREAK = builder
                .comment("Raiders and enemy warbands may break wall blocks. Off makes walls scenery;",
                        "on is the point of them - a wall you cannot lose is a wall nobody attacks.")
                .define("wallsCanBreak", true);
        THRESHOLD = builder
                .comment("Standing at or below which the declare-war button unlocks. War is never declared",
                        "by the game; this only decides when a person is allowed to declare it.")
                .defineInRange("warThreshold", -40, -100, 0);
        STYLE_PACK_WALLS = builder
                .comment("Count walls built from other style packs, not only this mod's own pieces.",
                        "Nearly every MineColonies style pack ships a walls/ category - Caledonia's",
                        "curtain walls, the Fortress ramparts, Nordic's palisade. With this on, a town",
                        "ringed with those scores as walled, measured inside the decoration's own",
                        "corners. Off leaves only blocks this mod placed itself counting.")
                .define("countStylePackWalls", true);
        EXTRA_WALL_FOLDERS = builder
                .comment("Extra blueprint folder names that mean 'this is a wall', comma separated.",
                        "Only needed for a style pack that files its walls under a name of its own;",
                        "walls, wall, gate, gates, fortifications, rampart, ramparts and palisade are",
                        "always counted, and moat, fence, misc, hedge and path never are.")
                .define("extraWallFolders", "");
        builder.pop();
        SPEC = builder.build();
    }

    public static String warLevel() {
        try {
            return WAR_LEVEL.get();
        } catch (final Throwable t) {
            return SKIRMISH;
        }
    }

    public static boolean wallsCanBreak() {
        try {
            return WALLS_BREAK.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static int threshold() {
        try {
            return THRESHOLD.get();
        } catch (final Throwable t) {
            return -40;
        }
    }

    public static boolean countStylePackWalls() {
        try {
            return STYLE_PACK_WALLS.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    /** The extra folder names, lower case and already split - empty when nobody has added any. */
    public static java.util.Set<String> extraWallFolders() {
        final String raw;
        try {
            raw = EXTRA_WALL_FOLDERS.get();
        } catch (final Throwable t) {
            return java.util.Set.of();
        }
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        if (raw.equals(extraRaw)) {
            return extraSplit;
        }
        final java.util.Set<String> out = new java.util.HashSet<>();
        for (final String part : raw.toLowerCase(java.util.Locale.ROOT).split(",")) {
            final String one = part.trim();
            if (!one.isEmpty()) {
                out.add(one);
            }
        }
        extraRaw = raw;
        extraSplit = java.util.Set.copyOf(out);
        return extraSplit;
    }

    /** The split is cached because it is asked for once per block of every wall decoration. */
    private static volatile String extraRaw = null;
    private static volatile java.util.Set<String> extraSplit = java.util.Set.of();
}
