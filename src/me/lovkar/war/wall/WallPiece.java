package me.lovkar.war.wall;

/**
 * A block that is part of a wall, and says how much of a wall it is.
 *
 * <p>This interface is the whole reason the mod ships its own wall. A wall built out of these
 * blocks does not have to be <i>found</i>: every piece tells the colony's {@link WallRegister}
 * where it is and what it is worth the moment it is placed, and takes itself back out when it is
 * broken. The assessment then asks the register instead of walking the ground, which is exact,
 * instant, and impossible to fool with a long barn.</p>
 */
public interface WallPiece {

    /** 1 palisade, 2 stone, 3 fortified. The tier is the strength - never the skin. */
    int tier();

    /**
     * What one block of this counts for in the run's strength, relative to a full rampart.
     * A stair or a parapet is part of a wall but not the part that stops anybody.
     */
    default float weight() {
        return 1.0f;
    }

    /** True if guards can walk along the top of this piece - what a patrol is laid on. */
    default boolean walkable() {
        return true;
    }
}
