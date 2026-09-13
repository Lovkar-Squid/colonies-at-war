"""Does every building hand the client a view that matches what the server made?

A MineColonies building is two classes: the server one and a client *view*. Nothing in the
compiler ties them together - `setBuildingViewProducer` takes any view at all - and nothing goes
wrong at load time either. It goes wrong the first time a window casts the view to the type the
server class implies, which for a guard building is the moment the player opens the settings tab
and the guard-task dropdown renders:

    ClassCastException: EmptyView cannot be cast to AbstractBuildingGuards$View
        at GuardTaskSetting.render(GuardTaskSetting.java:116)

That crashed a real game. So the rule, checked here against the registration source:

  * a building whose server class extends AbstractBuildingGuards MUST be given a view that
    extends AbstractBuildingGuards.View - EmptyView is the specific mistake, but any unrelated
    view is the same crash;
  * a building whose server class overrides serializeToView MUST NOT be given EmptyView, because
    EmptyView reads none of those bytes back and the rest of the packet is then misread;
  * a building whose server class extends AbstractBuildingGuards MUST be registered with
    MineColonies' own BuildingModules.GUARD_SETTINGS and not a settings module of its own.

The third rule is the one that cost an afternoon. Replacing that producer compiles, loads, and
renders a perfectly good settings tab - and then AbstractBuildingGuards.getTask() calls
getModule(BuildingModules.GUARD_SETTINGS) with that exact object, gets null back, and the guard's
AI throws an NPE out of CitizenAI.shouldEat about twice a second for the rest of the session:

    Cannot invoke "SettingsModule.getSetting(...)" because the return value of
    "AbstractBuildingGuards.getModule(BuildingEntry$ModuleProducer)" is null

getModule matches the producer by identity. A setting of your own goes INTO their module
(ISettingsModule.with) before its NBT is read, never into a module of yours.

Run from tools/ in any of the repos - the file is shared by symlink. Reads the Java,
so it works with no game and no jars.
"""
import pathlib
import re
import sys

# shared by all three repos through a symlink, so the tree to read is the one we were RUN in,
# never the one this file physically lives in - .resolve() follows the symlink and would send
# every repo's run back to the war mod's source
_here = pathlib.Path.cwd()
ROOT = _here.parent if _here.name == "tools" else _here
if not (ROOT / "src").is_dir():
    ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
GUARD_BASE = "AbstractBuildingGuards"
GUARD_VIEW = "AbstractBuildingGuards.View"

fails = []


def registrations(text):
    """(building class, view expression) for every BuildingEntry.Builder chain."""
    out = []
    for chunk in text.split("new BuildingEntry.Builder()")[1:]:
        chunk = chunk.split("createBuildingEntry()")[0]
        b = re.search(r"setBuildingProducer\(\s*([\w.]+)::new", chunk)
        v = re.search(r"setBuildingViewProducer\(\s*\(\)\s*->\s*([\w.]+)::new", chunk)
        if b:
            out.append((b.group(1), v.group(1) if v else None))
    return out


def guard_settings_near(text, building):
    """Is this building's own Builder chain given BuildingModules.GUARD_SETTINGS?"""
    simple = building.split(".")[-1]
    for chunk in text.split("new BuildingEntry.Builder()")[1:]:
        chunk = chunk.split("createBuildingEntry()")[0]
        if f"{simple}::new" in chunk:
            return "BuildingModules.GUARD_SETTINGS" in chunk
    return False


def source_of(name):
    """The file for a class, whether it was written plain or fully qualified in the builder."""
    simple = name.split(".")[-1]
    hits = list(SRC.rglob(f"{simple}.java"))
    return hits[0].read_text(encoding="utf-8") if hits else None


def main():
    seen = 0
    for java in SRC.rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        if "new BuildingEntry.Builder()" not in text:
            continue
        for building, view in registrations(text):
            seen += 1
            body = source_of(building)
            if body is None:
                fails.append(f"{building}: registered but no source found")
                continue
            if view is None:
                fails.append(f"{building}: no setBuildingViewProducer at all")
                continue

            simple = building.split(".")[-1]
            guards = re.search(rf"class\s+{simple}\s+extends\s+{GUARD_BASE}\b", body) is not None
            owner, _, inner = view.partition(".")
            view_body = body if owner.split('.')[-1] == simple else source_of(owner)
            view_extends = None
            if view_body:
                m = re.search(rf"class\s+{inner or owner}\s+extends\s+([\w.]+)", view_body)
                view_extends = m.group(1) if m else None

            if guards and not guard_settings_near(text, building):
                fails.append(
                    f"{building} extends {GUARD_BASE} but is not registered with "
                    f"BuildingModules.GUARD_SETTINGS - the guard AI looks that producer up by "
                    f"identity and gets null, then NPEs every tick")

            if guards:
                if view.endswith("EmptyView") or view_extends != GUARD_VIEW:
                    fails.append(
                        f"{building} extends {GUARD_BASE} but its view is {view}"
                        f"{'' if view_extends is None else f' (extends {view_extends})'}"
                        f" - the guard settings tab casts to {GUARD_VIEW} and will crash")
                else:
                    print(f"  ok  {building:22s} -> {view}  (guard building, guard view)")
            else:
                if "serializeToView" in body and view.endswith("EmptyView"):
                    fails.append(f"{building} writes serializeToView but its view is EmptyView "
                                 f"- those bytes are never read back")
                else:
                    print(f"  ok  {building:22s} -> {view}")
    if seen == 0:
        fails.append("no building registrations found - the parser is looking in the wrong place")


def run():
    fails.clear()
    print("building views:")
    main()
    if fails:
        print("check_views: FAIL")
        for f in fails:
            print("  -", f)
        return False
    print("check_views: ok")
    return True


if __name__ == "__main__":
    sys.exit(0 if run() else 1)
