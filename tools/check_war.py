"""Validate every design before anything is written.

The Colonist Thieves checker, with the questions this pack adds. For every level of the two
buildings and the four wall pieces:

  1  does every block id exist - in vanilla, in the three mods this pack may use, or in OUR OWN
     jar, which is where the rampart and parapet ids are read from
  2  does anything float, or hang off nothing
  3  does it fit the shared box, and do all five levels share one anchor
  4  is there somewhere to stand next to the hut block
  5  is every bunk a whole bed - both halves, facing the same way, not built over by a rack -
     and are there as many of them as the building hires men - garrison 2/4/6/9/12 for the War Room,
     (level+1)/2 + level/2+1 for the tower - and, where the building actually carries the BED
     module, can a citizen walk to every one of them
  6  and for the Wall Tower only: DOES THE WALL GO THROUGH IT. The deck has to be rampart all
     the way across at walk height with two clear cells over it, from the last block of stub on
     one side to the last on the other, and a guard has to be able to walk out onto it from the
     hut block. Otherwise BuildingWallTower.layPatrol has nothing to lay a patrol point on, a
     player's wall runs into a blank face, and the one thing this building is for does not work.

Then it hands the four wall pieces to check_walls.py, which asks the only question that matters
about a wall piece and cannot be asked of a piece on its own: does it join up. A segment that
passes every question above and does not tile is a wall with a seam every eight blocks, and
nothing here would have noticed.

The wall pieces are decorations, so questions 4 and 5 mean something different for them and
nothing was special-cased to make them pass: a decoration hires nobody, so it owes no bunks and
the count is zero against zero - but it still has to have somewhere a citizen can stand next to
its controller, or a player cannot reach the block that upgrades it.
"""
import sys

import build_pack as bp
import build_war as bw
import buildings
import floatcheck
import pieces
import voxel
import walltower
from walkcheck import climb_reachable, reachable


BED_FACING = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}


def beds_intact(s):
    """Every bed the design claims has to be a whole bed that is still there.

    A tag costs nothing and survives anything; a bed does not. Drop a rack on a bed's foot square
    at the wrong moment in the build order and you are left with a tag, half a bed, and a bunk
    MineColonies will never register - and every count in this checker still adds up. So the beds
    are counted from the blocks, not from the tags.
    """
    bad = []
    for pos, tags in s.tags.items():
        if "bed" not in tags:
            continue
        here = s.get(*pos)
        name, props = voxel.parse_state(here) if here else (None, {})
        if here is None or not name.endswith("_bed"):
            bad.append((pos, "tagged bed, no bed there"))
            continue
        if props.get("part") != "foot":
            bad.append((pos, "the tag is not on the foot"))
            continue
        dx, dz = BED_FACING[props["facing"]]
        head = (pos[0] + dx, pos[1], pos[2] + dz)
        hb = s.get(*head)
        hname, hprops = voxel.parse_state(hb) if hb else (None, {})
        if (hb is None or not hname.endswith("_bed") or hprops.get("part") != "head"
                or hprops.get("facing") != props["facing"]):
            bad.append((head, "no matching head"))
    return bad


def wall_carries_through(s, start):
    """Walk the tower's own wall line, block by block, the way BuildingWallTower.walkAbove does."""
    y, z = walltower.DECK_Y, 0
    ramparts = (pieces.RAMPART, pieces.F_RAMPART)
    broken = []
    points = 0
    for x in range(-walltower.STUB[1], walltower.STUB[1] + 1):
        here = s.get(x, y, z)
        name = voxel.parse_state(here)[0] if here else None
        if name not in ramparts:
            broken.append((x, name or "air"))
        elif s.get(x, y + 1, z) is not None or s.get(x, y + 2, z) is not None:
            broken.append((x, "no head room"))
        else:
            points += 1
    if broken:
        return False, f"the walk is broken at {broken[:3]}"
    ends = [(-walltower.STUB[1], y + 1, z), (walltower.STUB[1], y + 1, z)]
    if start is not None:
        got = [e for e in ends if reachable(s, start, e)[0]]
        if len(got) < 2:
            return False, f"only {len(got)} of the two wall ends can be walked to from {start}"
    return True, f"walk {points} cells, both ends reachable"


known = bp.known_blocks()
ok = True

for name, spec in buildings.SPEC.items():
    mod = spec["module"]
    (BX0, BY0, BZ0), (BX1, BY1, BZ1) = mod.BOX
    anchors = set()
    print(f"--- {name}   shared box {mod.BOX}")
    for level in range(1, 6):
        s = bp.aligned(mod.build, level)
        anchors.add(s.anchor)

        missing = bp.unknown_blocks(s, known)
        if missing:
            print(f"!! {s.name}: blocks that do not exist:", missing)
            ok = False

        loose = floatcheck.floating(s)
        if loose:
            comps = floatcheck.components(s, loose)
            print(f"!! {s.name}: {len(loose)} floating block(s) in {len(comps)} group(s), e.g.", loose[:4])
            ok = False

        # A block can be connected and still pop off on placement: a ladder with no wall behind
        # it, a lantern hung from air, a lectern on nothing.
        unattached = floatcheck.attachment(s)
        if unattached:
            print(f"!! {s.name}: {len(unattached)} block(s) with nothing to attach to, e.g.", unattached[:4])
            ok = False

        bare = sorted(pos for pos, b in s.blocks.items()
                      if voxel.parse_state(b)[0] in voxel.DO_SLOTS and pos not in s.materials)
        if bare:
            print(f"!! {s.name}: {len(bare)} Domum Ornamentum block(s) with no material", bare[:4])
            ok = False

        (x0, y0, z0), (x1, y1, z1) = s.bounds()
        fits = BX0 <= x0 and x1 <= BX1 and BY0 <= y0 and y1 <= BY1 and BZ0 <= z0 and z1 <= BZ1
        if not fits:
            print(f"!! {s.name}: x[{x0},{x1}] y[{y0},{y1}] z[{z0},{z1}] is outside the box {mod.BOX}")
            ok = False
        size = s.size()

        bw.pad_ground(s, mod.BOX)
        start = bw.stand_by_hut(s)
        if start is None:
            print(f"!! {s.name}: nowhere to stand next to the hut block {s.anchor}")
            ok = False
        walked, beds = bw.walkable_beds(s, start)
        broken = beds_intact(s)
        if broken:
            print(f"!! {s.name}: {len(broken)} bunk(s) are not whole beds:", broken[:3])
            ok = False
        if len(beds) != spec["crew"](level):
            print(f"!! {s.name}: {len(beds)} bunks for a crew of {spec['crew'](level)}")
            ok = False
        if spec["beds_required"] and walked < len(beds):
            print(f"!! {s.name}: only {walked} of {len(beds)} beds can be walked to from {start}")
            ok = False

        note = ""
        if name == "walltower":
            good, why = wall_carries_through(s, start)
            note = "  " + why
            if not good:
                print(f"!! {s.name}: {why}")
                ok = False
            # every floor the design builds has to be a floor a guard can get to - on foot if
            # the level has paid for a stair, up a ladder if it has not
            # a corner square of each floor, never the middle - the middle is where the fire is
            floors = [("deck", (3, walltower.DECK_Y + 1, 3), 1),
                      ("chamber", (3, walltower.CHAMBER[0], 3), 3),
                      ("top", (3, walltower.TOP_Y + 1, 3), 4)]
            for what, cell, from_level in floors:
                if level < from_level or start is None:
                    continue
                if not climb_reachable(s, start, cell)[0]:
                    print(f"!! {s.name}: the {what} at {cell} cannot be reached from {start}")
                    ok = False
                else:
                    note += f", {what} by {'stair' if reachable(s, start, cell)[0] else 'ladder'}"

        print(f"{s.name:12s} {size[0]:2d}x{size[1]:2d}x{size[2]:2d}  {len(s.blocks):5d} blocks  "
              f"{len(s.entities):2d} entities  {len(beds):2d} bunks ({walked} walkable)  "
              f"anchor {s.anchor}  y[{y0},{y1}]  {'fits' if fits else 'OUTSIDE THE BOX'}{note}")
    if len(anchors) != 1:
        print(f"!! {name}: the five levels do not share one anchor:", sorted(anchors))
        ok = False

# and the question a wall piece cannot be asked on its own
print("--- the joints")
import check_walls                                   # noqa: E402 - after the per-piece checks
ok = check_walls.run() and ok

# and then the planner's own arithmetic: perfect pieces laid one block out are still a broken wall
import check_ring                                    # noqa: E402
ok = check_ring.run() and ok

import check_grow                                    # noqa: E402 - the planner's other way to draw
ok = check_grow.run() and ok

import check_inner                                   # noqa: E402 - and the turn it could not make
ok = check_inner.main() == 0 and ok

import check_views                                   # noqa: E402 - server class vs client view
ok = check_views.run() and ok

print("OK" if ok else "PROBLEMS ABOVE")
sys.exit(0 if ok else 1)
