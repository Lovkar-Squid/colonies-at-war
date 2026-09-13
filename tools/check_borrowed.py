"""Which blueprints does the mod call somebody else's wall - and which does it leave alone?

WallSurvey decides whether a decoration counts as wall from its blueprint PATH, and that decision
is the whole feature: too narrow and a player who ringed his town in Caledonia still scores zero;
too wide and a park, a moat or a hedge quietly becomes a fortification and the score stops meaning
anything. The rule itself is four lines of Java and impossible to get visibly wrong, which is
exactly why it needs a test - the failure is never in the rule, it is in what the rule meets.

So this does not test the rule against made-up paths. It reads the folder lists STRAIGHT OUT OF
WallSurvey.java - so the test cannot drift away from the code - and then runs them over every
blueprint in every style pack that is actually installed: the eighteen-odd styles inside the
MineColonies jar, our own pack, and any style pack jar lying beside them. Then it asks the two
questions that matter:

  every wall is found        a hand-written list of paths that MUST come out as wall, one from
                             each pack's own idea of what a wall is - Caledonia's curtain, the
                             Fortress rampart, Nordic's palisade, Ancient Athens' city wall.
  nothing else is            a hand-written list that must NOT - the Fortress moat, Pagoda's
                             fences, a park, a house, and every piece of our own wall, which is
                             made of blocks that register themselves and would otherwise be
                             counted twice.

and then it prints the full classification per pack, because the interesting output is not "ok",
it is the list of folders the rule picked up in a pack nobody looked at.

  python3 tools/check_borrowed.py [extra-jar ...]
"""
import json
import pathlib
import re
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
SURVEY = REPO / "src" / "me" / "lovkar" / "war" / "wall" / "WallSurvey.java"

# where the style packs live when nobody names one on the command line
DEFAULT_JARS = [
    pathlib.Path("/root/voyager/libs/minecolonies-1.1.1368-1.21.1.jar"),
]


# --------------------------------------------------------------------------- the rule, as shipped

def sets_from_source():
    """The three folder lists, read out of the Java rather than copied into this file."""
    text = SURVEY.read_text(encoding="utf-8")
    out = {}
    for field in ("WALL_FOLDERS", "NOT_WALLS", "OUR_FOLDERS"):
        m = re.search(
            r"Set<String>\s+" + field + r"\s*=\s*Set\.of\((.*?)\);", text, re.S)
        if not m:
            raise SystemExit(f"check_borrowed: {field} not found in WallSurvey.java")
        out[field] = {s for s in re.findall(r'"([^"]+)"', m.group(1))}
    if not out["WALL_FOLDERS"]:
        raise SystemExit("check_borrowed: WALL_FOLDERS is empty")
    return out["WALL_FOLDERS"], out["NOT_WALLS"], out["OUR_FOLDERS"]


WALLS, NOT_WALLS, OURS = sets_from_source()


def says_wall(path):
    """WallSurvey.saysWall, line for line. The last segment is the file and is never read."""
    parts = path.lower().replace("\\", "/").split("/")
    wall = False
    for part in parts[:-1]:
        if part in NOT_WALLS or part in OURS:
            return False
        if part in WALLS:
            wall = True
    return wall


# --------------------------------------------------------------------------- what must and must not

MUST = [
    "walls/walls/wall_straight1.blueprint",           # Caledonia
    "walls/wall/wall1.blueprint",                     # Fortress
    "walls/segment/wallsegment1.blueprint",           # Medieval Oak - segment inside walls/
    "walls/tower/walltower1.blueprint",               # ...and its tower
    "walls/gate/gate1.blueprint",                     # ...and its gate
    "walls/palisade/palisade1.blueprint",             # Nordic
    "walls/city/citywall1.blueprint",                 # Ancient Athens
    "walls/fortress/wall1.blueprint",                 # ...and its fortress wall
    "walls/big_walls/bigwall1.blueprint",             # Pagoda
    "walls/corners/corner1.blueprint",                # Caledonia
    "walls/stairs/stair1.blueprint",                  # ...its stairs are part of the wall
    "walls/cobble/wall1.blueprint",                   # Original
    "walls/brick/wall1.blueprint",                    # Incan
    "decorations/gates/smallgatelava.blueprint",      # Cavern files its gates under decorations
    "infrastructure/gates/gate1.blueprint",           # Pagoda files its under infrastructure
]

MUST_NOT = [
    "walls/moat/moat1.blueprint",                     # a hole in the ground is not a wall
    "walls/fences/fence1.blueprint",                  # a fence is a fence
    "walls/misc/lamp1.blueprint",                     # whatever the author had left over
    "decorations/parks/waterside_park.blueprint",     # a park
    "decorations/supplies/supplycamp.blueprint",
    "buildings/home/house1.blueprint",                # a house
    "buildings/guardtower1.blueprint",
    "wallsegment/wallsegment1.blueprint",             # ours: its blocks register themselves
    "wallcorner/wallcorner3.blueprint",
    "wallgate/wallgate1.blueprint",
    "wallstair/wallstair5.blueprint",
    "walltower/walltower1.blueprint",
    "warroom/warroom1.blueprint",
    "decorations/walled_garden.blueprint",            # a name is not a folder
    "parks/wall_of_roses.blueprint",
]


# --------------------------------------------------------------------------- the real packs

def packs_in(jar):
    """Every structure pack in a jar: pack name -> {blueprint path relative to the pack}."""
    found = {}
    with zipfile.ZipFile(jar) as zf:
        roots = {}
        for name in zf.namelist():
            if name.endswith("/pack.json"):
                root = name[: -len("pack.json")]
                try:
                    meta = json.loads(zf.read(name).decode("utf-8"))
                    roots[root] = meta.get("name", root.strip("/").split("/")[-1])
                except Exception:
                    roots[root] = root.strip("/").split("/")[-1]
        for name in zf.namelist():
            if not name.endswith(".blueprint"):
                continue
            for root, pack in roots.items():
                if name.startswith(root):
                    found.setdefault(pack, set()).add(name[len(root):])
                    break
    return found


def main(argv):
    jars = [pathlib.Path(a) for a in argv[1:]] or list(DEFAULT_JARS)
    jars = [j for j in jars if j.is_file()]

    bad = []
    for path in MUST:
        if not says_wall(path):
            bad.append(f"  MISSED  {path} is a wall and the rule says it is not")
    for path in MUST_NOT:
        if says_wall(path):
            bad.append(f"  CAUGHT  {path} is not a wall and the rule says it is")

    print("borrowed walls:")
    print(f"  rule: {len(WALLS)} wall folder(s), {len(NOT_WALLS)} excluded, "
          f"{len(OURS)} of our own")
    print(f"  {len(MUST)} path(s) that must be found, {len(MUST_NOT)} that must not"
          f" - {'all correct' if not bad else str(len(bad)) + ' WRONG'}")

    # ...and the same rule over every blueprint actually installed
    total_packs = 0
    total_walls = 0
    for jar in jars:
        for pack, paths in sorted(packs_in(jar).items()):
            total_packs += 1
            hits = sorted(p for p in paths if says_wall(p))
            total_walls += len(hits)
            folders = sorted({"/".join(p.split("/")[:-1]) for p in hits})
            if hits:
                print(f"  {pack:<18} {len(hits):>4} of {len(paths):>4} count"
                      f"  [{', '.join(folders[:6])}{' ...' if len(folders) > 6 else ''}]")
            else:
                print(f"  {pack:<18} {'-':>4} of {len(paths):>4}")
    # our own pack, straight off disk
    ours = REPO / "resources" / "blueprints"
    if ours.is_dir():
        mine = [str(p.relative_to(ours)).split("/", 2)[-1]
                for p in ours.rglob("*.blueprint")]
        wrong = [p for p in mine if says_wall(p)]
        print(f"  {'Colonies at War':<18} {len(wrong):>4} of {len(mine):>4} count"
              f"   (must be 0 - our blocks register themselves)")
        if wrong:
            bad.append(f"  OURS    {len(wrong)} of our own piece(s) would be counted twice: "
                       + ", ".join(wrong[:4]))

    print(f"  {total_walls} wall blueprint(s) across {total_packs} style pack(s)")
    if bad:
        print()
        for line in bad:
            print(line)
        return 1
    print("check_borrowed: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
