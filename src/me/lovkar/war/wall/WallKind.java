package me.lovkar.war.wall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The three pieces a curtain wall is made of, and everything about them that is not a blueprint.
 *
 * <p>They are <b>decorations</b>, not buildings. That is the decision the whole feature rests on:
 * a ring round a real colony is twenty or thirty pieces, and thirty entries in the colony's
 * building list would bury the town hall. A decoration is built by the same Builder, upgraded by
 * the same work order and levelled the same way - MineColonies reads a decoration's level out of
 * the digits at the end of its schematic name, which is exactly why the blueprints are called
 * {@code wallsegment1..5} - but it stays out of the list of places people work.</p>
 *
 * <p>{@link #LENGTH} is 8 because the Wall Tower drops a patrol point every 8 blocks: one piece is
 * one stride of the patrol, and a wall laid in eights never leaves a guard half a segment from his
 * next point.</p>
 */
public enum WallKind {
    /** A straight run. The one that gets laid dozens of times. */
    SEGMENT("wallsegment", 8),
    /** Turns the run ninety degrees to the RIGHT. Six long, so the buttress rhythm stays on its four-grid. */
    CORNER("wallcorner", 6),
    /**
     * Turns it ninety degrees to the <b>left</b> - the re-entrant, concave corner.
     *
     * <p>Walk any of these walls with the outside on your left and the town on your right and a
     * right turn is {@link #CORNER}. A town that is not a rectangle - an L, a bay round a lake,
     * a notch cut for somebody else's claim - needs the other one, and mirroring {@code CORNER}
     * does not give it: the pieces are drawn with the outside on the left of the run, so a
     * mirrored corner is a ring laid anticlockwise, not a corner that turns the other way.</p>
     *
     * <p>Same six blocks as {@code CORNER}, and the same section throughout, but its anchor sits
     * at the NEAR end of its arms rather than the far one - so it does not share the corner's
     * layout offsets. See {@link WallLayout#AFTER_INNER} and {@link WallLayout#BEFORE_INNER}.</p>
     */
    INNER("wallinner", 6),
    /** A way through: an arch, doors a citizen can path through, and the walk carried over. */
    GATE("wallgate", 8),
    /**
     * A way <b>up</b>: a mural stair off the colony side onto the walk.
     *
     * <p>It stands exactly where a segment would - same length, same box, same anchor - so the
     * planner drops one into a run with no arithmetic of its own. Without it the only way onto a
     * wall is a Wall Tower, which makes a tower compulsory rather than useful.</p>
     */
    STAIR("wallstair", 8),
    /**
     * Not a piece at all: a length of run deliberately left <b>empty</b>.
     *
     * <p>It is where a tower goes. Our own Wall Tower carries the walk straight through, but a
     * MineColonies guard tower or gatehouse has a different footprint and a different height in
     * every style, so a wall cannot be promised to run through one. What a wall <i>can</i> do is
     * stop cleanly on both sides and let the building stand in the middle, and that is what this
     * is: one segment's worth of run that the Builder is never asked to build.</p>
     *
     * <p>It still occupies its slot in the arithmetic - same length as a segment, anchored the
     * same way - so a run with a gap in it stays in phase and the pieces after it land exactly
     * where they would have. {@link WallRegister} counts a gap as covered wall while something
     * qualifying is standing in it, so a ring with towers in it still scores as a closed ring.</p>
     */
    GAP("wallgap", 8);

    /** How many pieces of wall one colony may have. A ring round a big town is about forty. */
    public static final int MAX_PIECES = 96;

    private final String folder;
    private final int length;

    WallKind(final String folder, final int length) {
        this.folder = folder;
        this.length = length;
    }

    public String folder() {
        return folder;
    }

    /** How far along the run this piece reaches, in blocks. */
    public int length() {
        return length;
    }

    /**
     * Whether the Builder is ever asked to put this one up.
     *
     * <p>False only for {@link #GAP}, and every caller that orders, pastes or costs a piece has
     * to ask first - a gap has no blueprint, so treating it like a piece is a null away from a
     * Builder standing in a field with nothing to build.</p>
     */
    public boolean buildable() {
        return this != GAP;
    }

    /** The blueprint inside the pack: {@code wallsegment/wallsegment3.blueprint}. */
    public String path(final int level) {
        return folder + "/" + folder + Math.max(1, Math.min(5, level)) + ".blueprint";
    }

    /** What the work order calls itself in the Builder's list. */
    public String translationKey() {
        return "com.colonies_at_war.decoration." + folder;
    }

    public static WallKind byFolder(final String name) {
        if (name == null) {
            return null;
        }
        final String bare = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]", "");
        for (final WallKind kind : values()) {
            if (kind.folder.equals(bare)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * The level written into a schematic name, the way MineColonies reads it.
     *
     * <p>{@code BlockDecorationController.getLevel} takes the digits out of the name and parses
     * them, so {@code wallsegment3} is level 3. Nothing else decides a decoration's level, which
     * is why the pieces are numbered rather than named.</p>
     */
    public static int levelOf(final String schematicName) {
        if (schematicName == null) {
            return 0;
        }
        final String digits = schematicName.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (final NumberFormatException bad) {
            return 0;
        }
    }

    /**
     * Where the next piece starts, given this one's anchor and the way the run is heading.
     *
     * <p>The blueprint's own east is the direction of the run, so a piece laid facing {@code along}
     * occupies {@code length} blocks in that direction and the next one begins immediately after.
     * Nothing overlaps: the joint is a butt joint, which is what the tiling check in the tools
     * proves.</p>
     */
    public BlockPos next(final BlockPos from, final Direction along) {
        return from.relative(along, length);
    }
}
