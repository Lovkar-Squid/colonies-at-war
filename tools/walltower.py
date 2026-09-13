"""The Wall Tower - a bastion that stands in a wall line. Levels 1 to 5, as voxel structures.

x east, z south, front is SOUTH (+z): the door faces into the colony. y = 0 is the ground course
and the tower's floor; a citizen inside stands at y = 1.

WHAT IT HAS TO BE. Not a keep and not a watchtower on a hill - a piece of wall with a room in it,
built round one line:

    the wall walk goes straight through it, east to west, at y = 6.

  y = 5        a solid deck of stone_rampart across the whole tower, and a stub of the same thing
               out of either side to x = +-7. A rampart is the block a wall's walk runs on, and
               BuildingWallTower.layPatrol lays a patrol point on any rampart with two clear cells
               over it - so the deck and the stubs ARE the patrol. Run your own wall into the end
               of a stub and the walk carries on without a step.
  y = 6, 7     the walk: clear, and open through both side walls in a three-wide arch.
  y = 6        stone_parapet wherever a guard could walk off - knee-high on purpose, so the walk
               beside it stays clear (see ParapetBlock).

AND WHAT MAKES IT A BASTION RATHER THAN A BOX.

  the batter     a 13 x 13 footing: an apron, a plinth course, and a course of stairs leaning in
                 to the 11 x 11 tower. The tower looks footed instead of dropped, and a wall
                 running into it dies into the slope the way a real one does.
  arrow slits    real slits - one block wide, two tall, open to the sky - not barred windows.
  the newel      a spiral stair round a stone newel in the north-west corner, one step per block
                 and eight to the turn, which lands exactly on the deck, on the chamber floor and
                 on the fighting top. A citizen walks all of it; nothing here is a ladder.
  machicolation  from level 4 the top projects a block past the wall on corbels, with the gap
                 between them left open - the overhang you drop things through. The crenellation
                 above it is merlons of rampart with parapet embrasures between.
  the fire       a brazier on the fighting top from level 2, so the tower reads at night from as
                 far away as it can be seen at all - which is the point of a tower.

THE LEVELS.

  1  the footing, the ground room, the deck with the wall already through it, and a timber
     lookout over it: four dark oak posts and a plank roof. 2 guards.
  2  the lookout becomes stone - walls, arrow slits, a roof deck with a crenellated rim and the
     first brazier. 3 guards.
  3  taller: a chamber on the roof deck with its own slits and a fire in the middle of it, and a
     new deck over that. 4 guards.
  4  the top is rebuilt on corbels: machicolation, proper merlons, the colony's banner. 5 guards.
  5  a bastion - fortified rampart and parapet throughout the top, and a covered fighting top on
     four posts with the standard over it. 6 guards.

Crew: WarModules hires (level+1)/2 knights and level/2+1 rangers - 2, 3, 4, 5, 6 - and it gets
that many bunks. They are furniture: the Wall Tower registers no BED module (Warfare.java), so its
men keep their beds at home; a post that sleeps six should still look like one.
"""
from voxel import Structure

import build_pack as bp
from pieces import (F_PARAPET, F_RAMPART, PARAPET, RACK, RAMPART, armour_stand, banner, bed,
                    box_walls, campfire, chain, chest, connected, door, floor, interior, lantern,
                    log, slab, speck, stairs, wall_banner, wall_post)

HUT = "colonies_at_war:blockhutwalltower[facing=north]"

# The shared box. 15 x 13 on the ground because the wall stubs reach out of it, and tall - a
# bastion that does not stand over the wall is a shed on it.
BOX = ((-7, 0, -6), (7, 18, 6))

CORE = (-5, -5, 5, 5)       # the tower proper: 11 x 11 outer, 9 x 9 inside
SKIRT = (-6, -6, 6, 6)      # the battered footing: 13 x 13
GROUND_Y = 0
ROOM_TOP = 4                # the ground room is y = 1..4
DECK_Y = 5                  # the wall deck: the walk is the top of this course
WALK = (6, 7)               # the two clear cells of the walk
ARCH_Z = (-1, 0, 1)         # the passage through the tower, three wide
STUB = (6, 7)               # the wall stub: x = +-6 and +-7
FLOOR2_Y = 8                # the roof deck of level 2, the chamber floor from level 3
CHAMBER = (9, 11)           # the chamber
CORBEL_Y = 11               # the corbels the machicolation stands on
TOP_Y = 12                  # the fighting deck
CREN_Y = 13                 # merlons at 13 and 14, embrasures at 13
POSTS = (14, 15)            # the covered top's posts (level 5)
CAP_Y = 16                  # its roof
MAST_Y = 17                 # the mast, with the standard on top of it

HUT_POS = (0, 1, -4)        # inside, against the back wall, looking down the room at the door
ENTRY_X = (-1, 0, 1)        # where the batter is cut away for the door

# The newel stair, north-west corner: eight cells round a newel at (-3, -3), one block of rise
# each. Twelve steps take a man from the floor to the fighting top, and because the rises between
# floors are 5, 3 and 4, each landing falls exactly on a floor - see STEPS.
NEWEL = (-3, -3)
RING = ((-4, -4), (-3, -4), (-2, -4), (-2, -3), (-2, -2), (-3, -2), (-4, -2), (-4, -3))
RING_FACING = ("east", "east", "south", "south", "west", "west", "north", "north")
STEPS = {1: 5, 2: 8, 3: 12, 4: 12, 5: 12}   # stairs at y = 1..STEPS: deck at 5, floor at 8, top at 12

CREW = {1: 2, 2: 3, 3: 4, 4: 5, 5: 6}
# (head x, z): head against the wall, foot one in. The east wall first, then the west under the
# stair well, and never in the three cells the walk's own corridor crosses at ground level.
BUNKS = ((4, -2), (-4, 1), (4, -1), (-4, 2), (4, 0), (-4, 0), (4, 1), (4, 2))

P = {
    "wall":    "minecraft:stone_bricks",
    "wall2":   "minecraft:cracked_stone_bricks",
    "wall3":   "minecraft:mossy_stone_bricks",
    "rough":   "minecraft:cobblestone",
    "rough2":  "minecraft:mossy_cobblestone",
    "quoin":   "minecraft:chiseled_stone_bricks",
    "floor":   "minecraft:polished_andesite",
    "floor2":  "minecraft:andesite",
    "stair":   "minecraft:stone_brick_stairs",
    "slab":    "minecraft:stone_brick_slab",
    "newel":   "minecraft:stone_brick_wall",
    "post":    "minecraft:dark_oak_log",
    "plank":   "minecraft:dark_oak_planks",
    "shed":    "minecraft:dark_oak_stairs",
    "door":    "minecraft:dark_oak_door",
    "lamp":    "minecraft:lantern",
    "soul":    "minecraft:soul_lantern",
    "bed":     "minecraft:red_bed",
    "flag":    "red",
    "fort":    "minecraft:deepslate_bricks",
    "fort2":   "minecraft:polished_deepslate",
    "fortst":  "minecraft:deepslate_brick_stairs",
    # the level 5 cap is Domum Ornamentum shingle, laid on boards - the one mix-and-match block
    # Voyager already proves in game, with the materials its own blueprints use
    "shingle": "domum_ornamentum:shingle",
    "m_roof":  "minecraft:deepslate_tiles",
    "m_sup":   "minecraft:spruce_planks",
}


def stone(x, y, z):
    """Tower stone with the odd cracked or mossy block in it - a tower that looks new is a tower
    nobody believes has been shot at."""
    if speck(x, y, z, 13):
        return P["wall3"]
    return P["wall2"] if speck(x, y, z, 7) else P["wall"]


def rough(x, y, z):
    return P["rough2"] if speck(x, y, z, 5) else P["rough"]


def fortified(x, y, z):
    return P["fort2"] if speck(x, y, z, 6) else P["fort"]


def arch_cell(x, y, z):
    """The cells the wall walk passes through the tower's own walls."""
    return x in (CORE[0], CORE[2]) and z in ARCH_Z and y in WALK


def ring_cells(rect):
    """The perimeter of a rectangle, in order, as (x, z)."""
    x0, z0, x1, z1 = rect
    out = []
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                out.append((x, z))
    return out


def outward(x, z, rect):
    """Which way is out, for a cell on the perimeter of a rectangle."""
    x0, z0, x1, z1 = rect
    if x == x0:
        return "west"
    if x == x1:
        return "east"
    return "north" if z == z0 else "south"


# ================================================================ the footing

def footing(s, level):
    """The battered base: an apron, a plinth, and a course of stairs leaning in to the tower.

    The entrance bay is cut out of both, because a door that opens onto its own plinth is a door
    nobody walks through.
    """
    face = rough if level == 1 else stone
    floor(s, SKIRT, GROUND_Y, lambda x, z: face(x, 0, z))
    for x, z in ring_cells(SKIRT):
        if (x, z) in [(e, SKIRT[3]) for e in ENTRY_X]:
            continue                                    # the way in
        s.set(x, 1, z, face(x, 1, z))
        s.set(x, 2, z, stairs(P["stair"], _inward(x, z)))


def _inward(x, z):
    """A batter leans towards the tower, so its stairs rise inwards."""
    return {"west": "east", "east": "west", "north": "south", "south": "north"}[outward(x, z, SKIRT)]


def ground_room(s, level):
    """The room the guards live in: floor, walls, the door, and real arrow slits above the batter."""
    x0, z0, x1, z1 = CORE
    face = rough if level == 1 else stone
    floor(s, CORE, GROUND_Y, lambda x, z: P["wall"] if x in (x0, x1) or z in (z0, z1)
          else (P["floor2"] if speck(x, 0, z, 5) else P["floor"]))
    box_walls(s, CORE, 1, ROOM_TOP, face, corner=P["quoin"])

    s.set(0, 1, z1, door(P["door"], "south", "lower"))
    s.set(0, 2, z1, door(P["door"], "south", "upper"))
    s.set(0, 3, z1, P["quoin"])
    for dx in (-1, 1):                                  # a dressed surround to the doorway
        s.set(dx, 3, z1, P["quoin"])
    for dx in (-2, 2):                                  # and a light each side, up on the batter
        s.set(dx, 3, z1 + 1, lantern(P["lamp"]))
    if level >= 2:
        for sx, sz in slit_cells():
            for y in (3, 4):
                s.set(sx, y, sz, None)


def slit_cells():
    """Where the slits go: two to a face, never where the door is - and never in a wall cell the
    newel stair leans on. A slit is a hole, and the corner steps of a spiral are carried by the
    wall behind them: cut one there and the step hangs in the air."""
    x0, z0, x1, z1 = CORE
    cells = ([(x, z0) for x in (-1, 2)] + [(x, z1) for x in (-3, 3)]
             + [(x0, z) for z in (0, 2)] + [(x1, z) for z in (-2, 2)])
    leaned_on = {(rx + dx, rz + dz) for rx, rz in RING
                 for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))}
    return [c for c in cells if c not in leaned_on]


# ================================================================ the wall through it

def deck(s, level):
    """The wall deck and its two stubs - the reason this building exists."""
    rampart = F_RAMPART if level >= 5 else RAMPART
    parapet = F_PARAPET if level >= 5 else PARAPET
    floor(s, CORE, DECK_Y, rampart)
    for sign in (-1, 1):
        for sx in (sign * STUB[0], sign * STUB[1]):
            for z in ARCH_Z:
                s.set(sx, GROUND_Y, z, bp.GROUND)       # the stub stands on the ground, not on air
                for y in range(1, DECK_Y + 1):
                    s.set(sx, y, z, rampart)
            for z in (ARCH_Z[0], ARCH_Z[-1]):
                s.set(sx, WALK[0], z, parapet)          # and carries its own parapet


def walk_level(s, level):
    """y = 6 and 7, the level the walk crosses: open behind a parapet at first, walled in later,
    and always open through the two arches."""
    x0, z0, x1, z1 = CORE
    parapet = F_PARAPET if level >= 5 else PARAPET
    if level == 1:
        for x, z in ring_cells(CORE):
            if not arch_cell(x, WALK[0], z):
                s.set(x, WALK[0], z, parapet)
        for cx in (x0, x1):                             # the lookout's four posts
            for cz in (z0, z1):
                for y in WALK:
                    s.set(cx, y, cz, log(P["post"]))
        return
    for x, z in ring_cells(CORE):
        for y in WALK:
            if not arch_cell(x, y, z):
                s.set(x, y, z, stone(x, y, z))
    for cx in (x0, x1):
        for cz in (z0, z1):
            for y in WALK:
                s.set(cx, y, cz, P["quoin"])
    for x in (x0, x1):                                  # a dressed jamb either side of each arch
        for z in (ARCH_Z[0] - 1, ARCH_Z[-1] + 1):
            for y in WALK:
                s.set(x, y, z, P["quoin"])
    for sz in (z0, z1):                                 # and slits looking along the wall
        for sx in (-3, 3):
            s.set(sx, WALK[1], sz, None)


# ================================================================ everything above the walk

def upper(s, level):
    """The tower's own crown, which is what grows from level to level."""
    x0, z0, x1, z1 = CORE
    if level == 1:
        hip_roof(s, FLOOR2_Y, CORE, timber=True)
        for lx, lz in ((-3, 3), (3, 3), (3, -3)):       # never over the walk's own corridor
            s.set(lx, FLOOR2_Y - 1, lz, lantern(P["lamp"], hanging=True))
        return

    # a floor over the walk level - level 2 stands on it, level 3 builds a chamber on it
    floor(s, CORE, FLOOR2_Y, lambda x, z: P["wall"] if x in (x0, x1) or z in (z0, z1) else P["floor"])
    if level == 2:
        for x, z in ring_cells(CORE):
            s.set(x, FLOOR2_Y + 1, z, PARAPET)
        brazier(s, 0, FLOOR2_Y + 1, 0)
        return

    # the chamber
    box_walls(s, CORE, CHAMBER[0], CHAMBER[1], stone, corner=P["quoin"])
    for sx, sz in slit_cells():
        for y in (CHAMBER[1] - 1, CHAMBER[1]):
            s.set(sx, y, sz, None)
    brazier(s, 0, CHAMBER[0], 0)
    for dx, dz in ((3, 3), (-3, 3), (3, -3), (-3, -3)):
        if (dx, dz) != (NEWEL[0], NEWEL[1]):
            s.set(dx, CHAMBER[1], dz, lantern(P["lamp"], hanging=True))

    if level == 3:
        floor(s, CORE, TOP_Y, lambda x, z: P["wall"] if x in (x0, x1) or z in (z0, z1) else P["floor"])
        for x, z in ring_cells(CORE):
            s.set(x, CREN_Y, z, PARAPET)
        return

    machicolation(s, level)


def machicolation(s, level):
    """The overhang: corbels out of the wall head with the gaps left open between them, a deck a
    block wider than the tower standing on them, and merlons on top of that."""
    fort = level >= 5
    rampart = F_RAMPART if fort else RAMPART
    parapet = F_PARAPET if fort else PARAPET
    corbel = P["fortst"] if fort else P["stair"]
    deck_block = fortified if fort else (lambda x, y, z: P["wall"])

    for x, z in ring_cells(SKIRT):                      # the corbels, every other cell
        if (x + z) % 2 == 0:
            s.set(x, CORBEL_Y, z, stairs(corbel, outward(x, z, SKIRT), half="top"))
    floor(s, SKIRT, TOP_Y, lambda x, z: deck_block(x, TOP_Y, z))
    for x, z in ring_cells(SKIRT):                      # merlons and embrasures
        if (x + z) % 2 == 0:
            s.set(x, CREN_Y, z, rampart)
            s.set(x, CREN_Y + 1, z, rampart)
        else:
            s.set(x, CREN_Y, z, parapet)
    brazier(s, 0, CREN_Y, 0, great=True)
    for x in (-2, 2):                                   # the colours, hung on the tower's face
        s.set(x, CHAMBER[1] - 1, SKIRT[3], wall_banner(P["flag"], "south"))
    for x, z in ((-4, -4), (4, -4), (-4, 4), (4, 4)):
        s.set(x, CREN_Y, z, lantern(P["lamp"]))

    if not fort:
        return
    # the covered fighting top: four posts off the deck, a shingle cap over them, the standard
    for cx in (-4, 4):
        for cz in (-4, 4):
            for y in range(POSTS[0], POSTS[1] + 1):
                s.set(cx, y, cz, log(P["post"]))
    hip_roof(s, CAP_Y, (-4, -4, 4, 4), timber=False, shingle=True)
    s.set(0, MAST_Y, 0, wall_post(P["newel"]))
    s.set(0, MAST_Y + 1, 0, banner(P["flag"]))
    for cx in (-3, 3):
        for cz in (-3, 3):
            s.set(cx, CAP_Y - 1, cz, lantern(P["lamp"], hanging=True))


def brazier(s, x, y, z, great=False):
    """A fire in an iron basket, which is how a tower is seen at night."""
    s.set(x, y - 1, z, P["quoin"])
    s.set(x, y, z, campfire())
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        s.set(x + dx, y - 1, z + dz, P["slab"] if not great else P["fort2"])
        if great:
            s.set(x + dx, y, z + dz, connected("minecraft:iron_bars",
                                               "north" if dz else "east", "south" if dz else "west"))


def hip_roof(s, y, rect, timber, shingle=False):
    """A one-step hipped roof: a ring of stairs falling outwards, flat in the middle."""
    x0, z0, x1, z1 = rect
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                face = outward(x, z, rect)
                if shingle:
                    s.mixed(x, y, z, stairs(P["shingle"], face), P["m_roof"], P["m_sup"])
                else:
                    s.set(x, y, z, stairs(P["shed"] if timber else P["stair"], face))
            elif timber:
                s.set(x, y, z, P["plank"])
            elif shingle:
                s.mixed(x, y, z, slab("domum_ornamentum:vanilla_slab_compat", "top"), P["m_roof"])
            else:
                s.set(x, y, z, slab(P["slab"], "top"))


# ================================================================ the stair and the fittings

def spiral(s, level):
    """The newel stair: one step a block, eight to the turn, round a stone newel.

    Each step clears the two cells above it, which is how the stair punches its own holes through
    the deck and the floors it passes - and because the ring is eight and the storeys are five,
    three and four blocks apart, step 5 lands on the deck, step 8 on the chamber floor and step 12
    on the fighting top. Nothing here is a ladder: MineColonies will path a stair.
    """
    top = STEPS[level]
    for i in range(top):
        y = 1 + i
        x, z = RING[i % 8]
        for cy in (y + 1, y + 2):
            s.set(x, cy, z, None)
        s.set(x, y, z, stairs(P["stair"], RING_FACING[i % 8]))
    for y in range(GROUND_Y, 1 + top + 2):
        s.set(NEWEL[0], y, NEWEL[1], wall_post(P["newel"]))


def fittings(s, level):
    """The hut block, the bunks, the stores and the lights."""
    ix0, iz0, ix1, iz1 = interior(CORE)
    s.set_anchor(*HUT_POS, HUT)
    for hx, z in BUNKS[:CREW[level]]:
        facing = "west" if hx < 0 else "east"
        fx = hx + (1 if hx < 0 else -1)
        s.set(fx, 1, z, bed(P["bed"], facing, "foot"))
        s.set(hx, 1, z, bed(P["bed"], facing, "head"))
        s.tag(fx, 1, z, "bed")
    s.set(ix1, 1, iz1, RACK)
    s.set(ix1 - 1, 1, iz1, chest("north"))
    if level >= 3:
        s.set(ix0, 1, iz1, RACK)
    if level >= 2:
        armour_stand(s, ix0 + 1, 1, iz1, "north")
    for dx, dz in ((-2, 3), (2, -3)):                   # lamps hung off the deck beams
        s.set(dx, ROOM_TOP, dz, lantern(P["lamp"], hanging=True))
    if level >= 4:
        s.set(0, WALK[1], CORE[1] + 1, chain())
        s.set(0, WALK[0], CORE[1] + 1, lantern(P["lamp"], hanging=True))


def build(level):
    """One level of the Wall Tower, drawn around the same anchor as every other level."""
    s = Structure(f"walltower{level}")
    footing(s, level)
    ground_room(s, level)
    deck(s, level)
    walk_level(s, level)
    upper(s, level)
    spiral(s, level)
    fittings(s, level)
    s.tag(*s.anchor, "name=Wall Tower")
    return s


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        print(f"{st.name:12s} {str(st.size()):16s} {len(st.blocks):5d} blocks  "
              f"{CREW[lv]} crew  anchor {st.anchor}")
