"""The inner corner - the same section again, turned the other way, where the wall makes a left.

x east, z south, as everywhere. This piece turns a run that arrives heading SOUTH into one that
leaves heading EAST. Walk any of these walls with the outside on your left and the town on your
right and that is a **left** turn, which is the one turn the convex corner cannot make: there, the
town sits in the 90 degree elbow and the field wraps 270 degrees round the outside. Here it is the
other way about. The field is the wedge at **+x, -z** - the notch - and the wall wraps round it.

WHY MIRRORING DOES NOT DO THIS. Structurize can mirror a blueprint, and it is tempting to think a
mirrored corner is an inner corner. It is not. The pieces are drawn with the outside on the LEFT
of the run, so mirroring puts the outside on the right: that gives a ring laid anticlockwise, not
a corner that turns the other way. A concave corner needs blocks in places the convex piece simply
has none - its outer faces are on the INSIDE of the elbow - so it has to be drawn.

HOW IT IS THE SAME WALL, which is the whole trick and is one line of arithmetic. Nothing here
draws a wall either; every column asks wallsegment.section for its block, with

    u = x, v = -z        both counted into the notch, so both are >= 0 out along an arm
    off = -min(u, v)     how far this cell is from the wall's centre line, outward negative
    n   =  max(u, v)     how far along its own arm it is

Read it against the convex corner and the symmetry is exact: there off is min(x, z) and the
outside is the -x, -z quadrant; here off is -min(u, v) and the outside is the +x, -z quadrant.
The sign is the whole difference, and it is the sign because "outward" has swapped sides.

THE RULE IS min AND NOT max, and the first draft of the design note had it the other way round.
Take the walk two blocks east of the angle, (x=2, z=0): it is the centre line, off must be 0.
-min(2, 0) is 0; -max(2, 0) is -2, which would lay the walk out on the batter. Or (x=3, z=-1),
one course off the east arm's centre line: -min(3, 1) is -1, the outer face, and -max(3, 1) is -3,
which is not a line of the section at all. Seventeen of the twenty cells of an arm come out wrong
under max and none under min. `check_walls.py` now holds this to the section cell by cell.

ONE BOX, ONE RULE. The design note worried that two arms drawn as rectangles would leave a
diagonal notch in the inner faces. They would - but the piece is not two rectangles. It is one
6 x 6 box with the rule above, exactly as the convex corner is, and the only cell the rule throws
away is the far corner of the notch at (u, v) = (3, 3), where off would be -3 and there is no such
line. The inner face comes out as an unbroken L through (u, v) = (-1, -1). Nothing had to be said
about turning, in either piece, and that is the point of asking the section for everything.

SIX BY SIX, and the six is the same arithmetic as the convex corner's: the arms run n = 0 .. 3
out of the angle, four cells, and four divides every ornament period in the section, so a segment
laid against either arm carries the merlons, the piers and the lamps on in step.

WHAT THE ANGLE GETS, and it is not a bartizan. A turret is corbelled off a projecting angle and
this angle does not project - it points into the field. What a re-entrant angle has instead is the
thing that makes bastions worth building: **the two faces flank each other.** Anyone at the foot
of one face stands in front of the other. So

  the machicoulis  at level 5 the holes are left OPEN right across the angle. The convex corner
                   fills those two cells to make its turret a solid shaft; here there is no shaft,
                   and the notch is the one place on a wall where what you drop covers both faces
                   at once.
  the colours      from level 4, a banner on each outer face - which here look into the notch, so
                   both are read from the same ground an attacker has to cross.
  the light        a lamp on the banquette rail one down each arm, a mirror pair, because the two
                   arms are the same wall and check_walls proves it cell by cell.

The anchor sits on the berm in the open elbow behind the angle, at (-2, 1, 2) - the far side from
the notch, free at all five levels, with standing room beside it.
"""
from voxel import Structure

import build_pack as bp
import wallsegment as W
from pieces import lantern, wall_banner

HEAD_Y = W.HEAD_Y
FAR = 3                          # the last column of each arm; the next piece starts at FAR + 1
NEAR = W.TALUS                   # and the first, back on the town side

# u = x and v = -z, both in [NEAR, FAR] - so in world terms x runs -2..3 and z runs -3..2.
BOX = ((NEAR, W.GROUND_Y, -FAR), (FAR, HEAD_Y, -NEAR))
ANCHOR = (-2, 1, 2)

ROOM_TOP = {1: 4, 2: 4, 3: 5, 4: 5, 5: 5}
RENDER_SPIN = True
RENDER_CELL = 18
RENDER_LEVELS = (1, 5)

# The four cells of the 2 x 2 the angle turns through, in (u, v).
ANGLE = ((2, 2), (3, 2), (2, 3), (3, 3))


def uv(x, z):
    """Into the notch: both counted positive out along their own arm."""
    return x, -z


def place(u, v):
    """...and back again."""
    return u, -v


def leg(u, v):
    """Which way is out and which way is in, for this cell.

    The diagonal belongs to the outgoing arm by fiat, the same way the convex corner gives it to
    its east leg; it is quoined anyway, so nothing directional comes out of the choice.
    """
    return ("south", "north") if u >= v else ("west", "east")


def build(level):
    """One level of the inner corner, drawn around the same anchor as every other level."""
    s = Structure(f"wallinner{level}")
    for u in range(NEAR, FAR + 1):
        for v in range(NEAR, FAR + 1):
            off, n = -min(u, v), max(u, v)
            if off < W.TALUS:
                continue                         # the far corner of the notch: no such line
            x, z = place(u, v)
            ins, outs = leg(u, v)
            b1 = None
            for y in range(1, HEAD_Y + 1):
                b = W.section(n, off, y, level, ins, outs, quoin=(u == v), bays=False)
                if b is None:
                    continue
                s.set(x, y, z, b)
                if y == 1:
                    b1 = b
            if b1 is not None:
                s.set(x, W.GROUND_Y, z, bp.GROUND)
    fittings(s, level)
    W.anchor(s, ANCHOR, "Wall Inner Corner")
    return s


def fittings(s, level):
    """The colours and the lamps. The machicoulis needs nothing done to it - see the docstring:
    leaving the section's own holes open across the angle IS the feature."""
    if level < 4:
        return
    # A banner hangs on the outermost line, off = TALUS, against the outer face behind it - which
    # is where the convex corner hangs its pair too. But on a concave corner that line EXISTS only
    # where both arms are at least two out, because the outermost plane of the piece is the throat
    # of the notch: the three cells (2,2), (3,2) and (2,3), of which the first is the quoined
    # diagonal. So the pair is the other two, one on each face, both read from the same ground an
    # attacker has to cross. Two columns clear of the angle is not available here and not needed:
    # n = 3 falls between buttress piers either way.
    s.set(*banner_at(3, 2, "north"))          # the outgoing arm's face, looking north
    s.set(*banner_at(2, 3, "east"))           # the incoming arm's face, looking east
    # a lamp on the banquette rail, one down each arm - a mirror pair, because the two arms are
    # the same wall. Below level 4 there is no banquette to stand one on.
    for u, v in ((3, -W.BERM), (-W.BERM, 3)):
        x, z = place(u, v)
        s.set(x, W.WALK[1], z, lantern(W.P["lamp"]))


def banner_at(u, v, facing):
    """A banner at (u, v), given back as a set() call's four arguments."""
    x, z = place(u, v)
    return x, 4, z, wall_banner(W.P["flag"], facing)


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        wall = sum(1 for b in st.blocks.values() if b.startswith("colonies_at_war:"))
        print(f"{st.name:14s} {str(st.size()):14s} {len(st.blocks):4d} blocks  {wall:3d} wall  "
              f"anchor {st.anchor}")
