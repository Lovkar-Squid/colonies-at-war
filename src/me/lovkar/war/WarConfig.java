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
}
