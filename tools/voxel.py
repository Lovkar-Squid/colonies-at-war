"""Voxel structure DSL for the hut blueprints - the Voyager module, unchanged in substance.

A Structure is a sparse dict of block positions -> block state strings
("minecraft:oak_stairs[facing=north,half=bottom]"). The same data feeds the
preview (to_render_json) and the Structurize .blueprint export (to_blueprint),
so what is approved in the render is exactly what the MineColonies builder builds.

Coordinates: x east, y up, z south (Minecraft). Origin is free; export
normalises to the bounding box.
"""
import gzip
import io
import json
import math
import re

try:
    import nbtlib
    from nbtlib import tag as T
except ImportError:      # preview only (Blender's Python): .blueprint export unavailable
    nbtlib = None
    T = None

AIR = "minecraft:air"
DATA_VERSION = 3955  # 1.21.1


def parse_state(s):
    """'ns:name[a=b,c=d]' -> ('ns:name', {'a': 'b', 'c': 'd'})"""
    m = re.match(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(.*)\])?$", s.strip())
    if not m:
        raise ValueError("bad block state: " + s)
    name, props = m.group(1), {}
    if m.group(2):
        for kv in m.group(2).split(","):
            k, v = kv.split("=")
            props[k.strip()] = v.strip()
    return name, props


# Domum Ornamentum's mix-and-match blocks (shingles, timber frames, panels, pillars and the
# vanilla stairs/slab/wall shapes) do not carry their material in the block state: the block is one
# id and a block entity holds a map of "which texture slot" -> "which block's texture". MineColonies'
# own blueprints are full of them - university4 has 352 - and this is the exact shape they use:
#     {x,y,z, id: "domum_ornamentum:materially_retexturable", textureData: {<slot>: <block id>}}
# The slot names are the placeholder textures in DO's models, so they are per block, not per
# material. Order here is the order material() takes them in.
DO_TE = "domum_ornamentum:materially_retexturable"
DO_SLOTS = {
    "domum_ornamentum:shingle":                 ("minecraft:block/clay", "minecraft:block/oak_planks"),
    "domum_ornamentum:shingle_flat":            ("minecraft:block/clay", "minecraft:block/oak_planks"),
    "domum_ornamentum:shingle_flat_lower":      ("minecraft:block/clay", "minecraft:block/oak_planks"),
    "domum_ornamentum:shingle_slab":            ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks",
                                                 "minecraft:block/acacia_planks"),
    "domum_ornamentum:vanilla_stairs_compat":   ("minecraft:block/oak_planks",),
    "domum_ornamentum:vanilla_slab_compat":     ("minecraft:block/oak_planks",),
    "domum_ornamentum:vanilla_wall_compat":     ("minecraft:block/oak_planks",),
    "domum_ornamentum:vanilla_fence_compat":    ("minecraft:block/oak_planks",),
    "domum_ornamentum:squarepillar":            ("minecraft:block/oak_planks",),
    "domum_ornamentum:blockpillar":             ("minecraft:block/oak_planks",),
    "domum_ornamentum:blockypillar":            ("minecraft:block/oak_planks",),
    "domum_ornamentum:post":                    ("minecraft:block/oak_planks",),
    "domum_ornamentum:panel":                   ("minecraft:block/oak_planks",),
    # timber frames: frame material first, infill second
    "domum_ornamentum:framed":                  ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:plain":                   ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:side_framed":             ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:horizontal_plain":        ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:double_crossed":          ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:one_crossed_lr":          ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:one_crossed_rl":          ("minecraft:block/oak_planks", "minecraft:block/dark_oak_planks"),
    "domum_ornamentum:framed_light":            ("minecraft:block/oak_planks", "minecraft:block/glowstone"),
    "domum_ornamentum:center_light":            ("minecraft:block/oak_planks", "minecraft:block/glowstone"),
}


def entity_tag(x, y, z, entity_id, yaw=0.0, pitch=0.0, extra=None):
    """One entity, as a blueprint stores it.

    Positions are absolute inside the blueprint (0..size), the same way MineColonies' own armour
    stands and item frames are stored, and they sit in the middle of their block. Everything else
    is left at the entity's own defaults - the UUID is re-rolled on placement, so writing one here
    would only risk two copies of the same entity sharing it.
    """
    import nbtlib.tag as T
    tag = T.Compound({
        "id": T.String(entity_id),
        "Pos": T.List[T.Double]([T.Double(x + 0.5), T.Double(y), T.Double(z + 0.5)]),
        "Motion": T.List[T.Double]([T.Double(0.0), T.Double(0.0), T.Double(0.0)]),
        "Rotation": T.List[T.Float]([T.Float(yaw), T.Float(pitch)]),
        "FallDistance": T.Float(0.0),
        "Fire": T.Short(-1),
        "Air": T.Short(300),
        "OnGround": T.Byte(1),
        "Invulnerable": T.Byte(0),
        "PortalCooldown": T.Int(0),
        "CanUpdate": T.Byte(1),
    })
    for k, v in (extra or {}).items():
        if k == "__tile__":
            # A block-attached entity (a photograph frame, an item frame) also carries the block
            # it hangs in as TileX/Y/Z, and Minecraft refuses the entity if they disagree with Pos
            # by more than 16 blocks - so they are the same block, in blueprint coordinates.
            tag["TileX"], tag["TileY"], tag["TileZ"] = T.Int(x), T.Int(y), T.Int(z)
            continue
        tag[k] = v
    return tag


class Structure:
    def __init__(self, name):
        self.name = name
        self.blocks = {}
        self.tags = {}
        self.entities = []
        self.materials = {}
        self.anchor = None

    # ---------------------------------------------------------------- editing
    def set(self, x, y, z, block):
        # any plain set clears a material left by an earlier mixed() at this position; mixed()
        # writes its materials after calling here, so it is unaffected
        self.materials.pop((x, y, z), None)
        if block is None or block == AIR:
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = block

    def get(self, x, y, z):
        return self.blocks.get((x, y, z))

    def mixed(self, x, y, z, block, *materials):
        """Place a Domum Ornamentum mix-and-match block and give it its materials.

        `materials` are block ids in the order DO_SLOTS lists the block's slots - for a shingle
        that is (roof, support); for a timber frame (frame, infill); for the vanilla shapes just
        the one. A material outside DO's tag for that slot renders as missing, so stay inside
        what the tags allow (see docs) - the same trap as a block id that does not exist.
        """
        name = parse_state(block)[0]
        slots = DO_SLOTS.get(name)
        if slots is None:
            raise ValueError("not a materially textured Domum Ornamentum block: " + name)
        if not 1 <= len(materials) <= len(slots):
            raise ValueError(f"{name} takes 1..{len(slots)} materials, got {len(materials)}")
        self.set(x, y, z, block)
        self.materials[(x, y, z)] = dict(zip(slots, materials))

    def box(self, x0, y0, z0, x1, y1, z1, block, hollow=False):
        """Inclusive box. hollow=True keeps only the shell."""
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        for x in range(xa, xb + 1):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    if hollow and xa < x < xb and ya < y < yb and za < z < zb:
                        continue
                    self.set(x, y, z, block)

    def floor(self, x0, z0, x1, z1, y, block):
        self.box(x0, y, z0, x1, y, z1, block)

    def walls(self, x0, y0, z0, x1, y1, z1, block):
        """Four vertical walls of the box (no floor/ceiling)."""
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        for x in range(xa, xb + 1):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    if x in (xa, xb) or z in (za, zb):
                        self.set(x, y, z, block)

    def column(self, x, z, y0, y1, block):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            self.set(x, y, z, block)

    def disc(self, cx, cz, y, r, block, ring=False, inner=None):
        """Filled or ring disc (Bresenham-ish), r may be fractional."""
        for x in range(int(math.floor(cx - r)) - 1, int(math.ceil(cx + r)) + 2):
            for z in range(int(math.floor(cz - r)) - 1, int(math.ceil(cz + r)) + 2):
                d = math.hypot(x - cx, z - cz)
                if ring:
                    lo = inner if inner is not None else r - 1
                    if lo < d <= r:
                        self.set(x, y, z, block)
                elif d <= r:
                    self.set(x, y, z, block)

    def tag(self, x, y, z, *names):
        self.tags.setdefault((x, y, z), []).extend(names)

    def entity(self, x, y, z, entity_id, yaw=0.0, pitch=0.0, extra=None):
        """An entity standing in the middle of this block.

        Blueprints carry entities as well as blocks - that is how MineColonies places its armour
        stands and item frames, and how the builder knows to charge for them. Ours are the maps
        on the War Room's table and the armour standing along its wall.
        """
        self.entities.append(((x, y, z), entity_id, float(yaw), float(pitch), dict(extra or {})))

    def set_anchor(self, x, y, z, block):
        self.anchor = (x, y, z)
        self.set(x, y, z, block)

    def translated(self, dx, dy, dz):
        """A copy of this structure moved by (dx, dy, dz), tags and anchor included."""
        t = Structure(self.name)
        t.merge(self, dx, dy, dz)
        if self.anchor is not None:
            t.anchor = (self.anchor[0] + dx, self.anchor[1] + dy, self.anchor[2] + dz)
        return t

    def merge(self, other, dx=0, dy=0, dz=0):
        for (x, y, z), b in other.blocks.items():
            self.set(x + dx, y + dy, z + dz, b)
        for (x, y, z), t in other.tags.items():
            self.tag(x + dx, y + dy, z + dz, *t)
        for (x, y, z), m in other.materials.items():
            self.materials[(x + dx, y + dy, z + dz)] = dict(m)
        for (x, y, z), eid, yaw, pitch, extra in other.entities:
            self.entities.append(((x + dx, y + dy, z + dz), eid, yaw, pitch, dict(extra)))

    # ---------------------------------------------------------------- queries
    def bounds(self):
        xs = [p[0] for p in self.blocks]; ys = [p[1] for p in self.blocks]; zs = [p[2] for p in self.blocks]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    def size(self):
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        return x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1

    def count(self):
        from collections import Counter
        return Counter(parse_state(b)[0] for b in self.blocks.values())

    # ---------------------------------------------------------------- exports
    def to_render_json(self):
        (x0, y0, z0), _ = self.bounds()
        blocks = []
        for (x, y, z), b in sorted(self.blocks.items()):
            entry = [x - x0, y - y0, z - z0, b]
            mats = self.materials.get((x, y, z))
            if mats:
                # the first slot is the one you see: the shingle's tiles, the frame's timber, the
                # material of a vanilla-shape block. The preview has to show it or the render is
                # not what the builder will build.
                entry.append(next(iter(mats.values())))
            blocks.append(entry)
        return json.dumps({"name": self.name, "blocks": blocks, "size": list(self.size())}, separators=(",", ":"))

    def to_blueprint(self, path, file_name, pack_name, pack_path, building_type,
                     required_mods=("minecolonies", "colonies_at_war"), be_type="minecolonies:colonybuilding",
                     box=None):
        """Write a Structurize v1 .blueprint (gzip NBT).

        file_name: e.g. 'walltower1.blueprint'; pack_path: path inside the pack,
        e.g. 'walltower/walltower1.blueprint'; building_type: 'colonies_at_war:walltower'.
        box: optional ((x0, y0, z0), (x1, y1, z1)) forcing the blueprint size (filled with
        air, which the builder clears) so every level shares one footprint.

        building_type=None writes the anchor as a DECORATION instead of a hut - see below. That is
        the difference between the two things this pack ships: a building the colony hires men for
        and a piece of wall it simply builds.
        """
        if self.anchor is None:
            raise ValueError("structure has no anchor (hut block)")
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        if box is not None:
            (bx0, by0, bz0), (bx1, by1, bz1) = box
            if bx0 > x0 or by0 > y0 or bz0 > z0 or bx1 < x1 or by1 < y1 or bz1 < z1:
                raise ValueError(f"{self.name}: blocks {self.bounds()} exceed the box {box}")
            (x0, y0, z0), (x1, y1, z1) = (bx0, by0, bz0), (bx1, by1, bz1)
        sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
        palette = [AIR]
        index = {AIR: 0}
        arr = [0] * (sx * sy * sz)
        for (x, y, z), b in self.blocks.items():
            if b not in index:
                index[b] = len(palette)
                palette.append(b)
            arr[(y - y0) * sz * sx + (z - z0) * sx + (x - x0)] = index[b]
        packed = []
        for i in range(0, len(arr), 2):
            hi = arr[i]
            lo = arr[i + 1] if i + 1 < len(arr) else 0
            v = (hi << 16) | lo
            if v >= 1 << 31:
                v -= 1 << 32
            packed.append(v)
        pal = T.List[T.Compound]()
        for b in palette:
            name, props = parse_state(b)
            c = T.Compound({"Name": T.String(name)})
            if props:
                c["Properties"] = T.Compound({k: T.String(v) for k, v in props.items()})
            pal.append(c)
        ax, ay, az = self.anchor
        tag_map = T.List[T.Compound]()
        # (tag_map is filled below and shared by both kinds of anchor)
        for (tx, ty, tz), names in sorted(self.tags.items()):
            tag_map.append(T.Compound({
                "tagPos": T.Compound({"x": T.Int(tx - ax), "y": T.Int(ty - ay), "z": T.Int(tz - az)}),
                "tagNameList": T.List[T.Compound]([T.Compound({"tagName": T.String(n)}) for n in names]),
            }))
        schematic_name = file_name.replace(".blueprint", "")
        hut = T.Compound({
            "id": T.String(be_type),
            "x": T.Short(ax - x0), "y": T.Short(ay - y0), "z": T.Short(az - z0),
            "blueprintDataProvider": T.Compound({
                "corner1": T.Compound({"x": T.Int(x0 - ax), "y": T.Int(y0 - ay), "z": T.Int(z0 - az)}),
                "corner2": T.Compound({"x": T.Int(x1 - ax), "y": T.Int(y1 - ay), "z": T.Int(z1 - az)}),
                "path": T.String(pack_path),
                "schematicName": T.String(schematic_name),
                "pack": T.String(pack_name),
                "posTagMap": tag_map,
            }),
        })
        if building_type is None:
            # A DECORATION, not a building. MineColonies' own wall pieces - unzip
            # blueprints/minecolonies/nordic/walls/wall/*.blueprint out of its jar - are anchored
            # on a minecolonies:decorationcontroller, and that block entity carries no building
            # type and no version: TileEntityDecorationController reads its pack and its path out
            # of the blueprintDataProvider above, and BlockDecorationController.getLevel finds the
            # level in the digits of schematicName. The two fields added here are the ones its own
            # files carry and its reader still accepts - an empty Item and mirror = 0, which is
            # how a controller says "placed square".
            hut["Item"] = T.String("")
            hut["mirror"] = T.Byte(0)
        else:
            hut["type"] = T.String(building_type)
            hut["version"] = T.Int(2)
            hut["pack"] = T.String(pack_name)
            hut["path"] = T.String(pack_path)
        root = T.Compound({
            "version": T.Byte(1),
            "mcversion": T.Int(DATA_VERSION),
            "name": T.String(file_name),
            "size_x": T.Short(sx), "size_y": T.Short(sy), "size_z": T.Short(sz),
            "required_mods": T.List[T.String]([T.String(m) for m in required_mods]),
            "palette": pal,
            "blocks": T.IntArray(packed),
            "tile_entities": T.List[T.Compound]([hut] + [
                T.Compound({
                    "x": T.Short(mx - x0), "y": T.Short(my - y0), "z": T.Short(mz - z0),
                    "id": T.String(DO_TE),
                    "textureData": T.Compound({k: T.String(v) for k, v in mats.items()}),
                })
                for (mx, my, mz), mats in sorted(self.materials.items())
                if (mx, my, mz) in self.blocks
            ]),
            "entities": T.List[T.Compound]([
                entity_tag(ex - x0, ey - y0, ez - z0, eid, yaw, pitch, extra)
                for (ex, ey, ez), eid, yaw, pitch, extra in self.entities
            ]),
            "optional_data": T.Compound({"structurize": T.Compound({
                "primary_offset": T.Compound({"x": T.Int(ax - x0), "y": T.Int(ay - y0), "z": T.Int(az - z0)})})}),
        })
        f = nbtlib.File(root, gzipped=True)
        f.save(path)
        return path


def load_blueprint(path):
    """Read a .blueprint back into a Structure (for verification)."""
    f = nbtlib.load(path)
    sx, sy, sz = int(f["size_x"]), int(f["size_y"]), int(f["size_z"])
    raw = [int(v) & 0xFFFFFFFF for v in f["blocks"]]
    arr = []
    for v in raw:
        arr += [(v >> 16) & 0xFFFF, v & 0xFFFF]
    pal = []
    for p in f["palette"]:
        name = str(p["Name"])
        if "Properties" in p:
            props = ",".join(f"{k}={p['Properties'][k]}" for k in sorted(p["Properties"].keys()))
            name += "[" + props + "]"
        pal.append(name)
    s = Structure(str(f["name"]))
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                b = pal[arr[y * sz * sx + z * sx + x]]
                if b != AIR:
                    s.set(x, y, z, b)
    po = f["optional_data"]["structurize"]["primary_offset"]
    s.anchor = (int(po["x"]), int(po["y"]), int(po["z"]))
    return s
