"""Does the wall actually join up? Every piece, every level, against every piece it can meet.

A curtain wall is not one blueprint, it is the same blueprint laid forty times, and the only
interesting question about it is what happens at the joints. A building can be judged on its own;
a wall piece cannot. So this file never looks at a piece alone - it lays copies of them end to end
the way a player would, and asks the four questions that can go wrong at a seam:

  no overlap      butt two pieces and not one block may land on a block of the other. An overlap
                  is not a cosmetic fault: Structurize would have the second builder tear down and
                  rebuild what the first one just built, and whichever of them ran last would win.
  no gap          run the game's OWN arithmetic across the joint. WallRegister.measure throws away
                  any column holding less than two blocks of wall, so "no gap" is not "the blocks
                  touch" - it is "every column along the run still counts as wall to the code that
                  scores it". That catches a hole the eye would forgive and the colony would not.
  no step         at every position along the run, the walk is a RAMPART with two clear cells over
                  it - which is word for word what BuildingWallTower.walkAbove demands before it
                  will lay a patrol point. One course out anywhere and a guard's patrol stops dead
                  at the seam.
  it can be       and then a citizen is actually walked from one end of the joined walk to the
  walked          other with walkcheck.reachable, because a wall that measures right and cannot be
                  walked is the failure this whole mod exists to avoid.

THE JOINTS THAT ARE TESTED. Segment to its own copy (and three in a row, so a phase error in the
merlons or the buttresses cannot hide inside one piece); segment to corner on both of the corner's
open ends; segment to gate on both of the gate's; gate to corner; stair between two segments and
against a corner; and - the one that matters most - SEGMENT TO THE WALL TOWER, because the tower
shipped first and the wall has to come to it, never the other way round.

Two pieces are also asked the question they exist for, which no joint can ask: a citizen is walked
THROUGH the gate, from the field to the colony, and UP the stair, from the inside ground to the
walk. Both use walkcheck.reachable, which cannot climb a ladder - so a stair that passes it is a
stair a MineColonies citizen will path up rather than a ladder in a trench coat.

The corner's second leg is tested by transposing a segment, (x, y, z) -> (z, y, x), with every
facing, axis and hinge re-pointed to match - which is what Structurize does when a player turns
the piece. A right-angle corner is symmetric about its own diagonal, so that mirror is exactly the
map that takes the east leg onto the south one. The re-pointing is not a detail: a wall banner
hangs on the block behind it, and the first version of this transform left one facing north on a
wall that had turned to run north-south, which the attachment check duly found.

legs_match() compares the corner's own two legs by position and block NAME rather than state,
because a stair that faces north on one leg has to face west on the other, and that difference is
the thing being relied on rather than a fault.

Run it on its own, or let check_war.py call run().
"""
import sys

import build_pack as bp
import build_war as bw
import floatcheck
import wallcorner
import wallgate
import wallinner
import wallsegment as W
import wallstair
import walltower
from pieces import F_PARAPET, F_RAMPART, PALISADE, PALISADE_PARAPET, PARAPET, RAMPART
from voxel import Structure, parse_state
from walkcheck import reachable, standable

# What WallRegister.pack/weightOf makes of each of our blocks: a rampart is worth a whole course,
# a parapet a third of one, and anything else is not wall at all however much it looks like it.
WEIGHT = {RAMPART: 1.0, F_RAMPART: 1.0, PALISADE: 1.0,
          PARAPET: 0.35, F_PARAPET: 0.35, PALISADE_PARAPET: 0.35}
TIER = {PALISADE: 1, PALISADE_PARAPET: 1, RAMPART: 2, PARAPET: 2, F_RAMPART: 3, F_PARAPET: 3}
RAMPARTS = (RAMPART, F_RAMPART, PALISADE)

# WallRegister.measure: "one course is a kerb, not a wall".
COUNTS = 2.0

# The levels that have a walk at the tower's height. Level 1 is a palisade on purpose - a young
# colony gets a wall, not a wall walk - so there is nothing to step off and nothing to check.
WALKS = (2, 3, 4, 5)


# ================================================================ small tools

# Under (x, z) -> (z, x) east becomes south and north becomes west, so anything that carries a
# direction has to be re-pointed or the transposed piece is not the piece Structurize would place.
# Getting this wrong is not cosmetic: a wall banner hangs on the block BEHIND it, so a banner left
# facing north on a wall that now runs north-south hangs on nothing - which is exactly what the
# attachment check caught the first time this transform was written without it.
SWAP_FACING = {"north": "west", "west": "north", "south": "east", "east": "south"}
SWAP_AXIS = {"x": "z", "z": "x", "y": "y"}
SWAP_HINGE = {"left": "right", "right": "left"}      # a mirror swaps a door's hand


def turn_state(block):
    name, props = parse_state(block)
    if not props:
        return block
    out = dict(props)
    if "facing" in out:
        out["facing"] = SWAP_FACING.get(out["facing"], out["facing"])
    if "axis" in out:
        out["axis"] = SWAP_AXIS.get(out["axis"], out["axis"])
    if "hinge" in out:
        out["hinge"] = SWAP_HINGE.get(out["hinge"], out["hinge"])
    for a, b in (("north", "west"), ("south", "east")):
        if a in out and b in out:                    # fences, panes, bars: swap the joined sides
            out[a], out[b] = out[b], out[a]
    return name + "[" + ",".join(f"{k}={v}" for k, v in out.items()) + "]"


def transposed(s):
    """(x, y, z) -> (z, y, x): the mirror about the diagonal, which is what takes a piece running
    east onto the same piece running south with its outer face still outwards - block states and
    all."""
    t = Structure(s.name + "'")
    for (x, y, z), b in s.blocks.items():
        t.set(z, y, x, turn_state(b))
    if s.anchor is not None:
        t.anchor = (s.anchor[2], s.anchor[1], s.anchor[0])
    return t


def laid(*placed):
    """Merge pieces at their offsets and report every position two of them both wrote.

    Deliberately not Structure.merge on its own: merge is happy to overwrite, and an overwrite at a
    seam is the bug this is looking for.
    """
    union = Structure("joint")
    clash = []
    for s, (dx, dy, dz) in placed:
        for (x, y, z), b in s.blocks.items():
            p = (x + dx, y + dy, z + dz)
            if p in union.blocks:
                clash.append((p, union.blocks[p].split("[")[0], b.split("[")[0]))
            union.set(*p, b)
    return union, clash


def columns(s):
    """Every (x, z) that holds wall, with the weight and the tier WallRegister would give it."""
    out = {}
    for (x, y, z), b in s.blocks.items():
        name = parse_state(b)[0]
        if name in WEIGHT:
            w, t = out.get((x, z), (0.0, 0))
            out[(x, z)] = (w + WEIGHT[name], max(t, TIER[name]))
    return out


def tallest(s, axis):
    """The tallest wall column at each position along the run - which is precisely what a bearing
    keeps in WallRegister.measure, so a wall that passes this passes the game's own test."""
    best = {}
    for (x, z), (w, _t) in columns(s).items():
        k = x if axis == "x" else z
        best[k] = max(best.get(k, 0.0), w)
    return best


def lane_cells(level, span, axis="x"):
    """Every cell of the walk along a straight run, in every lane the level has.

    From level 4 the walk is two wide, and the second lane is a rampart with two clear cells over
    it exactly like the first - so the tower would lay patrol points on it too, and a break in it
    is as real a break as one in the middle. wallsegment.lanes is the single place that says how
    wide the walk is; asking it here means the check cannot drift from the design.
    """
    cells = []
    for off in W.lanes(level):
        for k in span:
            cells.append((k, W.DECK_Y, off) if axis == "x" else (off, W.DECK_Y, k))
    return cells


def rampart_at(s, x, y, z):
    b = s.get(x, y, z)
    return b is not None and parse_state(b)[0] in RAMPARTS


# ================================================================ the four questions

def check_no_overlap(name, clash, out):
    if clash:
        out.append(f"!! {name}: {len(clash)} block(s) built twice at the joint, e.g. {clash[:3]}")
        return False
    return True


def check_ring(name, union, axis, span, out):
    """Every position along the run still counts as wall to WallRegister."""
    best = tallest(union, axis)
    thin = [(k, round(best.get(k, 0.0), 2)) for k in span if best.get(k, 0.0) < COUNTS]
    if thin:
        out.append(f"!! {name}: the ring is broken - {len(thin)} position(s) hold less wall than "
                   f"the game counts ({COUNTS}), e.g. {thin[:4]}")
        return False
    return True


def check_walk(name, union, cells, out):
    """A rampart underfoot and two clear cells over it, everywhere along the walk.

    This is BuildingWallTower.walkAbove, copied on purpose: pass this and every cell of the joined
    wall can carry a patrol point; fail it and the patrol stops where the check stops.
    """
    bad = []
    for x, y, z in cells:
        if not rampart_at(union, x, y, z):
            here = union.get(x, y, z)
            bad.append(((x, y, z), parse_state(here)[0] if here else "air"))
        elif union.get(x, y + 1, z) is not None or union.get(x, y + 2, z) is not None:
            bad.append(((x, y, z), "no head room"))
    if bad:
        out.append(f"!! {name}: the walk steps or stops at {len(bad)} place(s), e.g. {bad[:4]}")
        return False
    return True


def check_walkable(name, union, start, goal, out):
    if not standable(union, *start):
        out.append(f"!! {name}: nobody can stand on the walk at {start}")
        return False
    if not reachable(union, start, goal)[0]:
        out.append(f"!! {name}: a citizen cannot walk the joined wall from {start} to {goal}")
        return False
    return True


def check_sound(name, union, out):
    ok = True
    loose = floatcheck.floating(union)
    if loose:
        out.append(f"!! {name}: {len(loose)} block(s) float at the joint, e.g. {loose[:4]}")
        ok = False
    unattached = floatcheck.attachment(union)
    if unattached:
        out.append(f"!! {name}: {len(unattached)} block(s) have nothing to hang on, e.g. "
                   f"{unattached[:3]}")
        ok = False
    return ok


# ================================================================ the joints

def pad(union):
    """The untouched ground course under the joined pieces.

    build_war.pad_ground's rule applied to the union's own footprint: every cell of the bottom
    course the wall did not claim is "leave whatever is there", which is both what the blueprint
    ships and what gives a citizen ground to stand on beside the wall while he is being walked.
    """
    (x0, y0, z0), (x1, _y1, z1) = union.bounds()
    bw.pad_ground(union, ((x0, y0, z0), (x1, y0, z1)))


def joint(name, placed, walk, out, axis=None, span=None, ends=None):
    """One joint, put together and asked all four questions.

    The soundness and the ring are asked of the raw union, before any padding - pad first and
    every loose block looks connected to the ground.
    """
    union, clash = laid(*placed)
    ok = check_no_overlap(name, clash, out)
    ok = check_sound(name, union, out) and ok
    if span is not None:
        ok = check_ring(name, union, axis, span, out) and ok
    if walk:
        ok = check_walk(name, union, walk, out) and ok
        pad(union)
        a, b = ends or (walk[0], walk[-1])
        ok = check_walkable(name, union, (a[0], a[1] + 1, a[2]), (b[0], b[1] + 1, b[2]), out) and ok
    return ok


def straight(level, out):
    """A run: segment to segment, and three of them, so a phase error cannot hide in one piece."""
    ok = True
    seg = W.build(level)
    run = W.RUN

    pair = [(seg, (0, 0, 0)), (W.build(level), (run, 0, 0))]
    walk = lane_cells(level, range(0, 2 * run)) if level in WALKS else None
    ok = joint(f"segment|segment L{level}", pair, walk, out, "x", range(0, 2 * run)) and ok

    three = [(W.build(level), (i * run, 0, 0)) for i in range(3)]
    walk3 = lane_cells(level, range(0, 3 * run)) if level in WALKS else None
    ok = joint(f"segment x3 L{level}", three, walk3, out, "x", range(0, 3 * run)) and ok
    return ok


def corners(level, out):
    """Both open ends of the corner, each against a segment.

    The east leg takes a segment as it is drawn; the south leg takes one transposed, which is the
    same piece turned - see the module docstring.
    """
    ok = True
    deck, run = W.DECK_Y, W.RUN
    east = wallcorner.FAR + 1                      # where the next piece starts off the east leg
    # The run of the east leg starts at its outer face, x = -1. The column at x = -2 belongs to
    # the OTHER leg - it is that leg's batter - and holds no wall block by design, so counting it
    # as a position on this run would be asking the corner to be two walls at once.
    first = wallcorner.NEAR + 1

    east_pair = [(wallcorner.build(level), (0, 0, 0)), (W.build(level), (east, 0, 0))]
    walk = lane_cells(level, range(0, east + run)) if level in WALKS else None
    ok = joint(f"corner|segment east L{level}", east_pair, walk, out, "x",
               range(first, east + run)) and ok

    south_pair = [(wallcorner.build(level), (0, 0, 0)), (transposed(W.build(level)), (0, 0, east))]
    walk_s = lane_cells(level, range(0, east + run), "z") if level in WALKS else None
    ok = joint(f"corner|segment south L{level}", south_pair, walk_s, out, "z",
               range(first, east + run)) and ok

    # and round the angle itself: on at the far end of one leg, off at the far end of the other,
    # which is the only test that says the walk actually turns
    both = [(wallcorner.build(level), (0, 0, 0)), (W.build(level), (east, 0, 0)),
            (transposed(W.build(level)), (0, 0, east))]
    turn = None
    if level in WALKS:
        turn = ([(x, deck, 0) for x in range(east + run - 1, -1, -1)]
                + [(0, deck, z) for z in range(1, east + run)])
    ok = joint(f"corner turn L{level}", both, turn, out) and ok
    return ok


def gates(level, out):
    """The gate between two segments, and against a corner - and then walked THROUGH."""
    ok = True
    run = W.RUN
    three = [(W.build(level), (0, 0, 0)), (wallgate.build(level), (run, 0, 0)),
             (W.build(level), (2 * run, 0, 0))]
    walk = lane_cells(level, range(0, 3 * run)) if level in WALKS else None
    ok = joint(f"segment|gate|segment L{level}", three, walk, out, "x", range(0, 3 * run)) and ok

    east = wallcorner.FAR + 1
    first = wallcorner.NEAR + 1                    # see corners(): x = -2 is the other leg's batter
    pair = [(wallcorner.build(level), (0, 0, 0)), (wallgate.build(level), (east, 0, 0))]
    walk2 = lane_cells(level, range(0, east + run)) if level in WALKS else None
    ok = joint(f"corner|gate L{level}", pair, walk2, out, "x",
               range(first, east + run)) and ok

    # the road: outside the wall, through the doors, and into the colony
    road, _ = laid(*three)
    bw.pad_ground(road, ((0, 0, W.TALUS), (3 * run - 1, 0, W.BERM)))
    out_side = (run + 3, 1, W.TALUS)
    in_side = (run + 3, 1, W.BERM)
    if not reachable(road, out_side, in_side)[0]:
        out.append(f"!! gate L{level}: a citizen cannot path through the gateway "
                   f"{out_side} -> {in_side}")
        ok = False
    return ok


def stairs(level, out):
    """The stair standing in a run, and against a corner - and then CLIMBED.

    Three questions, and the third is the reason the piece exists. The first two are the ordinary
    joint questions, and the stair has to pass them exactly as a segment does: it is a segment with
    something added, so if the wall steps or thins where a stair stands, the something added has
    eaten into the wall and the patrol and the colony's own coverage go with it.

    The third is new. A citizen is put on the inside ground at the foot and walked up - with
    walkcheck.reachable, which deliberately cannot climb a ladder, so passing it means the stair is
    a stair. At level 1 there is no walk to reach and the goal is the top of the earth bank, which
    is the only thing a level 1 wall has to climb to and is where the docstring says the flight
    arrives.
    """
    ok = True
    run = W.RUN
    three = [(W.build(level), (0, 0, 0)), (wallstair.build(level), (run, 0, 0)),
             (W.build(level), (2 * run, 0, 0))]
    walk = lane_cells(level, range(0, 3 * run)) if level in WALKS else None
    ok = joint(f"segment|stair|segment L{level}", three, walk, out, "x", range(0, 3 * run)) and ok

    east = wallcorner.FAR + 1
    first = wallcorner.NEAR + 1                    # see corners(): x = -2 is the other leg's batter
    pair = [(wallcorner.build(level), (0, 0, 0)), (wallstair.build(level), (east, 0, 0))]
    walk2 = lane_cells(level, range(0, east + run)) if level in WALKS else None
    ok = joint(f"corner|stair L{level}", pair, walk2, out, "x", range(first, east + run)) and ok

    # up it: from the square at the foot of the flight, on the colony's side of the wall
    climb, _ = laid(*three)
    pad(climb)
    foot = (run + wallstair.FOOT, 1, W.BERM)
    if not standable(climb, *foot):
        out.append(f"!! stair L{level}: there is nowhere to stand at the foot of the flight {foot}")
        return False
    hx, hy = wallstair.head(level)
    goal = (run + hx, hy, W.MID) if level in WALKS else (run + hx, hy + 1, W.MID)
    if not reachable(climb, foot, goal)[0]:
        out.append(f"!! stair L{level}: a citizen cannot climb from {foot} to {goal}")
        ok = False
    if level in WALKS:
        # and then all the way along, so the stair really lands ON the wall and not beside it
        far = (3 * run - 1, W.DECK_Y + 1, W.MID)
        if not reachable(climb, foot, far)[0]:
            out.append(f"!! stair L{level}: the climb reaches the walk but not along it, {far}")
            ok = False
    return ok


def tower(level, out):
    """The joint the whole design is built round: a run of wall into the Wall Tower's stub.

    The tower lays rampart out to x = +-7 with its walk open at z = 0, so a segment starts at
    x = +8 and the walk has to carry straight on from x = 7 to x = 8 with no step. Checked with the
    tower at the same level as the wall, which is how a colony would actually have built them.
    """
    if level not in WALKS:
        return True
    ok = True
    deck, run = W.DECK_Y, W.RUN
    stub = walltower.STUB[1]
    placed = [(bp.aligned(walltower.build, level), (0, 0, 0)),
              (W.build(level), (stub + 1, 0, 0)),
              (W.build(level), (stub + 1 + run, 0, 0))]
    union, clash = laid(*placed)
    name = f"tower|segment L{level}"
    ok = check_no_overlap(name, clash, out) and ok
    # the walk from the far end of the tower's west stub to the far end of the second segment
    walk = [(x, deck, 0) for x in range(-stub, stub + 1 + 2 * run)]
    ok = check_walk(name, union, walk, out) and ok
    bw.pad_ground(union, ((-stub, 0, -6), (stub + 2 * run, 0, 6)))
    ok = check_walkable(name, union, (-stub, deck + 1, 0),
                        (stub + 2 * run, deck + 1, 0), out) and ok
    return ok


def stretch(level):
    """A corner, some curtain, a gate and a stair - the wall as a colony would lay it, including
    the one piece that lets anybody get onto it.

    Nothing checks this; it is here because the pictures in docs/renders should show what the
    pieces are FOR, and one run of them says more about whether they tile than any single piece
    can. It uses the same `laid` that the checks do, so a picture can never show a joint the
    checks have not already agreed to.
    """
    run, east = W.RUN, wallcorner.FAR + 1
    union, _clash = laid(
        (wallcorner.build(level), (0, 0, 0)),
        (W.build(level), (east, 0, 0)),
        (wallgate.build(level), (east + run, 0, 0)),
        (wallstair.build(level), (east + 2 * run, 0, 0)),
        (transposed(W.build(level)), (0, 0, east)))
    union.name = f"wall-run{level}"
    union.anchor = (0, 1, 0)
    return union


def legs_match(out):
    """The corner's two legs are the same wall, block for block.

    Cheap to say and easy to get wrong: the whole corner is generated from min(x, z) and max(x, z),
    both of which are symmetric, so if this ever fails something has been special-cased onto one
    leg and the other end of the piece no longer joins what this file says it joins.
    """
    ok = True
    for level in range(1, 6):
        s = wallcorner.build(level)
        t = transposed(s)
        here = {p: parse_state(b)[0] for p, b in s.blocks.items()}
        there = {p: parse_state(b)[0] for p, b in t.blocks.items()}
        if here != there:
            odd = sorted(set(here) ^ set(there))
            diff = [p for p in set(here) & set(there) if here[p] != there[p]]
            out.append(f"!! wallcorner{level}: the two legs are not the same wall - "
                       f"{len(odd)} cell(s) on one leg only, {len(diff)} different, "
                       f"e.g. {(odd + diff)[:4]}")
            ok = False
    return ok


def inner_matches_section(out):
    """The inner corner really is the same wall, cell by cell.

    The convex corner gets `legs_match` because min and max are symmetric and a special case on
    one leg would show up as an asymmetry. The concave one needs the stronger statement, because
    its whole claim is that ONE rule - off = -min(u, v), n = max(u, v) - reproduces the section
    everywhere, and the first draft of the design note had that rule as -max. Under -max
    seventeen of the twenty cells of an arm come out on the wrong line of the section and the
    piece still builds, still looks like a wall, and joins nothing. So every cell is asked.

    The fittings are exempt: a banner and a lamp are the two things this piece adds on purpose,
    and they are held to their own rule - they must be a mirror pair across the diagonal, since
    the two arms are the same wall.
    """
    ok = True
    fitting = ("wall_banner", "lantern", "decorationcontroller")
    for level in range(1, 6):
        s = wallinner.build(level)
        wrong, stray = [], []
        for u in range(wallinner.NEAR, wallinner.FAR + 1):
            for v in range(wallinner.NEAR, wallinner.FAR + 1):
                off, n = -min(u, v), max(u, v)
                x, z = wallinner.place(u, v)
                if off < W.TALUS:
                    # the far corner of the notch is not a line of the section; nothing may be there
                    stray += [(x, y, z) for y in range(0, W.HEAD_Y + 1) if (x, y, z) in s.blocks]
                    continue
                ins, outs = wallinner.leg(u, v)
                for y in range(1, W.HEAD_Y + 1):
                    want = W.section(n, off, y, level, ins, outs, quoin=(u == v), bays=False)
                    got = s.blocks.get((x, y, z))
                    if want != got and not (got and any(f in got for f in fitting)):
                        wrong.append((u, v, y))
        if wrong:
            out.append(f"!! wallinner{level}: {len(wrong)} cell(s) are not what the section lays "
                       f"there, e.g. {wrong[:4]} - is the rule still -min(u, v)?")
            ok = False
        if stray:
            out.append(f"!! wallinner{level}: {len(stray)} block(s) outside the section, at {stray[:4]}")
            ok = False

        # the walk turns the other way and still has two clear cells over it
        walk = [(u, v) for u in range(wallinner.NEAR, wallinner.FAR + 1)
                for v in range(wallinner.NEAR, wallinner.FAR + 1) if -min(u, v) == W.MID]
        if len(walk) != 7:
            out.append(f"!! wallinner{level}: the walk is {len(walk)} cells, not the 7 of an L")
            ok = False
        for u, v in walk:
            x, z = wallinner.place(u, v)
            if not any((u + du, v + dv) in walk for du, dv in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.append(f"!! wallinner{level}: the walk cell (u={u}, v={v}) is stranded")
                ok = False
            if level in WALKS and any(s.blocks.get((x, y, z)) is not None for y in W.WALK):
                out.append(f"!! wallinner{level}: the walk at (u={u}, v={v}) is blocked")
                ok = False

        # the fittings, mirrored
        fit = sorted(wallinner.uv(x, z) + (y,) for (x, y, z), b in s.blocks.items()
                     if b and ("wall_banner" in b or "lantern" in b))
        if level >= 4 and not all((v, u, y) in fit for u, v, y in fit):
            out.append(f"!! wallinner{level}: the fittings are not a mirror pair - {fit}")
            ok = False

        ax, ay, az = wallinner.ANCHOR
        if "decorationcontroller" not in (s.blocks.get((ax, ay, az)) or ""):
            out.append(f"!! wallinner{level}: the anchor at {wallinner.ANCHOR} is not free")
            ok = False
    return ok


def section_matches_tower(out):
    """The section is the tower's, not a copy of it that has drifted."""
    ok = True
    if W.DECK_Y != walltower.DECK_Y:
        out.append(f"!! the wall's deck is y={W.DECK_Y}, the tower's is y={walltower.DECK_Y}")
        ok = False
    if W.WALK != walltower.WALK:
        out.append(f"!! the wall's walk cells are {W.WALK}, the tower's are {walltower.WALK}")
        ok = False
    # and the stub really is three wide with the walk in the middle of it, or the section is
    # matching something that is no longer there
    t = walltower.build(5)
    stub = walltower.STUB[1]
    want = {W.OUT: "parapet at the rail", W.MID: "open, it is the walk", W.IN: "parapet at the rail"}
    for z in want:
        if t.get(stub, W.DECK_Y, z) is None:
            out.append(f"!! the tower's stub has no deck at z={z} - the wall's section is wrong")
            ok = False
    if t.get(stub, W.WALK[0], W.MID) is not None or t.get(stub, W.WALK[1], W.MID) is not None:
        out.append("!! the tower's stub no longer leaves its walk open at z=0")
        ok = False
    return ok


# ================================================================ the report

def run(verbose=True):
    """Every joint at every level. True if the wall joins up."""
    out = []
    ok = section_matches_tower(out)
    ok = legs_match(out) and ok
    ok = inner_matches_section(out) and ok
    for level in range(1, 6):
        before = len(out)
        good = straight(level, out)
        good = corners(level, out) and good
        good = gates(level, out) and good
        good = stairs(level, out) and good
        good = tower(level, out) and good
        ok = good and ok
        if verbose:
            walk = "walk y=%d" % (W.DECK_Y + 1) if level in WALKS else "no walk yet"
            print(f"joints L{level}  segment|segment, x3, corner east+south, the turn, "
                  f"segment|gate|segment, corner|gate, the road, "
                  f"segment|stair|segment, corner|stair, the climb, tower|segment  "
                  f"{walk}  {'ok' if len(out) == before else 'FAILED'}")
    for line in out:
        print(line)
    if verbose:
        print("the wall joins up" if ok else "!! THE WALL DOES NOT JOIN UP")
    return ok


if __name__ == "__main__":
    sys.exit(0 if run() else 1)
