"""Does a wall grown by hand join up the same way a wall laid as a ring does?

The planner now has two ways to draw the same wall. `ring` works the whole rectangle out at once
from two marks; `grow` lays one piece per click, turning a corner when the player looks right. They
share no code - grow walks forward from the tip, ring counts sides - so nothing but arithmetic
keeps them agreeing, and arithmetic that only one of them uses is arithmetic that drifts.

So this grows a wall by hand, clockwise, four sides and four corners, and asserts the pieces land
on EXACTLY the positions `WallLayout.ring` would have put them - same anchors, same facings, same
order of slots. If the two ever disagree, a player who grew three sides and let the planner close
the fourth would get a wall with a seam, and nothing in the game would say so.

It also asks the question a gap raises: a slot left empty for a tower has to occupy a whole
segment's worth of run, or every piece after it is off by the difference.

  python3 tools/check_grow.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = (ROOT / "src/me/lovkar/war/wall/WallLayout.java").read_text()
KINDS = (ROOT / "src/me/lovkar/war/wall/WallKind.java").read_text()


def const(name):
    hit = re.search(rf"{name} = (\d+);", JAVA)
    if hit is None:
        raise SystemExit(f"  could not read {name} out of WallLayout.java")
    return int(hit.group(1))


AFTER_CORNER = const("AFTER_CORNER")
BEFORE_CORNER = const("BEFORE_CORNER")
GATE_OFFSET = const("GATE_OFFSET")
LENGTH = {m.group(1).lower(): int(m.group(2))
          for m in re.finditer(r'([A-Z]+)\("wall[a-z]+", (\d+)\)', KINDS)}
SEG = LENGTH["segment"]

# (dx, dz) per direction, and the clockwise order the pieces are drawn for
STEP = {"east": (1, 0), "south": (0, 1), "west": (-1, 0), "north": (0, -1)}
RIGHT = {"north": "east", "east": "south", "south": "west", "west": "north"}


def go(pos, along, n):
    dx, dz = STEP[along]
    return (pos[0] + dx * n, pos[1], pos[2] + dz * n)


def slot_anchor(piece):
    """WallLayout.slotAnchor - a gate is anchored along its own slot, everything else at the start."""
    pos, kind, along = piece
    if kind == "corner":
        return None
    if kind == "gate":
        return go(pos, RIGHT[RIGHT[along]], GATE_OFFSET)     # back the way it came
    return pos


def tip_after(piece):
    """WallLayout.tipAfter."""
    pos, kind, along = piece
    if kind == "corner":
        return go(pos, along, AFTER_CORNER)
    return go(slot_anchor(piece), along, SEG)


def corner_after(piece):
    """WallLayout.cornerAfter."""
    slot = slot_anchor(piece)
    return None if slot is None else go(slot, piece[2], BEFORE_CORNER)


def grow_a_ring(start, along, per_side):
    """Lay a closed rectangle the way the planner's grow mode does: pieces, then a right turn."""
    out = []
    tip, facing = start, along
    for side in range(4):
        for i in range(per_side[side]):
            kind = "stair" if (side == 0 and i == 0) else "segment"
            out.append((tip, kind, facing))
            tip = tip_after(out[-1])
        turn = RIGHT[facing]
        corner_at = corner_after(out[-1])
        out.append((corner_at, "corner", turn))
        facing = turn
        tip = tip_after(out[-1])
    return out, tip, facing


def ring_pieces(nw, k, m, gate_on=None):
    """WallLayout.ring, in Python - the shape the planner lays from two marks."""
    side_for = lambda n: n * SEG + 3                       # noqa: E731
    clockwise = ["east", "south", "west", "north"]
    corners = [nw,
               go(nw, "east", side_for(k)),
               go(go(nw, "east", side_for(k)), "south", side_for(m)),
               go(nw, "south", side_for(m))]
    out = []
    for side, along in enumerate(clockwise):
        out.append((corners[side], "corner", along))
        count = k if side % 2 == 0 else m
        at = go(corners[side], along, AFTER_CORNER)
        for i in range(count):
            out.append((at, "segment", along))
            at = go(at, along, SEG)
    return out


def main():
    bad = []
    print("grow:")
    print(f"  constants from the Java: segment {SEG}, after a corner {AFTER_CORNER}, "
          f"before one {BEFORE_CORNER}, gate {GATE_OFFSET}")

    # 1 - a hand-grown ring has to close on itself
    for k, m in ((1, 1), (3, 2), (4, 4), (6, 3)):
        nw = (0, 64, 0)
        start = go(nw, "east", AFTER_CORNER)
        grown, tip, facing = grow_a_ring(start, "east", (k, m, k, m))
        # after four sides and four corners the tip must be back where the first piece began
        if tip != start or facing != "east":
            bad.append(f"a {k}x{m} ring grown by hand ends at {tip} facing {facing}, "
                       f"not back at {start} facing east")
        # and every slot must sit where the ring would have put it
        want = {(p[0], p[2]) for p in ring_pieces(nw, k, m) if p[1] != "corner"}
        got = {(p[0], p[2]) for p in grown if p[1] not in ("corner",)}
        if want != got:
            bad.append(f"a {k}x{m} grown ring does not land on the ring's own slots: "
                       f"{len(want - got)} missed, {len(got - want)} extra")
        wantc = {(p[0], p[2]) for p in ring_pieces(nw, k, m) if p[1] == "corner"}
        gotc = {(p[0], p[2]) for p in grown if p[1] == "corner"}
        if wantc != gotc:
            bad.append(f"a {k}x{m} grown ring puts its corners somewhere else: "
                       f"want {sorted(wantc)}, got {sorted(gotc)}")
        print(f"  {k}x{m}: {len(grown)} piece(s) grown, closes at {tip} - "
              f"{'matches the ring' if want == got and wantc == gotc else 'DOES NOT MATCH'}")

    # 2 - a gap has to keep the run in phase
    here = (0, 64, 0)
    for kind in ("segment", "stair", "gap"):
        if tip_after((here, kind, "east")) != go(here, "east", SEG):
            bad.append(f"a {kind} does not advance the run by one segment")
    gate = go(here, "east", GATE_OFFSET)
    if tip_after((gate, "gate", "east")) != go(here, "east", SEG):
        bad.append("a gate does not advance the run by one segment")
    # tipAfter advances by a SEGMENT whatever is standing there, so the run stays in phase on its
    # own - but a gap's declared length is what the register credits and what the planner measures
    # to when you click at it, so it has to be a whole segment or those two are wrong by the
    # difference while the wall still looks right
    for kind in ("gap", "stair", "gate"):
        if LENGTH.get(kind) != SEG:
            bad.append(f"a {kind} is {LENGTH.get(kind)} long where a segment is {SEG} - "
                       f"it stands in a segment's slot, so it has to measure like one")
    print(f"  a segment, a stair, a gate and a gap all advance the run by one slot, "
          f"and all measure {SEG} long")

    # 3 - only right turns, and a corner needs something to measure from
    if corner_after(((0, 64, 0), "corner", "east")) is not None:
        bad.append("a corner offers a place for another corner, with no wall in between")
    if any(RIGHT[RIGHT[RIGHT[RIGHT[d]]]] != d for d in STEP):
        bad.append("four right turns do not come back to where they started")
    print("  turning is clockwise only, and a corner cannot follow a corner")

    print("")
    if bad:
        for line in bad:
            print(f"  !! {line}")
        print(f"  {len(bad)} problem(s)")
        return 1
    print("  a wall grown by hand lands exactly where the ring would have put it")
    return 0


def run():
    """For check_war.py, which runs every check in one go."""
    return main() == 0


if __name__ == "__main__":
    sys.exit(main())
