package me.lovkar.war.wall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the pieces of a wall go, worked out once and written down.
 *
 * <p>Every number in this file comes from the blueprints themselves rather than from a guess, and
 * they are the difference between a wall and a row of buildings that nearly touch:</p>
 *
 * <ul>
 *   <li>A <b>segment</b> is 8 long. Its anchor sits at the start of its run, on the inner face, so
 *       a segment anchored at P holds the eight columns P .. P+7 along the run.</li>
 *   <li>A <b>corner</b> reaches 4 back and 1 forward from its anchor on each leg. So the piece
 *       that leaves a corner starts at <b>corner + 2</b> along the new run, and the corner that
 *       ends a run sits at <b>last segment + 9</b>.</li>
 *   <li>A <b>gate</b> is 8 long like a segment but anchored at its far end, so a gate standing
 *       where a segment would stand is anchored at <b>that segment + 6</b>.</li>
 * </ul>
 *
 * <p>Which makes a closed ring exactly <b>(8k + 3) by (8m + 3)</b> blocks between its corner
 * anchors, and the whole of the planner's job is to round what the player marked to the nearest
 * one of those.</p>
 *
 * <p><b>Clockwise, always.</b> The pieces are drawn with the outside on the <i>left</i> of the
 * run, so a ring laid clockwise from its north-west corner needs no mirroring anywhere - north
 * side east, east side south, south side west, west side north, and the four corners are the same
 * piece turned 0, 90, 180 and 270. A ring laid the other way would need mirrored copies of every
 * piece, which is why this never offers the choice.</p>
 */
public final class WallLayout {

    /** A segment leaving a corner starts this far along the new run. Right turns; see {@link #AFTER_INNER}. */
    public static final int AFTER_CORNER = 2;
    /** A corner closing a run sits this far past the last segment's anchor. */
    public static final int BEFORE_CORNER = 9;
    /**
     * A segment leaving an <b>inner</b> corner starts this far along the new run.
     *
     * <p>Not the convex corner's 2, and the difference is the anchor. Both pieces are six long,
     * but the convex one is anchored in its elbow at the far end of its arms and so reaches only
     * +1 past its anchor along the outgoing run, while the inner corner is anchored on the berm
     * behind the angle and reaches +5. The rule is the same either way - the next piece starts one
     * block past the corner's last - and {@code tools/check_inner.py} derives both numbers from
     * the blueprints' own boxes and then lays an L-shaped wall to prove they tile.</p>
     */
    public static final int AFTER_INNER = 6;
    /** ...and a corner closing a run into an inner corner sits this far past the last segment. */
    public static final int BEFORE_INNER = 13;
    /** A gate stands where a segment would, but is anchored at the far end of its own run. */
    public static final int GATE_OFFSET = 6;
    /**
     * A stair stands where a segment would and is anchored where a segment is - so nought.
     *
     * <p>Written out rather than left implicit because it is the promise the stair's blueprint
     * makes, and {@code tools/check_ring.py} holds both sides to it.</p>
     */
    public static final int STAIR_OFFSET = 0;
    /** A side needs at least this many segments before one of them is given over to a stair. */
    public static final int STAIR_FROM = 2;
    /** The shortest side worth calling a wall: one corner, one segment, one corner. */
    public static final int MIN_SEGMENTS = 1;
    /** As long a side as the planner will lay in one go. */
    public static final int MAX_SEGMENTS = 12;

    private WallLayout() {
    }

    /** The four sides of a ring, clockwise from the north-west corner. */
    private static final Direction[] CLOCKWISE =
            {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    /** How many whole segments fit a side of this many blocks between corner anchors. */
    public static int segmentsFor(final int blocks) {
        final int n = Math.round((blocks - 3) / (float) WallKind.SEGMENT.length());
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, n));
    }

    /** The size a side actually takes once it is a whole number of segments. */
    public static int sideFor(final int segments) {
        return segments * WallKind.SEGMENT.length() + 3;
    }

    /**
     * A closed ring through the two marks, snapped so every side is a whole number of segments.
     *
     * @param a       one mark
     * @param b       the other, taken as the opposite corner
     * @param gateOn  the side that gets a gateway in the middle of it, or null for no gate
     */
    public static List<WallPlan.Piece> ring(final BlockPos a, final BlockPos b, final Direction gateOn) {
        final int x0 = Math.min(a.getX(), b.getX());
        final int z0 = Math.min(a.getZ(), b.getZ());
        final int k = segmentsFor(Math.abs(b.getX() - a.getX()));
        final int m = segmentsFor(Math.abs(b.getZ() - a.getZ()));
        final int y = a.getY();

        final BlockPos nw = new BlockPos(x0, y, z0);
        final BlockPos[] corners = {
                nw,
                nw.offset(sideFor(k), 0, 0),
                nw.offset(sideFor(k), 0, sideFor(m)),
                nw.offset(0, 0, sideFor(m)),
        };

        final List<WallPlan.Piece> out = new ArrayList<>();
        for (int side = 0; side < 4; side++) {
            final Direction along = CLOCKWISE[side];
            out.add(new WallPlan.Piece(corners[side], WallKind.CORNER, along, 1));
            final int count = (side % 2 == 0) ? k : m;
            final int gateAt = along == gateOn ? count / 2 : -1;
            // one way up per side, as the first piece after the corner - a ring you can only get
            // onto at one place is a ring the guards spend the night walking round
            final int stairAt = count >= STAIR_FROM && gateAt != 0 ? 0 : -1;
            BlockPos at = corners[side].relative(along, AFTER_CORNER);
            for (int i = 0; i < count; i++) {
                if (i == gateAt) {
                    out.add(new WallPlan.Piece(at.relative(along, GATE_OFFSET), WallKind.GATE, along, 1));
                } else if (i == stairAt) {
                    out.add(new WallPlan.Piece(at.relative(along, STAIR_OFFSET), WallKind.STAIR, along, 1));
                } else {
                    out.add(new WallPlan.Piece(at, WallKind.SEGMENT, along, 1));
                }
                at = at.relative(along, WallKind.SEGMENT.length());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ growing a wall by hand

    /**
     * The next direction clockwise. The only turn a run may make.
     *
     * <p>The pieces are drawn with the outside on the <i>left</i>, so a right turn keeps the
     * outside outside. A left turn would need a mirrored inner corner, and there is no such
     * blueprint - which is why the planner refuses one out loud rather than laying a piece that
     * faces the wrong way.</p>
     */
    public static Direction rightOf(final Direction along) {
        return switch (along) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /**
     * Where this piece's <b>slot</b> begins.
     *
     * <p>Every piece that stands in a run occupies one slot of {@link WallKind#SEGMENT}'s length,
     * but they are not all anchored at the start of it: a gate is anchored {@link #GATE_OFFSET}
     * along its own slot. Normalising to the slot is what lets the rest of this file do the same
     * arithmetic whatever is standing there.</p>
     *
     * @return null for a corner, which is not a slot in a run
     */
    public static BlockPos slotAnchor(final WallPlan.Piece piece) {
        return switch (piece.kind()) {
            case CORNER -> null;
            case GATE -> piece.pos().relative(piece.along().getOpposite(), GATE_OFFSET);
            default -> piece.pos();
        };
    }

    /**
     * Where the next piece of the run begins, given the one that is currently last.
     *
     * <p>After a corner the run restarts {@link #AFTER_CORNER} along the corner's own direction -
     * a corner stores the direction of the side it <i>begins</i>, not the one it ends. After
     * anything standing in a slot, the next slot begins one segment further on.</p>
     */
    public static BlockPos tipAfter(final WallPlan.Piece piece) {
        if (piece.kind() == WallKind.CORNER) {
            return piece.pos().relative(piece.along(), AFTER_CORNER);
        }
        return slotAnchor(piece).relative(piece.along(), WallKind.SEGMENT.length());
    }

    /**
     * Where the corner goes that closes a run ending with this piece.
     *
     * <p>{@link #BEFORE_CORNER} past the last slot, which is the same number the ring uses - and
     * it has to be, or a wall grown by hand would not join a wall laid by the ring.</p>
     *
     * @return null if the run ends on a corner already, which is a turn with nothing between it
     */
    public static BlockPos cornerAfter(final WallPlan.Piece piece) {
        final BlockPos slot = slotAnchor(piece);
        return slot == null ? null : slot.relative(piece.along(), BEFORE_CORNER);
    }

    /**
     * A single straight run from a towards b, as many whole segments as fit.
     *
     * <p>No corners: this is the piece-by-piece way of drawing a wall that does not want to be a
     * rectangle, and the corners are then the player's to place. The run follows whichever axis
     * the two marks differ on most, because a player who clicks two corners of a field means the
     * long side.</p>
     */
    public static List<WallPlan.Piece> line(final BlockPos a, final BlockPos b) {
        final int dx = b.getX() - a.getX();
        final int dz = b.getZ() - a.getZ();
        final Direction along = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        final int span = Math.max(Math.abs(dx), Math.abs(dz));
        final int count = Math.max(MIN_SEGMENTS,
                Math.min(MAX_SEGMENTS, span / WallKind.SEGMENT.length()));

        final List<WallPlan.Piece> out = new ArrayList<>();
        BlockPos at = new BlockPos(a.getX(), a.getY(), a.getZ());
        for (int i = 0; i < count; i++) {
            // a run of two or more gets its own way up, at the end the player started from
            final WallKind kind = i == 0 && count >= STAIR_FROM ? WallKind.STAIR : WallKind.SEGMENT;
            out.add(new WallPlan.Piece(at.relative(along, kind == WallKind.STAIR ? STAIR_OFFSET : 0),
                    kind, along, 1));
            at = at.relative(along, WallKind.SEGMENT.length());
        }
        return out;
    }
}
