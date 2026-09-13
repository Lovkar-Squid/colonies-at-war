"""The wall corner - the same section, turned ninety degrees, with a bartizan on the angle.

One leg runs EAST and one runs SOUTH out of the same angle, so the colony is inside the elbow at
+x, +z and the field is outside it at -x, -z. Rotate the blueprint and it is any of the four
corners of an enceinte; that is the whole reason it is drawn as one piece and not four.

HOW IT IS THE SAME WALL. A corner that is hand-drawn is a corner that will not meet a segment: one
course out of line at either end and the walk steps, the buttress rhythm stutters and the join
shows. So nothing here draws a wall at all. Every column asks wallsegment.section for the block,
with

    off = min(x, z)      how far this cell is from the wall's centre line, outward negative
    n   = max(x, z)      how far along its own leg it is

and those two are exact, not approximate. For a cell out on the east leg (z <= x) off is z and n
is x, which is the straight section; for one down the south leg it is x and z; and in the angle
they agree with each other - min(-1, -1) is the outer face, min(0, 0) is the walk, min(1, 1) is
the inner face. The walk therefore turns as the L of cells with off = 0 and nothing had to be
said about turning. The one thing the section cannot answer for itself is which way is out, so
the leg is told: north and south on the east leg, west and east on the south one.

QUOINS, because a stair has a direction and a corner does not. On the diagonal x = z the batter's
stairs, the corbels and the timber braces would have to point two ways at once, so they are
replaced by the block they are cut from. That is not a dodge - a real corner is quoined, in big
squared stones, precisely because you cannot run a moulding round it.

SIX BY SIX, and the six is arithmetic. The piece spans x and z from -2 to +3, so the next segment
starts at +4; every ornament in the section repeats on 2 or on 4, and 4 divides 4, so a segment
laid against either leg carries the merlons, the buttress piers and the lamps on in step. Make
the corner eight like the segment and the buttress that should fall four blocks after the corner's
falls six. The legs are short on purpose too: a corner piece should be a corner, and the straight
wall either side of it is what segments are for.

WHAT THE ANGLE GETS THAT A STRAIGHT RUN DOES NOT.

  the bartizan   at level 5 the outer angle is corbelled out into a little turret: the pier that
                 the section already puts at n = -2 carries it, the two machicolation holes either
                 side of the angle are filled in to make its shaft solid, and it is capped with
                 its own crenellation two courses above the wall head. A corner is the one place
                 on a wall you can see along both faces from, which is why real walls put a turret
                 there and why this one does.
  the colours    from level 4 a banner on each outer face, where they are seen from two sides.
  the light      a lamp in the elbow, on the banquette rail, because the inside of a corner is
                 the darkest square on the whole wall.

The anchor is the decoration controller again, tucked into the elbow at (2, 1, 2) - inside the
banquette, under its corbel, free at all five levels and with standing room beside it.
"""
from voxel import Structure

import build_pack as bp
import wallsegment as W
from pieces import F_PARAPET, F_RAMPART, lantern, stairs, wall_banner

HEAD_Y = W.HEAD_Y
FAR = 3                          # the last column of each leg; the next piece starts at FAR + 1
NEAR = W.TALUS                   # and the first, out on the batter line

BOX = ((NEAR, W.GROUND_Y, NEAR), (FAR, HEAD_Y, FAR))
ANCHOR = (2, 1, 2)

ROOM_TOP = {1: 4, 2: 4, 3: 5, 4: 5, 5: 5}
RENDER_SPIN = True          # the pictures look at the outer face, which is the one that matters
RENDER_CELL = 18            # a wall piece is small; draw it big enough to read
RENDER_LEVELS = (1, 5)

# The outer angle: the four cells of the 2 x 2 the bartizan stands on.
ANGLE = ((-2, -2), (-1, -2), (-2, -1), (-1, -1))


def leg(x, z):
    """Which way is out and which way is in, for this cell.

    The diagonal belongs to the east leg by fiat; it is quoined anyway, so nothing directional
    comes out of the choice.
    """
    return ("south", "north") if x >= z else ("east", "west")


def build(level):
    """One level of the wall corner, drawn around the same anchor as every other level."""
    s = Structure(f"wallcorner{level}")
    for x in range(NEAR, FAR + 1):
        for z in range(NEAR, FAR + 1):
            off, n = min(x, z), max(x, z)
            if off > W.BERM:
                continue                         # the open ground inside the elbow
            ins, outs = leg(x, z)
            # one cell of one column: the section is asked for the whole height and told nothing
            # about corners except that this one may not point a stair
            b1 = None
            for y in range(1, HEAD_Y + 1):
                b = W.section(n, off, y, level, ins, outs, quoin=(x == z), bays=False)
                if b is None:
                    continue
                s.set(x, y, z, b)
                if y == 1:
                    b1 = b
            if b1 is not None:
                s.set(x, W.GROUND_Y, z, bp.GROUND)
    bartizan(s, level)
    fittings(s, level)
    W.anchor(s, ANCHOR, "Wall Corner")
    return s


def bartizan(s, level):
    """The turret on the angle: level 5 only, and corbelled, never founded.

    The section has already put a pier under the angle (n = -2 falls on the buttress's four) and
    left the two cells either side of it open, because those are machicolation holes. A turret
    wants a solid shaft, so those two get their own corbels and are filled - which is exactly how
    a bartizan is built: it hangs off the corner on brackets, over the ground an attacker has to
    stand on.
    """
    if level < 5:
        return
    for x, z in ((-1, -2), (-2, -1)):
        s.set(x, W.DECK_Y, z, stairs(W.P["fortst"], leg(x, z)[0], "top"))
    for x, z in ANGLE:
        for y in (6, 7):
            s.set(x, y, z, F_RAMPART)
    # its own crenellation, a course above the curtain's: merlons on the diagonal, embrasures
    # across the two faces, so the turret reads as round-ish from every side it is seen from
    s.set(-2, HEAD_Y, -2, F_RAMPART)
    s.set(-1, HEAD_Y, -1, F_RAMPART)
    s.set(-1, HEAD_Y, -2, F_PARAPET)
    s.set(-2, HEAD_Y, -1, F_PARAPET)


def fittings(s, level):
    """The colours and the lamp in the elbow."""
    if level < 4:
        return
    # a banner on each outer face, two columns clear of the angle so neither sits on a buttress
    # pier or in the mouth of a machicolation
    s.set(1, 4, W.TALUS, wall_banner(W.P["flag"], "north"))
    s.set(W.TALUS, 4, 1, wall_banner(W.P["flag"], "west"))
    # and a lamp on the banquette rail, one down each leg. They have to be a mirror pair: the two
    # legs of this piece are the same wall and check_walls proves it cell by cell, so a lamp on one
    # leg and not the other is a failure, not a flourish. Below level 4 there is no banquette to
    # stand one on, and the section has already lit the elbow itself at (1, 7, 1) - which is on the
    # diagonal and so needs no twin.
    s.set(3, W.WALK[1], W.BERM, lantern(W.P["lamp"]))
    s.set(W.BERM, W.WALK[1], 3, lantern(W.P["lamp"]))


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        wall = sum(1 for b in st.blocks.values() if b.startswith("colonies_at_war:"))
        print(f"{st.name:14s} {str(st.size()):14s} {len(st.blocks):4d} blocks  {wall:3d} wall  "
              f"anchor {st.anchor}")
