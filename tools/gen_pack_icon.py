"""The Structurize pack icon: resources/blueprints/colonies_at_war/colonies_at_war/colonies_at_war.png

What the pack is, in one 64 x 64 picture the build tool shows in a list: a wall running across the
frame with a tower standing in it and the colony's colours on top - which is the Wall Tower, and
the War Room is the reason it is manned. Drawn block by block and blown up with no smoothing,
because at this size a drawing of a Minecraft thing should look like Minecraft.
"""
import os
import sys

from PIL import Image, ImageDraw

SIZE = 64
SCALE = 4                       # drawn at 16 x 16 "blocks", then blown up

SKY = (58, 72, 96)
SKY2 = (78, 94, 116)
GROUND = (72, 92, 52)
GROUND2 = (60, 78, 44)
WALL = (136, 136, 136)
WALL_D = (104, 104, 104)
WALL_L = (162, 162, 162)
TOWER = (150, 150, 150)
TOWER_D = (112, 112, 112)
DARK = (58, 58, 62)
FLAG = (168, 46, 46)
FLAG_D = (120, 32, 32)
POLE = (92, 68, 44)


def px(d, x, y, colour, w=1, h=1):
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=colour)


def draw():
    im = Image.new("RGB", (16, 16), SKY)
    d = ImageDraw.Draw(im)
    px(d, 0, 6, SKY2, 16, 4)                       # a lighter band at the horizon
    px(d, 0, 14, GROUND, 16, 2)                    # the ground the wall stands on
    for x in range(1, 16, 3):
        px(d, x, 14, GROUND2)

    # the wall, running out of both sides of the frame, with its walk and its crenellations
    px(d, 0, 11, WALL, 16, 3)
    px(d, 0, 11, WALL_L, 16, 1)                    # the walk catching the light
    for x in range(0, 16, 2):
        px(d, x, 10, WALL_D)                       # merlons, every other block
    for x in range(2, 16, 5):
        px(d, x, 12, WALL_D)                       # a little wear in the face

    # the tower standing in it
    px(d, 5, 4, TOWER, 6, 10)
    px(d, 5, 4, TOWER_D, 1, 10)
    px(d, 10, 4, TOWER_D, 1, 10)
    for x in range(5, 11, 2):
        px(d, x, 3, TOWER)                         # its own crenellations
    px(d, 6, 7, DARK, 1, 3)                        # two arrow slits
    px(d, 9, 7, DARK, 1, 3)
    px(d, 7, 11, DARK, 2, 3)                       # and the gate the walk goes through
    px(d, 7, 11, WALL_L, 2, 1)

    # the colours, on a pole off the top
    px(d, 8, 0, POLE, 1, 4)
    px(d, 5, 0, FLAG, 3, 3)
    px(d, 5, 2, FLAG_D, 3, 1)
    return im.resize((SIZE, SIZE), Image.NEAREST)


def main():
    import build_pack as bp
    os.makedirs(bp.PACK_DIR, exist_ok=True)
    path = os.path.join(bp.PACK_DIR, bp.PACK_ICON)
    draw().save(path)
    print("wrote", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
