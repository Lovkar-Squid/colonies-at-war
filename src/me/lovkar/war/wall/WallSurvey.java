package me.lovkar.war.wall;

import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The walls somebody else built.
 *
 * <p>Nearly every MineColonies style pack ships a <b>{@code walls/}</b> category - Caledonia's
 * curtain walls, the Fortress ramparts, Medieval Oak's segments and towers, Nordic's palisade,
 * Pagoda's big walls. A player who rings his town with those has built a wall by any reading of
 * the word, and until now this mod scored him at zero for it, because {@link WallRegister} only
 * knows the blocks it placed itself. That is the right default - a block that registers itself is
 * exact and impossible to fool - but it must not be the only way in.</p>
 *
 * <p>So this class is the second door. It finds the <b>decoration controller</b> that every built
 * decoration leaves behind, reads the blueprint path out of it, and if that path says walls, it
 * measures what is standing inside the decoration's own corners and hands the result to the
 * register as <i>borrowed</i> wall. Borrowed wall counts towards the colony's wall score and
 * nothing else: it is not patrolled, because a patrol laid through the body of a three-thick
 * foreign wall is worse than no patrol at all, and it is never mistaken for a piece of ours.</p>
 *
 * <p><b>Why the controller and not the ground.</b> The register's whole promise is that it never
 * samples the ground and so never mistakes a barn for a rampart. That promise survives here: the
 * only thing measured is the inside of a box that a blueprint called a wall. A long barn is still
 * a barn, because the barn's blueprint is not in {@code walls/}.</p>
 */
public final class WallSurvey {

    private WallSurvey() {
    }

    /** A path segment that means this decoration is part of a wall. */
    private static final Set<String> WALL_FOLDERS = Set.of(
            "walls", "wall", "gate", "gates", "fortifications", "rampart", "ramparts", "palisade");

    /**
     * A path segment that takes it back out again.
     *
     * <p>Every one of these really is shipped under {@code walls/} by some pack: the Fortress moat
     * is a hole in the ground, Pagoda's fences are a fence, and a {@code misc} folder is whatever
     * the author had left over. None of them is a thing an army has to climb.</p>
     */
    private static final Set<String> NOT_WALLS = Set.of(
            "moat", "moats", "fence", "fences", "misc", "hedge", "hedges", "path", "paths");

    /**
     * Our own wall pieces are made of our own blocks, which register themselves.
     *
     * <p>Matched against the pack name with every separator taken out, because the pack is called
     * "Colonies at War" in {@code pack.json} and {@code colonies_at_war} everywhere else, and
     * Structurize hands back whichever of the two the author typed.</p>
     */
    private static final String OURS = "coloniesatwar";

    /** ...and the folders those pieces live in, in case the pack is ever renamed. */
    private static final Set<String> OUR_FOLDERS = Set.of(
            "wallsegment", "wallcorner", "wallgate", "wallstair", "walltower", "warroom");

    /** A decoration bigger than this in any direction is not a wall piece, whatever it is called. */
    private static final int MAX_SPAN = 32;
    /** ...and no single piece may hand in more than this many blocks. */
    private static final int MAX_BLOCKS = 2048;

    /** How often the queue of chunks waiting to be looked at is drained. */
    private static final int EVERY = 20;
    /** ...and how many of them are looked at each time. */
    private static final int PER_PASS = 16;
    /** ...and how long the queue may get before new arrivals are dropped on the floor. */
    private static final int QUEUE_CAP = 8192;

    /**
     * How many passes may fail before the survey gives up for the rest of the session.
     *
     * <p>This whole class is a nicety: it counts walls somebody else built, and a colony whose
     * borrowed walls are not counted loses a number on a screen. On 14 Sep 2026 it was instead the
     * reason a world stopped ticking, which is not a trade worth making at any odds. So a pass that
     * throws is a line in the log, and a handful of them switch the survey off rather than filling
     * the log and the tick budget with the same failure forever.</p>
     */
    private static final int MAX_STRIKES = 5;
    private static int strikes;
    private static boolean off;

    /**
     * Chunks that have loaded and not yet been looked at, per dimension.
     *
     * <p>A set, not a list, and in arrival order: a chunk load queues its eight neighbours as well
     * as itself, so the same position is offered nine times over and only the first one matters.
     * The neighbours are queued because a wall piece is wider than its anchor - the controller can
     * sit in one chunk with half the wall in the next, and until that next chunk is there the
     * piece cannot be measured at all.</p>
     */
    private static final Map<net.minecraft.resources.ResourceKey<Level>, java.util.LinkedHashSet<Long>>
            WAITING = new HashMap<>();
    private static int ticks;

    // ------------------------------------------------------------------ the hooks

    /**
     * A chunk arrived. Its block entities are not necessarily built yet - they are deserialized on
     * first touch - so nothing is read here; the chunk goes in a queue and is read a tick or two
     * later, which also stops a world load from surveying four hundred chunks in one frame.
     */
    public static void onChunkLoad(final ChunkEvent.Load event) {
        if (off || !WarConfig.countStylePackWalls() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            final java.util.LinkedHashSet<Long> queue =
                    WAITING.computeIfAbsent(level.dimension(), k -> new java.util.LinkedHashSet<>());
            if (queue.size() >= QUEUE_CAP) {
                return;
            }
            final ChunkPos at = event.getChunk().getPos();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    queue.add(ChunkPos.asLong(at.x + dx, at.z + dz));
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[wall] could not queue a loaded chunk: {}", t.toString());
        }
    }

    /** A few of the waiting chunks, every couple of seconds. */
    public static void onLevelTick(final LevelTickEvent.Post event) {
        if (off || !(event.getLevel() instanceof ServerLevel level) || ++ticks % EVERY != 0) {
            return;
        }
        try {
            drain(level);
        } catch (final Throwable t) {
            if (++strikes >= MAX_STRIKES) {
                off = true;
                Warfare.LOGGER.error("[wall] the style-pack wall survey has failed {} times and is now "
                        + "off for the rest of this session; walls from other packs will not be counted. "
                        + "The last failure was: {}", strikes, t.toString(), t);
            } else {
                Warfare.LOGGER.warn("[wall] survey pass failed ({} of {}): {}", strikes, MAX_STRIKES, t.toString());
            }
        }
    }

    /**
     * This pass's chunks, taken <b>out</b> of the queue before a single one of them is looked at.
     *
     * <p>Looking at a chunk can load another one: {@code getChunk} promotes it, {@code
     * ChunkEvent.Load} fires on this same thread before the call returns, and {@link #onChunkLoad}
     * then adds nine positions to the very set the iterator is walking. That is a {@code
     * ConcurrentModificationException} thrown out of the level tick, which is to say a crashed
     * server - and it is exactly what happened on 14 Sep 2026, while a wall tower was being
     * built.</p>
     *
     * <p>So nothing here may iterate the live queue. The batch is lifted out first, with only the
     * iterator's own methods running between {@code iterator()} and the last {@code remove()}, and
     * everything that arrives while the pass works lands in the queue for the next one - which is
     * where it belonged anyway.</p>
     */
    private static void drain(final ServerLevel level) {
        final java.util.LinkedHashSet<Long> queue = WAITING.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        final long[] batch = new long[Math.min(PER_PASS, queue.size())];
        int n = 0;
        final java.util.Iterator<Long> it = queue.iterator();
        while (n < batch.length && it.hasNext()) {
            batch[n++] = it.next();
            it.remove();
        }
        for (int i = 0; i < n; i++) {
            final ChunkPos at = new ChunkPos(batch[i]);
            if (!level.hasChunk(at.x, at.z)) {
                continue;
            }
            try {
                look(level, level.getChunk(at.x, at.z));
            } catch (final Throwable t) {
                Warfare.LOGGER.warn("[wall] could not look at chunk {}: {}", at, t.toString());
            }
        }
    }

    /**
     * Somebody broke a decoration controller.
     *
     * <p>The blocks of the decoration are still standing at this moment, and most of them will go
     * on standing - breaking the controller does not demolish the wall. But the mod has lost the
     * thing that told it those blocks were a wall, so the honest answer is to stop counting them.
     * Build the decoration again and the next chunk load takes it back in.</p>
     */
    public static void onBreak(final BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            if (level.getBlockEntity(event.getPos()) instanceof IBlueprintDataProviderBE) {
                WallRegister.unborrow(level, event.getPos());
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[wall] could not drop the decoration at {}: {}", event.getPos(), t.toString());
        }
    }

    /**
     * A world was closed. The queue is keyed by dimension, and {@code minecraft:overworld} is the
     * same key in every world there has ever been - so without this, joining a second singleplayer
     * world inherits the first one's backlog, and a survey switched off by a bug in one world stays
     * off in the next. Both are quiet wrongnesses rather than crashes, which is why they lasted
     * until the crash made somebody read this file again.
     */
    public static void forget() {
        WAITING.clear();
        ticks = 0;
        strikes = 0;
        off = false;
    }

    // ------------------------------------------------------------------ the survey

    /**
     * Everything in one chunk, taken in or dropped.
     *
     * <p>A chunk is the unit on purpose: what is found here is the whole truth about this chunk,
     * so a controller the register still remembers and the chunk no longer has is removed in the
     * same pass. That is what makes the thing self-correcting - a wall demolished while nobody was
     * watching stops counting the next time anyone walks past it.</p>
     */
    public static void look(final ServerLevel level, final ChunkAccess chunk) {
        final Set<BlockPos> here = new HashSet<>();
        for (final BlockPos pos : chunk.getBlockEntitiesPos()) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof IBlueprintDataProviderBE data)) {
                continue;
            }
            here.add(pos.immutable());
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
            if (colony == null || !isWall(data)) {
                WallRegister.unborrow(level, pos);
                continue;
            }
            if (WallRegister.borrowedAlready(level, colony.getID(), pos)) {
                continue;               // already counted, and a wall does not change on its own
            }
            final Map<Long, Byte> blocks = measure(level, colony.getID(), data);
            if (!blocks.isEmpty()) {
                WallRegister.borrow(level, colony.getID(), pos, blocks);
            }
        }
        WallRegister.forgetMissing(level, chunk.getPos(), here);
    }

    /**
     * Whether this decoration's blueprint says wall.
     *
     * <p>Read off the <b>path</b>, never the name: {@code caledonia/walls/gates/gate_large1} is a
     * wall because it lives in {@code walls}, and a house called "walled garden" is not, because
     * it does not. The last segment is the file and is ignored, so a {@code parks/wall_of_roses}
     * cannot sneak in on its name either.</p>
     */
    static boolean isWall(final IBlueprintDataProviderBE data) {
        final String pack = data.getPackName();
        if (pack != null && squash(pack).contains(OURS)) {
            return false;           // our own pieces are our own blocks; counting both is counting twice
        }
        final String path = data.getBlueprintPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        return saysWall(path);
    }

    /** The path test on its own, so it can be checked without a world. */
    public static boolean saysWall(final String path) {
        final String[] parts = path.toLowerCase(Locale.ROOT).replace('\\', '/').split("/");
        boolean wall = false;
        for (int i = 0; i < parts.length - 1; i++) {      // the last part is the file name
            final String part = parts[i];
            if (NOT_WALLS.contains(part) || OUR_FOLDERS.contains(part)) {
                return false;
            }
            if (WALL_FOLDERS.contains(part) || WarConfig.extraWallFolders().contains(part)) {
                wall = true;
            }
        }
        return wall;
    }

    /** A name with everything but its letters taken out, so two spellings of it compare equal. */
    private static String squash(final String raw) {
        final StringBuilder out = new StringBuilder(raw.length());
        for (final char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * What is actually standing inside this decoration, as wall.
     *
     * <p>Counted from the <b>anchor's own height upwards</b>. Everything below the controller is
     * the hole the piece was set into - foundation, footing, the hillside it was cut out of - and
     * counting it would credit a wall for the ground it stands on. Above the anchor, a full block
     * is a full block's worth; a fence, wall, slab or stair is worth what a parapet is worth,
     * because it is height without body; a torch or a banner is worth nothing.</p>
     *
     * <p>Tier is decided once for the whole piece, by what most of it is made of. Per block it
     * would take one iron bar in a portcullis to call a cobblestone wall fortified.</p>
     */
    static Map<Long, Byte> measure(final ServerLevel level, final int colonyId,
                                   final IBlueprintDataProviderBE data) {
        final Tuple<BlockPos, BlockPos> corners;
        try {
            corners = data.getInWorldCorners();
        } catch (final Throwable t) {
            return Map.of();
        }
        if (corners == null || corners.getA() == null || corners.getB() == null) {
            return Map.of();
        }
        final BlockPos a = corners.getA();
        final BlockPos b = corners.getB();
        final int minX = Math.min(a.getX(), b.getX());
        final int maxX = Math.max(a.getX(), b.getX());
        final int minZ = Math.min(a.getZ(), b.getZ());
        final int maxZ = Math.max(a.getZ(), b.getZ());
        final int maxY = Math.max(a.getY(), b.getY());
        // never below the anchor: what is under it is the ground, not the wall
        final int minY = Math.max(Math.min(a.getY(), b.getY()), data.getTilePos().getY());
        if (maxX - minX >= MAX_SPAN || maxZ - minZ >= MAX_SPAN || maxY - minY >= MAX_SPAN) {
            return Map.of();
        }

        final List<BlockPos> found = new ArrayList<>();
        final List<Float> weights = new ArrayList<>();
        final int[] tiers = new int[4];
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    return Map.of();              // half the piece is not loaded: come back later
                }
                for (int y = minY; y <= maxY; y++) {
                    at.set(x, y, z);
                    final BlockState state = level.getBlockState(at);
                    if (state.getBlock() instanceof WallPiece
                            || WallRegister.isWall(level, colonyId, at)) {
                        continue;                 // ours already, and counted once is enough
                    }
                    final float weight = worth(state, level, at);
                    if (weight <= 0f) {
                        continue;
                    }
                    if (found.size() >= MAX_BLOCKS) {
                        return Map.of();
                    }
                    found.add(at.immutable());
                    weights.add(weight);
                    tiers[tierOf(state)]++;
                }
            }
        }
        if (found.isEmpty()) {
            return Map.of();
        }
        int tier = 2;
        for (int t = 1; t <= 3; t++) {
            if (tiers[t] > tiers[tier]) {
                tier = t;
            }
        }
        final Map<Long, Byte> out = new HashMap<>();
        for (int i = 0; i < found.size(); i++) {
            out.put(found.get(i).asLong(), WallRegister.pack(tier, weights.get(i)));
        }
        return out;
    }

    /** What one block of somebody else's wall is worth. Zero means it is not part of the wall. */
    private static float worth(final BlockState state, final ServerLevel level, final BlockPos pos) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return 0f;
        }
        try {
            if (state.isCollisionShapeFullBlock(level, pos)) {
                return 1.0f;
            }
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                return 0.35f;         // a fence, a wall, a slab: height without body, like a parapet
            }
        } catch (final Throwable t) {
            return 0f;
        }
        return 0f;                    // a torch, a banner, a carpet of moss
    }

    /** 1 palisade, 2 stone, 3 fortified - read off what the block is, never how tall it is. */
    private static int tierOf(final BlockState state) {
        final net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        final String name = id == null ? "" : id.getPath();
        if (name.contains("iron") || name.contains("copper") || name.contains("obsidian")
                || name.contains("netherite") || name.contains("reinforced")) {
            return 3;
        }
        if (name.contains("log") || name.contains("planks") || name.contains("wood")
                || name.contains("bamboo") || name.contains("fence") || name.contains("hyphae")
                || name.contains("stem")) {
            return 1;
        }
        return 2;
    }
}
