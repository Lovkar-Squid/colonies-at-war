package me.lovkar.war.campaign;

import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The places in the world a warband can march on that are nobody's: raider camps and villages.
 *
 * <p>What a camp or a village is comes off two structure tags, {@code #colonies_at_war:camp}
 * and {@code #colonies_at_war:village}, the same way Colonist Thieves finds somewhere to rob - a
 * datapack can make any structure in any mod a target without touching this class. They ship with
 * MineColonies' barbarian, amazon and desert camps, its pirate ship, the pillager outpost, and
 * every vanilla village.</p>
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
                return new Place(kind, pos, pretty(found), found);
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

    /**
     * What stands against a warband there. A camp is its spawners, five apiece over a base of
     * twenty, and whoever is awake around them; a village is its golems and its people. Neither is
     * a kingdom: a large barbarian camp with eight spawners comes to sixty, a hamlet to about thirty.
     */
    public static int defence(final ServerLevel level, final Place place) {
        int armed = 0;
        int golems = 0;
        int folk = 0;
        if (level.isPositionEntityTicking(place.pos())) {
            final AABB box = new AABB(place.pos()).inflate(REACH);
            armed = level.getEntitiesOfClass(Monster.class, box, m -> m.isAlive()).size();
            golems = level.getEntitiesOfClass(IronGolem.class, box, g -> g.isAlive()).size();
            folk = level.getEntitiesOfClass(Villager.class, box, v -> v.isAlive()).size();
        }
        if (place.isCamp()) {
            return 20 + spawnersIn(level, place, false) * 5 + armed * 3;
        }
        return Math.max(20, 12 + golems * 15 + folk * 2 + armed * 3);
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

    /** "minecolonies:barbarian_camp" reads as "Barbarian Camp". */
    private static String pretty(final ResourceLocation id) {
        final StringBuilder out = new StringBuilder();
        for (final String w : id.getPath().split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
