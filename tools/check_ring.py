"""Does the ring the Wall Planner lays actually join up?

check_walls.py proves the pieces fit each other. This proves the JAVA ARITHMETIC that decides
where to put them - WallLayout's constants - by laying a whole ring exactly the way the game will,
with a gate in one side and a stair in another, and then asking check_walls' own questions of the
result.

It is a separate file because it tests a different thing. check_walls tests the blueprints; this
tests the planner. A wall can be made of perfect pieces and still be laid one block out, and that
mistake is invisible in a render and obvious in a world.

HOW THE GAME PLACES A PIECE, and therefore how this does:

  Structurize puts the blueprint's ANCHOR on the position it is given and turns the rest of the
  piece around it. So a cell at design position p, in a piece whose design anchor is a, placed at
  world position P with rotation R, ends up at  P + R(p - a).

  R is a plain rotation about the vertical axis. Clockwise from above takes (x, z) to (-z, x),
  which is what the four sides of a ring need: the pieces are drawn with the OUTSIDE on the left
  of the run, so laying the ring clockwise from its north-west corner needs no mirroring anywhere.

Block states are NOT rotated here, and that is on purpose: a rotated stair still occupies the same
cell, WallRegister weighs blocks by name and BuildingWallTower.walkAbove asks only whether a cell
is a rampart with two clear cells over it. Every question this file asks is about occupancy, so
turning the facings would add a large pile of code that could not change an answer.
"""
import sys

import wallcorner
import wallgate
import wallsegment as W
import wallstair
import walltower
from check_walls import COUNTS, RAMPARTS, WEIGHT
from voxel import Structure, parse_state

# --- WallLayout's constants. If these and the Java ever disagree, one of them is a bug. ---
AFTER_CORNER = 2                 # a segment leaving a corner starts this far along the new run
BEFORE_CORNER = 9                # a corner closing a run sits this far past the last segment
GATE_OFFSET = 6                  # a gate standing where a segment would, anchored at its far end
# The stair needs no constant of its own, and that is the point of it: it is drawn as a segment
# with a flight added, its anchor is a segment's anchor, so it goes down on the stride the planner
# was already stepping. Nought is written out rather than left implicit so that the day somebody
# moves the stair's anchor, this line and the Java disagree loudly instead of the wall being laid
# a few blocks out.
STAIR_OFFSET = 0
RUN = W.RUN                      # 8

# east, south, west, north - the order the planner walks a ring
CLOCKWISE = [(1, 0), (0, 1), (-1, 0), (0, -1)]


def rot(off, turns):
    """(x, y, z) turned `turns` quarter-turns clockwise about the vertical axis."""
    x, y, z = off
    for _ in range(turns % 4):
        x, z = -z, x
    return (x, y, z)


def where(structure, world, turns, cell):
    """Where one design cell of a piece ends up, placed the way Structurize places it."""
    ax, ay, az = structure.anchor
    dx, dy, dz = rot((cell[0] - ax, cell[1] - ay, cell[2] - az), turns)
    return (world[0] + dx, world[1] + dy, world[2] + dz)


def place(structure, world, turns):
    """Every cell of a piece, in world coordinates, placed the way Structurize places it."""
    out = {}
    for cell, block in structure.blocks.items():
        out[where(structure, world, turns, cell)] = block
    return out


def step(pos, side, n):
    """n blocks along one side of the ring."""
    dx, dz = CLOCKWISE[side]
    return (pos[0] + dx * n, pos[1], pos[2] + dz * n)


def ring(level, k, m, gate_side=0, stair_side=1, origin=(0, 0, 0)):
    """The pieces a ring of k by m segments puts down, exactly as WallLayout.ring does.

    One gate and one stair, on different sides, because that is the least a real ring has: a way
    through it and a way onto it. Both stand where a segment would have stood - the gate anchored
    GATE_OFFSET along its stride and the stair STAIR_OFFSET, which is nought.
    """
    side_x = k * RUN + 3
    side_z = m * RUN + 3
    corners = [
        origin,
        (origin[0] + side_x, origin[1], origin[2]),
        (origin[0] + side_x, origin[1], origin[2] + side_z),
        (origin[0], origin[1], origin[2] + side_z),
    ]
    placed = []
    for side in range(4):
        corner = wallcorner.build(level)
        placed.append(("corner", place(corner, corners[side], side), corner, corners[side], side))
        count = k if side % 2 == 0 else m
        at = step(corners[side], side, AFTER_CORNER)
        gate_at = count // 2 if side == gate_side else -1
        stair_at = count // 2 if side == stair_side else -1
        for i in range(count):
            if i == gate_at:
                gate_pos = step(at, side, GATE_OFFSET)
                gate = wallgate.build(level)
                placed.append(("gate", place(gate, gate_pos, side), gate, gate_pos, side))
            elif i == stair_at:
                stair_pos = step(at, side, STAIR_OFFSET)
                stair = wallstair.build(level)
                placed.append(("stair", place(stair, stair_pos, side), stair, stair_pos, side))
            else:
                seg = W.build(level)
                placed.append(("segment", place(seg, at, side), seg, at, side))
            at = step(at, side, RUN)
        # and the corner that closes this side is the next one: prove the arithmetic agrees
        last = step(at, side, -RUN)
        expected = step(last, side, BEFORE_CORNER)
        got = corners[(side + 1) % 4]
        if expected != got:
            raise AssertionError(f"side {side}: the run ends at {expected}, the corner is at {got}")
    return placed


def union(placed):
    """Merge the pieces and report every cell two of them both wrote."""
    out = {}
    clash = []
    for name, cells, *_rest in placed:
        for p, b in cells.items():
            if p in out:
                clash.append((p, name))
            out[p] = b
    return out, clash


def columns(cells):
    """Every (x, z) that holds wall, with the weight WallRegister would give the column."""
    out = {}
    for (x, y, z), b in cells.items():
        name = parse_state(b)[0]
        if name in WEIGHT:
            out[(x, z)] = out.get((x, z), 0.0) + WEIGHT[name]
    return out


def climbable(level, placed, out):
    """Can anybody actually get onto this ring? Walk a citizen up the stair it was given.

    Worth asking here as well as in check_walls, because the stair in a ring is one the planner has
    TURNED. check_walls climbs a stair standing the way it was drawn; this one climbs a stair the
    planner rotated onto a side, so the treads have to land in the right cells relative to the
    piece's own anchor. Get AFTER_CORNER or the anchor wrong and the flight climbs into the wall
    instead of up it, and nothing else in this file would notice.
    """
    from check_walls import pad
    from voxel import Structure
    from walkcheck import reachable, standable

    mine = [p for p in placed if p[0] == "stair"]
    if not mine:
        out.append(f"!! L{level}: the ring has no way onto it - no stair was laid")
        return False
    _name, _cells, structure, pos, turns = mine[0]
    world = Structure("ring")
    for _n, cells, *_r in placed:
        for at, block in cells.items():
            world.set(*at, block)
    pad(world)                                     # the ground the ring stands on and is walked on

    foot = where(structure, pos, turns, (wallstair.FOOT, 1, W.BERM))
    hx, hy = wallstair.head(level)
    # at level 1 the flight arrives on the back of the bank and the bank top is one course above it
    goal = where(structure, pos, turns, (hx, hy if level >= 2 else hy + 1, W.MID))
    if not standable(world, *foot):
        out.append(f"!! L{level}: nowhere to stand at the foot of the ring's stair, {foot}")
        return False
    if not reachable(world, foot, goal)[0]:
        out.append(f"!! L{level}: a citizen cannot climb the ring's stair, {foot} -> {goal}")
        return False
    return True


def check(level, k=3, m=2, out=None):
    out = [] if out is None else out
    placed = ring(level, k, m)
    cells, clash = union(placed)
    ok = True

    if clash:
        out.append(f"!! L{level}: {len(clash)} cell(s) written twice, first at {clash[0]}")
        ok = False

    # no gap: walk the outside of the ring and demand a wall column at every step
    weights = columns(cells)
    side_x, side_z = k * RUN + 3, m * RUN + 3
    perimeter = []
    for x in range(-1, side_x + 2):
        perimeter.append((x, -4))                          # the north face
        perimeter.append((x, side_z + 1))                  # the south face
    for z in range(-1, side_z + 2):
        perimeter.append((-4, z))
        perimeter.append((side_x + 4, z))
    thin = 0
    for x, z in perimeter:
        best = max((weights.get((x + dx, z + dz), 0.0)
                    for dx in (-1, 0, 1) for dz in (-1, 0, 1)), default=0.0)
        if best < COUNTS:
            thin += 1
    if thin:
        out.append(f"!! L{level}: {thin} place(s) round the ring hold less than {COUNTS} of wall")
        ok = False

    # no step: the WALK LANE - design z = 0, the middle of the wall's thickness - must be a
    # rampart with two clear cells over it at every position, in every piece, all the way round.
    # Looking at every rampart at deck height would be wrong: the body of the wall is rampart at
    # that height too, and it is meant to be built on.
    if level >= 2:
        deck = W.DECK_Y
        broken = []
        for name, _cells, structure, pos, turns in placed:
            run_len = {"segment": W.RUN, "gate": W.RUN, "stair": W.RUN, "corner": None}[name]
            lane = []
            if run_len is not None:
                lane = [(x, deck, 0) for x in range(0, run_len)]
            else:
                # the corner's walk is the L of its two legs at design z = 0 and x = 0. It starts
                # at NEAR + 1: the column at NEAR belongs to the OTHER leg - it is that leg's
                # batter line and holds no wall by design, which check_walls says in as many words.
                lane = [(x, deck, 0) for x in range(wallcorner.NEAR + 1, wallcorner.FAR + 1)]
                lane += [(0, deck, z) for z in range(wallcorner.NEAR + 1, wallcorner.FAR + 1)]
            for cell in lane:
                p = where(structure, pos, turns, cell)
                block = cells.get(p)
                if block is None or parse_state(block)[0] not in RAMPARTS:
                    broken.append((name, p, "no rampart"))
                elif (p[0], p[1] + 1, p[2]) in cells or (p[0], p[1] + 2, p[2]) in cells:
                    broken.append((name, p, "built over"))
        # Some blocked cells are the design rather than a fault: a corner carries a lamp post and
        # from level 4 a bartizan, a gate is roofed between its turrets, and level 5 covers a bay
        # in every segment. What this is really looking for is a MISSING lane - a piece laid one
        # block out loses its whole lane at once, which no amount of ornament can explain.
        missing = [b for b in broken if b[2] == "no rampart"]
        allowed_over = 8 if level < 4 else 40
        if missing:
            out.append(f"!! L{level}: {len(missing)} walk cell(s) have no rampart, first {missing[0]}")
            ok = False
        elif len(broken) > allowed_over:
            out.append(f"!! L{level}: {len(broken)} walk cell(s) built over, first {broken[0]}")
            ok = False
        allowed = allowed_over
    ok = climbable(level, placed, out) and ok
    return ok, len(placed), len(cells)


def run(verbose=True):
    ok = True
    for level in range(1, 6):
        out = []
        good, pieces, cells = check(level, out=out)
        ok = good and ok
        if verbose:
            print(f"ring L{level}  3x2 segments, 4 corners, 1 gate, 1 stair  "
                  f"{pieces} pieces, {cells} blocks  {'ok' if good else 'FAILED'}")
        for line in out:
            print(line)
    # and the shapes the planner will actually be asked for
    for k, m in ((1, 1), (2, 5), (6, 6), (12, 1)):
        try:
            ring(5, k, m)
        except AssertionError as bad:
            print(f"!! {k}x{m}: {bad}")
            ok = False
    if verbose:
        print("the ring closes" if ok else "!! THE RING DOES NOT CLOSE")
    return ok


if __name__ == "__main__":
    sys.exit(0 if run() else 1)
