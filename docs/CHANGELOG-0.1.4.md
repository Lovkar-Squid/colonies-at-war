**Your style pack's walls now count.**

Until this build the colony's wall score only knew about blocks this mod had placed itself. That is exact — a block that registers itself can never be mistaken for a barn — but it meant a town ringed in Caledonia curtain wall, Fortress rampart or Nordic palisade scored **zero**, and the War Room told it so.

## What's new

- **Walls from every style pack are counted.** Any decoration built from a pack's `walls/` category — Caledonia, Fortress, Medieval Oak/Birch/Spruce/Dark Oak, Nordic, Pagoda, Ancient Athens, Incan, Lost Mesa City, Colonial, Desert Oasis, Urban Savanna, Space Wars, Original and the rest — is measured where it stands and credited to the colony it stands in. **515 wall blueprints across 23 style packs** are recognised out of the box, gates included, whether they live in `walls/`, `decorations/gates/` or `infrastructure/gates/`.
- **What is a wall is read off the blueprint's folder, never its name.** A moat, a fence, a hedge and a `misc` folder are shipped under `walls/` by some packs and are all left out; a park called "walled garden" was never in. The colony's score still cannot be fooled by a long barn.
- **Strength comes from what the wall is made of**, the same as ours: wood is a palisade, stone is stone, iron and obsidian are fortified — decided by what most of the piece is built from, so one iron portcullis does not promote a cobblestone wall.
- **Only the courses above the anchor count.** What is under a wall piece is the hole it was set into, and a wall is not credited for the ground it stands on.
- **It keeps up.** A wall is taken in the first time anyone loads its chunk and dropped the moment it is gone — knocked down, broken or demolished while nobody was watching.
- **The War Room says so.** The walls panel now reads `62/100 — 3 of 3 up, 4 borrowed`, so a colony whose whole wall came out of a style pack can see where its number comes from.
- Two new server settings: `countStylePackWalls` turns the whole thing off, and `extraWallFolders` adds folder names for a style pack that files its walls under a name of its own.

## Still ours alone

Borrowed wall counts towards the **score**. It is not patrolled: the Wall Tower still lays its patrol on this mod's own pieces, because a patrol run through the body of a three-thick foreign wall is worse than no patrol at all.

## Requires

MineColonies and Structurize, Minecraft 1.21.1 on NeoForge.

Optional: **Colonist Thieves**.
