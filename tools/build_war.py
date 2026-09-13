"""Generate everything Colonies at War ships into the Structurize pack.

    resources/blueprints/colonies_at_war/colonies_at_war/walltower/walltower1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/warroom/warroom1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallsegment/wallsegment1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallcorner/wallcorner1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallgate/wallgate1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallstair/wallstair1-5.blueprint

Same shape as the Colonist Thieves build script: check the design, fill the untouched ground,
write the file, read it back and compare. Run check_war.py first - this writes the files, it does
not judge them, and a blueprint that is wrong is wrong in somebody's world, quietly.

The two buildings and the four wall pieces go through exactly the same code; what differs is one
entry in buildings.SPEC, because a decoration is a building with no hut block, no crew and no
beds, and there is no reason for it to be a second script.
"""
import os
import sys

import build_pack as bp
import buildings
import voxel
from floatcheck import attachment, floating
from walkcheck import reachable, standable

REQUIRED_MODS = ("structurize", "minecolonies", "colonies_at_war")


def pad_ground(s, box):
    """The ground course inside the shared footprint that the design does not use is
    structurize:blocksubstitution - "leave whatever is there".

    Both of these buildings stand on the ground, so this is one course, not a cellar's worth: the
    builder clears everything above it (trees, hillside) and does no landscaping under it, which
    is the rule the Voyager tools set and the reason a survival build does not crawl.
    """
    (bx0, by0, bz0), (bx1, _by1, bz1) = box
    for x in range(bx0, bx1 + 1):
        for z in range(bz0, bz1 + 1):
            if (x, by0, z) not in s.blocks:
                s.set(x, by0, z, bp.KEEP)


def stand_by_hut(s):
    """Somewhere for a citizen to stand next to the hut block - that is where MineColonies puts a
    man when it wants him at his building."""
    x, y, z = s.anchor
    for dx, dz in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        if standable(s, x + dx, y, z + dz):
            return (x + dx, y, z + dz)
    return None


def walkable_beds(s, start):
    beds = sorted(p for p, tags in s.tags.items() if "bed" in tags)
    if start is None:
        return 0, beds
    return sum(1 for b in beds if reachable(s, start, b)[0]), beds


def one(name, level, known):
    spec = buildings.SPEC[name]
    mod = spec["module"]
    s = bp.aligned(mod.build, level)
    ok = True

    # --- the design itself, before any padding: padding would make everything look connected
    missing = bp.unknown_blocks(s, known)
    if missing:
        print("!! blocks that do not exist:", s.name, missing)
        ok = False
    loose = floating(s)
    if loose:
        print("!! floating:", s.name, loose[:4])
        ok = False
    unattached = attachment(s)
    if unattached:
        print("!! nothing to attach to:", s.name, unattached[:3])
        ok = False

    # --- then the ground under it, and the walking
    pad_ground(s, mod.BOX)
    start = stand_by_hut(s)
    if start is None:
        print("!! nowhere to stand next to the hut block on", s.name)
        ok = False
    walked, beds = walkable_beds(s, start)
    if len(beds) != spec["crew"](level):
        print(f"!! {s.name}: {len(beds)} bunks for a crew of {spec['crew'](level)}")
        ok = False
    if spec["beds_required"] and walked < len(beds):
        print(f"!! {s.name}: only {walked} of {len(beds)} beds can be walked to from {start}")
        ok = False

    file_name = f"{s.name}.blueprint"
    path = os.path.join(bp.PACK_DIR, name, file_name)
    s.to_blueprint(path, file_name, bp.PACK_NAME, f"{name}/{file_name}", spec["building"],
                   required_mods=REQUIRED_MODS, be_type=spec["be_type"], box=mod.BOX)

    # read it back: the palette packing is the one place a silent mistake would survive everything
    back = voxel.load_blueprint(path)
    (x0, y0, z0), _ = mod.BOX
    norm = {(px - x0, py - y0, pz - z0): voxel.parse_state(b) for (px, py, pz), b in s.blocks.items()}
    same = norm == {q: voxel.parse_state(b) for q, b in back.blocks.items()}
    anchor_ok = back.anchor == (s.anchor[0] - x0, s.anchor[1] - y0, s.anchor[2] - z0)
    wall = sum(1 for b in s.blocks.values() if b.startswith("colonies_at_war:"))
    print(f"{file_name:22s} anchor {s.anchor!s:13s} {len(s.blocks):5d} blocks {wall:3d} wall "
          f"{len(s.entities):2d} entities {len(beds):2d} bunks({walked} walkable) "
          f"{os.path.getsize(path):6d} B roundtrip={'ok' if same else 'MISMATCH'} "
          f"anchor={'ok' if anchor_ok else 'BAD'} stand {start}")
    return ok and same and anchor_ok


def main():
    print("wrote", bp.write_pack_json())
    known = bp.known_blocks()
    ok = True
    for name in buildings.SPEC:
        os.makedirs(os.path.join(bp.PACK_DIR, name), exist_ok=True)
        for level in range(1, 6):
            ok = one(name, level, known) and ok
    print("ALL OK" if ok else "PROBLEMS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
