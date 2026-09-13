"""The project logo: a Wall Tower with the wall running off both sides, level 5.

The same flat-colour isometric renderer that makes the docs images, composed with the mod's own
joint arithmetic (the offsets check_walls.py uses to butt a segment against the tower), so the
picture is a wall that would really stand rather than three pieces pushed together by eye.
400x400, which is what CurseForge wants for a project avatar.

    python3 gen_logo.py            -> ../resources/colonies_at_war_logo.png
"""
import pathlib

from PIL import Image, ImageDraw

import build_pack as bp
import isorender
import paths
import wallsegment as W
import walltower
from voxel import Structure

SIDE = 400
GROUND = (24, 26, 32)
SKY_TOP = (18, 20, 30)
SKY_BOTTOM = (46, 38, 34)
MARGIN = 14
LEVEL = 5


def scene():
    """Tower in the middle, a segment each side - the mod in one object."""
    stub = walltower.STUB[1]
    run = W.RUN
    placed = [
        (bp.aligned(walltower.build, LEVEL), (0, 0, 0)),
        (W.build(LEVEL), (stub + 1, 0, 0)),
        (W.build(LEVEL), (-stub - run, 0, 0)),
    ]
    union = Structure("logo")
    for s, (dx, dy, dz) in placed:
        for (x, y, z), b in s.blocks.items():
            union.set(x + dx, y + dy, z + dz, b)
    return union


def trimmed(img, bg):
    mask = Image.new("L", img.size, 0)
    px, mk = img.load(), mask.load()
    for y in range(img.height):
        for x in range(img.width):
            if px[x, y] != bg:
                mk[x, y] = 255
    box = mask.getbbox()
    if box is None:
        raise SystemExit("the render came out empty")
    return img.crop(box), mask.crop(box)


def sky(side):
    img = Image.new("RGB", (side, side))
    d = ImageDraw.Draw(img)
    for y in range(side):
        t = y / (side - 1)
        d.line([(0, y), (side, y)],
               fill=tuple(round(a + (b - a) * t) for a, b in zip(SKY_TOP, SKY_BOTTOM)))
    return img


def main():
    scratch = pathlib.Path(paths.out("logo_raw.png"))
    isorender.render(scene(), str(scratch), cell=10)
    raw = Image.open(scratch).convert("RGB")
    body, mask = trimmed(raw, GROUND)

    room = SIDE - 2 * MARGIN
    scale = min(room / body.width, room / body.height)
    size = (max(1, round(body.width * scale)), max(1, round(body.height * scale)))
    body = body.resize(size, Image.LANCZOS)
    mask = mask.resize(size, Image.LANCZOS)

    out = sky(SIDE)
    out.paste(body, ((SIDE - size[0]) // 2, (SIDE - size[1]) // 2), mask)
    dest = pathlib.Path(__file__).resolve().parent.parent / "resources" / "colonies_at_war_logo.png"
    out.save(dest)
    scratch.unlink(missing_ok=True)
    print(dest, out.size)


if __name__ == "__main__":
    main()
