package me.lovkar.war.wall;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every wall block this mod has placed on a colony's land, and what it adds up to.
 *
 * <p>This is the answer to "MineColonies has no idea of a wall". It does not have to: a wall built
 * out of the mod's own pieces <b>registers itself</b>. A block placed inside a colony tells this
 * class where it is and what tier it is; a block broken takes itself back out. So the assessment
 * never samples the ground, never mistakes a barn for a rampart, and never misses a wall because
 * nobody was standing near enough to load it. It asks here and gets an exact answer.</p>
 *
 * <p>Two things are computed from the same set of positions. <b>Coverage</b> - how much of the
 * circle round the town has wall between it and the outside, with a real bonus for closing the
 * ring, because the difference between a wall with a gap and a wall without one is the whole point
 * of a wall. And <b>strength</b> - tier by height, because two blocks of palisade is a fence and
 * five of stone with a walk on top is a wall.</p>
 *
 * <p>Kept per dimension with the level's own save data, because that is where both the walls and
 * the colonies are.</p>
 */
public class WallRegister extends SavedData {
    private static final String NAME = "colonies_at_war_walls";
    private static final String TAG_COLONIES = "colonies";
    private static final String TAG_POS = "pos";
    private static final String TAG_TIER = "tier";

    /** Bearings the circle is cut into when coverage is measured. */
    private static final int BEARINGS = 64;
    /** Above this many blocks of wall in one column, more height stops counting. */
    private static final int TALL_ENOUGH = 6;
    /** How close to closed a ring has to be to earn the sealed bonus. */
    private static final float SEALED = 0.95f;
    private static final float SEALED_BONUS = 1.25f;

    /** colony id -> (packed position -> tier * 10 + weight in tenths) */
    private final Map<Integer, Map<Long, Byte>> walls = new HashMap<>();
    /** colony id -> last computed score, cleared whenever a block moves. */
    private final Map<Integer, Integer> scores = new HashMap<>();

    public static final SavedData.Factory<WallRegister> FACTORY =
            new SavedData.Factory<>(WallRegister::new, WallRegister::read);

    public static WallRegister of(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // ------------------------------------------------------------------ the blocks

    /** A wall block was placed. If it is on a colony's land, that colony now owns a little more wall. */
    public static void laid(final ServerLevel level, final BlockPos pos, final WallPiece piece) {
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
        if (colony == null) {
            // a wall out in the countryside defends nothing and counts for nothing
            return;
        }
        final WallRegister register = of(level);
        register.walls.computeIfAbsent(colony.getID(), k -> new HashMap<>())
                .put(pos.asLong(), pack(piece));
        register.scores.remove(colony.getID());
        register.setDirty();
    }

    /** A wall block was broken - by a player, a raider or a catapult. */
    public static void pulled(final ServerLevel level, final BlockPos pos) {
        final WallRegister register = of(level);
        final long key = pos.asLong();
        for (final Map.Entry<Integer, Map<Long, Byte>> e : register.walls.entrySet()) {
            if (e.getValue().remove(key) != null) {
                register.scores.remove(e.getKey());
                register.setDirty();
                return;
            }
        }
    }

    /**
     * Tier and weight in one byte: tier in the high nibble, weight in tenths in the low one.
     *
     * <p>Base sixteen, not base ten. A rampart's weight is 1.0 - ten tenths - which does not fit in
     * a base-ten low digit, and squeezing it to nine turned a two-block wall into 1.8 and then into
     * no wall at all, because the measurement quite rightly does not call one course a wall. The
     * test server found it in a minute; it would have been invisible for weeks.</p>
     */
    private static byte pack(final WallPiece piece) {
        final int tier = Math.max(1, Math.min(3, piece.tier()));
        final int weight = Math.max(1, Math.min(15, Math.round(piece.weight() * 10f)));
        return (byte) (tier * 16 + weight);
    }

    private static int tierOf(final byte packed) {
        return (packed & 0xFF) / 16;
    }

    private static float weightOf(final byte packed) {
        return ((packed & 0xFF) % 16) / 10f;
    }

    // ------------------------------------------------------------------ the number

    /**
     * How walled this colony is, 0..100, or -1 if it has laid no wall of ours at all - which is
     * the signal to whoever asked to fall back on measuring the ground.
     */
    public static int score(final IColony colony) {
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            return -1;
        }
        final WallRegister register = of(level);
        final Map<Long, Byte> mine = register.walls.get(colony.getID());
        if (mine == null || mine.isEmpty()) {
            return -1;
        }
        final Map<Long, Byte> filled = withFilledGaps(colony, level, mine);
        if (filled != mine) {
            // a tower standing in a gap moves no wall block, so nothing would ever clear the
            // cache for it. A colony with gaps pays for a fresh measurement every time; a colony
            // without one - which is nearly all of them - keeps the fast path untouched.
            return withMasonry(colony, register.measure(colony.getCenter(), filled));
        }
        final Integer cached = register.scores.get(colony.getID());
        if (cached != null) {
            return withMasonry(colony, cached);
        }
        final int computed = register.measure(colony.getCenter(), mine);
        register.scores.put(colony.getID(), computed);
        return withMasonry(colony, computed);
    }

    // ------------------------------------------------------------------ the gaps towers stand in

    /** How much of a wall a tower standing in a gap is worth, per course. */
    private static final byte GAP_AS_OURS = (byte) (3 * 16 + 10);
    private static final byte GAP_AS_THEIRS = (byte) (2 * 16 + 10);
    /** How far from the middle of a gap a building may stand and still be filling it. */
    private static final double FILLS = 8.0;

    /** MineColonies' own buildings that close a wall as well as a piece of ours would. */
    private static final java.util.Set<String> THEIRS =
            java.util.Set.of("guardtower", "gatehouse", "barrackstower", "barracks");

    /**
     * The wall, plus every gap that has something standing in it.
     *
     * <p>A gap is a length of run deliberately left empty for a tower, and the tower that fills it
     * is a building, not our blocks - so the register knows nothing about it and the bearing would
     * read as a hole. That would punish the player for doing the thing the feature exists for:
     * cut a slot in your wall for a guard tower and watch the score fall.</p>
     *
     * <p>So a filled gap is counted as wall for as long as the building is there, at full height
     * and at the tier the thing deserves - ours is purpose-built and counts as fortified, theirs
     * is a real stone building and counts as stone. Take the tower away and the gap stops
     * counting the moment it is gone, because nothing is cached here.</p>
     *
     * @return the same map when there is nothing to add, so the ordinary case costs nothing
     */
    private static Map<Long, Byte> withFilledGaps(final IColony colony, final ServerLevel level,
                                                  final Map<Long, Byte> mine) {
        java.util.List<WallPlan.Piece> plan;
        try {
            plan = WallPlan.of(level).all(colony.getID());
        } catch (final Throwable t) {
            return mine;
        }
        Map<Long, Byte> out = mine;
        for (final WallPlan.Piece piece : plan) {
            if (piece.kind() != WallKind.GAP) {
                continue;
            }
            final Byte worth = fillerWorth(colony, piece);
            if (worth == null) {
                continue;
            }
            if (out == mine) {
                out = new HashMap<>(mine);
            }
            for (int step = 0; step < piece.kind().length(); step++) {
                final BlockPos at = piece.pos().relative(piece.along(), step);
                for (int up = 0; up < TALL_ENOUGH; up++) {
                    out.put(at.above(up).asLong(), worth);
                }
            }
        }
        return out;
    }

    /** Whether something qualifying is standing in this gap - for the War Room's panel. */
    public static boolean gapFilled(final IColony colony, final WallPlan.Piece gap) {
        return gap != null && gap.kind() == WallKind.GAP && fillerWorth(colony, gap) != null;
    }

    /** What is standing in this gap, as a packed tier, or null if the gap is still a hole. */
    private static Byte fillerWorth(final IColony colony, final WallPlan.Piece gap) {
        final BlockPos middle = gap.pos().relative(gap.along(), gap.kind().length() / 2);
        try {
            for (final com.minecolonies.api.colony.buildings.IBuilding building
                    : colony.getServerBuildingManager().getBuildings().values()) {
                final BlockPos at = building.getPosition();
                final double dx = at.getX() - middle.getX();
                final double dz = at.getZ() - middle.getZ();
                if (dx * dx + dz * dz > FILLS * FILLS || building.getBuildingLevel() <= 0) {
                    continue;
                }
                if (building instanceof me.lovkar.war.colony.BuildingWallTower) {
                    return GAP_AS_OURS;
                }
                final net.minecraft.resources.ResourceLocation id =
                        building.getBuildingType().getRegistryName();
                if (id != null && THEIRS.contains(id.getPath())) {
                    return GAP_AS_THEIRS;
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[wall] could not look in the gap at {}: {}",
                    gap.pos().toShortString(), t.toString());
        }
        return null;
    }

    /**
     * What the University's masonry adds to a wall that is already there.
     *
     * <p>Applied outside the cache on purpose: the cache is cleared when a block moves, and a
     * research finishing does not move a block. Reading the effect here costs a map lookup and
     * means the number is right the moment the research lands.</p>
     */
    private static int withMasonry(final IColony colony, final int raw) {
        if (raw <= 0) {
            return raw;
        }
        final double bonus = me.lovkar.war.colony.WarResearch.strength(colony,
                me.lovkar.war.colony.WarResearch.MASONRY);
        return bonus <= 0.0 ? raw : (int) Math.min(100L, Math.round(raw * (1.0 + bonus)));
    }

    /**
     * Coverage and strength from the positions alone.
     *
     * <p>Height is measured <b>per column</b>, not per bearing. That distinction is the whole
     * difference between a number that means something and one that does not: a long stretch of
     * two-high wall running through one bearing would otherwise read as a tower. What the bearing
     * keeps is its <i>tallest</i> column, which is what somebody standing outside would call the
     * height of the wall there.</p>
     */
    private int measure(final BlockPos centre, final Map<Long, Byte> mine) {
        // first the columns: how tall the wall stands at each spot on the ground
        final Map<Long, float[]> columns = new HashMap<>();
        for (final Map.Entry<Long, Byte> e : mine.entrySet()) {
            final BlockPos at = BlockPos.of(e.getKey());
            final long xz = (((long) at.getX()) << 32) ^ (at.getZ() & 0xffffffffL);
            final float[] column = columns.computeIfAbsent(xz, k -> new float[3]);
            column[0] += weightOf(e.getValue());
            column[1] = Math.max(column[1], tierOf(e.getValue()));
            column[2] = at.getX();
        }
        // then the circle: each bearing keeps its tallest column
        final float[] tallest = new float[BEARINGS];
        final float[] best = new float[BEARINGS];
        for (final Map.Entry<Long, float[]> e : columns.entrySet()) {
            final int x = (int) (e.getKey() >> 32);
            final int z = (int) (e.getKey() & 0xffffffffL);
            final int dx = x - centre.getX();
            final int dz = z - centre.getZ();
            if (dx == 0 && dz == 0) {
                continue;
            }
            double angle = Math.atan2(dz, dx);
            if (angle < 0) {
                angle += Math.PI * 2;
            }
            final int b = (int) (angle / (Math.PI * 2) * BEARINGS) % BEARINGS;
            if (e.getValue()[0] > tallest[b]) {
                tallest[b] = e.getValue()[0];
                best[b] = e.getValue()[1];
            }
        }
        int covered = 0;
        float tallness = 0f;
        float tiers = 0f;
        for (int b = 0; b < BEARINGS; b++) {
            // one course is a kerb, not a wall
            if (tallest[b] >= 2f) {
                covered++;
                tallness += Math.min(TALL_ENOUGH, tallest[b]) / (float) TALL_ENOUGH;
                tiers += best[b] / 3f;
            }
        }
        if (covered == 0) {
            return 0;
        }
        final float coverage = covered / (float) BEARINGS;
        final float tall = tallness / covered;
        final float made = tiers / covered;
        final float sealed = coverage >= SEALED ? SEALED_BONUS : 1f;
        // height carries most of it, what it is made of the rest, and merely existing a little:
        // a closed two-high palisade is worth something, and nowhere near what a five-high
        // fortified ring is worth.
        return Math.round(Math.min(100f, 100f * coverage * (0.20f + 0.55f * tall + 0.25f * made) * sealed));
    }

    /** Every wall block a colony has laid - for a tower working out where to send its guards. */
    public static Set<BlockPos> wallOf(final ServerLevel level, final int colonyId) {
        final Map<Long, Byte> mine = of(level).walls.get(colonyId);
        if (mine == null) {
            return Set.of();
        }
        final Set<BlockPos> out = new HashSet<>();
        for (final Long key : mine.keySet()) {
            out.add(BlockPos.of(key));
        }
        return out;
    }

    /** True if a wall block of ours stands here. */
    public static boolean isWall(final ServerLevel level, final int colonyId, final BlockPos pos) {
        final Map<Long, Byte> mine = of(level).walls.get(colonyId);
        return mine != null && mine.containsKey(pos.asLong());
    }

    /**
     * The run of wall this position belongs to, in walking order from here outwards.
     *
     * <p>A run is a maximal chain of wall blocks that touch - side by side, or a step up or down,
     * so a wall that climbs a hill is still one wall. This is what a Wall Tower turns into a list
     * of patrol points, and it is why the tower never has to look at the world: the register knows
     * the shape of the wall already.</p>
     */
    public static List<BlockPos> run(final ServerLevel level, final int colonyId, final BlockPos from, final int limit) {
        final Map<Long, Byte> mine = of(level).walls.get(colonyId);
        final List<BlockPos> ordered = new ArrayList<>();
        if (mine == null || mine.isEmpty()) {
            return ordered;
        }
        final Set<Long> seen = new HashSet<>();
        final Deque<BlockPos> queue = new ArrayDeque<>();
        final BlockPos start = nearest(mine, from);
        if (start == null) {
            return ordered;
        }
        queue.add(start);
        seen.add(start.asLong());
        while (!queue.isEmpty() && ordered.size() < limit) {
            final BlockPos at = queue.poll();
            ordered.add(at);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        final BlockPos next = at.offset(dx, dy, dz);
                        final long key = next.asLong();
                        if (mine.containsKey(key) && seen.add(key)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return ordered;
    }

    private static BlockPos nearest(final Map<Long, Byte> mine, final BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (final Long key : mine.keySet()) {
            final BlockPos at = BlockPos.of(key);
            final double d = at.distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = at;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ saving

    private static WallRegister read(final CompoundTag tag, final HolderLookup.Provider provider) {
        final WallRegister out = new WallRegister();
        final CompoundTag colonies = tag.getCompound(TAG_COLONIES);
        for (final String key : colonies.getAllKeys()) {
            final CompoundTag one = colonies.getCompound(key);
            final long[] positions = one.getLongArray(TAG_POS);
            final byte[] tiers = one.getByteArray(TAG_TIER);
            final Map<Long, Byte> mine = new HashMap<>();
            for (int i = 0; i < positions.length && i < tiers.length; i++) {
                mine.put(positions[i], tiers[i]);
            }
            out.walls.put(Integer.parseInt(key), mine);
        }
        return out;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        final CompoundTag colonies = new CompoundTag();
        for (final Map.Entry<Integer, Map<Long, Byte>> e : walls.entrySet()) {
            final CompoundTag one = new CompoundTag();
            final long[] positions = new long[e.getValue().size()];
            final byte[] tiers = new byte[e.getValue().size()];
            int i = 0;
            for (final Map.Entry<Long, Byte> w : e.getValue().entrySet()) {
                positions[i] = w.getKey();
                tiers[i] = w.getValue();
                i++;
            }
            one.putLongArray(TAG_POS, positions);
            one.putByteArray(TAG_TIER, tiers);
            colonies.put(String.valueOf(e.getKey()), one);
        }
        tag.put(TAG_COLONIES, colonies);
        return tag;
    }
}
