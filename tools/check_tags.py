"""Does every structure the camp and village tags name exist in the mod it names?

The two structure tags #colonies_at_war:camp and :village list other mods' structures by id, every
one "required": false, so a world without that mod loads the tag and simply has one target fewer.
That same leniency is what makes a typo invisible: "valarian_conquest:bandit_ruin" loads without a
word in any log, and the camp it meant is never marched on. Nothing in the game will ever say so.

So this reads every id out of both tags and looks for the structure's own file in the other mods'
jars: data/<namespace>/worldgen/structure/<path>.json. An id whose namespace has a jar but no such
file is a typo and fails the check; a namespace with no jar at all fails too (the jar folder is the
list of mods the tags were written against). Vanilla and MineColonies ids, and #tag references, are
left alone - the game itself checks the first, and MineColonies is not in the compat folder.

The jars are looked for under WAR_COMPAT (default D:/Claude/caw-compat-jars), one folder per mod.
They are third-party: read here, never copied into the repo. Without that folder the check prints
a skip line and passes.

  python3 tools/check_tags.py
"""
import json
import os
import pathlib
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TAGS = REPO / "resources" / "data" / "colonies_at_war" / "tags" / "worldgen" / "structure"
SKIP_NAMESPACES = {"minecraft", "minecolonies"}


def ids_of(tag_file):
    """Every structure id the tag names, without #tag references."""
    out = []
    for value in json.loads(tag_file.read_text(encoding="utf-8"))["values"]:
        one = value["id"] if isinstance(value, dict) else value
        if not one.startswith("#"):
            out.append(one)
    return out


def structures_in(folder):
    """namespace -> set of structure paths, read out of every jar under the folder."""
    found = {}
    for jar in sorted(folder.rglob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    parts = name.split("/")
                    # data/<ns>/worldgen/structure/<path...>.json
                    if (len(parts) >= 5 and parts[0] == "data" and parts[2] == "worldgen"
                            and parts[3] == "structure" and name.endswith(".json")):
                        found.setdefault(parts[1], set()).add("/".join(parts[4:])[:-len(".json")])
        except zipfile.BadZipFile:
            print(f"  {jar.name}: not a jar, skipped")
    return found


def main():
    folder = pathlib.Path(os.environ.get("WAR_COMPAT", "D:/Claude/caw-compat-jars"))
    if not folder.is_dir():
        print(f"check_tags: skipped - no compat jar folder at {folder} (set WAR_COMPAT)")
        return 0
    have = structures_in(folder)
    bad = 0
    checked = 0
    for tag_file in sorted(TAGS.glob("*.json")):
        for one in ids_of(tag_file):
            ns, _, path = one.partition(":")
            if ns in SKIP_NAMESPACES:
                continue
            checked += 1
            if ns not in have:
                print(f"  FAIL {tag_file.stem}: {one} - no jar under {folder} has any structure of {ns}")
                bad += 1
            elif path not in have[ns]:
                print(f"  FAIL {tag_file.stem}: {one} - not in {ns}'s jar (a typo?)")
                bad += 1
            else:
                print(f"  ok   {tag_file.stem}: {one}")
    if checked == 0:
        print("check_tags: FAIL - the tags name no other mod's structure at all")
        return 1
    print(f"check_tags: {checked} ids checked, {bad} bad")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
