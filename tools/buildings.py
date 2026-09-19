"""Everything this pack ships, and what Structurize and MineColonies have to be told about each.

Everything that is per-piece lives here and nowhere else, so build_war.py and check_war.py are the
same code run five times. The ids are the ones Warfare.java registers - the hut block, our own
block-entity type (MineColonies' own only accepts MineColonies hut blocks), and the building
registry name, which has to agree with getSchematicName() on the building class or the colony
looks for a blueprint that is not there.

TWO KINDS OF THING, and the difference is the anchor.

  buildings     the Wall Tower and the War Room. Anchored on their own hut block, with our own
                block entity behind it, and they hire men: every one of them owes as many bunks as
                WarModules gives it, and the ones that carry MineColonies' BED module owe REAL
                beds a citizen can walk to.
  decorations   the five wall pieces. No hut block, no crew, no beds - a colony does not staff
                a wall, it builds one. They are anchored on minecolonies:decorationcontroller, which
                is what MineColonies' own wall blueprints use, and their level is read out of the
                digits of the file name, so wallsegment1..5 upgrade in place exactly the way a hut
                does. `building=None` is what tells voxel.to_blueprint to write that anchor.

A wall piece is judged by check_walls.py rather than here, because the only interesting question
about a wall piece is what it does when it meets another one.
"""
import wallcorner
import wallgate
import wallinner
import wallsegment
import wallstair
import walltower
import warroom

BE_TYPE = "colonies_at_war:colonybuilding"          # Warfare.BUILDING_BE
DECO_BE = "minecolonies:decorationcontroller"       # MineColonies', because a decoration is theirs

NO_CREW = lambda lv: 0                              # noqa: E731 - a wall hires nobody


def deco(module):
    """One of the wall pieces: built and upgraded, never staffed."""
    return dict(module=module, building=None, hut=DECO_BE, be_type=DECO_BE,
                crew=NO_CREW, beds_required=False)


SPEC = {
    # folder / file stem      module     building type                   hut block
    "walltower": dict(module=walltower, building="colonies_at_war:walltower",
                      hut="colonies_at_war:blockhutwalltower", be_type=BE_TYPE,
                      crew=lambda lv: walltower.CREW[lv],
                      beds_required=False),          # no BED module: the bunks are furniture
    "warroom":   dict(module=warroom, building="colonies_at_war:warroom",
                      hut="colonies_at_war:blockhutwarroom", be_type=BE_TYPE,
                      crew=lambda lv: warroom.GARRISON[lv],
                      beds_required=True),           # BuildingModules.BED: one real bed per man
    "wallsegment": deco(wallsegment),
    "wallcorner":  deco(wallcorner),
    "wallinner":   deco(wallinner),
    "wallgate":    deco(wallgate),
    "wallstair":   deco(wallstair),
}

ALL = {name: spec["module"] for name, spec in SPEC.items()}
WALLS = ("wallsegment", "wallcorner", "wallinner", "wallgate", "wallstair")
