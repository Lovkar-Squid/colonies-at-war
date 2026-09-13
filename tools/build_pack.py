"""The Structurize pack shipped inside the Colonies at War jar, and the helpers the generators
here share.

    resources/blueprints/colonies_at_war/colonies_at_war/pack.json
    resources/blueprints/colonies_at_war/colonies_at_war/colonies_at_war.png    (pack icon)
    resources/blueprints/colonies_at_war/colonies_at_war/walltower/walltower1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/warroom/warroom1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallsegment/wallsegment1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallcorner/wallcorner1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallgate/wallgate1-5.blueprint
    resources/blueprints/colonies_at_war/colonies_at_war/wallstair/wallstair1-5.blueprint

Carried over from the Colonist Thieves tools. The one thing that is new: this mod ships blocks of
its own that the blueprints use - the ramparts and parapets a wall is built of - so the "does this
block id exist" check reads OUR OWN ids out of the built jar as well, rather than a hand-written
list that goes stale the first time a block is renamed. Run ./build.sh before the checks.
"""
import glob
import json
import os
import re
import sys
import zipfile

import paths
import voxel

MODID = "colonies_at_war"
PACK_NAME = "Colonies at War"
PACK_DIR = paths.res("blueprints", MODID, MODID)
PACK_ICON = f"{MODID}.png"

GROUND = "structurize:blocksolidsubstitution"     # "a solid block of this terrain" (fills, costs materials)
KEEP = "structurize:blocksubstitution"            # "leave whatever is there" (no work, no materials)


def aligned(fn, level):
    """Level `level` of a building, moved so its anchor coincides with level 5's anchor.

    Both buildings here are drawn around one anchor at every level, so this is a no-op - it is
    kept because it is what makes a shared box mean anything, and the first level that wanders
    gets caught by it rather than by a player watching his tower jump sideways on an upgrade.
    """
    s = fn(level)
    a5 = fn(5).anchor
    dx, dy, dz = a5[0] - s.anchor[0], 0, a5[2] - s.anchor[2]
    return s.translated(dx, dy, dz) if (dx or dz) else s


# ---------------------------------------------------------------- block ids that exist

MC_ASSETS = paths.lib(os.environ.get("MC_ASSETS_JAR", "mc-extra.jar"))   # client assets jar (blockstates)

# The one block with no blockstate file anywhere to read: MineColonies' rack is registered in code
# and its model is built at runtime.
KNOWN_MOD_BLOCKS = {"minecolonies:blockminecoloniesrack"}


def _blockstate_ids(jar_path, namespace):
    """Every block id in `namespace` that has a blockstate file in this jar."""
    ids = set()
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            m = re.match(rf"assets/{namespace}/blockstates/([a-z0-9_]+)\.json$", name)
            if m:
                ids.add(f"{namespace}:{m.group(1)}")
    return ids


def own_jar():
    """This mod's own built jar, which is where our block ids are read from."""
    hits = sorted(glob.glob(os.path.join(paths.ROOT, f"{MODID}-*.jar")))
    if not hits:
        raise FileNotFoundError(f"no {MODID}-*.jar in {paths.ROOT} - run ./build.sh first, the "
                                f"checks read our own block ids out of it")
    return hits[-1]


def vanilla_blocks():
    """Every vanilla block id that has a blockstate file in the client assets - a block that is
    not in here (polished_andesite_wall, say) silently becomes air when Structurize loads the
    blueprint, with nothing in the log to say so."""
    return _blockstate_ids(MC_ASSETS, "minecraft")


def known_blocks():
    """Vanilla, the three mods this pack may use, and our own blocks - every one of them read out
    of a jar, so a renamed block is caught the next time the jars are rebuilt."""
    ids = vanilla_blocks()
    ids |= _blockstate_ids(paths.lib("structurize-*.jar"), "structurize")
    ids |= _blockstate_ids(paths.lib("minecolonies-*.jar"), "minecolonies")
    ids |= _blockstate_ids(paths.lib("domum-ornamentum-*.jar"), "domum_ornamentum")
    ids |= _blockstate_ids(own_jar(), MODID)
    return ids | KNOWN_MOD_BLOCKS


def unknown_blocks(s, known):
    return sorted({voxel.parse_state(b)[0] for b in s.blocks.values() if b is not None
                   and voxel.parse_state(b)[0] not in known})


# ---------------------------------------------------------------- pack.json

DESC = ("Colonies at War: two buildings and a wall. The Wall Tower is a guard post that stands in "
        "a wall line - run your rampart into either side of it and the walk carries straight "
        "through. The War Room is the table a colony decides to march at, and the quarters of the "
        "garrison that marches. The wall segment, corner, gate and stair are the wall itself: mark "
        "out a line, and the colony raises a real curtain around the town, eight blocks at a time, "
        "with the walk at the tower's own height, a gate to come through and a stair to get up on. "
        "Works with every colony style.")


def write_pack_json():
    os.makedirs(PACK_DIR, exist_ok=True)
    path = os.path.join(PACK_DIR, "pack.json")
    with open(path, "w") as f:
        json.dump({
            "icon": PACK_ICON,
            "name": PACK_NAME,
            "authors": ["Lovkar", "Claude"],
            "desc": DESC,
            "mods": ["structurize", "minecolonies", MODID],
            "version": "1",
            "pack-format": "1",
        }, f, indent=2)
    return path


def main():
    print("wrote", write_pack_json())
    print("our own blocks:", sorted(_blockstate_ids(own_jar(), MODID)))
    print("now run build_war.py for the blueprints themselves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
