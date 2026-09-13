"""The wall segment - eight blocks of curtain wall, and the section every other piece is cut from.

x east, z south. The run goes EAST, the colony is to the SOUTH: +z is inside, -z is the field.
y = 0 is the ground course; the wall stands on it and a citizen on the walk stands at y = 6.

WHY THE SECTION IS NOT NEGOTIABLE. The Wall Tower already exists, and it ends in a stub: at
x = +-6 and +-7 it lays rampart at z = -1, 0, +1 from y = 1 to y = 5, parapet at z = +-1 on y = 6,
and leaves z = 0 open at y = 6 and 7 (walltower.deck). That stub IS the interface. A wall that is
thicker than it leaves a notch at every tower; a wall whose walk is a course out leaves a step a
guard has to jump. So the body of this wall is three wide, z = -1 .. +1, at every level, the walk
is the single line z = 0, and the walk is the top of the course at DECK_Y - all three taken from
walltower rather than written down again, so the day somebody moves the tower's deck this file
moves with it or the checks shout.

    outer face  z = -1    what an attacker sees: crenellated from level 4, machicolated at 5
    the walk    z =  0    rampart underfoot, two clear cells above - which is exactly what
                          BuildingWallTower.walkAbove looks for before it lays a patrol point
    inner face  z = +1    the rail, until level 4 opens it as a second lane
    z = -2                the batter, the buttresses, and at level 5 the machicoulis
    z = +2                nothing, until level 4 corbels a banquette out over it

EIGHT BLOCKS, and the number is not arbitrary. BuildingWallTower.SPACING is 8: the tower drops one
patrol point every eight blocks of run, so one segment is exactly one stride of the patrol and a
wall of n segments is a wall of n patrol points. Eight is also two to a chunk, which is how a
player paces a ring out; and every ornament here repeats on 2, on 4 or on 8, every one of which
divides 8, so the merlons, the buttress piers, the covered bay, the lamps and the colours all
carry across a joint without a stutter no matter how many copies are laid. A shorter piece would cost the colony a build order every four blocks; a
longer one would not turn a corner without a gap.

WHAT THE LEVELS BUY, and they are not decoration - WallRegister.measure adds the weight of every
wall block in a column, keeps each bearing's TALLEST column, caps it at six, and multiplies by the
tier. So the curve below is a curve in the number:

  1  a palisade         stakes four high on an earth bank, no walk.      tallest column 4.35, tier 1
  2  a palisade walk    the stockade goes to five and a timber gallery
                        runs behind it at the tower's own walk height.   5.35, tier 1
  3  stone              the same section in rampart, solid, parapet
                        both sides, buttressed and corniced.             5.35, tier 2
  4  stone with depth   a battered footing, merlons and embrasures
                        instead of a plain rail, and the walk widened
                        to two lanes on a corbelled banquette.           7 -> capped 6, tier 2
  5  fortified          top tier throughout, the parapet carried out
                        on corbels with the holes left open between
                        them, and a covered fighting bay once a bay.     7 -> capped 6, tier 3

A closed ring of these scores 85, 97, 100, 100, 100; an open one at four fifths coverage scores
55, 62, 69, 73, 80. Level 1 is worth building and level 5 is worth besieging, which is the point.

THE THINGS THAT MAKE IT A WALL RATHER THAN AN EXTRUSION.

  the batter      from level 4 a plinth and a course of stairs leaning in, the same idiom the
                  tower's own footing uses - a wall that meets the ground at a right angle looks
                  dropped on it.
  the buttresses  a pier every four blocks on the outer face. At level 5 the pier is what the
                  machicoulis corbel stands on, so the ornament at the bottom explains the
                  ornament at the top.
  the banquette   level 4 does not widen the wall - it cannot, the tower's neck is three - so it
                  corbels a gallery out over the INSIDE, where nobody is shooting at it, and the
                  walk becomes two lanes with the rail moved out to z = +2.
  machicolation   level 5 carries the parapet a block clear of the face on corbels and leaves
                  every other cell of the projecting course out. Those gaps are the whole point:
                  they are the holes you drop things through, and they are why the level 5 face
                  reads as a fortress and the level 3 face reads as a garden wall.
  the covered bay once per segment the walk is roofed between two solid cheeks - a hourd. It is
                  "covered where it should be": over the two middle columns, where the bay lands
                  on the seam-free middle of the piece and repeats every eight blocks.

NOTHING HERE IS A BUILDING. There is no hut block: the anchor is minecolonies:decorationcontroller,
which is what MineColonies' own wall pieces (blueprints/minecolonies/nordic/walls/wall/*) are
anchored on, and BlockDecorationController.getLevel reads the level straight out of the digits of
the schematic name - so wallsegment1..5 upgrade in place the way a hut does.
"""
from voxel import Structure

import build_pack as bp
import walltower
from pieces import (F_PARAPET, F_RAMPART, PALISADE, PALISADE_PARAPET, PARAPET, RAMPART, lantern,
                    log, slab, speck, stairs, wall_banner)

# The decoration controller is the anchor of every piece here - see the docstring. facing=south
# because that is the way a player standing inside the colony looks at his own wall.
DECO = "minecolonies:decorationcontroller[facing=south,mirror=false,waterlogged=false]"

RUN = 8                         # blocks of wall in one piece - and one stride of the tower's patrol
GROUND_Y = 0
DECK_Y = walltower.DECK_Y       # 5: the top course of the body. The walk is its top face.
WALK = walltower.WALK           # (6, 7): the two cells a citizen occupies on the walk
HEAD_Y = 8                      # the highest course a straight run ever reaches

# the five lines of the section, outside to inside
TALUS, OUT, MID, IN, BERM = -2, -1, 0, 1, 2
BODY = (OUT, MID, IN)           # the three that are always wall

BOX = ((0, GROUND_Y, TALUS), (RUN - 1, HEAD_Y, BERM))
ANCHOR = (0, 1, BERM)           # in the lee of the wall at the west end, clear at every level

# what the two cutaway renders slice at, and which levels docs/renders keeps
ROOM_TOP = {1: 4, 2: 4, 3: 5, 4: 5, 5: 5}
RENDER_SPIN = True          # the pictures look at the outer face, which is the one that matters
RENDER_CELL = 18            # a wall piece is small; draw it big enough to read
RENDER_LEVELS = (1, 3, 5)   # 1, 3 and 5 - timber, stone, fortified; 2 and 4 are the same silhouette


def lanes(level):
    """How wide the walk is at this level, as the offsets a citizen may stand on.

    One lane to level 3 and two from level 4, and the first of them is always z = 0 because that
    is the lane the tower's stub carries. Said once, here, so the design and check_walls cannot
    disagree about it.
    """
    return (MID,) if level <= 3 else (MID, IN)


P = {
    # Level 1 and 2 are timber, and the timber is spruce because that is literally what the
    # palisade block is textured with (models/block/palisade.json -> minecraft:block/spruce_log).
    "beam":    "minecraft:spruce_log",
    "brace":   "minecraft:spruce_stairs",
    "plank":   "minecraft:spruce_planks",
    # Level 3 and 4 are stone_rampart, textured with stone_bricks over chiseled_stone_bricks, so
    # its dressing is the same family and nothing looks stuck on.
    "stone":   "minecraft:stone_bricks",
    "stone2":  "minecraft:cracked_stone_bricks",
    "stone3":  "minecraft:mossy_stone_bricks",
    "quoin":   "minecraft:chiseled_stone_bricks",
    "stair":   "minecraft:stone_brick_stairs",
    "slab":    "minecraft:stone_brick_slab",
    # Level 5 is fortified_rampart - deepslate_bricks over polished_deepslate.
    "fort":    "minecraft:deepslate_bricks",
    "fort2":   "minecraft:cracked_deepslate_bricks",
    "fortq":   "minecraft:chiseled_deepslate",
    "fortst":  "minecraft:deepslate_brick_stairs",
    "fortsl":  "minecraft:deepslate_brick_slab",
    "road":    "minecraft:cobblestone",
    "road2":   "minecraft:gravel",
    "lamp":    "minecraft:lantern",
    "flag":    "red",
}

# A stair has a direction; a corner has no unambiguous one. Where a piece turns, the stair is
# replaced by the block it is cut from - which is what a real corner does anyway: it is quoined,
# not chamfered.
SOLID_OF = {
    P["stair"]: P["stone"],
    P["fortst"]: P["fort"],
    P["brace"]: P["plank"],
}


def masonry(n, y, off):
    """Stone with the odd cracked or mossy block in it, so a face reads as laid rather than cast."""
    if speck(n, y, off, 13):
        return P["stone3"]
    return P["stone2"] if speck(n, y, off, 7) else P["stone"]


def fortified(n, y, off):
    return P["fort2"] if speck(n, y, off, 6) else P["fort"]


def tiers(level):
    """The mod's own pair for this level: the block the wall is made of, and the rail on top of it.

    The tier is the whole of the score that is not height (WallRegister.measure), so this is the
    one table that decides whether an upgrade was worth paying for.
    """
    if level <= 2:
        return PALISADE, PALISADE_PARAPET
    if level <= 4:
        return RAMPART, PARAPET
    return F_RAMPART, F_PARAPET


def bay(n):
    """The two columns of a segment the level 5 walk is roofed over.

    In the middle of the piece on purpose: a covered bay that straddled the joint would have to be
    built half by one blueprint and half by the next, and the first player to build only one of
    them would have half a roof.
    """
    return n % RUN in (3, 4)


# ================================================================ the section

def section(n, off, y, level, ins="south", outs="north", quoin=False, bays=True):
    """What a plain column of curtain wall has at cross-offset `off` and height `y`.

    `n` is how far along the run this column is - every repeating ornament is a function of it and
    of nothing else, which is what makes a piece tile with its own copy. `ins`/`outs` say which
    compass direction is into the colony and which is out, because the corner runs this same
    section twice at ninety degrees to itself; `quoin` is the corner telling it that this column
    is on the turn and may not use a directional block; `bays` is the corner and the gate saying
    they roof their own walk and do not want the straight run's covered bay landing in the middle
    of a turn or a gateway.

    Returns a block state, or None for air.
    """
    body, rail = tiers(level)
    roofed = bays and bay(n)

    def st(block, towards, half="bottom"):
        if quoin:
            return SOLID_OF.get(block, block)
        return stairs(block, ins if towards == "in" else outs, half)

    # ---------------------------------------------------------- 1: the palisade
    # Stakes on an earth bank, and the bank is stepped - four courses at the face, three in the
    # middle, two at the back. A wall that is the same height all the way through its thickness is
    # a fence; this is a rampart with a stockade on it, which is what a young colony actually digs.
    if level == 1:
        if off == OUT:
            if 1 <= y <= 4:
                return body
            if y == 5 and n % 2 == 0:
                return rail                       # sharpened tops, every other stake
        elif off == MID:
            if 1 <= y <= 3:
                return body
        elif off == IN:
            if 1 <= y <= 2:
                return body
            if y == 3 and n % 4 == 1:
                return st(P["brace"], "out")      # a shore raking up against the bank
            if y == 3 and n % RUN == 6:
                return lantern(P["lamp"])
        return None

    # ---------------------------------------------------------- 2: the timber walk
    # The stockade goes up to five so its walk lands at the tower's height, and the walk itself is
    # a gallery on posts rather than a solid bank: cheaper, and it leaves a covered way behind the
    # stakes at y = 3 that a citizen can actually use.
    if level == 2:
        if off == OUT:
            if 1 <= y <= DECK_Y:
                return body
            if y == 6:
                return rail                       # the breastwork
            if y == 7 and n % 4 == 0:
                return log(P["beam"])             # a stake standing proud of it
        elif off == MID:
            if 1 <= y <= 2:
                return body                       # what is left of level 1's bank
            if y == DECK_Y:
                return body                       # the walk: a rampart, so a patrol lands on it
        elif off == IN:
            if 1 <= y <= 2:
                return body
            if y in (3, 4) and n % 2 == 0:
                return log(P["beam"])             # the gallery posts
            if y == 4 and n % 2 == 1:
                return st(P["brace"], "in", "top")   # knee braces between them
            if y == DECK_Y:
                return body
            if y == 6:
                return rail
            if y == 7 and n % RUN == 1:
                return lantern(P["lamp"])
        return None

    # ---------------------------------------------------------- 3, 4, 5: stone
    stone = fortified if level >= 5 else masonry
    stair = P["fortst"] if level >= 5 else P["stair"]
    flat = P["fortsl"] if level >= 5 else P["slab"]

    if off in BODY and 1 <= y <= DECK_Y:
        return body                               # the body: solid, three wide, five courses

    if off == TALUS:
        pier = n % 4 == 2
        if pier and 1 <= y <= 4:
            return stone(n, y, off)               # the buttress
        if level >= 4:
            if y == 1:
                return stone(n, y, off)           # the plinth
            if y == 2:
                return st(stair, "in")            # the batter, leaning in to the wall
        if y == DECK_Y:
            if level == 5 and n % 2 == 0:
                return st(stair, "in", "top")     # the machicoulis corbel
            if level < 5:
                return st(stair, "in") if pier else slab(flat, "top")   # weathering, or cornice
        if level == 5 and n % 2 == 0 and y in (6, 7):
            # the projecting course and the outer half of its merlon. The odd columns are left
            # empty on purpose - those gaps ARE the machicolation.
            return body
        return None

    if off == OUT:
        if level == 3 and y == 6:
            return rail
        if level == 4:
            if y == 6:
                return body if n % 2 == 0 else rail       # merlon / embrasure
            if y == 7 and n % 2 == 0:
                return body
        if level == 5:
            if y == 6:
                return body                               # the wall head, solid at the top tier
            if y == 7:
                return body if (n % 2 == 0 or roofed) else rail
            if y == HEAD_Y and roofed:
                return slab(flat, "bottom")               # the covered bay's roof
        return None

    if off == MID:
        if level == 5 and y == HEAD_Y and roofed:
            return slab(flat, "bottom")
        return None                                       # everything else here is the walk

    if off == IN:
        if level == 3 and y == 6:
            return rail
        if level == 3 and y == 7 and n % RUN == 1:
            return lantern(P["lamp"])
        if level == 5 and y == HEAD_Y and roofed:
            return slab(flat, "bottom")
        return None                                       # from level 4 this is the second lane

    if off == BERM:
        if level == 3:
            # Level 3 puts nothing on the inside at all. That is deliberate: it leaves the whole
            # inner face for level 4 to corbel its banquette off, so the upgrade a player pays for
            # is visible from the one side he ever looks at his own wall from.
            return None
        if y == 4:
            return st(stair, "in", "top")                 # the corbel the banquette stands on
        if y == DECK_Y:
            return body                                   # the banquette itself
        if y == 6:
            return rail                                   # and its rail, out of the walk's way
        if y == 7:
            if level == 5 and roofed:
                return body                               # the covered bay's inner cheek
            if n % RUN == 1:
                return lantern(P["lamp"])
        if level == 5 and y == HEAD_Y and roofed:
            return slab(flat, "bottom")
    return None


# ================================================================ drawing it

def column(s, level, n, at, quoin=False, ins="south", outs="north", skip=(), bays=True,
           top=HEAD_Y):
    """Draw one column of curtain: every offset of the section at run-position `n`.

    `at(off)` says where in the world this offset lands, which is the whole of the difference
    between a straight run and a corner. `skip` is a set of (off, y) a piece has cut away - the
    gate's passage, and nothing else so far.
    """
    for off in range(TALUS, BERM + 1):
        x, z = at(off)
        foot = False
        for y in range(1, top + 1):
            if (off, y) in skip:
                continue
            b = section(n, off, y, level, ins, outs, quoin, bays)
            if b is None:
                continue
            s.set(x, y, z, b)
            if y == 1:
                foot = True
        if foot:
            # A column that starts on the ground is given ground to start on - solid substitution,
            # "a block of whatever this terrain is", exactly as the tower's stub does it. Anything
            # this piece does not use stays untouched (build_war.pad_ground).
            s.set(x, GROUND_Y, z, bp.GROUND)


def anchor(s, pos=ANCHOR, name="Wall Segment"):
    """The decoration controller, and the ground under it.

    It is not a hut block and this is not a building: MineColonies builds and upgrades a decoration
    from this one block, and reads the level out of the schematic name's digits. It stands in the
    lee of the wall where a player can reach it at every level - which is the reason nothing in
    the section is ever drawn at z = +2 below y = 4.
    """
    s.set(pos[0], GROUND_Y, pos[2], bp.GROUND)
    s.set_anchor(*pos, DECO)
    s.tag(*pos, "name=" + name)


def build(level):
    """One level of the wall segment, drawn around the same anchor as every other level."""
    s = Structure(f"wallsegment{level}")
    for n in range(RUN):
        column(s, level, n, lambda off, n=n: (n, off))
    banners(s, level)
    anchor(s)
    return s


def banners(s, level):
    """The colony's colours on the outer face, from the level the wall is worth flying them from."""
    if level < 4:
        return
    # mid-bay, clear of the buttress piers at n = 2 and 6, and at level 5 directly under the
    # machicoulis - which is where colours hang on a real wall head
    s.set(4, 4, TALUS, wall_banner(P["flag"], "north"))


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        wall = sum(1 for b in st.blocks.values() if b.startswith("colonies_at_war:"))
        print(f"{st.name:14s} {str(st.size()):14s} {len(st.blocks):4d} blocks  {wall:3d} wall  "
              f"anchor {st.anchor}")
