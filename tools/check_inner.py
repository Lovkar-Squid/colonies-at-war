"""Does a wall that turns BOTH ways still tile?

`check_ring.py` proves the clockwise ring: four right turns, every side a whole number of segments,
every joint a butt joint. It cannot say anything about a left turn, because until now the wall
could not make one. This does the same job for a run that mixes them.

THE THING THAT IS EASY TO GET BACKWARDS, and which cost a wrong answer the first time it was
worked out: a corner's two offsets are not properties of the piece alone, they depend on which
side the run arrives from.

    forward   how far the piece reaches past its anchor along the run it LEAVES on.
              Both corners measure this the same way, along local +x.
    backward  how far it reaches past its anchor against the run it ARRIVES on - and the convex
              corner's incoming arm lies along local +z while the inner corner's lies along
              local -z, because they turn opposite ways. Measure the wrong one and the convex
              corner comes out as 12 instead of 9, which lays every following piece three blocks
              too far on and leaves a hole at every corner.

From those two, with a segment eight long:

    AFTER_CORNER  = forward + 1                  the next piece starts one past the last block
    BEFORE_CORNER = backward + 1 + (8 - 1)       ...and a segment ending there is anchored 7 back

which gives the convex corner the 2 and 9 that WallLayout has always used - that is the check on
the method - and the inner corner 6 and 13.

    python3 tools/check_inner.py
"""
import pathlib
import re
import sys

import wallcorner
import wallinner
import wallsegment as W

SEG = W.RUN

# The numbers below are derived from the blueprints' own boxes. The Java hardcodes them, because a
# server cannot open a blueprint to find out where the next piece goes - so the two must agree, and
# a wall where they do not agree has a hole at every corner that nobody sees until it is built.
ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = (ROOT / "src/me/lovkar/war/wall/WallLayout.java").read_text(encoding="utf-8")
KINDS = (ROOT / "src/me/lovkar/war/wall/WallKind.java").read_text(encoding="utf-8")


def const(name):
    hit = re.search(rf"{name} = (\d+);", JAVA)
    if not hit:
        raise SystemExit(f"  could not read {name} out of WallLayout.java")
    return int(hit.group(1))

DIRS = {"E": (1, 0), "S": (0, 1), "W": (-1, 0), "N": (0, -1)}
LEFT = {"E": "N", "N": "W", "W": "S", "S": "E"}
RIGHT = {"E": "S", "S": "W", "W": "N", "N": "E"}


def foot(mod):
    """(along +x), (across +z) extents of the piece, relative to its ANCHOR."""
    (x0, _, z0), (x1, _, z1) = mod.BOX
    ax, _, az = mod.ANCHOR
    return (x0 - ax, x1 - ax), (z0 - az, z1 - az)


def offsets(mod, turns):
    """AFTER_CORNER and BEFORE_CORNER for this corner piece.

    @param turns  "right" for a convex corner - its incoming arm is on its local +z side -
                  or "left" for an inner one, whose incoming arm is on local -z.
    """
    (_, fh), (cl, ch) = foot(mod)
    backward = ch if turns == "right" else -cl
    return fh + 1, backward + 1 + (SEG - 1)


def cells(mod, anchor, along):
    """Every (x, z) the piece covers, anchored at `anchor` and heading `along`."""
    (fl, fh), (cl, ch) = foot(mod)
    dx, dz = DIRS[along]
    px, pz = -dz, dx                         # the piece's own +z: its right hand
    return {(anchor[0] + dx * f + px * c, anchor[1] + dz * f + pz * c)
            for f in range(fl, fh + 1) for c in range(cl, ch + 1)}


def walk_line(mod, anchor, along):
    """The run's centre line through this piece, as (first, last) in world cells.

    For a corner it is an L and the two ends are what matter: where the run comes in and where it
    goes out. Returned as the pair, which is all the tiling cares about.
    """
    (fl, fh), _ = foot(mod)
    dx, dz = DIRS[along]
    return ((anchor[0] + dx * fl, anchor[1] + dz * fl),
            (anchor[0] + dx * fh, anchor[1] + dz * fh))


def lay(script, start=(0, 0), heading="E", closes=None):
    """Walk a script of (corner kind, how many segments) and place every piece.

    Nothing is laid twice: the corner that would CLOSE a loop is not placed, it is given back, so
    the caller can say whether it came home without the check reporting the join as an overlap.

    @param closes  the kind of the corner that would close the loop, or None for an open run
    @return        (the pieces, where the next corner would go, which way it would leave)
    """
    plan, at, along = [], start, heading
    for i, (kind, count) in enumerate(script):
        mod = wallcorner if kind == "right" else wallinner
        after, _ = offsets(mod, kind)
        plan.append((mod, at, along, kind + " corner"))
        dx, dz = DIRS[along]
        seg_at = (at[0] + dx * after, at[1] + dz * after)
        last = None
        for _ in range(count):
            plan.append((W, seg_at, along, "segment"))
            last = seg_at
            seg_at = (seg_at[0] + dx * SEG, seg_at[1] + dz * SEG)
        nkind = script[i + 1][0] if i + 1 < len(script) else closes
        if nkind is None:
            at, along = None, along
            break
        nmod = wallcorner if nkind == "right" else wallinner
        _, before = offsets(nmod, nkind)
        at = (last[0] + dx * before, last[1] + dz * before)
        along = RIGHT[along] if nkind == "right" else LEFT[along]
    return plan, at, along


def tile(plan, out):
    """No two pieces may share a cell, and the run may not break between them."""
    ok = True
    seen = {}
    for mod, anchor, along, label in plan:
        for cell in cells(mod, anchor, along):
            if cell in seen:
                out.append(f"!! {label} at {anchor} overlaps {seen[cell]} at {cell}")
                ok = False
            seen[cell] = f"{label}@{anchor}"

    prev_end, prev_along = None, None
    for mod, anchor, along, label in plan:
        first, last = walk_line(mod, anchor, along)
        if prev_end is not None:
            dx, dz = DIRS[prev_along] if prev_along == along else DIRS[along]
            want = (prev_end[0] + DIRS[along][0], prev_end[1] + DIRS[along][1]) \
                if prev_along == along else None
            if want is not None and first != want:
                out.append(f"!! the run breaks before {label} at {anchor}: "
                           f"it should start at {want}, it starts at {first}")
                ok = False
        prev_end, prev_along = last, along
    return ok


def main():
    out = []
    print("piece footprints, relative to the anchor:")
    for name, mod in (("segment", W), ("corner (right)", wallcorner), ("inner (left)", wallinner)):
        (fl, fh), (cl, ch) = foot(mod)
        print(f"  {name:16s} along {fl:+d}..{fh:+d}   across {cl:+d}..{ch:+d}")

    a_r, b_r = offsets(wallcorner, "right")
    a_i, b_i = offsets(wallinner, "left")
    print(f"\n  right turn: AFTER {a_r}, BEFORE {b_r}")
    print(f"  left  turn: AFTER {a_i}, BEFORE {b_i}")

    # The method has to reproduce the numbers WallLayout has been using since the ring worked -
    # that is the check on the method - and the Java has to carry the ones it derives for the left
    # turn, or the server lays a wall the blueprints do not make.
    for label, got, java in (("AFTER_CORNER", a_r, const("AFTER_CORNER")),
                             ("BEFORE_CORNER", b_r, const("BEFORE_CORNER")),
                             ("AFTER_INNER", a_i, const("AFTER_INNER")),
                             ("BEFORE_INNER", b_i, const("BEFORE_INNER"))):
        if got != java:
            out.append(f"!! {label}: the blueprints say {got}, WallLayout.java says {java}")
    if (a_r, b_r) != (2, 9):
        out.append(f"!! the convex corner comes out as {a_r} and {b_r}, not the known-good 2 and 9 "
                   f"- the forward/backward rule is wrong")
    if 'INNER("wallinner", 6)' not in KINDS:
        out.append("!! WallKind has no INNER piece, or it is not six long")

    # a plain clockwise ring: four right turns, and it must come home
    ring, home, heading = lay([("right", 2)] * 4, closes="right")
    print(f"\na square ring, 4 right turns: {len(ring)} pieces, closes at {home} heading {heading}")
    tile(ring, out)
    if home != (0, 0) or heading != "E":
        out.append(f"!! the ring does not close: it comes back to {home} heading {heading}, "
                   f"not (0, 0) heading E")

    # An L-shaped town. Going round it clockwise there are FIVE right turns and one left - a
    # closed loop's turns must come to four right angles, and a left one has to be paid for.
    ell, home, heading = lay(
        [("right", 2), ("right", 2), ("left", 2), ("right", 2), ("right", 2), ("right", 1)],
        closes="right")
    print(f"an L-shaped wall, 5 right turns and 1 left: {len(ell)} pieces, "
          f"closes at {home} heading {heading}")
    tile(ell, out)
    if heading != "E":
        out.append(f"!! the L comes home heading {heading}, not E - the turns do not add up")

    # ...and an open run that turns left twice in a row, which is the shape of a bay
    bay, _, _ = lay([("right", 1), ("left", 1), ("left", 1), ("right", 1)])
    print(f"a bay, two left turns in a row: {len(bay)} pieces")
    tile(bay, out)

    print()
    for line in out:
        print(line)
    print("a wall that turns both ways tiles" if not out else "!! IT DOES NOT TILE")
    return 0 if not out else 1


if __name__ == "__main__":
    sys.exit(main())
