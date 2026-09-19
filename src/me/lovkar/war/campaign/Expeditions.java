package me.lovkar.war.campaign;

import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Where an expedition can go and what it brings back.
 *
 * <p>The world is read, not guessed. The biomes within reach of a colony are sampled off the
 * world's own noise every sixty-four blocks - no chunk is loaded to ask - and sorted into
 * <b>families</b> a person would name: forest, jungle, desert, the sea. What the men bring home
 * is two things. The <b>land's</b> goods come from a loot table per family in the datapack, so a
 * pack can say what its own forests are worth. The <b>beasts'</b> come from the biome itself:
 * MineColonies-style, the men "kill" a handful of whatever that biome's spawn lists say lives
 * there, hostile and not, and each kill rolls that creature's real loot table - a zombie's rotten
 * flesh, a skeleton's bones, a cow's leather, a sheep's wool - so a jungle trip reads like a
 * jungle trip. Nobody is spawned for it.</p>
 */
public final class Expeditions {
    private Expeditions() {
    }

    // ------------------------------------------------------------------ families

    /** A kind of country, as a person names it. */
    public record Family(String id, String name, Predicate<Holder<Biome>> is, boolean underground) {
    }

    private static TagKey<Biome> tag(final String path) {
        return TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(path));
    }

    private static Predicate<Holder<Biome>> ids(final String... paths) {
        final List<ResourceKey<Biome>> keys = new ArrayList<>();
        for (final String path : paths) {
            keys.add(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(path)));
        }
        return h -> {
            for (final ResourceKey<Biome> key : keys) {
                if (h.is(key)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * In order of precedence: a snowy taiga is snow first and taiga second, a frozen peak is
     * snow before it is a mountain.
     */
    public static final List<Family> FAMILIES = List.of(
            new Family("caves", "Caves", ids("lush_caves", "dripstone_caves", "deep_dark"), true),
            new Family("snowy", "Snow", ids("snowy_plains", "ice_spikes", "snowy_taiga", "snowy_beach", "frozen_river",
                    "snowy_slopes", "frozen_peaks", "grove"), false),
            new Family("mushroom", "Mushroom Fields", ids("mushroom_fields"), false),
            new Family("cherry", "Cherry Grove", ids("cherry_grove"), false),
            new Family("swamp", "Swamp", ids("swamp", "mangrove_swamp"), false),
            new Family("desert", "Desert", ids("desert"), false),
            new Family("plains", "Plains", ids("plains", "sunflower_plains", "meadow"), false),
            new Family("jungle", "Jungle", h -> h.is(BiomeTags.IS_JUNGLE), false),
            new Family("badlands", "Badlands", h -> h.is(BiomeTags.IS_BADLANDS), false),
            new Family("savanna", "Savanna", h -> h.is(BiomeTags.IS_SAVANNA), false),
            new Family("mountains", "Mountains", h -> h.is(BiomeTags.IS_MOUNTAIN) || h.is(tag("is_hill")), false),
            new Family("taiga", "Taiga", h -> h.is(BiomeTags.IS_TAIGA), false),
            new Family("forest", "Forest", h -> h.is(BiomeTags.IS_FOREST), false),
            new Family("ocean", "Ocean", h -> h.is(BiomeTags.IS_OCEAN) || h.is(BiomeTags.IS_DEEP_OCEAN), false),
            new Family("beach", "Beach", h -> h.is(BiomeTags.IS_BEACH), false),
            new Family("river", "River", h -> h.is(BiomeTags.IS_RIVER), false));

    /** Whatever fits no family: a modded biome nobody tagged. Still worth a walk. */
    public static final Family WILDS = new Family("wilds", "Wilds", h -> true, false);

    public static Family family(final String id) {
        for (final Family f : FAMILIES) {
            if (f.id().equalsIgnoreCase(id)) {
                return f;
            }
        }
        return WILDS.id().equalsIgnoreCase(id) ? WILDS : null;
    }

    public static Family familyOf(final Holder<Biome> biome) {
        for (final Family f : FAMILIES) {
            if (f.is().test(biome)) {
                return f;
            }
        }
        return WILDS;
    }

    // ------------------------------------------------------------------ the survey

    /** Somewhere to go: the family, the biome that was actually found there, and where. */
    public record Destination(Family family, ResourceLocation biome, BlockPos pos, int distance, String direction) {
        /** "the Jungle (Sparse Jungle), 1,240 blocks NE" */
        public String describe() {
            final String exact = pretty(biome);
            return "the " + family.name() + (exact.equalsIgnoreCase(family.name()) ? "" : " (" + exact + ")")
                    + ", " + distance + " blocks " + direction;
        }

        public String title() {
            return "the " + family.name();
        }
    }

    private record Cached(long at, List<Destination> found) {
    }

    private static final Map<Long, Cached> surveys = new HashMap<>();
    private static final long KEEP = 6000L;           // five minutes: the country does not move
    private static final int STEP = 64;

    private static long key(final ServerLevel level, final BlockPos centre) {
        return (long) level.dimension().location().hashCode() * 31 + centre.asLong();
    }

    /**
     * Every family within reach of this point, nearest instance of each, nearest first. Sampled
     * off the noise every {@value #STEP} blocks; about four thousand looks at the default range,
     * a few milliseconds, and remembered for five minutes.
     */
    public static synchronized List<Destination> survey(final ServerLevel level, final BlockPos centre) {
        final long key = key(level, centre);
        final Cached cached = surveys.get(key);
        final long now = level.getGameTime();
        if (cached != null && now - cached.at() < KEEP) {
            return cached.found();
        }
        final Map<String, Destination> best = new LinkedHashMap<>();
        final int range = WarConfig.expeditionRange();
        try {
            for (int dx = -range; dx <= range; dx += STEP) {
                for (int dz = -range; dz <= range; dz += STEP) {
                    final int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (dist > range || dist < STEP) {
                        continue;                   // the town itself is not an expedition
                    }
                    final int x = centre.getX() + dx;
                    final int z = centre.getZ() + dz;
                    look(level, best, new BlockPos(x, 70, z), centre, dist, false);
                    look(level, best, new BlockPos(x, -24, z), centre, dist, true);
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[expedition] could not survey the country round {}: {}", centre.toShortString(), t.toString());
        }
        final List<Destination> found = new ArrayList<>(best.values());
        found.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        final List<Destination> kept = Collections.unmodifiableList(found);
        surveys.put(key, new Cached(now, kept));
        return kept;
    }

    private static void look(final ServerLevel level, final Map<String, Destination> best, final BlockPos at,
                             final BlockPos centre, final int dist, final boolean underground) {
        final Holder<Biome> biome = level.getBiome(at);
        final Family family = familyOf(biome);
        if (family.underground() != underground) {
            return;                                 // a cave family is only read underground, and the rest only above
        }
        final Destination have = best.get(family.id());
        if (have == null || dist < have.distance()) {
            best.put(family.id(), new Destination(family, idOf(biome), at, dist, direction(centre, at)));
        }
    }

    /** The nearest of a family, or of an exact biome ({@code minecraft:cherry_grove}), or null. */
    public static Destination find(final ServerLevel level, final BlockPos centre, final String what) {
        if (what == null || what.isBlank()) {
            return null;
        }
        final Family family = family(what.trim());
        if (family != null) {
            for (final Destination d : survey(level, centre)) {
                if (d.family() == family) {
                    return d;
                }
            }
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(what.trim().toLowerCase(Locale.ROOT));
        if (id == null) {
            return null;
        }
        final ResourceKey<Biome> wanted = ResourceKey.create(Registries.BIOME, id);
        Destination best = null;
        final int range = WarConfig.expeditionRange();
        try {
            for (int dx = -range; dx <= range; dx += STEP) {
                for (int dz = -range; dz <= range; dz += STEP) {
                    final int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (dist > range || dist < STEP || (best != null && dist >= best.distance())) {
                        continue;
                    }
                    for (final int y : new int[] {70, -24}) {
                        final BlockPos at = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
                        final Holder<Biome> biome = level.getBiome(at);
                        if (biome.is(wanted)) {
                            best = new Destination(familyOf(biome), id, at, dist, direction(centre, at));
                            break;
                        }
                    }
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[expedition] could not look for {}: {}", id, t.toString());
        }
        return best;
    }

    // ------------------------------------------------------------------ the haul

    public static final String[] DIFFICULTIES = {"easy", "normal", "hard", "deadly"};

    /** 1..4 for a word, or 0 for a word that is not one. */
    public static int difficulty(final String word) {
        for (int i = 0; i < DIFFICULTIES.length; i++) {
            if (DIFFICULTIES[i].equalsIgnoreCase(word)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static String difficultyName(final int difficulty) {
        return DIFFICULTIES[Math.max(1, Math.min(4, difficulty)) - 1];
    }

    /** How long the men spend out there once they have arrived, in ticks. */
    public static int exploreTicks(final int difficulty) {
        return (int) Math.max(200, WarConfig.exploreMinutesPerLevel() * 1200.0 * Math.max(1, Math.min(4, difficulty)));
    }

    /** What came of it. */
    public record Haul(List<ItemStack> items, String bestiary, int kills, boolean success, int strength, int danger,
                       int luck, String report) {
    }

    /**
     * The roll, made when the time out there is up.
     *
     * @param skill  the explorer's Adaptability
     * @param guards how many guards went along
     */
    public static Haul roll(final ServerLevel level, final Destination where, final int difficulty, final int guards,
                            final int skill, final RandomSource random) {
        final int d = Math.max(1, Math.min(4, difficulty));
        final List<ItemStack> items = new ArrayList<>();
        final Holder<Biome> biome = level.getBiome(where.pos());

        // the beasts: a handful of whatever lives there, each rolled for real
        final List<MobSpawnSettings.SpawnerData> hostile = spawns(biome, MobCategory.MONSTER);
        final List<MobSpawnSettings.SpawnerData> tame = new ArrayList<>(spawns(biome, MobCategory.CREATURE));
        // what swims is only met where the water is the point of the place, and what glows in
        // flooded caves only underground - every overworld biome lists both, and a squid on a
        // stony shore reads wrong
        final String f = where.family().id();
        if ("ocean".equals(f) || "river".equals(f) || "beach".equals(f) || "swamp".equals(f)) {
            tame.addAll(spawns(biome, MobCategory.WATER_CREATURE));
            tame.addAll(spawns(biome, MobCategory.WATER_AMBIENT));
        }
        if (where.family().underground()) {
            tame.addAll(spawns(biome, MobCategory.UNDERGROUND_WATER_CREATURE));
        }
        final int wantHostile = hostile.isEmpty() ? 0 : d * 2 + guards + random.nextInt(3);
        final int wantTame = tame.isEmpty() ? 0 : 2 + d + random.nextInt(3) + skill / 20;
        final Map<String, Integer> killed = new LinkedHashMap<>();
        int kills = 0;
        for (int i = 0; i < wantHostile; i++) {
            kills += kill(level, pick(hostile, random), where.pos(), items, killed, random) ? 1 : 0;
        }
        for (int i = 0; i < wantTame; i++) {
            kills += kill(level, pick(tame, random), where.pos(), items, killed, random) ? 1 : 0;
        }

        // the land: the family's table, more of it for a harder trip and a bigger party
        final int rolls = Math.min(14, WarConfig.expeditionRolls() + d * 2 + guards / 2 + skill / 20);
        items.addAll(land(level, where.family(), where.pos(), rolls, skill, random));

        // the danger: the party against the country
        // a lone novice can manage an easy walk about half the time and a deadly one never; six
        // seasoned guards behind a good explorer come through a deadly one more often than not
        final int strength = 10 + guards * 5 + skill;
        final int danger = d * 6 + wantHostile;
        final int swing = Math.max(8, Math.round(0.35f * (strength + danger)));
        final int luck = random.nextInt(swing * 2 + 1) - swing;
        final boolean success = strength + luck >= danger;
        if (!success) {
            // driven off: half of it dropped on the way out
            for (int i = items.size() - 1; i >= 0; i -= 2) {
                items.remove(i);
            }
        }
        final String bestiary = bestiary(killed);
        final String report = (success ? "explored " : "was driven out of ") + where.title() + " (" + difficultyName(d) + ")"
                + (kills > 0 ? ", killed " + bestiary : ", met nothing worth killing")
                + " - " + strength + (luck >= 0 ? " +" : " ") + luck + " against " + danger;
        return new Haul(items, bestiary, kills, success, strength, danger, luck, report);
    }

    private static List<MobSpawnSettings.SpawnerData> spawns(final Holder<Biome> biome, final MobCategory category) {
        try {
            final WeightedRandomList<MobSpawnSettings.SpawnerData> list = biome.value().getMobSettings().getMobs(category);
            return list.unwrap();
        } catch (final Throwable t) {
            return List.of();
        }
    }

    private static MobSpawnSettings.SpawnerData pick(final List<MobSpawnSettings.SpawnerData> list, final RandomSource random) {
        int total = 0;
        for (final MobSpawnSettings.SpawnerData s : list) {
            total += Math.max(1, s.getWeight().asInt());
        }
        int at = random.nextInt(Math.max(1, total));
        for (final MobSpawnSettings.SpawnerData s : list) {
            at -= Math.max(1, s.getWeight().asInt());
            if (at < 0) {
                return s;
            }
        }
        return list.get(list.size() - 1);
    }

    /**
     * One kill, rolled on the creature's own loot table with a creature that was never in the
     * world - which is what a loot table needs to answer: this entity, here, killed by something.
     */
    private static boolean kill(final ServerLevel level, final MobSpawnSettings.SpawnerData spawn, final BlockPos at,
                                final List<ItemStack> into, final Map<String, Integer> killed, final RandomSource random) {
        if (spawn == null) {
            return false;
        }
        final EntityType<?> type = spawn.type;
        try {
            final Entity e = type.create(level);
            if (!(e instanceof LivingEntity living)) {
                return false;
            }
            living.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
            final String name = type.getDescription().getString();
            killed.merge(name, 1, Integer::sum);
            final LootTable table = level.getServer().reloadableRegistries().getLootTable(living.getLootTable());
            if (table != null && table != LootTable.EMPTY) {
                final LootParams params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, living)
                        .withParameter(LootContextParams.ORIGIN, living.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                        .create(LootContextParamSets.ENTITY);
                for (final ItemStack stack : table.getRandomItems(params, random)) {
                    if (stack != null && !stack.isEmpty()) {
                        into.add(stack);                // a modded table can hand back air
                    }
                }
            }
            living.discard();
            return true;
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[expedition] {} could not be killed on paper: {}", type, t.toString());
            return false;
        }
    }

    private static List<ItemStack> land(final ServerLevel level, final Family family, final BlockPos at, final int rolls,
                                        final int skill, final RandomSource random) {
        final List<ItemStack> out = new ArrayList<>();
        LootTable table = null;
        for (final String id : new String[] {family.id(), WILDS.id()}) {
            try {
                final LootTable candidate = level.getServer().reloadableRegistries().getLootTable(
                        ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "expedition/" + id)));
                if (candidate != null && candidate != LootTable.EMPTY) {
                    table = candidate;
                    break;
                }
            } catch (final Throwable ignored) {
                // try the next
            }
        }
        if (table == null) {
            Warfare.LOGGER.warn("[expedition] no loot table for {} - the land gave nothing", family.id());
            return out;
        }
        final LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
                .withLuck(skill / 25f)
                .create(LootContextParamSets.CHEST);
        for (int i = 0; i < rolls; i++) {
            for (final ItemStack stack : table.getRandomItems(params, random)) {
                if (stack != null && !stack.isEmpty()) {
                    out.add(stack);
                }
            }
        }
        return out;
    }

    /** "3 Zombies, 2 Cows, a Skeleton" - most first. */
    public static String bestiary(final Map<String, Integer> killed) {
        if (killed.isEmpty()) {
            return "nothing";
        }
        final List<Map.Entry<String, Integer>> rows = new ArrayList<>(killed.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, Integer> row : rows) {
            if (out.length() > 0) {
                out.append(", ");
            }
            final int n = row.getValue();
            out.append(n == 1 ? article(row.getKey()) : n + " " + plural(row.getKey()));
        }
        return out.toString();
    }

    private static String article(final String name) {
        return (name.isEmpty() ? "" : "aeiouAEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    private static String plural(final String name) {
        if (name.endsWith("Fish") || name.endsWith("Salmon") || name.endsWith("Cod") || name.endsWith("Sheep")
                || name.endsWith("Squid")) {
            return name;                            // one salmon, four salmon
        }
        if (name.endsWith("s") || name.endsWith("x") || name.endsWith("sh") || name.endsWith("ch")) {
            return name + "es";
        }
        if (name.endsWith("f")) {
            return name.substring(0, name.length() - 1) + "ves";       // Wolf, Wolves
        }
        if (name.endsWith("y") && name.length() > 1 && "aeiou".indexOf(name.charAt(name.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        return name + "s";
    }

    // ------------------------------------------------------------------ plumbing

    public static ResourceLocation idOf(final Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location).orElse(ResourceLocation.withDefaultNamespace("unknown"));
    }

    /** "minecraft:sparse_jungle" reads as "Sparse Jungle". */
    public static String pretty(final ResourceLocation id) {
        final StringBuilder out = new StringBuilder();
        for (final String w : id.getPath().split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** N, NE, E... from one point to another. */
    public static String direction(final BlockPos from, final BlockPos to) {
        final double dx = to.getX() - from.getX();
        final double dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return "here";
        }
        final double angle = Math.toDegrees(Math.atan2(dx, -dz));   // 0 = north, 90 = east
        final String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.round(((angle + 360) % 360) / 45.0) % 8];
    }
}
