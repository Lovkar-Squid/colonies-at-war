"""Find blocks that are not connected to the ground through solid neighbours.

A block "floats" when its 6-neighbourhood component never reaches the lowest
layer of the structure. Chains, lanterns hanging from a block above, ladders
on a wall, etc. are connected through their neighbour so they pass; a mast
that starts one block above a roof edge fails.
"""
import sys
from collections import deque

NEI = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]


def floating(s):
    if not s.blocks:
        return []
    ymin = min(y for (_, y, _) in s.blocks)
    seen = set()
    q = deque(p for p in s.blocks if p[1] == ymin)
    seen.update(q)
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in NEI:
            n = (x + dx, y + dy, z + dz)
            if n in s.blocks and n not in seen:
                seen.add(n)
                q.append(n)
    return sorted(p for p in s.blocks if p not in seen)


def components(s, pts):
    pts = set(pts)
    comps = []
    while pts:
        start = pts.pop()
        comp = [start]
        q = deque([start])
        while q:
            x, y, z = q.popleft()
            for dx, dy, dz in NEI:
                n = (x + dx, y + dy, z + dz)
                if n in pts:
                    pts.remove(n)
                    comp.append(n)
                    q.append(n)
        comps.append(sorted(comp))
    return comps


if __name__ == "__main__":
    # Run on its own it reports both buildings: "tools/floatcheck.py walltower 1 3".
    import buildings
    args = sys.argv[1:]
    which = [a for a in args if not a.isdigit()] or list(buildings.ALL)
    levels = [int(a) for a in args if a.isdigit()] or [1, 2, 3, 4, 5]
    for key in which:
      for level in levels:
        s = buildings.ALL[key].build(level)
        name = s.name
        fl = floating(s)
        comps = components(s, fl)
        print(f"{name}: {len(fl)} floating blocks in {len(comps)} groups")
        for c in comps[:12]:
            kinds = sorted({s.blocks[p].split("[")[0].split(":")[1] for p in c})
            print(f"   {len(c):4d} blocks  at {c[0]}..{c[-1]}  {', '.join(kinds)[:90]}")


OPP = {"north": (0, 0, 1), "south": (0, 0, -1), "west": (1, 0, 0), "east": (-1, 0, 0)}


def attachment(s):
    """Blocks that would pop off in game: ladders without a wall behind them,
    standing lanterns without a block below, hanging lanterns without a block
    above, doors without a floor, torches on air."""
    from voxel import parse_state
    bad = []
    for (x, y, z), st in s.blocks.items():
        name, props = parse_state(st)
        short = name.split(":")[1]
        need = None
        if short == "ladder":
            d = OPP[props.get("facing", "north")]
            need = (x + d[0], y, z + d[2])
        elif short in ("lantern", "soul_lantern"):
            need = (x, y + 1, z) if props.get("hanging") == "true" else (x, y - 1, z)
        elif short.endswith("_door") and props.get("half", "lower") == "lower":
            need = (x, y - 1, z)
        elif short in ("torch", "soul_torch", "redstone_torch", "lectern", "enchanting_table", "crafting_table",
                       "cartography_table", "bookshelf", "chorus_flower"):
            need = (x, y - 1, z)
        elif short == "wall_torch" or short.endswith("_wall_banner") or short.endswith("_wall_sign"):
            # a wall banner hangs on the block BEHIND it, the way a wall torch does: face south
            # and the block that holds you is the one to the north
            d = OPP[props.get("facing", "north")]
            need = (x + d[0], y, z + d[2])
        if need is not None and need not in s.blocks:
            bad.append(((x, y, z), st))
    return bad
