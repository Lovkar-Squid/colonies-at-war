"""Does every name the mod asks for actually exist?

The one bug this mod has shipped twice is a window showing a raw translation key, and both times
it was the same shape: some code built a key out of a prefix and an id - "com.colonist_thieves.
kind." plus whatever the place was - and one of the ids had no line in en_us.json. Nothing in a
compiler will ever catch that, and it does not show up until the tab is opened in the right state.

So it gets a checker. This walks the Java and the BlockUI layouts, collects every key the mod can
ask for, and expands the built ones against the enums they are built from: a key made of
"com.colonist_thieves.work." plus a Work id is not one key, it is three, and all three have to be
there. Then it says which are missing, and which lines in the lang file nothing asks for any more.

Run it against either mod:  python3 tools/check_lang.py [repo ...]
"""
import json
import pathlib
import re
import sys

# key prefixes that are completed at runtime, and the enum each one is completed from:
# (prefix, source file, the regex that finds the ids in it)
BUILT = [
    ("com.colonist_thieves.kind.", "src/me/lovkar/thieves/target/TargetKind.java"),
    ("com.colonist_thieves.work.", "src/me/lovkar/thieves/target/Work.java"),
    ("com.colonist_thieves.outcome.", "src/me/lovkar/thieves/job/Outcome.java"),
    ("com.colonist_thieves.gui.sightings.sent.", "src/me/lovkar/thieves/target/Work.java"),
    ("com.colonies_at_war.piece.", "src/me/lovkar/war/wall/WallKind.java"),
    ("com.colonies_at_war.planner.mode.", "src/me/lovkar/war/item/WallPlannerItem.java"),
    ("com.colonies_at_war.planner.help.", "src/me/lovkar/war/item/WallPlannerItem.java"),
    ("com.colonies_at_war.decoration.", "src/me/lovkar/war/wall/WallKind.java"),
]

ENUM_ID = re.compile(r'^\s*[A-Z][A-Z_0-9]*\("([a-z_]+)"', re.M)
LITERAL = re.compile(r'"(com\.[a-z_]+\.[A-Za-z0-9_.]+)"')
JOINED = re.compile(r'"(com\.[a-z_]+\.[A-Za-z0-9_.]*\.)"\s*\+')
IN_XML = re.compile(r'\$\((com\.[A-Za-z0-9_.]+)\)')


def ids_in(path):
    """The string ids of an enum, in the order it declares them."""
    if not path.exists():
        return []
    return ENUM_ID.findall(path.read_text())


def check(repo):
    repo = pathlib.Path(repo)
    lang_files = list(repo.glob("resources/assets/*/lang/en_us.json"))
    if not lang_files:
        print(f"  {repo}: no en_us.json - nothing to check")
        return 0
    have = set()
    for one in lang_files:
        have |= set(json.loads(one.read_text()).keys())

    wanted = {}          # key -> where it was asked for

    def want(key, where):
        wanted.setdefault(key, where)

    for java in repo.rglob("src/**/*.java"):
        text = java.read_text()
        for key in LITERAL.findall(text):
            # a bare prefix ending in a dot is half a key; it is handled below
            if not key.endswith("."):
                want(key, java.name)
        for prefix in JOINED.findall(text):
            known = False
            for pre, source in BUILT:
                if prefix != pre:
                    continue
                known = True
                for one in ids_in(repo / source):
                    want(prefix + one, java.name)
            if not known:
                print(f"  ?  {java.name} builds keys from '{prefix}' and this checker does not "
                      f"know what completes it - add it to BUILT")

    for xml in repo.rglob("resources/**/*.xml"):
        for key in IN_XML.findall(xml.read_text()):
            want(key, xml.name)

    # a key can also be claimed by a data file rather than by code - a research names its own
    # title, a block its own name - and those are asked for just as much as the rest
    claimed = set()
    for data in repo.rglob("resources/**/*.json"):
        if data.parent.name == "lang":
            continue
        text = data.read_text()
        for key in have:
            if '"' + key + '"' in text:
                claimed.add(key)
    # and anything a registered block, item, job or research is named after is claimed by the
    # registry entry rather than by a line of code that mentions the key
    for java in repo.rglob("src/**/*.java"):
        text = java.read_text()
        for key in have:
            if '"' + key + '"' in text:
                claimed.add(key)

    missing = sorted(k for k in wanted if k not in have)
    # a settings label is built by MineColonies, not by us, so it is never "asked for" in our
    # source; it is still ours to provide, and it is not spare
    # what MineColonies builds for us - a settings label, a hut name, a job name - is ours to
    # provide and is never mentioned in our own source, so it is never spare
    THEIRS = ("com.minecolonies.", "block.", "item.", "entity.", "itemGroup.", "gui.", "key.",
              # and the ones MineColonies builds out of OUR namespace: a hut's name, a job's
              # name, a settings label, every line of a research tree
              "com.colonist_thieves.building.", "com.colonist_thieves.job.",
              "com.colonist_thieves.setting.", "com.colonist_thieves.research.",
              "com.colonies_at_war.building.", "com.colonies_at_war.job.",
              "com.colonies_at_war.setting.", "com.colonies_at_war.research.",
              "colonist_thieves:", "colonies_at_war:")
    spare = sorted(k for k in have
                   if k not in wanted and k not in claimed and not k.startswith(THEIRS))

    print(f"  {repo.name}: {len(wanted)} key(s) asked for, {len(have)} in the lang file")
    for key in missing:
        print(f"  !! MISSING  {key}   (asked for in {wanted[key]})")
    for key in spare:
        print(f"  -  spare    {key}")
    return len(missing)


if __name__ == "__main__":
    # run from a repo and it checks that repo, even through a symlinked copy of this file
    here = pathlib.Path.cwd()
    default = here if (here / "src").is_dir() else pathlib.Path(__file__).resolve().parent.parent
    repos = sys.argv[1:] or [default]
    bad = 0
    print("names:")
    for repo in repos:
        bad += check(repo)
    print("  all names present" if bad == 0 else f"  {bad} name(s) missing")
    sys.exit(1 if bad else 0)
