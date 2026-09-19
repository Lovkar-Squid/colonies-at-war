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
    private static final ModConfigSpec.BooleanValue FIND_THE_RAIDERS;
    private static final ModConfigSpec.BooleanValue SIEGE_IN_WORLD;
    private static final ModConfigSpec.ConfigValue<String> ARMY_LOOK;
    private static final ModConfigSpec.IntValue SIEGE_NIGHTS;
    private static final ModConfigSpec.DoubleValue MARCH_MINUTES_PER_200;
    private static final ModConfigSpec.DoubleValue MARCH_MINIMUM_MINUTES;
    private static final ModConfigSpec.IntValue PLUNDER_ROLLS;
    private static final ModConfigSpec.IntValue RATIONS_PER_GUARD;
    private static final ModConfigSpec.BooleanValue MARCH_ON_KINGDOMS;
    private static final ModConfigSpec.IntValue KINGDOM_STANDING_COST;
    private static final ModConfigSpec.BooleanValue KINGDOMS_STRIKE_BACK;
    private static final ModConfigSpec.BooleanValue MARCH_ON_CAMPS;
    private static final ModConfigSpec.IntValue CAMPAIGN_EXPERIENCE;
    private static final ModConfigSpec.BooleanValue CAPTIVES;
    private static final ModConfigSpec.IntValue RANSOM_GOLD;
    private static final ModConfigSpec.IntValue RANSOM_DAYS;
    private static final ModConfigSpec.IntValue REPRISAL_DAYS;
    private static final ModConfigSpec.BooleanValue EXPEDITIONS;
    private static final ModConfigSpec.IntValue EXPEDITION_RANGE;
    private static final ModConfigSpec.DoubleValue EXPLORE_MINUTES_PER_LEVEL;
    private static final ModConfigSpec.IntValue EXPEDITION_ROLLS;
    private static final ModConfigSpec.IntValue TRUCE_DAYS;
    private static final ModConfigSpec.IntValue OFFER_DAYS;
    private static final ModConfigSpec.IntValue MERCENARY_GOLD;
    private static final ModConfigSpec.IntValue MERCENARIES_PER_LEVEL;
    private static final ModConfigSpec.IntValue MERCENARY_DAYS;
    private static final ModConfigSpec.IntValue VASSAL_DAYS;
    private static final ModConfigSpec.IntValue TRIBUTE_STACKS;
    private static final ModConfigSpec.BooleanValue CONVOYS;
    private static final ModConfigSpec.IntValue CONVOY_STACKS;
    private static final ModConfigSpec.IntValue CONVOY_STANDING;
    private static final ModConfigSpec.IntValue CONVOYS_PER_WEEK;
    private static final ModConfigSpec.IntValue ALLY_THRESHOLD;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Colonies at War").push("war");
        WAR_LEVEL = builder
                .comment("How far a war may go: skirmish, siege or conquest.",
                        "skirmish  - a warband marches and a battle is fought, in the world or as a number, and the",
                        "            winner carries off plunder; but no wall is breached, nobody is taken captive, and",
                        "            peace is a white peace or gold - the war goals raze and conquer are refused.",
                        "siege     - breaches (the raze goal), captives and ransom, mercenaries.",
                        "conquest  - all of the above, plus the conquer goal: a town overrun becomes the victor's",
                        "            vassal and pays tribute out of its warehouse every day for a while.")
                .define("warLevel", SIEGE, v -> SKIRMISH.equals(v) || SIEGE.equals(v) || CONQUEST.equals(v));
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
        FIND_THE_RAIDERS = builder
                .comment("What the Find the Raiders research does. While a town is raided, its patrolling",
                        "guards are told every few seconds where the nearest raider is and go to him, and",
                        "any idle or napping guard with a raider within reach is set on him at once. Off",
                        "leaves the research in the tree doing nothing.")
                .define("findTheRaiders", true);
        builder.pop();

        builder.comment("Campaigns - the warband that marches when a war is declared").push("campaigns");
        SIEGE_IN_WORLD = builder
                .comment("When the warband arrives at a colony somebody is watching, fight it for real: a raid",
                        "at their gate, their guards against your soldiers. Off, every battle is a number and",
                        "a report, even with the defender standing on the wall.")
                .define("siegeInWorld", true);
        ARMY_LOOK = builder
                .comment("What a warband's soldiers look like: barbarian, norsemen, pirate or amazon.",
                        "They are MineColonies' raiders with a colony's name; a model of their own is later.")
                .define("armyLook", "barbarian", me.lovkar.war.campaign.ArmyLook::valid);
        SIEGE_NIGHTS = builder
                .comment("How many nights a warband stays at the walls before the fight is decided.",
                        "Soldiers still standing when the last night ends have overrun the town.")
                .defineInRange("siegeNights", 1, 1, 3);
        MARCH_MINUTES_PER_200 = builder
                .comment("Minutes of real time a warband needs per 200 blocks, each way.")
                .defineInRange("marchMinutesPer200Blocks", 1.0, 0.1, 30.0);
        MARCH_MINIMUM_MINUTES = builder
                .comment("The shortest march there is, in minutes, however close the two towns stand.")
                .defineInRange("marchMinimumMinutes", 3.0, 0.1, 60.0);
        PLUNDER_ROLLS = builder
                .comment("Rolls of the plunder loot table a decisive victory over a poor town earns;",
                        "a rich town and the 'plunder' war goal add to it.")
                .defineInRange("plunderRolls", 3, 1, 12);
        RATIONS_PER_GUARD = builder
                .comment("Food taken from the warehouse per guard when a warband musters. Short of it the men",
                        "still march, hungry, at three quarters of their strength. 0 turns supply off.")
                .defineInRange("rationsPerGuard", 2, 0, 16);
        MARCH_ON_KINGDOMS = builder
                .comment("With The Waking World installed, a warband may march on one of its kingdoms: a number,",
                        "never a raid, and the kingdom holds it against the colony's owner afterwards.")
                .define("marchOnKingdoms", true);
        KINGDOM_STANDING_COST = builder
                .comment("How much of a kingdom's standing with the player an attack on it costs; a sack costs it twice.")
                .defineInRange("kingdomStandingCost", 10, 0, 50);
        CAPTIVES = builder
                .comment("The men who fell in a lost fight are held by the colony or kingdom that beat them, up to half the",
                        "warband, until a ransom is paid (/war ransom) or they are let go. Nobody dies. Off, and they all limp home.")
                .define("captives", true);
        RANSOM_GOLD = builder
                .comment("Gold ingots a held man costs to buy back. A colony that holds him gets the gold.")
                .defineInRange("ransomGold", 4, 0, 64);
        RANSOM_DAYS = builder
                .comment("Days a held man is kept before he is let go for nothing.")
                .defineInRange("ransomDays", 3, 1, 30);
        CAMPAIGN_EXPERIENCE = builder
                .comment("Experience every guard who fought earns when the warband is home (half again for a victory),",
                        "on his guard job's own skills, so a warband that has been out is worth more. 0 turns it off.")
                .defineInRange("campaignExperience", 20, 0, 200);
        MARCH_ON_CAMPS = builder
                .comment("A warband may march on a raider camp (the camp is cleared - its spawners smashed) or a",
                        "village (robbed). What counts as which is the structure tags #colonies_at_war:camp and :village.")
                .define("marchOnCamps", true);
        KINGDOMS_STRIKE_BACK = builder
                .comment("A kingdom that was marched on sends its own men to the colony some days later - a real raid,",
                        "fought when somebody is there. Off, and a kingdom only holds a grudge.")
                .define("kingdomsStrikeBack", true);
        REPRISAL_DAYS = builder
                .comment("Days until a kingdom's men arrive after an attack on it; a sack is answered in half the time.")
                .defineInRange("reprisalDays", 2, 1, 14);
        TRUCE_DAYS = builder
                .comment("Days a truce holds after a peace: neither side may declare war again until it runs out.")
                .defineInRange("truceDays", 7, 1, 60);
        OFFER_DAYS = builder
                .comment("Days a peace offer, or an offer of alliance, stands before it lapses unanswered.")
                .defineInRange("offerDays", 2, 1, 30);
        MERCENARY_GOLD = builder
                .comment("Gold ingots a sellsword costs to hire for one campaign (at siege depth or deeper).",
                        "Sellswords wait at the War Room and go with the next warband; they are not citizens,",
                        "they cannot be held captive, and they take a share of the plunder.")
                .defineInRange("mercenaryGold", 3, 0, 64);
        MERCENARIES_PER_LEVEL = builder
                .comment("How many sellswords a War Room may keep waiting, per level of the War Room.")
                .defineInRange("mercenariesPerLevel", 2, 0, 10);
        MERCENARY_DAYS = builder
                .comment("Days sellswords wait for a campaign before they take their pay and leave.")
                .defineInRange("mercenaryDays", 3, 1, 30);
        VASSAL_DAYS = builder
                .comment("Days a town overrun by a warband whose war goal was 'conquer' stays the victor's vassal",
                        "(at conquest depth). While it does it pays tribute daily and cannot march on its lord.")
                .defineInRange("vassalDays", 7, 1, 60);
        TRIBUTE_STACKS = builder
                .comment("Stacks of goods (16 a stack) a vassal's warehouse pays its lord every day.")
                .defineInRange("tributeStacks", 2, 0, 12);
        ALLY_THRESHOLD = builder
                .comment("Standing at or above which an alliance may be offered - the mirror of warThreshold.",
                        "An alliance is never made by the game; one side offers, the other accepts.")
                .defineInRange("allyThreshold", 40, 0, 100);
        builder.pop();

        builder.comment("Convoys - goods sent to another colony or a kingdom, with an escort, for standing").push("convoys");
        CONVOYS = builder
                .comment("Whether the War Room may send convoys at all.")
                .define("convoys", true);
        CONVOY_STACKS = builder
                .comment("Stacks of goods (16 a stack) a convoy carries out of the warehouse; an ally gets three times as many.")
                .defineInRange("convoyStacks", 4, 1, 24);
        CONVOY_STANDING = builder
                .comment("Standing a convoy that arrives earns with the colony or kingdom it went to.")
                .defineInRange("convoyStanding", 8, 0, 40);
        CONVOYS_PER_WEEK = builder
                .comment("How many convoys a colony may send to the same partner in seven days. An alliance",
                        "should be a season's work, not an afternoon's.")
                .defineInRange("convoysPerWeek", 2, 1, 20);
        builder.pop();

        builder.comment("Expeditions - the War Room's Explorer, sent out to a biome with an escort").push("expeditions");
        EXPEDITIONS = builder
                .comment("Whether the War Room may send expeditions at all.")
                .define("expeditions", true);
        EXPEDITION_RANGE = builder
                .comment("How far round the colony the Explorer looks for a biome, in blocks. The country is",
                        "sampled every 64 blocks off the world's noise; nothing is loaded to ask.")
                .defineInRange("expeditionRange", 2048, 256, 8192);
        EXPLORE_MINUTES_PER_LEVEL = builder
                .comment("Minutes spent out there per level of difficulty, once the party has arrived:",
                        "easy is one level, deadly is four. The walk there and back is on top, at the",
                        "campaign's own rate.")
                .defineInRange("exploreMinutesPerLevel", 2.0, 0.1, 60.0);
        EXPEDITION_ROLLS = builder
                .comment("Base rolls on the biome's own loot table; a harder trip, a bigger escort and a",
                        "better Explorer each add to it, up to fourteen.")
                .defineInRange("expeditionRolls", 2, 0, 10);
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

    public static boolean findTheRaiders() {
        try {
            return FIND_THE_RAIDERS.get();
        } catch (final Throwable t) {
            return true;
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

    public static boolean siegeInWorld() {
        try {
            return SIEGE_IN_WORLD.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static String armyLook() {
        try {
            return ARMY_LOOK.get();
        } catch (final Throwable t) {
            return "barbarian";
        }
    }

    public static int siegeNights() {
        try {
            return SIEGE_NIGHTS.get();
        } catch (final Throwable t) {
            return 1;
        }
    }

    public static double marchMinutesPer200() {
        try {
            return MARCH_MINUTES_PER_200.get();
        } catch (final Throwable t) {
            return 1.0;
        }
    }

    public static double marchMinimumMinutes() {
        try {
            return MARCH_MINIMUM_MINUTES.get();
        } catch (final Throwable t) {
            return 3.0;
        }
    }

    public static int plunderRolls() {
        try {
            return PLUNDER_ROLLS.get();
        } catch (final Throwable t) {
            return 3;
        }
    }

    public static boolean marchOnKingdoms() {
        try {
            return MARCH_ON_KINGDOMS.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static boolean captives() {
        try {
            return CAPTIVES.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static int ransomGold() {
        try {
            return RANSOM_GOLD.get();
        } catch (final Throwable t) {
            return 4;
        }
    }

    public static int ransomDays() {
        try {
            return RANSOM_DAYS.get();
        } catch (final Throwable t) {
            return 3;
        }
    }

    public static int campaignExperience() {
        try {
            return CAMPAIGN_EXPERIENCE.get();
        } catch (final Throwable t) {
            return 20;
        }
    }

    public static boolean marchOnCamps() {
        try {
            return MARCH_ON_CAMPS.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static boolean kingdomsStrikeBack() {
        try {
            return KINGDOMS_STRIKE_BACK.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static int reprisalDays() {
        try {
            return REPRISAL_DAYS.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    public static int kingdomStandingCost() {
        try {
            return KINGDOM_STANDING_COST.get();
        } catch (final Throwable t) {
            return 10;
        }
    }

    public static boolean expeditions() {
        try {
            return EXPEDITIONS.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static int expeditionRange() {
        try {
            return EXPEDITION_RANGE.get();
        } catch (final Throwable t) {
            return 2048;
        }
    }

    public static double exploreMinutesPerLevel() {
        try {
            return EXPLORE_MINUTES_PER_LEVEL.get();
        } catch (final Throwable t) {
            return 2.0;
        }
    }

    public static int expeditionRolls() {
        try {
            return EXPEDITION_ROLLS.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    /** 0 skirmish, 1 siege, 2 conquest. */
    public static int depth() {
        final String level = warLevel();
        return CONQUEST.equals(level) ? 2 : SIEGE.equals(level) ? 1 : 0;
    }

    public static int truceDays() {
        try {
            return TRUCE_DAYS.get();
        } catch (final Throwable t) {
            return 7;
        }
    }

    public static int offerDays() {
        try {
            return OFFER_DAYS.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    public static int mercenaryGold() {
        try {
            return MERCENARY_GOLD.get();
        } catch (final Throwable t) {
            return 3;
        }
    }

    public static int mercenariesPerLevel() {
        try {
            return MERCENARIES_PER_LEVEL.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    public static int mercenaryDays() {
        try {
            return MERCENARY_DAYS.get();
        } catch (final Throwable t) {
            return 3;
        }
    }

    public static int vassalDays() {
        try {
            return VASSAL_DAYS.get();
        } catch (final Throwable t) {
            return 7;
        }
    }

    public static int tributeStacks() {
        try {
            return TRIBUTE_STACKS.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    public static int allyThreshold() {
        try {
            return ALLY_THRESHOLD.get();
        } catch (final Throwable t) {
            return 40;
        }
    }

    public static boolean convoys() {
        try {
            return CONVOYS.get();
        } catch (final Throwable t) {
            return true;
        }
    }

    public static int convoyStacks() {
        try {
            return CONVOY_STACKS.get();
        } catch (final Throwable t) {
            return 4;
        }
    }

    public static int convoyStanding() {
        try {
            return CONVOY_STANDING.get();
        } catch (final Throwable t) {
            return 8;
        }
    }

    public static int convoysPerWeek() {
        try {
            return CONVOYS_PER_WEEK.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    public static int rationsPerGuard() {
        try {
            return RATIONS_PER_GUARD.get();
        } catch (final Throwable t) {
            return 2;
        }
    }

    /** The split is cached because it is asked for once per block of every wall decoration. */
    private static volatile String extraRaw = null;
    private static volatile java.util.Set<String> extraSplit = java.util.Set.of();
}
