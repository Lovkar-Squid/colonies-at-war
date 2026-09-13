package me.lovkar.war.wall;

import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The wall a colony has decided on: where every piece stands, which way it faces and what level
 * it is meant to be.
 *
 * <p>Separate from {@link WallRegister}, and the two answer different questions. The register
 * counts <b>blocks that exist</b> - it is what scores, and it does not care who put them there, so
 * a wall somebody laid by hand counts exactly as much as one the Builder raised. The plan is the
 * colony's <b>intention</b>: the pieces it has ordered, so that "upgrade every wall to level 4" is
 * one button rather than thirty trips with the build tool.</p>
 *
 * <p>The level kept here is the level the plan <i>wants</i>. What is actually standing is read off
 * the decoration controller in the world, because the Builder may not have got there yet - and a
 * plan that lied about that would make the War Room's panel a work of fiction.</p>
 */
public class WallPlan extends SavedData {
    private static final String NAME = "colonies_at_war_wallplan";
    private static final String TAG_COLONIES = "colonies";
    private static final String TAG_ID = "id";
    private static final String TAG_PIECES = "pieces";
    private static final String TAG_POS = "pos";
    private static final String TAG_KIND = "kind";
    private static final String TAG_FACING = "facing";
    private static final String TAG_LEVEL = "level";

    /** One piece of wall, as the colony asked for it. */
    public record Piece(BlockPos pos, WallKind kind, Direction along, int wanted) {

        public CompoundTag save() {
            final CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_POS, pos.asLong());
            tag.putString(TAG_KIND, kind.folder());
            tag.putString(TAG_FACING, along.getName());
            tag.putInt(TAG_LEVEL, wanted);
            return tag;
        }

        public static Piece load(final CompoundTag tag) {
            final WallKind kind = WallKind.byFolder(tag.getString(TAG_KIND));
            if (kind == null) {
                return null;
            }
            Direction along = Direction.byName(tag.getString(TAG_FACING));
            if (along == null || along.getAxis().isVertical()) {
                along = Direction.EAST;
            }
            return new Piece(BlockPos.of(tag.getLong(TAG_POS)), kind, along,
                    Math.max(1, Math.min(5, tag.getInt(TAG_LEVEL))));
        }

        public Piece at(final int level) {
            return new Piece(pos, kind, along, Math.max(1, Math.min(5, level)));
        }
    }

    private final Map<Integer, List<Piece>> pieces = new HashMap<>();

    public static final SavedData.Factory<WallPlan> FACTORY =
            new SavedData.Factory<>(WallPlan::new, WallPlan::read);

    public static WallPlan of(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // ------------------------------------------------------------------ reading

    /** Every piece this colony has planned, in the order they were laid. */
    public List<Piece> all(final int colonyId) {
        return pieces.getOrDefault(colonyId, List.of());
    }

    public static List<Piece> of(final ServerLevel level, final IColony colony) {
        return colony == null ? List.of() : of(level).all(colony.getID());
    }

    /**
     * The piece laid most recently - the growing end of the wall.
     *
     * <p>The list is kept in the order the pieces were laid, so the last of it is the tip, and a
     * planner needs nothing of its own to remember where it got to. Hand the planner to somebody
     * else and they carry on from the same place.</p>
     */
    public Piece last(final int colonyId) {
        final List<Piece> mine = all(colonyId);
        return mine.isEmpty() ? null : mine.get(mine.size() - 1);
    }

    /**
     * The planned piece nearest this spot, or null if nothing is within {@code reach}.
     *
     * <p>Measured to the middle of the piece's slot and on the flat, because a player pointing at
     * a wall is pointing at a face of it and may be standing above or below. Forgiving on purpose:
     * "the bit of wall I am looking at" is the question being asked.</p>
     */
    public Piece nearest(final int colonyId, final BlockPos pos, final double reach) {
        Piece best = null;
        double closest = reach * reach;
        for (final Piece piece : all(colonyId)) {
            final BlockPos slot = WallLayout.slotAnchor(piece);
            final BlockPos middle = slot == null
                    ? piece.pos()
                    : slot.relative(piece.along(), piece.kind().length() / 2);
            final double dx = middle.getX() - pos.getX();
            final double dz = middle.getZ() - pos.getZ();
            final double away = dx * dx + dz * dz;
            if (away <= closest) {
                closest = away;
                best = piece;
            }
        }
        return best;
    }

    /** The piece whose anchor is exactly here, or null. */
    public Piece at(final int colonyId, final BlockPos pos) {
        for (final Piece piece : all(colonyId)) {
            if (piece.pos().equals(pos)) {
                return piece;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Remember a piece. A second piece on the same spot replaces the first rather than joining it,
     * because two decorations in one place is how a wall ends up built twice.
     */
    public boolean add(final int colonyId, final Piece piece) {
        final List<Piece> mine = pieces.computeIfAbsent(colonyId, k -> new ArrayList<>());
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).pos().equals(piece.pos())) {
                mine.set(i, piece);
                setDirty();
                return true;
            }
        }
        if (mine.size() >= WallKind.MAX_PIECES) {
            return false;
        }
        mine.add(piece);
        setDirty();
        return true;
    }

    /**
     * Swap one piece for another <b>in place</b>, keeping its position in the list.
     *
     * <p>Position in the list is not cosmetic: the last entry is the growing tip of the wall, so
     * carving a slot out of the middle of a run with a forget-then-add would quietly move the tip
     * into the middle and the next piece laid would appear in the wrong place. This is the only
     * safe way to change a piece that is not at the end.</p>
     */
    public boolean replace(final int colonyId, final BlockPos oldPos, final Piece with) {
        final List<Piece> mine = pieces.get(colonyId);
        if (mine == null) {
            return false;
        }
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).pos().equals(oldPos)) {
                mine.set(i, with);
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Forget a piece. The blocks stay where they are; the colony simply stops calling them its wall. */
    public boolean forget(final int colonyId, final BlockPos pos) {
        final List<Piece> mine = pieces.get(colonyId);
        if (mine == null) {
            return false;
        }
        final boolean gone = mine.removeIf(p -> p.pos().equals(pos));
        if (gone) {
            setDirty();
        }
        return gone;
    }

    /** Raise what the plan wants of one piece. */
    public void want(final int colonyId, final BlockPos pos, final int level) {
        final List<Piece> mine = pieces.get(colonyId);
        if (mine == null) {
            return;
        }
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).pos().equals(pos)) {
                mine.set(i, mine.get(i).at(level));
                setDirty();
                return;
            }
        }
    }

    /** Raise what the plan wants of every piece. */
    public void wantAll(final int colonyId, final int level) {
        final List<Piece> mine = pieces.get(colonyId);
        if (mine == null) {
            return;
        }
        for (int i = 0; i < mine.size(); i++) {
            mine.set(i, mine.get(i).at(level));
        }
        setDirty();
    }

    // ------------------------------------------------------------------ persistence

    private static WallPlan read(final CompoundTag tag, final HolderLookup.Provider provider) {
        final WallPlan out = new WallPlan();
        final ListTag colonies = tag.getList(TAG_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < colonies.size(); i++) {
            final CompoundTag one = colonies.getCompound(i);
            final List<Piece> mine = new ArrayList<>();
            final ListTag list = one.getList(TAG_PIECES, Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) {
                final Piece piece = Piece.load(list.getCompound(j));
                if (piece != null) {
                    mine.add(piece);
                }
            }
            if (!mine.isEmpty()) {
                out.pieces.put(one.getInt(TAG_ID), mine);
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        final ListTag colonies = new ListTag();
        for (final Map.Entry<Integer, List<Piece>> entry : pieces.entrySet()) {
            final CompoundTag one = new CompoundTag();
            one.putInt(TAG_ID, entry.getKey());
            final ListTag list = new ListTag();
            for (final Piece piece : entry.getValue()) {
                list.add(piece.save());
            }
            one.put(TAG_PIECES, list);
            colonies.add(one);
        }
        tag.put(TAG_COLONIES, colonies);
        return tag;
    }
}
