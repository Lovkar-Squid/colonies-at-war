**The War Room's garrison walks its rounds.**

A bug fix, nothing else.

## Fixed

- **The War Room's guards stood at the hut block instead of patrolling.** The War Room patrolled the way MineColonies' Barracks does: the whole garrison walks to one point, and the patrol moves on to the next point only once every citizen of the building has arrived.
  - The Explorer is one of those citizens and never walks the patrol, and neither does a man away on a campaign. So every point waited for the colony's slow tick, 25 seconds.
  - Whenever the town's other buildings were beyond the room's patrol range, the next point was the hut itself. On a headless test server, with the town hall 120 blocks away, the guards never got further than 11 blocks from the hut in five minutes.
- **Now the War Room patrols the way MineColonies' own Guard Tower does:** every guard walks his own rounds through the town. On the same test they went up to 128 blocks out and spent less than a third of the time at the hut.
- Patrol points set by hand in the hut window are still walked together, as before.
- Worlds from 0.2.1 load as they were.

## Requires

The same as 0.2.1: Minecraft 1.21.1, NeoForge 21.1 and MineColonies 1.1.x.
