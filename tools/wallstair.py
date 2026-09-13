"""The wall stair - one segment's worth of curtain with a way up the back of it.

Everything about it is a segment: eight blocks of run, the same five lines of section, the same
shared box, and the anchor in the same square (0, 1, BERM). That is not tidiness, it is the
planner's contract - WallLayout steps along a side in strides of RUN and puts each piece's anchor
on the stride, so a piece whose anchor is anywhere else needs its own constant in the Java. A
stair that is a segment with something added needs none.

WHAT IS ADDED, AND WHAT IS NOT TOUCHED. The wall itself is untouched: the body at z = -1, 0, +1 is
laid by wallsegment.section exactly as a segment lays it, so the walk enters at x = 0 and leaves
at x = 7 at DECK_Y with two clear cells over it, and a guard patrolling the wall walks across this
piece without knowing it is one. That matters twice over - BuildingWallTower.layPatrol drops a
point every eight blocks and would simply lose one here, and WallRegister would read a thin column
as a gap in the ring. So the stair is built in the ONE column the section leaves free: z = +2,
the berm, on the colony's side, where from level 4 the banquette already lives.

THE FLIGHT. Five treads, because the rise is five: the inside ground is y = 1 and the walk is
y = 6. One tread to a block of rise, climbing east, in columns x = 2 to 6.

    x = 0   the decoration controller, where a segment keeps its own
    x = 1   the square a citizen stands on at the foot - which is why the flight starts at 2
    x = 2..6  the treads, at y = 1, 2, 3, 4, 5, each on a solid flank of the wall's own block
    x = 7   left plain, so from level 4 the banquette runs along and dies into the stair head

Under every tread the flank is solid wall block, not air on brackets: a mural stair is a lump of
masonry with steps cut out of the top of it, and a staircase with daylight under it is a staircase
nobody believes. Over every tread two cells are cleared, which is what a citizen occupies and also
what cuts the banquette away where the stair climbs through it - the gallery stops at the foot and
starts again at the head, which is what a gallery does when it meets a stair.

THE HEAD. The top tread is at x = 6, y = 5, so a citizen standing on it has his feet at y = 6 -
the walk's own height. He then steps sideways onto the wall: one cell of the inner rail is left
out at x = 6, and that gap IS the stair head. From level 4 there is no rail there to leave out,
because the inner face is the walk's second lane by then, and he simply walks on.

LEVEL 1 IS NOT A STAIRCASE, and pretending otherwise would be a lie. At level 1 there is no walk -
the wall is a four-high stockade on a three-high bank of earth and timber - so there is nothing to
climb to. What a palisade has instead is a step up onto the back of its own bank, and that is what
this builds: two treads from the ground to the back of the bank, and from there one step up onto
the bank top, which runs the whole length of the wall at y = 4 and is the firing step a level 1
colony actually shoots from. Two steps, not five, and it arrives somewhere real.

THE LEVELS, following the curtain's own.

  1  two spruce treads onto the back of the earth bank.
  2  five spruce treads on a palisade flank, and a gap cut in the timber rail at the head.
  3  stone: five treads on a rampart flank, and a lamp at the head so the steps are not a hole in
     the dark.
  4  the curtain's batter and crenellation arrive; the banquette runs along the berm and dies into
     the stair head, and what the stair now arrives at is a walk two lanes wide.
  5  fortified throughout, and the head is capped: a pier off the banquette rail carrying a short
     canopy over the top two treads, with a lantern hung under it. The one place on a wall a man
     has to stop and turn is the one place worth putting a roof.

WHAT IT HAS NOT GOT is a rail on the open side of the flight. There is nowhere to put one: the
berm is the last column of the section's z band, and widening the piece would break the very thing
that makes it drop into a run without a special case. Real mural stairs are open on that side for
the same reason - the wall is only so thick.
"""
from voxel import Structure

import build_pack as bp
import wallsegment as W
from pieces import F_RAMPART, lantern, slab, stairs, wall_banner

RUN = W.RUN
FOOT = 1                         # the square a citizen stands on at the bottom of the flight
FLIGHT = (2, 3, 4, 5, 6)         # the columns the treads climb, west to east
TAIL = 7                         # left plain, so the banquette has somewhere to die into

BOX = ((0, W.GROUND_Y, W.TALUS), (RUN - 1, W.HEAD_Y, W.BERM))
ANCHOR = W.ANCHOR                # a segment's anchor exactly: the planner needs no constant of its own

ROOM_TOP = {1: 4, 2: 4, 3: 5, 4: 5, 5: 5}
RENDER_SPIN = False         # the stair is on the INSIDE: this is the one piece to look at from there
RENDER_CELL = 22            # bigger than the others: the flight is the point and it is fine detail
RENDER_LEVELS = (1, 3, 5)


def treads(level):
    """The flight, as (column, height of the tread in it).

    Five at every level that has a walk, because the rise from the inside ground to DECK_Y is
    five. Two at level 1, because the bank is only three high and its back is all there is to
    climb to.
    """
    if level == 1:
        return ((FLIGHT[0], 1), (FLIGHT[1], 2))
    return tuple((x, 1 + i) for i, x in enumerate(FLIGHT))


def head(level):
    """The column the stair arrives in, and the height a citizen stands at when he gets there."""
    x, y = treads(level)[-1]
    return x, y + 1


def step_block(level):
    """The treads themselves, in the dressing the level's wall block is textured with."""
    if level <= 2:
        return W.P["brace"]
    return W.P["stair"] if level <= 4 else W.P["fortst"]


# ================================================================ building it

def flight(s, level):
    """The flank, the treads, and the head room over them."""
    body, _rail = W.tiers(level)
    tread = step_block(level)
    for x, y in treads(level):
        for fill in range(1, y):
            s.set(x, fill, W.BERM, body)             # the masonry the steps are cut out of
        s.set(x, y, W.BERM, stairs(tread, "east"))
        # Everything above a tread goes, all the way up. That is not just head room: from level 4
        # it is what takes the banquette out of the stair's way, and taking it out column by column
        # as the flight rises is exactly how a gallery meets a stair - it stops at the foot and
        # starts again at the head.
        for clear in range(y + 1, W.HEAD_Y + 1):
            s.set(x, clear, W.BERM, None)
        s.set(x, W.GROUND_Y, W.BERM, bp.GROUND)
    # the foot stands on the ground, not on air - one block of it, and no landscaping
    s.set(FOOT, W.GROUND_Y, W.BERM, bp.GROUND)


def opening(s, level):
    """The gap in the inner rail the stair arrives through.

    One cell, at the head's own column. From level 4 the inner face is the walk's second lane and
    there is no rail there to take out, so this does nothing - which is the right answer rather
    than a special case.
    """
    if level == 1:
        return                                        # nothing to open: the bank has no rail
    x, _y = head(level)
    s.set(x, W.WALK[0], W.IN, None)
    s.set(x, W.WALK[0], W.BERM, None)


def fittings(s, level):
    """The light at the head, the colours on the outer face, and level 5's cap."""
    if level >= 4:
        # the same square the segment flies its colours from, so a run of wall keeps its rhythm
        # when a stair stands in it
        s.set(4, 4, W.TALUS, wall_banner(W.P["flag"], "north"))
    if level in (2, 3):
        s.set(TAIL, W.WALK[1], W.IN, lantern(W.P["lamp"]))        # on the inner rail
    elif level == 4:
        s.set(TAIL, W.WALK[1], W.BERM, lantern(W.P["lamp"]))      # on the banquette rail
    elif level == 5:
        cap(s)


def cap(s):
    """Level 5: a pier off the banquette rail carrying a canopy over the top of the flight.

    It stands at x = 7 because that is the only column of the berm the flight has not cleared, and
    it reaches back over the head from there. The lantern hangs from the canopy rather than
    standing on the rail, so nothing is left in the two cells a citizen occupies at the head.
    """
    for y in (W.WALK[1], W.HEAD_Y):
        s.set(TAIL, y, W.BERM, F_RAMPART)
    for x in (TAIL - 2, TAIL - 1):
        s.set(x, W.HEAD_Y, W.BERM, slab(W.P["fortsl"], "bottom"))
    s.set(TAIL - 2, W.WALK[1], W.BERM, lantern(W.P["lamp"], hanging=True))


def build(level):
    """One level of the wall stair, drawn around the same anchor as every other level."""
    s = Structure(f"wallstair{level}")
    for n in range(RUN):
        W.column(s, level, n, lambda off, n=n: (n, off), bays=False)
    flight(s, level)
    opening(s, level)
    fittings(s, level)
    W.anchor(s, ANCHOR, "Wall Stair")
    return s


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        wall = sum(1 for b in st.blocks.values() if b.startswith("colonies_at_war:"))
        print(f"{st.name:14s} {str(st.size()):14s} {len(st.blocks):4d} blocks  {wall:3d} wall  "
              f"{len(treads(lv))} treads  head {head(lv)}  anchor {st.anchor}")
