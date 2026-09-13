"""A quick isometric preview of a voxel Structure, for eyeballing shapes without Blender.

The Voyager tool, with a driver for this repository's one building. Not textures - flat colours
by block family, three shades for the three visible faces - but enough to see that the roof sits
on the walls, the stair goes where it should and the bunks are in the room.

    python3 isorender.py walltower 1 5      levels 1 and 5 of the tower
    python3 isorender.py warroom --cut 4    the War Room with its roof taken off at y=4
    python3 isorender.py --docs             every picture docs/renders keeps
    python3 isorender.py --docs wallgate    just that one piece's

-> tools/out/iso_walltower5.png. The cut is what makes it useful on a building with a roof:
seen whole, a hall is a roof.
"""
import sys
from PIL import Image, ImageDraw

from voxel import parse_state

FAMILY = {
    "copper": (120, 170, 130), "oxidized": (90, 160, 140), "weathered": (110, 165, 135),
    "deepslate": (70, 72, 80), "blackstone": (40, 38, 42), "black_brick": (45, 45, 50),
    "beige": (215, 200, 170), "cream": (235, 225, 200), "brown": (150, 120, 90),
    "stone_brick": (150, 150, 150), "cobblestone": (125, 125, 125), "chiseled": (170, 170, 175),
    "sandstone": (225, 205, 150), "sand_stone": (225, 205, 150), "terracotta": (90, 160, 170),
    "quartz": (240, 238, 232), "calcite": (235, 235, 230), "purpur": (170, 120, 175),
    "amethyst": (150, 100, 200), "glass": (200, 230, 240), "tinted": (40, 40, 50),
    "lantern": (255, 220, 120), "sea_lantern": (190, 240, 230), "glowstone": (250, 220, 130),
    "bulb": (250, 200, 100), "end_rod": (250, 250, 250), "door": (110, 80, 50),
    "ladder": (160, 120, 70), "bed": (200, 60, 80), "lectern": (150, 110, 70),
    "bookshelf": (160, 120, 80), "rack": (120, 90, 60), "lightroom": (200, 60, 60),
    "analyzer": (80, 200, 220), "blockhut": (255, 120, 40), "cutter": (170, 150, 120),
    "barrel": (130, 100, 60), "cartography": (150, 120, 90), "enchanting": (90, 60, 120),
    "lodestone": (90, 90, 100), "blue_brick": (60, 80, 160), "blue_": (60, 80, 160),
    "gray_brick": (120, 120, 125), "light_blue": (120, 170, 220), "cyan": (60, 160, 170),
    # this repository's own blocks, and the warm things the two buildings are lit by
    "rampart": (128, 128, 132), "parapet": (150, 150, 154),
    "fortified_rampart": (72, 72, 80), "fortified_parapet": (92, 92, 100),
    "campfire": (240, 150, 60), "candle": (250, 220, 150), "chain": (90, 90, 96),
    "banner": (170, 45, 45), "polished_diorite": (222, 222, 218),
    "gravel": (140, 135, 130), "coarse_dirt": (120, 90, 62), "dirt_path": (150, 125, 85),
    "andesite": (150, 150, 148), "oak_planks": (185, 150, 95), "smithing": (70, 70, 80),
    "grindstone": (140, 140, 145), "crafting": (160, 120, 75), "trapdoor": (125, 92, 55),
    "chest": (170, 130, 70), "fence": (110, 82, 55), "stairs": (150, 120, 85),
    "shingle": (150, 80, 70), "bricks": (160, 80, 70), "smooth_stone": (170, 170, 170),
    "concrete": (200, 200, 200), "iron_bars": (140, 140, 150), "spruce": (100, 75, 50),
    "mangrove": (110, 50, 50), "warped": (60, 140, 130), "acacia": (190, 100, 50),
    "dark_oak": (70, 50, 30), "cut_": (225, 205, 150), "solidsubstitution": (90, 130, 80),
    "substitution": (90, 130, 80), "squarepillar": (200, 200, 195), "vanilla": (200, 200, 195),
}


# This mod's own six blocks, by their exact names. They have to be looked up before FAMILY's
# substring match or "fortified_rampart" finds "rampart" first and a deepslate wall renders as a
# limestone one - which on the wall pieces would hide the entire difference between level 4 and
# level 5. The palisade is spruce log and the parapets are a shade lighter than their ramparts,
# because that is what the block models are textured with.
OURS = {
    "colonies_at_war:palisade": (118, 92, 60),
    "colonies_at_war:palisade_parapet": (138, 110, 74),
    "colonies_at_war:stone_rampart": (128, 128, 132),
    "colonies_at_war:stone_parapet": (150, 150, 154),
    "colonies_at_war:fortified_rampart": (62, 62, 70),
    "colonies_at_war:fortified_parapet": (84, 84, 94),
}


def colour(block):
    full = parse_state(block)[0]
    if full in OURS:
        return OURS[full]
    name = full.split(":")[1]
    for key, rgb in FAMILY.items():
        if key in name:
            return rgb
    return (180, 180, 180)


def render(s, path, cell=10, top=False):
    (x0, y0, z0), (x1, y1, z1) = s.bounds()
    ents = {(x, y, z): eid for (x, y, z), eid, *_ in s.entities}
    # iso: screen u = (x - z), v = (x + z)/2 - y
    def proj(x, y, z):
        u = (x - x0) - (z - z0)
        v = ((x - x0) + (z - z0)) * 0.5 - (y - y0)
        return u, v
    us, vs = [], []
    for c in ((x0, y0, z0), (x1, y0, z0), (x0, y0, z1), (x1, y0, z1), (x0, y1, z0), (x1, y1, z1), (x0, y1, z1), (x1, y1, z0)):
        u, v = proj(*c)
        us.append(u); vs.append(v)
    w = int((max(us) - min(us) + 3) * cell)
    h = int((max(vs) - min(vs) + 3) * cell)
    ou, ov = -min(us) + 1.5, -min(vs) + 1.5
    img = Image.new("RGB", (w, h), (24, 26, 32))
    d = ImageDraw.Draw(img)
    order = sorted(s.blocks.items(), key=lambda kv: (kv[0][0] + kv[0][2], kv[0][1]))
    mats = getattr(s, "materials", {})
    for (x, y, z), b in order:
        # a Domum Ornamentum block wears its material, not its own name: colour the preview by
        # what the builder will actually put there, or a slate roof comes out looking like clay
        here = mats.get((x, y, z))
        rgb = colour(next(iter(here.values()))) if here else colour(b)
        u, v = proj(x, y, z)
        px, py = (u + ou) * cell, (v + ov) * cell
        hx, hy = cell, cell * 0.5
        # top face
        d.polygon([(px, py - cell), (px + hx, py - cell + hy), (px, py - cell + 2 * hy), (px - hx, py - cell + hy)], fill=rgb)
        dark = tuple(int(c * 0.72) for c in rgb)
        darker = tuple(int(c * 0.5) for c in rgb)
        # left (south-west... x-) face and right (z+) face
        d.polygon([(px - hx, py - cell + hy), (px, py - cell + 2 * hy), (px, py + hy), (px - hx, py)], fill=dark)
        d.polygon([(px + hx, py - cell + hy), (px, py - cell + 2 * hy), (px, py + hy), (px + hx, py)], fill=darker)
    for (x, y, z), eid in ents.items():
        u, v = proj(x, y, z)
        px, py = (u + ou) * cell, (v + ov) * cell
        col = (255, 255, 0) if "camera" in eid else (0, 255, 255)
        d.ellipse([px - 4, py - cell - 4, px + 4, py - cell + 4], fill=col)
    img.save(path)
    return path


def turned(s):
    """The same structure seen from the other side: a half turn about the y axis.

    The projection here always looks at the +x +z corner, which for a house is its front and for a
    piece of wall is its INSIDE - so the machicolation, the merlons and everything else a wall
    spends its levels on would be hidden round the back. A half turn is an honest camera move, not
    a mirror: nothing is reversed, we are simply standing in the field looking at the wall the way
    somebody who minded about it would be.
    """
    from voxel import Structure
    t = Structure(s.name)
    for (x, y, z), b in s.blocks.items():
        t.set(-x, y, -z, b)
    t.materials = {(-x, y, -z): dict(m) for (x, y, z), m in getattr(s, "materials", {}).items()}
    t.entities = [((-e[0][0], e[0][1], -e[0][2]),) + tuple(e[1:]) for e in s.entities]
    t.anchor = s.anchor
    return t


def slice_y(s, lo=None, hi=None):
    """A copy of the structure with only the courses between lo and hi - a cutaway."""
    from voxel import Structure
    t = Structure(s.name)
    for (x, y, z), b in s.blocks.items():
        if (lo is None or y >= lo) and (hi is None or y <= hi):
            t.set(x, y, z, b)
    t.entities = [e for e in s.entities
                  if (lo is None or e[0][1] >= lo) and (hi is None or e[0][1] <= hi)]
    t.anchor = s.anchor
    return t


def to_docs(only=None):
    """The set of pictures docs/renders keeps: each piece whole, and cut down to the course that
    shows the inside of it.

    Which levels is the piece's own business - a building says it with RENDER_LEVELS, and the
    default is the first and the last, which is the whole story for a hall. The wall segment asks
    for three, because the levels it spends on depth rather than height do not show in a silhouette.
    """
    import os
    import buildings
    import paths
    out_dir = os.path.join(paths.ROOT, "docs", "renders")
    os.makedirs(out_dir, exist_ok=True)
    made = []
    for key, mod in buildings.ALL.items():
        if only and key not in only:
            continue
        cell = getattr(mod, "RENDER_CELL", 12)
        face = turned if getattr(mod, "RENDER_SPIN", False) else (lambda x: x)
        for level in getattr(mod, "RENDER_LEVELS", (1, 5)):
            st = mod.build(level)
            path = os.path.join(out_dir, f"{key}-level{level}.png")
            render(face(st), path, cell=cell)
            made.append(path)
            cut = mod.ROOM_TOP[level] if isinstance(getattr(mod, "ROOM_TOP", None), dict) else mod.ROOM_TOP
            inside = os.path.join(out_dir, f"{key}-level{level}-cutaway.png")
            render(face(slice_y(st, hi=cut)), inside, cell=cell)
            made.append(inside)
    if only is None or "wallsegment" in (only or ()):
        # and one picture of the wall as a wall: a corner, curtain, a gate and more curtain, laid
        # the way a colony lays them. A wall piece on its own shows nothing about the only thing
        # that matters about it.
        import check_walls
        for level in (1, 3, 5):
            stretch = check_walls.stretch(level)
            # from the field, which is the side the batter, the merlons and the machicolation are
            # built for...
            path = os.path.join(out_dir, f"wall-run-level{level}.png")
            render(turned(stretch), path, cell=11)
            made.append(path)
            # ...and from inside the colony, which is the only side the stair, the banquette and
            # the gate's road are on. A wall has two faces and they are not the same picture.
            inside = os.path.join(out_dir, f"wall-run-level{level}-inside.png")
            render(stretch, inside, cell=11)
            made.append(inside)
    return made


if __name__ == "__main__":
    import buildings
    import paths
    args = sys.argv[1:]
    if "--docs" in args:
        at = args.index("--docs")
        only = [a for a in args[at + 1:] if not a.isdigit()] or None
        for p in to_docs(only):
            print(p)
        sys.exit(0)
    cut = None
    if "--cut" in args:
        at = args.index("--cut")
        cut = int(args[at + 1])
        args = args[:at] + args[at + 2:]
    which = [a for a in args if not a.isdigit()] or list(buildings.ALL)
    levels = [int(a) for a in args if a.isdigit()] or [1, 2, 3, 4, 5]
    for key in which:
        mod = buildings.ALL[key]
        for level in levels:
            st = mod.build(level)
            if cut is not None:
                st = slice_y(st, hi=cut)
            out = paths.out(f"iso_{key}{level}{'' if cut is None else f'_cut{cut}'}.png")
            render(st, out)
            print(out)
