# Colonies at War

Walls your colony can actually build, towers your guards actually walk, and a room where the
marching is decided.

## The wall

MineColonies has no idea of a wall — a wall is whatever blocks a player happened to lay. So this
mod ships its own, and **a wall built out of it registers itself**: every piece tells the colony
where it stands and what tier it is, and takes itself back out when it is broken. Nothing has to
sample the ground, mistake a long barn for a rampart, or miss a wall because nobody was standing
near enough to load it.

Three tiers — **palisade**, **stone rampart**, **fortified rampart**, each with its parapet — and
the tier *is* the strength, never the skin. The parapet is deliberately knee-high rather than a
full block, so the walk stays clear for MineColonies' pathfinding: a guard who will not climb onto
his own wall is the fastest way to make the whole idea look broken.

Two numbers come out of it. **Coverage** — how much of the circle round the town has wall between
it and the outside, with a real bonus for closing the ring. And **strength** — height by tier,
measured per column, because a long stretch of two-high wall is not a tower.

Measured on a test server: a closed two-high stone ring scores **69**; the same ring raised to five
high scores **100**; knock a thirteen-block breach in it and it falls to **75**.

Wall blocks are hard but **not invincible**. A wall you cannot lose is a wall nobody attacks.

## The Wall Tower

A guard post that stands *in* a wall — the walk runs through it at head height, so a rampart butted
into either side carries straight on. Its men patrol the wall itself, turning at the next tower, and
they see further than men on the ground.

It needs no mixin and fights nothing: MineColonies' own `addPatrolTarget`, `resetPatrolTargets` and
the manual patrol mode are public API, and **a wall patrol is simply a list of points laid along the
walk** — taken from the register, so the tower never looks at the world. Set the patrol mode back to
automatic and you have an ordinary guard tower; the choice stays yours.

## The War Room

A table, a map, and the people who march. It hires a garrison the way the Barracks does, with the
level deciding how many: **2, 4, 6, 9, 12**, knights and rangers.

One line matters more than it looks: **tower guards defend, War Room guards campaign.** Without it
every war strips your walls and the answer to every war is "stay home". With it, a colony that
empties its towers to march is a colony a thief can walk into.

## Standing, and war

**Standing falls only when somebody is caught.** There is no drift, no proximity, no tick that
decides two neighbours must dislike each other. The only way down is something being done and the
victim knowing who did it.

**War is never declared by the game.** Low standing unlocks the choice; a person makes it. A peace
is a treaty, and the truce that follows it holds for seven days. `/war declare` and `/war peace` are
the entry points, and nothing in the mod calls them.

`warLevel` is `skirmish`, `siege` or `conquest` — all three ship, and the server picks.

## Building

See [BUILDING.md](BUILDING.md). Plain `javac`, no Gradle; the blueprints are generated from Python.

Made by Lovkar & Claude.
