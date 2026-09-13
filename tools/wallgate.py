"""The wall gate - the curtain pierced, and the only place on a wall a citizen crosses it.

The same eight blocks of run as a segment, the same section at both ends, and in the middle of it
a way through: a two-leaf door in a vaulted passage, two turrets standing out of the face either
side of it, and the wall walk carried straight over the top without a step.

TWO WIDE, and that is arithmetic rather than taste. The piece is eight long, so its centre falls
BETWEEN two columns; an odd opening cannot be centred on it and a gate that is not centred on its
own turrets is a hole in a wall. Two is also what a gate is - a pair of leaves that meet, which is
why MineColonies' own gate blueprints are two wide as well. The passage is three clear at both
mouths and the doors hang in the middle of the thickness with a tympanum over them, so from
outside you see an arch and not a doorframe.

IT MUST NOT BREAK THE RING, and this is the part that is easy to get wrong. WallRegister.measure
throws away any column with less than two blocks of wall in it - one course is a kerb, not a wall -
so a gateway that simply removes the wall over the road puts a hole in the colony's coverage at
the exact place an attacker would walk in. So the opening is paid for above:

  * the vault over the doors is wall block, not decoration - two courses of it at the centre line
  * the face over the arch is carried up two courses HIGHER than the curtain either side, which is
    what a gatehouse does anyway, and leaves every column of the bay at three or more
  * the turrets at x = 2 and x = 5 are full height from the ground

Every column of the gate therefore still counts, at every level. check_walls proves it with the
game's own arithmetic rather than by eye.

THE LEVELS, following the curtain's.

  1  a timber gate: the palisade steps over the opening on a lintel, two log posts flank it and
     a pair of spruce doors closes it. Nothing above - there is no walk yet to carry.
  2  the walk arrives: the deck runs over the vault and the posts become watch posts with a
     breastwork and a lamp on each.
  3  stone. The passage is properly arched, the face over it rises to the turrets' height, and the
     doors are heavy dark oak.
  4  the batter and the crenellation reach the gate, and a BRETECHE is thrown across between the
     turrets - a box of parapet standing clear of the face with the two cells under it left open.
     They are over the doors on purpose.
  5  fortified throughout, and the walk between the turrets is roofed. Of all the wall, the gate
     is where a covered fighting position earns its keep: it is the one stretch a man has to hold
     while something below him is trying to come through.

WHAT IT DOES NOT HAVE is a stair. A gate is not a way onto the wall - the Wall Tower's newel is,
and a wall without towers has no business having a gate. That is a piece for another day.
"""
from voxel import Structure

import build_pack as bp
import wallsegment as W
from pieces import door, lantern, log, slab, speck, stairs, wall_banner

PASS = (3, 4)                    # the two columns of the opening - the centre of an eight-long piece
PIER = (2, 5)                    # the turrets that flank it
BAY = (2, 3, 4, 5)               # the whole gate bay
ARCH_Y = 4                       # the head of the opening; the deck is the course above it
TOP_Y = 11                       # the tallest thing here: the lamp on a level 4 turret

# The top solid course of the gate's face over the bay, by level: two above the curtain's own
# head, so the gate reads as a gatehouse and every column of the bay still counts as wall.
HEAD = {1: 5, 2: 6, 3: 7, 4: 8, 5: 8}

BOX = ((0, W.GROUND_Y, W.TALUS), (W.RUN - 1, TOP_Y, W.BERM))
ANCHOR = (6, 1, W.BERM)          # inside, east of the gate, where a player walks up to it

ROOM_TOP = {1: 4, 2: 4, 3: 4, 4: 4, 5: 4}   # the cutaway: take the head off and see the passage
RENDER_SPIN = True          # the pictures look at the outer face, which is the one that matters
RENDER_CELL = 18            # a wall piece is small; draw it big enough to read
RENDER_LEVELS = (1, 5)

LEAF = {1: "minecraft:spruce_door", 2: "minecraft:spruce_door", 3: "minecraft:dark_oak_door",
        4: "minecraft:dark_oak_door", 5: "minecraft:dark_oak_door"}


def dress(level):
    """The stone (or timber) the gate's own work is built of, to match the level's wall block."""
    if level <= 2:
        return W.P["beam"], W.P["brace"], W.P["beam"]
    if level <= 4:
        return W.P["stone"], W.P["stair"], W.P["quoin"]
    return W.P["fort"], W.P["fortst"], W.P["fortq"]


def road(x, z):
    """The way through: cobbles where they are trodden, gravel where they are not."""
    return W.P["road"] if speck(x, 0, z, 3) else W.P["road2"]


def build(level):
    """One level of the wall gate, drawn around the same anchor as every other level."""
    s = Structure(f"wallgate{level}")
    body, rail = W.tiers(level)

    # ---- the curtain, exactly as a segment draws it, with the opening left out of it
    hole = {(off, y) for off in (W.TALUS, W.OUT, W.MID, W.IN) for y in (1, 2, 3)}
    for n in range(W.RUN):
        W.column(s, level, n, lambda off, n=n: (n, off), skip=hole if n in PASS else (),
                 bays=False)

    # the gate's own outer works replace whatever the plain section left on the batter line
    for x in PASS:
        for y in range(1, HEAD[level]):
            s.set(x, y, W.TALUS, None)

    jambs(s, level, body)
    passage(s, level, body)
    face(s, level, body, rail)
    turrets(s, level, rail)
    if level >= 4:
        breteche(s, body, rail)
    if level >= 5:
        covered(s, body)
    paving(s)
    W.anchor(s, ANCHOR, "Wall Gate")
    return s


def jambs(s, level, body):
    """The two piers the doors are hung between, solid through the whole thickness of the wall.

    At levels 1 and 2 the curtain itself is not solid - it is a stockade with a gallery behind it -
    but a gate's jambs have to be, because they carry the arch, they take the doors' hinges and
    they are what a battering ram meets. So they are built up to the deck course whatever the
    curtain either side is doing, and the gate is the one place a young colony's wall is real
    masonry all the way through.
    """
    for x in PIER:
        for z in (W.OUT, W.MID, W.IN):
            for y in range(1, W.DECK_Y + 1):
                s.set(x, y, z, body)
            s.set(x, W.GROUND_Y, z, bp.GROUND)


def passage(s, level, body):
    """The way through: a vault, a pair of leaves in the middle of the thickness, and an arch at
    each mouth.

    The doors hang at z = 0 rather than at either face because that is where a gate's doors hang -
    they want a thickness of wall in front of them and a thickness behind, and it leaves both
    mouths reading as arches. Above them the tympanum and the vault are wall block, which is what
    keeps the two columns of the opening counting towards the colony's ring.
    """
    _stone, stair, _quoin = dress(level)
    for i, x in enumerate(PASS):
        hinge = "left" if i == 0 else "right"
        s.set(x, 1, W.MID, door(LEAF[level], "north", "lower", hinge))
        s.set(x, 2, W.MID, door(LEAF[level], "north", "upper", hinge))
        s.set(x, 3, W.MID, body)                      # the tympanum over the leaves
        s.set(x, ARCH_Y, W.MID, body)                 # the vault over the passage
        s.set(x, W.DECK_Y, W.MID, body)               # and the deck the walk crosses it on
    # the arch at both mouths: two stones leaning in off the jambs, which is as much of an arch as
    # a two-wide opening has room for and exactly the shape a two-wide opening wants
    for z in (W.OUT, W.IN):
        s.set(PASS[0], ARCH_Y, z, stairs(stair, "west", "top"))
        s.set(PASS[1], ARCH_Y, z, stairs(stair, "east", "top"))
    # a light on each jamb, inside the mouths
    for z in (W.OUT, W.IN):
        s.set(PASS[0], 3, z, "minecraft:wall_torch[facing=east]")
        s.set(PASS[1], 3, z, "minecraft:wall_torch[facing=west]")


def face(s, level, body, rail):
    """The elevation over the gate, carried above the curtain and crowned.

    Two courses higher than the wall either side, because a gatehouse is higher - and because the
    columns of the opening have to earn back the wall they lost to the road.
    """
    for x in BAY:
        for y in range(W.DECK_Y, HEAD[level] + 1):
            s.set(x, y, W.OUT, body)
        crest = HEAD[level] + 1
        if level >= 4:
            s.set(x, crest, W.OUT, body if x in PIER else rail)     # merlon over each turret
        else:
            s.set(x, crest, W.OUT, rail)
    if level >= 3:
        for x in (1, 6):                    # the colours, on the curtain flanking the gate bay
            s.set(x, 4, W.TALUS, wall_banner(W.P["flag"], "north"))


def turrets(s, level, rail):
    """The two towers the arch stands between, standing a block clear of the face.

    They are what makes a gate look like a gate from the field: a flat wall with a hole in it is a
    breach, and a hole between two turrets is a gate.
    """
    stone, _stair, quoin = dress(level)
    top = HEAD[level] + 1
    for x in PIER:
        for y in range(1, top + 1):
            s.set(x, y, W.TALUS, log(stone) if level <= 2 else stone)
        s.set(x, top, W.TALUS, quoin if level > 2 else log(quoin))
        s.set(x, top + 1, W.TALUS, rail)
        s.set(x, top + 2, W.TALUS, lantern(W.P["lamp"]))
        s.set(x, W.GROUND_Y, W.TALUS, bp.GROUND)


def breteche(s, body, rail):
    """The box of parapet slung across between the turrets, with nothing under its middle.

    A breteche is the one piece of a fortification whose whole purpose is the gap: the two open
    cells beneath it are directly over the doors, and they are how a garrison answers somebody
    trying to burn them. It is carried on the turrets, not on corbels off the face, because that
    is what it has to hand - and because a box on two towers is how the real ones are built.

    It sits two courses over the head of the arch, not up at the crest: high enough that nobody
    reaches it from the road, low enough that what is dropped out of it lands on the doors.
    """
    for x in PASS:
        s.set(x, W.WALK[0], W.TALUS, body)            # its floor, hanging between the turrets
        s.set(x, W.WALK[1], W.TALUS, rail)            # and its own parapet


def covered(s, body):
    """Level 5 roofs the walk between the turrets.

    "Covered where it should be" is over the gate and nowhere else on this piece: the gate is the
    stretch of wall a man cannot leave, so it is the stretch worth building him a roof over. The
    two clear cells of the walk are untouched - the roof sits a course above his head.
    """
    for x in PIER:
        s.set(x, W.WALK[1], W.BERM, body)                 # cheeks, on the banquette rail
    for x in BAY:
        for z in (W.MID, W.IN, W.BERM):
            s.set(x, W.HEAD_Y, z, slab(W.P["fortsl"], "bottom"))


def paving(s):
    """The road through the gate - laid only where the wall has not already claimed the ground, so
    the footing under the turrets and the curtain stays solid substitution."""
    for x in BAY:
        for z in range(W.TALUS, W.BERM + 1):
            if s.get(x, W.GROUND_Y, z) is None:
                s.set(x, W.GROUND_Y, z, road(x, z))


if __name__ == "__main__":
    for lv in range(1, 6):
        st = build(lv)
        wall = sum(1 for b in st.blocks.values() if b.startswith("colonies_at_war:"))
        print(f"{st.name:14s} {str(st.size()):14s} {len(st.blocks):4d} blocks  {wall:3d} wall  "
              f"anchor {st.anchor}")
