"""Can a 2-tall citizen walk from A to B? BFS over standable cells: solid block below, two
passable cells (air, doors; ladders, glass panes are NOT passable), step up 1, drop down up to 3.

Copied from the Voyager tools. Note what it cannot do: a ladder is not passable here, so this
answers "can a citizen WALK there", never "can a citizen get there" - the Wall Tower's upper
chambers are reached by ladder until level 4, and that is the pathfinder's business, not this
file's. The wall walk itself is reachable on foot at every level, which is the part that matters.
"""
from collections import deque
from voxel import parse_state

PASSABLE_WORDS = ("_door", "air", "chorus_flower")   # doors get opened; flowers are nothing to a colonist
HALF_STEP = ("_stairs", "_slab")                     # half a block high: walked up, never jumped


def passable(s, x, y, z):
    b = s.get(x, y, z)
    if b is None:
        return True
    name = parse_state(b)[0]
    return any(w in name for w in PASSABLE_WORDS)


def solid(s, x, y, z):
    b = s.get(x, y, z)
    if b is None:
        return False
    name = parse_state(b)[0]
    if name in ("minecraft:lantern", "minecraft:soul_lantern"):
        return False                # the hanging kind; a sea lantern is a whole block you stand on
    return not any(w in name for w in ("_door", "chorus", "torch", "end_rod", "lightning_rod", "iron_bars", "chain"))


def standable(s, x, y, z):
    return solid(s, x, y - 1, z) and passable(s, x, y, z) and passable(s, x, y + 1, z)


def half_step(s, x, y, z):
    """True if the block here is a stair or a slab - something whose surface is half a block up.

    This is the difference between a staircase and a wall with footholds. Stepping onto a full
    block is a jump, and a jump needs a third cell of head room; stepping onto a stair is a walk,
    and every staircase in MineColonies' own blueprints runs in a two-high stairwell. Getting this
    wrong the first time said the Wall Tower's men could not reach their own wall.
    """
    b = s.get(x, y, z)
    return b is not None and any(w in parse_state(b)[0] for w in HALF_STEP)


def on_ladder(s, x, y, z):
    b = s.get(x, y, z)
    return b is not None and parse_state(b)[0] == "minecraft:ladder"


def is_hatch(s, x, y, z):
    b = s.get(x, y, z)
    return b is not None and "trapdoor" in parse_state(b)[0]


def climb_reachable(s, start, goal_adjacent):
    """reachable(), but it may also climb ladders.

    The plain walk deliberately cannot - that is what makes it a useful answer about stairs and
    doorways. But MineColonies' pathfinder does climb, its own guard towers and its sewage
    blueprints are full of ladders, so "can a citizen get up there at all" is a different and
    fairer question, and the one to ask of a hatch onto a roof.
    """
    seen = {start}
    q = deque([start])
    gx, gy, gz = goal_adjacent
    while q:
        x, y, z = q.popleft()
        if abs(x - gx) + abs(z - gz) <= 1 and abs(y - gy) <= 1:
            return True, seen
        nxt = []
        if on_ladder(s, x, y, z):
            for ny in (y + 1, y - 1):                      # up and down the rungs
                if on_ladder(s, x, ny, z) or standable(s, x, ny, z):
                    nxt.append((x, ny, z))
            if is_hatch(s, x, y + 1, z) and standable(s, x, y + 2, z):
                nxt.append((x, y + 2, z))                  # up through the hatch at the top
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if standable(s, x + dx, y, z + dz):        # and off it onto a floor
                    nxt.append((x + dx, y, z + dz))
        else:
            # down through a hatch onto the ladder under it, and back up the same way. This is
            # MineColonies' own pattern, not an invention - its sewage blueprints are literally
            # called road_hatch_ladder1..3 - so a citizen goes through a closed trapdoor.
            if is_hatch(s, x, y - 1, z) and on_ladder(s, x, y - 2, z):
                nxt.append((x, y - 2, z))
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if on_ladder(s, nx, y, nz):                # step onto a ladder beside you
                    nxt.append((nx, y, nz))
                for ny in (y + 1, y, y - 1, y - 2, y - 3):
                    if standable(s, nx, ny, nz):
                        if (ny == y + 1 and not half_step(s, nx, ny - 1, nz)
                                and not passable(s, x, y + 2, z)):
                            continue
                        nxt.append((nx, ny, nz))
                        break
        for n in nxt:
            if n not in seen:
                seen.add(n)
                q.append(n)
    return False, seen


def reachable(s, start, goal_adjacent):
    """BFS from start; returns the set of visited cells and whether any cell next to goal was reached."""
    seen = {start}
    q = deque([start])
    gx, gy, gz = goal_adjacent
    while q:
        x, y, z = q.popleft()
        if abs(x - gx) + abs(z - gz) <= 1 and abs(y - gy) <= 1:
            return True, seen
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            for ny in (y + 1, y, y - 1, y - 2, y - 3):
                if (nx, ny, nz) in seen:
                    continue
                if standable(s, nx, ny, nz):
                    # climbing onto a full block is a jump, and a jump needs headroom at the cell
                    # being jumped from; climbing onto a stair or a slab is just walking
                    if (ny == y + 1 and not half_step(s, nx, ny - 1, nz)
                            and not passable(s, x, y + 2, z)):
                        continue
                    seen.add((nx, ny, nz))
                    q.append((nx, ny, nz))
                    break
    return False, seen


if __name__ == "__main__":
    # Run on its own it walks both buildings, hut block -> every bed, on the padded structure
    # (the ground around the building is what a citizen walks on).
    import build_war
    import buildings
    for key, mod in buildings.ALL.items():
      for lv in range(1, 6):
        st = mod.build(lv)
        build_war.pad_ground(st, mod.BOX)
        x, y, z = st.anchor
        start = next(((x + dx, y, z + dz) for dx, dz in ((0, 1), (0, -1), (1, 0), (-1, 0))
                      if standable(st, x + dx, y, z + dz)), None)
        beds = sorted(p for p, ts in st.tags.items() if "bed" in ts)
        got = [p for p in beds if reachable(st, start, p)[0]]
        print(f"{st.name}: stand {start} -> {len(got)}/{len(beds)} beds walkable {got}")
