"""Block states, wall pieces and the small shapes both buildings are made of.

The block-state helpers are the ones the Voyager and Colonist Thieves tools keep in their design
modules, pulled out here because this repository has two buildings that want the same ones.

THE MOD'S OWN BLOCKS. A rampart is an ordinary full block and a parapet is the same block cut down
to ten sixteenths (ParapetBlock), and neither carries a single block-state property - so they are
written as bare ids and voxel.py needs to know nothing about them. What matters for the designs is
what they are FOR:

  rampart   the body of the wall and the surface its walk is on. BuildingWallTower.layPatrol lays
            a patrol point on a rampart that has two clear cells above it, so a rampart under open
            air is what a guard actually walks. Its walk is the thing a tower has to carry through.
  parapet   knee-high on purpose (see ParapetBlock's javadoc): what a guard stands behind, never
            what he stands on, and never laid where the walk should run.

Tiers: palisade (1), stone (2), fortified (3), each with its parapet. The tower is stone until
level 5 and fortified at the top, which is the one upgrade a besieger would notice; the wall
pieces climb all three, which is where most of the colony's score comes from.

The palisade pair is the only one the tower never uses - a tower is masonry from the day it is
built - so it lives here for the wall segment, corner and gate, whose first two levels are timber.
"""

# ---------------------------------------------------------------- the mod's own wall pieces
RAMPART = "colonies_at_war:stone_rampart"
PARAPET = "colonies_at_war:stone_parapet"
F_RAMPART = "colonies_at_war:fortified_rampart"
F_PARAPET = "colonies_at_war:fortified_parapet"
PALISADE = "colonies_at_war:palisade"
PALISADE_PARAPET = "colonies_at_war:palisade_parapet"

# MineColonies' own furniture
RACK = "minecolonies:blockminecoloniesrack"


# ---------------------------------------------------------------- block states

def stairs(block_id, facing, half="bottom"):
    return f"{block_id}[facing={facing},half={half},shape=straight,waterlogged=false]"


def slab(block_id, kind="bottom"):
    return f"{block_id}[type={kind},waterlogged=false]"


def door(block_id, facing, half, hinge="left"):
    return f"{block_id}[facing={facing},half={half},hinge={hinge},open=false,powered=false]"


def trapdoor(block_id, facing="north", half="top", open_=False):
    return (f"{block_id}[facing={facing},half={half},open={'true' if open_ else 'false'},"
            f"powered=false,waterlogged=false]")


def ladder(facing):
    """A ladder is held by the block BEHIND it: facing=north hangs on the wall to its south."""
    return f"minecraft:ladder[facing={facing},waterlogged=false]"


def lantern(kind="minecraft:lantern", hanging=False):
    return f"{kind}[hanging={'true' if hanging else 'false'},waterlogged=false]"


def connected(block_id, *dirs):
    """Fences, panes and bars: a post with the sides that join something set true."""
    props = {"north": "false", "south": "false", "east": "false", "west": "false", "waterlogged": "false"}
    for d in dirs:
        props[d] = "true"
    return block_id + "[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]"


def wall_post(block_id):
    return f"{block_id}[north=none,south=none,east=none,west=none,up=true,waterlogged=false]"


def bed(block, facing, part):
    """One half of a bed. The head sits at foot + facing, and both halves must agree on facing or
    Minecraft drops them."""
    return f"{block}[facing={facing},occupied=false,part={part}]"


def chest(facing="south"):
    return f"minecraft:chest[facing={facing},type=single,waterlogged=false]"


def banner(colour, rotation=8):
    """A standing banner on a pole or a block: rotation 8 faces south, the way a visitor sees it."""
    return f"minecraft:{colour}_banner[rotation={rotation}]"


def wall_banner(colour, facing="south"):
    return f"minecraft:{colour}_wall_banner[facing={facing}]"


def chain(axis="y"):
    return f"minecraft:chain[axis={axis},waterlogged=false]"


def candle(colour=None, candles=1, lit=True):
    name = f"minecraft:{colour}_candle" if colour else "minecraft:candle"
    return f"{name}[candles={candles},lit={'true' if lit else 'false'},waterlogged=false]"


def campfire(lit=True):
    return (f"minecraft:campfire[facing=north,lit={'true' if lit else 'false'},"
            f"signal_fire=false,waterlogged=false]")


def log(kind="minecraft:spruce_log", axis="y"):
    return f"{kind}[axis={axis}]"


# ---------------------------------------------------------------- shared shapes

def interior(rect):
    """The cells inside a rectangle of outer walls."""
    x0, z0, x1, z1 = rect
    return x0 + 1, z0 + 1, x1 - 1, z1 - 1


def speck(x, y, z, n=5):
    """A repeatable scatter, so the mossy blocks and the cracked ones land in the same places
    every time this runs - a render that was approved has to come back identical."""
    return (x * 7 + y * 13 + z * 11) % n == 0


def box_walls(s, rect, y0, y1, block, corner=None):
    """Four walls of a rectangle, with a different block at the four corners if one is given."""
    x0, z0, x1, z1 = rect
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if not (x in (x0, x1) or z in (z0, z1)):
                    continue
                if corner is not None and x in (x0, x1) and z in (z0, z1):
                    s.set(x, y, z, corner)
                else:
                    s.set(x, y, z, block(x, y, z) if callable(block) else block)


def floor(s, rect, y, block):
    x0, z0, x1, z1 = rect
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            s.set(x, y, z, block(x, z) if callable(block) else block)


def flight(s, cells, y0, step_block, clear_to, hole=None):
    """A straight flight of stairs a citizen can walk.

    `cells` are the (x, z) squares of the flight in the order they are climbed; the first gets its
    stair at y0, the next at y0+1 and so on. Feet land one above each stair, so the two cells above
    each stair have to be clear - `hole` is called with every (x, y, z) that has to be opened,
    which is how a flight punches its own hole through the floor it arrives at. `clear_to` is the
    highest y that could be in the way.
    """
    for i, (x, z) in enumerate(cells):
        y = y0 + i
        for cy in range(y + 1, min(clear_to, y + 2) + 1):
            if hole is not None:
                hole(x, cy, z)
        s.set(x, y, z, step_block(i) if callable(step_block) else step_block)


def item_frame(s, x, y, z, facing="south", item="minecraft:paper", rotation=0):
    """One item frame, as MineColonies' own blueprints carry them.

    An entity, not a block. It holds the block it hangs in as TileX/Y/Z as well as its position,
    because Minecraft refuses a hanging entity whose two disagree, and the builder asks the colony
    for the frame and for what is in it the way it does for MineColonies' own.
    """
    import nbtlib.tag as T
    facing3d = {"down": 0, "up": 1, "north": 2, "south": 3, "west": 4, "east": 5}
    yaw = {"south": 0.0, "west": 90.0, "north": 180.0, "east": 270.0, "up": 0.0, "down": 0.0}[facing]
    s.entity(x, y, z, "minecraft:item_frame", yaw=yaw, extra={
        "Facing": T.Byte(facing3d[facing]),
        "Item": T.Compound({"count": T.Int(1), "id": T.String(item)}),
        "ItemRotation": T.Byte(rotation),
        "ItemDropChance": T.Float(1.0),
        "Fixed": T.Byte(0),
        "Invisible": T.Byte(0),
        "__tile__": True,
    })


def armour_stand(s, x, y, z, facing="south"):
    """An armour stand, the way MineColonies' own blueprints decorate a guard building."""
    import nbtlib.tag as T
    yaw = {"south": 0.0, "west": 90.0, "north": 180.0, "east": 270.0}[facing]
    s.entity(x, y, z, "minecraft:armor_stand", yaw=yaw, extra={
        "Invisible": T.Byte(0),
        "NoBasePlate": T.Byte(0),
        "ShowArms": T.Byte(1),
        "Small": T.Byte(0),
    })
