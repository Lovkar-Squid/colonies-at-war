package me.lovkar.war.campaign;

import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The places in the world a warband can march on that are nobody's: raider camps and villages.
 *
 * <p>What a camp or a village is comes off two structure tags, {@code #colonies_at_war:camp}
 * and {@code #colonies_at_war:village}, the same way Colonist Thieves finds somewhere to rob - a
 * datapack can make any structure in any mod a target without touching this class. They ship with
 * MineColonies' barbarian, amazon and desert camps, its pirate ship, the pillager outpost, every
 * vanilla village, and - each only when its mod is there - the camps, outposts and castles of
 * Kings &amp; Monsters, IFC-Integrations and Valarian Conquest, the kobold den and the dwarf
 * village. Whoever stands in them is counted by what he does, not by which mod he comes from.</p>
 *
 * <p>A camp is the one target a warband changes for good: its spawners are the camp, and a
 * warband that overruns it smashes them. A village only loses what the men carry off.</p>
 */
public final class Places {
    private Places() {
    }

    public static final String CAMP = "camp";
    public static final String VILLAGE = "village";
    public static final ResourceLocation CAMP_TAG = ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "camp");
    public static final ResourceLocation VILLAGE_TAG = ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "village");
    /** How far round a place its people are counted, when they are loaded at all. */
    private static final int REACH = 48;
    /** How far the nearest-camp search looks, in blocks. */
    public static final int RANGE = 1200;

    /** What stands somewhere: its kind, where it is, what it is called, and which structure it is. */
    public record Place(String kind, BlockPos pos, String name, ResourceLocation structure) {
        public boolean isCamp() {
            return CAMP.equals(kind);
        }

        /** "the Barbarian Camp at 1200, -340" - a place has no name of its own, so it is where it is. */
        public String title() {
            return "the " + name + " at " + pos.getX() + ", " + pos.getZ();
        }
    }

    /** The camp or village this position belongs to, or null if it is just countryside. */
    public static Place at(final ServerLevel level, final BlockPos pos) {
        for (final String kind : new String[] {CAMP, VILLAGE}) {
            final ResourceLocation found = structureOf(level, pos, kind);
            if (found != null) {
                return new Place(kind, pos, nameOf(found), found);
            }
        }
        return null;
    }

    /** The nearest place of this kind within {@link #RANGE}, or null. A slow question; asked by a command. */
    public static Place nearest(final ServerLevel level, final BlockPos from, final String kind) {
        try {
            final BlockPos at = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, tagOf(kind)), from, RANGE / 16, false);
            if (at == null) {
                return null;
            }
            final Place read = at(level, at);
            return read != null && read.kind().equals(kind) ? read : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ what it is worth

    /** Guard Villagers' guard, by its entity id only: nothing of that mod is ever named here. */
    public static final ResourceLocation VILLAGE_GUARD = ResourceLocation.fromNamespaceAndPath("guardvillagers", "guard");

    /** Who stands in a place, each counted once: a guard, a golem, an armed man, or one of its folk. */
    public record Defenders(int guards, int golems, int armed, int folk) {
        public static final Defenders NONE = new Defenders(0, 0, 0, 0);
    }

    /**
     * Guard Villagers' guard type, or null when that mod is not installed. Through the optional
     * lookup: the entity registry has a default (the pig), and a plain get would hand that back for
     * a mod that is not there.
     */
    public static EntityType<?> villageGuardType() {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(VILLAGE_GUARD).orElse(null);
    }

    /** Whether this is one of Guard Villagers' guards. */
    public static boolean isVillageGuard(final Entity entity, final EntityType<?> guard) {
        return guard != null && entity.getType() == guard;
    }

    /**
     * Whether a mob holds something to fight with: a bow or a crossbow, or anything that adds to
     * its attack - a sword, an axe, a trident, a mace, or another mod's weapon, which says so
     * through the same attribute even when it is no vanilla weapon class.
     */
    public static boolean holdsWeapon(final Mob mob) {
        final ItemStack held = mob.getMainHandItem();
        if (held.isEmpty()) {
            return false;
        }
        if (held.getItem() instanceof ProjectileWeaponItem) {
            return true;
        }
        for (final ItemAttributeModifiers.Entry entry : held.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE) && entry.modifier().amount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a mob is one of a place's people: alive, not hostile and not a beast - villagers, a
     * wandering trader, kobolds, dwarves - whatever mod it comes from.
     */
    private static boolean isFolk(final Mob mob) {
        return !(mob instanceof Enemy) && !(mob instanceof Animal) && !(mob instanceof WaterAnimal)
                && !(mob instanceof AmbientCreature) && !(mob instanceof AbstractGolem);
    }

    /**
     * Who stands in the place, when its ground is loaded (nobody is counted otherwise): within a
     * fixed reach of the place, and the whole of the structure's own box with a little round it
     * when the structure can be read - a big village is wider than any fixed reach.
     */
    public static Defenders defenders(final ServerLevel level, final Place place) {
        if (!level.isPositionEntityTicking(place.pos())) {
            return Defenders.NONE;
        }
        final StructureStart start = startOf(level, place);
        final AABB reach = new AABB(place.pos()).inflate(REACH);
        final AABB box = start != null ? AABB.of(start.getBoundingBox()).inflate(8).minmax(reach) : reach;
        final EntityType<?> guard = villageGuardType();
        int guards = 0;
        int golems = 0;
        int armed = 0;
        int folk = 0;
        for (final Mob mob : level.getEntitiesOfClass(Mob.class, box, Mob::isAlive)) {
            if (isVillageGuard(mob, guard)) {
                guards++;
            } else if (mob instanceof IronGolem) {
                golems++;
            } else if (mob instanceof Enemy || holdsWeapon(mob)) {
                armed++;
            } else if (isFolk(mob)) {
                folk++;
            }
        }
        return new Defenders(guards, golems, armed, folk);
    }

    /**
     * What stands against a warband there. A camp is its spawners, five apiece over a base of
     * twenty, and its armed men, three apiece, whatever mod they come from (a Guard Villagers guard
     * who stands in a camp is one of them); a village is its iron golems (fifteen), Guard Villagers'
     * guards ({@link WarConfig#villageGuardWeight()}, twelve by default), its armed men (three) and
     * its people (two). Neither is a kingdom: a large barbarian camp with eight spawners comes to
     * sixty, a hamlet to about thirty.
     */
    public static int defence(final ServerLevel level, final Place place) {
        return defence(level, place, defenders(level, place));
    }

    /** The same, from defenders already counted. */
    public static int defence(final ServerLevel level, final Place place, final Defenders d) {
        if (place.isCamp()) {
            return 20 + spawnersIn(level, place, false) * 5 + (d.armed() + d.guards()) * 3;
        }
        return Math.max(20, 12 + d.golems() * 15 + d.guards() * WarConfig.villageGuardWeight() + d.folk() * 2 + d.armed() * 3);
    }

    /** A place's wealth in the thief's units: a camp is worth a little, a village a little less. */
    public static int wealth(final Place place) {
        return place.isCamp() ? 45 : 35;
    }

    /** The loot table a sack of this kind rolls. */
    public static ResourceLocation plunderTable(final Place place) {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "plunder/" + place.kind());
    }

    // ------------------------------------------------------------------ what a warband does to it

    /** Smash every spawner in the camp. @return how many came down */
    public static int clear(final ServerLevel level, final Place place) {
        return place.isCamp() ? spawnersIn(level, place, true) : 0;
    }

    /**
     * The spawners inside the structure this place belongs to, counted or smashed.
     *
     * <p>Loads the structure's chunks if they are not: a camp a thousand blocks out is usually
     * not, and a warband that arrived there has to be able to see it. A camp is a few chunks.</p>
     */
    private static int spawnersIn(final ServerLevel level, final Place place, final boolean smash) {
        final StructureStart start = startOf(level, place);
        if (start == null) {
            return 0;
        }
        int found = 0;
        final BoundingBox box = start.getBoundingBox();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        for (final BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.SPAWNER)) {
                found++;
                if (smash) {
                    level.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        return found;
    }

    /**
     * Where a place is, once and for all: the middle of its structure, whichever of its houses a
     * warband was sent at, so that two attacks on one village are one village. The place's own
     * position when its structure cannot be read.
     */
    public static BlockPos anchor(final ServerLevel level, final Place place) {
        final StructureStart start = startOf(level, place);
        return start == null ? place.pos() : start.getBoundingBox().getCenter();
    }

    private static StructureStart startOf(final ServerLevel level, final Place place) {
        try {
            final Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            final Structure structure = registry.get(place.structure());
            if (structure == null) {
                return null;
            }
            // by chunk, not by block: a position found by the nearest-structure search sits at
            // y = 0, and a start's box knows its height
            for (final StructureStart start : level.structureManager().startsForStructure(new ChunkPos(place.pos()), s -> s == structure)) {
                if (start != null && start.isValid()) {
                    return start;
                }
            }
            return null;
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[places] cannot read the structure at {}: {}", place.pos().toShortString(), t.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static ResourceLocation tagOf(final String kind) {
        return CAMP.equals(kind) ? CAMP_TAG : VILLAGE_TAG;
    }

    /** Said once per run: a broken tag should be loud the first time and quiet afterwards. */
    private static volatile boolean warned = false;

    /**
     * The id of a structure of this kind at the position, or null. The chunk-level question -
     * which structures this chunk belongs to - because a village is mostly the gaps between its
     * houses; a hit on a real piece still wins.
     */
    private static ResourceLocation structureOf(final ServerLevel level, final BlockPos pos, final String kind) {
        try {
            final Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            final Optional<HolderSet.Named<Structure>> holders = registry.getTag(TagKey.create(Registries.STRUCTURE, tagOf(kind)));
            if (holders.isEmpty() || holders.get().size() == 0) {
                if (!warned) {
                    warned = true;
                    Warfare.LOGGER.warn("[places] the structure tag {} is empty or missing - nothing of that kind will be marched on", tagOf(kind));
                }
                return null;
            }
            final Set<Structure> wanted = new HashSet<>();
            for (final Holder<Structure> holder : holders.get()) {
                wanted.add(holder.value());
            }
            for (final Structure structure : wanted) {
                final StructureStart exact = level.structureManager().getStructureWithPieceAt(pos, structure);
                if (exact != null && exact.isValid()) {
                    return registry.getKey(structure);
                }
            }
            for (final Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
                if (wanted.contains(structure)) {
                    return registry.getKey(structure);
                }
            }
            return null;
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                Warfare.LOGGER.warn("[places] cannot read structures at {}: {}", pos.toShortString(), t.toString());
            }
            return null;
        }
    }

    /**
     * The words a structure's id may end on that say where it stands and not what it is: a mod
     * that makes one outpost per biome names them "ogre_outpost_plains", "ogre_outpost_swamp".
     */
    private static final Set<String> WHERE_WORDS = Set.of("plains", "swamp", "desert", "savanna", "taiga", "snowy", "jungle",
            "forest", "badlands", "mesa", "mountain", "mountains", "beach", "ocean", "tundra", "birch", "mangrove", "cherry",
            "meadow", "grove", "cold", "warm");

    /**
     * A structure's own name, out of its id: "minecolonies:barbarian_camp" reads "Barbarian Camp",
     * "kingsandmonsters:ogre_outpost_plains" reads "Ogre Outpost" and "valarian_conquest:bandit_ruins_2"
     * reads "Bandit Ruins" - a trailing biome word or number is dropped, as long as a word is left.
     */
    public static String nameOf(final ResourceLocation id) {
        final String path = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
        final List<String> words = new ArrayList<>();
        for (final String w : path.split("_")) {
            if (!w.isEmpty()) {
                words.add(w.toLowerCase(Locale.ROOT));
            }
        }
        while (words.size() > 1) {
            final String last = words.get(words.size() - 1);
            if (!WHERE_WORDS.contains(last) && !last.chars().allMatch(Character::isDigit)) {
                break;
            }
            words.remove(words.size() - 1);
        }
        final StringBuilder out = new StringBuilder();
        for (final String w : words) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }
}
