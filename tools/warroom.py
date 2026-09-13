"""The War Room - a great hall with a garrison in its galleries. Levels 1 to 5.

x east, z south, front is SOUTH (+z): the great door opens onto the muster yard. y = 0 is the
ground course and the hall's floor; a citizen stands at y = 1.

THE SECTION, which is what makes it a hall and not a shed:

        |<-- aisle -->|<------ nave ------>|<-- aisle -->|
   x =  -6  -5  -4    -3   -2 .. 2    3     4   5   6
        bed bed walk  arcade   open    arcade  walk bed bed

  The nave runs the length of the hall, open from the floor to the ridge - eleven blocks of air
  over the map table. The aisles are two bays of bunks either side of it, behind an arcade of
  stone columns, and from level 4 there is a GALLERY over them: a second floor of bunks that
  looks down into the nave over a rail, reached by a real stair and crossed at the north end by
  a bridge behind the hut block.

THE CONSTRAINT THAT SHAPES IT. BuildingWarRoom.garrison is 2, 4, 6, 9, 12 and the building carries
MineColonies' BED module, so it owes that many REAL beds, every one of them walkable. The bunk
files - the two columns against each long wall, in four bed rows - hold bunks and nothing else,
upstairs and down. Six fit in the aisles; the other six live on the gallery, which is why level 4
is where the hall grows its second floor.

THE PIECES THAT MAKE IT WORTH LOOKING AT.

  the dais       the map table stands a step up in the middle of the nave, with a lantern on a
                 chain over it and candles on it. Everything in the room points at it.
  the arcade     stone columns with dark oak beams across them, carrying the gallery.
  banners        down both long walls, between the columns.
  braziers       fire in iron baskets flanking the dais and at the yard gate - warm light, no
                 glowstone anywhere in the building.
  the porch      two piers and the roof carried out over the great door.
  the yard       at level 5, a muster yard walled in the mod's own stone_rampart and parapet,
                 with a three-wide gate, gate piers and the colony's standard on a pole.

THE LEVELS.

  1  the nave alone: a rough hall in cobble and timber, the table on its step, two bunks. It is
     the same building - same door, same axis, same table - with none of it finished.
  2  the aisles and the arcade go up in dressed stone, the roof is tiled, the porch is built:
     four bunks.
  3  six bunks, the weapon wall behind the hut block, banners down the walls, the braziers lit.
  4  the gallery: a second floor over both aisles, a stair up to it and a bridge across the
     north end. Nine bunks.
  5  twelve bunks, and outside the door the muster yard with its gate and its standard.
"""
from voxel import Structure

import build_pack as bp
from pieces import (PARAPET, RACK, RAMPART, armour_stand, banner, bed, box_walls, campfire,
                    candle, chain, chest, connected, door, flight, floor, interior, item_frame,
                    lantern, log, slab, speck, stairs, wall_banner, wall_post)

HUT = "colonies_at_war:blockhutwarroom[facing=north]"

# The shared box: big, because the hall is meant to be. The muster yard is inside it too.
BOX = ((-8, 0, -10), (8, 12, 8))

HALL = (-7, -9, 7, 0)       # the full hall from level 2: outer walls inclusive, 15 x 10
CORE = (-4, -9, 4, 0)       # level 1 builds the nave alone, on the same axis and the same door
YARD = (-7, 1, 7, 8)        # the muster yard, level 5
GROUND_Y = 0
ROOM_TOP = {1: 4, 2: 4, 3: 4, 4: 6, 5: 6}   # the hall walls; the gallery needs two more courses
GALLERY_Y = 4               # the gallery floor (level 4 and up)
RIDGE = (-5, -4)            # the roof ridge rows; the roof slopes north and south off them
DAIS = (-2, -6, 2, -4)      # the step the map table stands on
TABLE = (-1, -6, 1, -5)
HUT_POS = (0, 1, -8)        # at the head of the hall, behind the dais, looking down the nave
DOOR_X = 0
ARCADE_X = (-3, 3)          # the columns, and the line the gallery rail stands on
ARCADE_Z = (-7, -5, -3)     # every other bay
BED_ROWS = (-8, -6, -4, -2)  # the bays between the columns; z = -1 is left for the stair
STAIR = ((1, -1), (2, -1), (3, -1))          # the flight up to the gallery, arriving at x = 4
BRIDGE_Z = -8               # the gallery crosses the nave here, behind the hut block

GARRISON = {1: 2, 2: 4, 3: 6, 4: 9, 5: 12}   # BuildingWarRoom.garrison - and that many beds
GROUND_BEDS = {1: 2, 2: 4, 3: 6, 4: 6, 5: 6}

P = {
    "wall":    "minecraft:stone_bricks",
    "wall2":   "minecraft:cracked_stone_bricks",
    "wall3":   "minecraft:mossy_stone_bricks",
    "rough":   "minecraft:cobblestone",
    "rough2":  "minecraft:mossy_cobblestone",
    "quoin":   "minecraft:chiseled_stone_bricks",
    "column":  "minecraft:stone_brick_wall",
    "floor":   "minecraft:polished_andesite",
    "floor2":  "minecraft:andesite",
    "nave":    "minecraft:polished_diorite",
    "dais":    "minecraft:polished_blackstone",
    "dais_st": "minecraft:polished_blackstone_stairs",
    "stair":   "minecraft:stone_brick_stairs",
    "slab":    "minecraft:stone_brick_slab",
    "beam":    "minecraft:dark_oak_log",
    "plank":   "minecraft:dark_oak_planks",
    "shed":    "minecraft:dark_oak_stairs",
    "table":   "minecraft:dark_oak_slab",
    "fence":   "minecraft:dark_oak_fence",
    "door":    "minecraft:dark_oak_door",
    "glass":   "minecraft:glass_pane",
    "lamp":    "minecraft:lantern",
    "bed":     "minecraft:red_bed",
    "flag":    "red",
    "blade":   "minecraft:iron_sword",
    "map":     "minecraft:map",
    "yard":    "minecraft:gravel",
    "paving":  "minecraft:cobblestone",
    # the roof is Domum Ornamentum shingle on boards from level 2 - the mix-and-match block
    # Voyager already proves in game, with the materials its own blueprints use
    "shingle": "domum_ornamentum:shingle",
    "m_roof":  "minecraft:deepslate_tiles",
    "m_sup":   "minecraft:spruce_planks",
}


def stone(x, y, z):
    if speck(x, y, z, 15):
        return P["wall3"]
    return P["wall2"] if speck(x, y, z, 9) else P["wall"]


def rough(x, y, z):
    return P["rough2"] if speck(x, y, z, 5) else P["rough"]


def rect(level):
    return CORE if level == 1 else HALL


def roof_y(z, base):
    """A gable with its ridge running east-west down the middle of the hall, one course per row
    from the eave in. It depends on z alone, so level 1's narrow hall and the full one share it."""
    return base + max(0, min(z + 10, 1 - z))


def base_y(level):
    return ROOM_TOP[level] + 1


# ================================================================ the shell

def shell(s, level):
    """Floor, walls, door, windows, the gable ends, the roof and the trusses under it."""
    r = rect(level)
    x0, z0, x1, z1 = r
    top = ROOM_TOP[level]
    face = rough if level == 1 else stone
    floor(s, r, GROUND_Y, lambda x, z: P["wall"] if x in (x0, x1) or z in (z0, z1)
          else (P["nave"] if abs(x) <= 2 else (P["floor2"] if speck(x, 0, z, 6) else P["floor"])))
    box_walls(s, r, 1, top, face, corner=P["quoin"])
    for y in range(1, top + 1):                          # a timber post every four blocks of wall
        for x in range(x0 + 3, x1, 4):
            for z in (z0, z1):
                s.set(x, y, z, log(P["beam"], "y"))

    great_door(s, level)
    if level >= 2:
        for wz in (-7, -5, -3):                          # windows down both long walls
            for wx in (x0, x1):
                window(s, wx, 2, wz, "north", "south")
        for wx in (x0 + 2, x1 - 2):
            window(s, wx, 2, z0, "east", "west")
    if level >= 4:
        for wz in (-7, -5, -3):                          # and the gallery gets its own
            for wx in (x0, x1):
                window(s, wx, 5, wz, "north", "south")

    base = base_y(level)
    for x in (x0, x1):                                   # the gable ends, up under the roof
        for z in range(z0, z1 + 1):
            for y in range(base, roof_y(z, base)):
                s.set(x, y, z, face(x, y, z))
    for z in RIDGE:                                      # a light in each gable, up in the peak
        for x in (x0, x1):
            s.set(x, base + 3, z, connected(P["glass"], "north", "south"))
    roof(s, level)
    trusses(s, level)


def window(s, x, y, z, *dirs):
    s.set(x, y, z, connected(P["glass"], *dirs))
    s.set(x, y + 1, z, connected(P["glass"], *dirs))


def great_door(s, level):
    """The door the colony marches out of: a double door in a dressed surround."""
    r = rect(level)
    _x0, _z0, _x1, z1 = r
    for dx, hinge in ((0, "left"), (-1, "right")):
        s.set(DOOR_X + dx, 1, z1, door(P["door"], "south", "lower", hinge))
        s.set(DOOR_X + dx, 2, z1, door(P["door"], "south", "upper", hinge))
    for dx in (-2, 1):
        for y in (1, 2, 3):
            s.set(DOOR_X + dx, y, z1, P["quoin"])
    s.set(DOOR_X - 1, 3, z1, P["quoin"])
    s.set(DOOR_X, 3, z1, P["quoin"])


def roof(s, level):
    """Dark tiles on boards, laid as stairs with a slab along the ridge."""
    r = rect(level)
    x0, z0, x1, z1 = r
    base = base_y(level)
    for z in range(z0 - 1, z1 + 2):
        y = roof_y(z, base)
        for x in range(x0 - 1, x1 + 2):
            if z in RIDGE:
                # BOTTOM, not top. The ridge row sits one course above its neighbours, so a top
                # slab here floats: it fills y+0.5..y+1.0 and leaves the half block under it empty,
                # a slot running the whole length of the ridge that reads as the cap hovering over
                # the roof. A bottom slab lands flush on the stairs below it, which is a ridge.
                if level == 1:
                    s.set(x, y, z, slab(P["table"], "bottom"))
                else:
                    s.mixed(x, y, z, slab("domum_ornamentum:vanilla_slab_compat", "bottom"), P["m_roof"])
            elif level == 1:
                s.set(x, y, z, stairs(P["shed"], "south" if z < RIDGE[0] else "north"))
            else:
                s.mixed(x, y, z, stairs(P["shingle"], "south" if z < RIDGE[0] else "north"),
                        P["m_roof"], P["m_sup"])


def trusses(s, level):
    """Tie beams across the hall at the eaves line, on the columns - what the roof is held by and
    what the lamps hang from in a hall with no ceiling."""
    r = rect(level)
    x0, _z0, x1, _z1 = r
    base = base_y(level)
    for z in ARCADE_Z:
        for x in range(x0 + 1, x1):
            s.set(x, base, z, log(P["beam"], "x"))
        for x in ARCADE_X:
            if x0 < x < x1:
                s.set(x, base - 1, z, log(P["beam"], "y"))   # the post the truss stands on


def porch(s, level):
    """Two piers and the roof carried out over the great door."""
    if level < 2:
        return
    base = base_y(level)
    for x in range(-3, 4):
        for z in (1, 2):
            s.set(x, GROUND_Y, z, P["paving"] if speck(x, 0, z, 3) else P["floor2"])
        s.mixed(x, base, 2, stairs(P["shingle"], "north"), P["m_roof"], P["m_sup"])
    for px in (-2, 2):
        for y in range(1, base):
            s.set(px, y, 2, log(P["beam"], "y"))
        s.set(px, base - 1, 1, lantern(P["lamp"], hanging=True))


# ================================================================ the inside

def arcade(s, level):
    """The columns down both sides of the nave, with their capitals."""
    if level < 2:
        return
    top = ROOM_TOP[level]
    for x in ARCADE_X:
        for z in ARCADE_Z:
            for y in range(1, top if level >= 4 else top + 1):
                s.set(x, y, z, wall_post(P["column"]))
            if level < 4:
                s.set(x, top, z, P["quoin"])


def gallery(s, level):
    """Level 4: a floor over both aisles, a rail along its edge, a stair up and a bridge across.

    The rail is left out at the two squares that matter - where the stair arrives and where the
    bridge leaves - because a rail across the only way off a landing is a fence, not a rail.
    """
    if level < 4:
        return
    x0, z0, x1, z1 = HALL
    ix0, iz0, ix1, iz1 = interior(HALL)
    for z in range(iz0, iz1 + 1):
        for x in list(range(ix0, ARCADE_X[0] + 1)) + list(range(ARCADE_X[1], ix1 + 1)):
            s.set(x, GALLERY_Y, z, P["plank"] if abs(x) < 6 else P["beam"])
    for x in range(ARCADE_X[0] + 1, ARCADE_X[1]):        # the bridge behind the hut block
        s.set(x, GALLERY_Y, BRIDGE_Z, P["plank"])

    for x in ARCADE_X:
        for z in range(iz0, iz1 + 1):
            if z in ARCADE_Z:
                for y in (GALLERY_Y + 1, GALLERY_Y + 2):
                    s.set(x, y, z, log(P["beam"], "y"))   # the upper order of the arcade
            elif z not in (BRIDGE_Z, iz1):
                s.set(x, GALLERY_Y + 1, z, connected(P["fence"], "north", "south"))

    flight(s, STAIR, 1, lambda i: stairs(P["stair"], "east"), clear_to=GALLERY_Y,
           hole=lambda x, y, z: s.set(x, y, z, None))


def dais(s, level):
    """The step the hall is built round, and the table on it."""
    x0, z0, x1, z1 = DAIS
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            s.set(x, 1, z, P["dais"])
    for x in range(x0, x1 + 1):                          # dressed with a step on the open sides
        s.set(x, 1, z1, stairs(P["dais_st"], "north"))
    for z in range(z0, z1):
        s.set(x0, 1, z, stairs(P["dais_st"], "east"))
        s.set(x1, 1, z, stairs(P["dais_st"], "west"))

    tx0, tz0, tx1, tz1 = TABLE
    for x in range(tx0, tx1 + 1):
        for z in range(tz0, tz1 + 1):
            s.set(x, 2, z, slab(P["table"], "top"))
    for i, (x, z) in enumerate(((0, tz0), (tx0, tz1), (tx1, tz0))[:min(level, 3)]):
        item_frame(s, x, 3, z, "up", P["map"], rotation=(i * 2) % 8)
    s.set(tx1, 3, tz1, candle(candles=2))
    if level >= 3:
        s.set(tx0, 3, tz0, candle(candles=3))

    # the lantern over the table, on a chain off the truss - and the truss is at ARCADE_Z, so
    # the chandelier hangs where there is something to hang it from
    base = base_y(level)
    hang_z = ARCADE_Z[1]
    for y in range(4, base):
        s.set(0, y, hang_z, chain())
    s.set(0, 3, hang_z, lantern(P["lamp"], hanging=True))


def brazier(s, x, z):
    """Fire in an iron basket: the only light in this building that is not a flame or a lantern."""
    s.set(x, 1, z, wall_post(P["column"]))
    s.set(x, 2, z, campfire())


def bunk_slots(level):
    """Every square a bunk may stand in, in the order they fill. Nothing else goes in these."""
    r = rect(level)
    ix0, _iz0, ix1, _iz1 = interior(r)
    slots = []
    for z in BED_ROWS:
        slots.append((ix0, z, "west"))
        slots.append((ix1, z, "east"))
    return slots


def bunks(s, level, y, n):
    for hx, z, facing in bunk_slots(level)[:n]:
        fx = hx + (1 if facing == "west" else -1)
        s.set(fx, y, z, bed(P["bed"], facing, "foot"))
        s.set(hx, y, z, bed(P["bed"], facing, "head"))
        s.tag(fx, y, z, "bed")


def fittings(s, level):
    """The hut block, the bunks, the weapon wall, the banners and the lights."""
    r = rect(level)
    x0, z0, x1, z1 = r
    ix0, iz0, ix1, iz1 = interior(r)
    s.set_anchor(*HUT_POS, HUT)
    bunks(s, level, 1, GROUND_BEDS[level])
    if level >= 4:
        bunks(s, level, GALLERY_Y + 1, GARRISON[level] - GROUND_BEDS[level])
    dais(s, level)

    # The head of the hall, behind the dais: stores at first, the weapon wall later. Everything
    # here keeps to x = -2..2, because at level 1 the hall is only seven squares wide inside and
    # the two bunks take the ends of this very row.
    s.set(-1, 1, iz0, RACK)
    s.set(1, 1, iz0, chest("south"))
    if level >= 3:
        for x in (-2, 2):
            s.set(x, 1, iz0, RACK)
            item_frame(s, x, 2, iz0, "south", P["blade"])
        armour_stand(s, -2, 1, iz0 + 1, "south")
        armour_stand(s, 2, 1, iz0 + 1, "south")
    # the braziers stand in the nave at the foot of the dais - never on the arcade line, which
    # carries the gallery and must not turn into a column with a fire where its middle was
    brazier(s, DAIS[0], DAIS[3] + 1)
    if level >= 3:
        brazier(s, DAIS[2], DAIS[3] + 1)

    # banners between the columns, down both long walls
    if level >= 3:
        for z in BED_ROWS[1:]:
            s.set(x0 + 1, 3, z, wall_banner(P["flag"], "east"))
            s.set(x1 - 1, 3, z, wall_banner(P["flag"], "west"))

    # lamps: hung off the trusses in the nave, and under the gallery in the aisles
    base = base_y(level)
    for z in ARCADE_Z:                                   # lamps hung off the trusses, over the nave
        for x in (-2, 2):
            s.set(x, base - 1, z, lantern(P["lamp"], hanging=True))
    if level >= 4:
        for z in (-7, -3):
            for x in (ix0 + 2, ix1 - 2):
                s.set(x, GALLERY_Y - 1, z, lantern(P["lamp"], hanging=True))


def muster_yard(s):
    """Level 5: the ground the garrison forms up on, walled in the mod's own pieces."""
    x0, z0, x1, z1 = YARD
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if s.get(x, GROUND_Y, z) is None:
                s.set(x, GROUND_Y, z, P["paving"] if speck(x, 0, z, 4) else P["yard"])
    gate = (-1, 0, 1)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if not (x in (x0, x1) or z == z1):
                continue                                 # the north side is the hall itself
            if z == z1 and x in gate:
                continue
            s.set(x, 1, z, RAMPART)
            s.set(x, 2, z, PARAPET)
    for x in (gate[0] - 1, gate[-1] + 1):                # gate piers, a course taller
        s.set(x, 1, z1, RAMPART)
        s.set(x, 2, z1, RAMPART)
        s.set(x, 3, z1, PARAPET)
        s.set(x, 4, z1, lantern(P["lamp"]))               # a lamp on each gatepost
    brazier(s, gate[0] - 2, z1 - 1)
    brazier(s, gate[-1] + 2, z1 - 1)

    px, pz = 0, (z0 + z1) // 2 + 1                       # the standard, in the middle of the yard
    s.set(px, GROUND_Y, pz, P["quoin"])
    for y in range(1, 6):
        s.set(px, y, pz, connected(P["fence"]))
    s.set(px, 6, pz, banner(P["flag"]))
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        s.set(px + dx, GROUND_Y, pz + dz, P["paving"])


def build(level):
    """One level of the War Room, drawn around the same anchor as every other level."""
    s = Structure(f"warroom{level}")
    shell(s, level)
    porch(s, level)
    arcade(s, level)
    gallery(s, level)
    fittings(s, level)
    if level >= 5:
        muster_yard(s)
    s.tag(*s.anchor, "name=War Room")
    return s


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        beds = sum(1 for t in st.tags.values() if "bed" in t)
        print(f"{st.name:10s} {str(st.size()):16s} {len(st.blocks):5d} blocks  "
              f"{beds}/{GARRISON[lv]} beds  anchor {st.anchor}")
