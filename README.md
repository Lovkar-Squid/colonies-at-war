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

### Somebody else's wall

Registering itself is the right *default*, not the only way in. Nearly every MineColonies style
pack ships a `walls/` category, and a town ringed in Caledonia curtain wall or Nordic palisade has
built a wall by any reading of the word. So a second door: the **decoration controller** every
built decoration leaves behind is read where it stands, and if its blueprint path says walls, what
is standing inside the decoration's own corners is measured and credited — 515 wall blueprints
across 23 style packs, out of the box.

What counts is read off the blueprint's **folder**, never its name, so a moat, a fence and a hedge
— all of them shipped under `walls/` by somebody — stay out, and the promise that a long barn is
still a barn survives. Strength comes from the material, the same as ours; only the courses above
the anchor count, because a wall is not credited for the ground it stands on; and a piece is
dropped the moment it is gone. Borrowed wall counts towards the score and is deliberately **not**
patrolled: a patrol laid through the body of a three-thick foreign wall is worse than none.

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
level deciding how many: **2, 4, 6, 9, 12**, knights and rangers in whatever mix you like.

One line matters more than it looks: **tower guards defend, War Room guards campaign.** Without it
every war strips your walls and the answer to every war is "stay home". With it, a colony that
empties its towers to march is a colony a thief can walk into.

## Find the Raiders

A research in the University's Combat tree, under *A Standing Army*, that needs a War Room. Without
it a guard finds a raider the way anyone does: by seeing him, within thirty blocks and in line of
sight, on a patrol that wanders between your own buildings — and a raid that comes in from the far
side of a big town is at the warehouse before a tower has noticed. With it, the town has an alarm.
Every three seconds of a raid, **every guard building on patrol is pointed at the raider nearest it**
through the same door MineColonies' guards use to call each other, and **every idle or napping guard
with a raider within reach is set on him at once**, the way a citizen's cry for help sets him on an
attacker. A guard still on his way runs. Nothing here is a new AI — it is the information the guards
were missing, handed to code that already knew what to do with it.

A guard told to **stand** somewhere keeps his ground and answers only within his own reach — with one
exception: **a raider who has got behind him**, nearer the Town Hall than his post, is his. His post
and a rally are moved onto that raider until nobody is behind him, then put back where they were.
That is how the alarm meets Colonist Errands' defensive line: the line stands, and whatever slips
through it is hunted. A guard told to **follow** stays with you, and a rallying banner on a patrol
still wins: your orders, kept. It works on every raid — barbarians, pirates, a neighbour's warband,
a kingdom's reprisal. Off switch: `findTheRaiders`.

### Hue and Cry

The second bell, a research under *Find the Raiders*: posted guards leave their posts for any
raider within forty-eight blocks of them, not only for one that has got behind them — and go
back when he is dead. Costs two bells and eight iron.

## The Explorer, and expeditions

The one person in the War Room who goes out to look rather than to fight. Hire an **Explorer**
there (one; Adaptability and Agility) and send him on an **expedition**: pick a kind of country —
forest, jungle, taiga, desert, savanna, swamp, snow, mountains, ocean, beach, river, badlands,
mushroom fields, a cherry grove, the caves — or an exact biome, pick how hard the trip should be
(easy, normal, hard, deadly), pick how many of the War Room's guards go with him, and he goes.
The country round the colony is read off the world's own noise every sixty-four blocks, so the
table knows what is within reach and how far, and nothing is loaded to ask.

What comes home is two things. The **land's** goods come from a loot table per kind of country in
the datapack (`expedition/forest`, `expedition/jungle`, …), so a pack can say what its own forests
are worth. The **beasts'** goods come from the biome itself: the men kill a handful of whatever
that biome's spawn lists say lives there, hostile and not, and each kill rolls that creature's real
loot table — a zombie's rotten flesh, a skeleton's bones, a cow's leather, a sheep's wool — and the
report names them: *killed 3 Zombies, 2 Spiders, 4 Cows*. Nobody is spawned for it. A harder trip
means more of both and more danger: the party (10 + guards ×5 + the Explorer's Adaptability)
against the country (difficulty ×6 + what it met), with luck the size of the fight. Driven off, they drop
half of it and come home hurt; even a trip that went well wears the men down by how hard it was.
Everyone who went learns from it.

`/war biomes` lists what is within reach, `/war explore <kind> [easy|normal|hard|deadly] [guards]`
sends him, `/war campaigns` shows him out. Config block `expeditions`: `expeditions`,
`expeditionRange`, `exploreMinutesPerLevel`, `expeditionRolls`.

## The table

Three tabs in the War Room window do what the commands do, so none of this needs the chat. **The
war table** lists everything a warband could march on — colonies, kingdoms, the nearest camp and
village — nearest first, with distance, direction, strength and standing, a *March* button lit only
when the men could actually go and the reason in small print when they could not, and a second
button for whatever else there is to do with that neighbour: *Peace* while at war, *Accept* or
*Withdraw* while an offer is on the table, *Ally* when the standing has earned it, *Convoy* the
rest of the time. Above them a button for how many go, *Recall*, *Ransom*, *Hire* (a sellsword a
press), the warband out, the men held or the sellswords waiting, and the last lines of the war
log. **Expeditions** shows
the Explorer, every kind of country within reach with the biome found and how far, a difficulty
button, a guards button, and *Go* beside each. **The convoy** is the loading bay: the manifest,
what has come, and the shelf to fill it from. Every press is re-checked on the server. And the red
**?** every MineColonies hut has is on the War Room and the Wall Tower too — the same book, with
pages of its own.

## Standing, and war

**Standing falls only when somebody is caught.** There is no drift, no proximity, no tick that
decides two neighbours must dislike each other. The only way down is something being done and the
victim knowing who did it.

**War is never declared by the game.** Low standing unlocks the choice; a person makes it. The war
is declared for a goal — **plunder**, **raze** or **conquer** — that decides what the victor may
take. `/war declare` is the entry point, and nothing in the mod calls it.

**Neither is peace.** Peace is a negotiation: one side puts terms on the table and the other
accepts or refuses them, by hand, within `offerDays`. The terms are a **white** peace, **tribute**
(gold from the other side), **pay** (gold to them) or their **vassalage**. Accepting does all of
it at once — the gold changes warehouses, every man held on either side goes home (a prisoner
exchange), a truce holds for `truceDays` and the war weariness on both sides is gone, because
that was the point. `/war offer <colony> [white|tribute <gold>|pay <gold>|vassal]`, `/war accept
<colony>`, `/war refuse <colony>`; `/war peace <colony>` offers a white peace. An offer nobody
answers lapses.

**Nor is an alliance.** Standing at or above `allyThreshold` (40, the mirror of the war threshold)
unlocks the offer the way low standing unlocks a declaration; the other side accepts, and then
neither may declare on the other and convoys between them carry three times the goods. Walking
out (`/war break`) costs the standing it stood on: the pair is back at nothing. `/war ally
<colony>`, `/war accept`, `/war break`.

### Depth

`warLevel` in the server config says how far a war may go, and the rest of the mod obeys it:

- **skirmish** — a warband marches and a battle is fought, in the world or as a number, and the
  winner carries off plunder; but no wall is breached, nobody is taken, no sellswords are for
  hire, and peace is a white peace or gold. The goals *raze* and *conquer* are refused.
- **siege** (the default) — breaches, captives and ransom, sellswords.
- **conquest** — all of that, and the goal *conquer*: a town overrun becomes the victor's
  **vassal** for `vassalDays`. The war is over at once (a truce), the vassal pays `tributeStacks`
  of goods out of its warehouse to its lord every day, cannot march on him, and is its own again
  when the days are up or its lord releases it (`/war release`). Vassalage can also be a term of
  peace. Nobody's colony changes hands: a vassal is still yours, it just pays.

### Sellswords

At siege depth or deeper the War Room hires **sellswords** — `mercenaryGold` a man, out of the
warehouse, up to `mercenariesPerLevel` times the War Room's level. They wait `mercenaryDays` for
the next warband and go with it, counted in its strength (six a man) and, in a raid fought in the
world, standing on the field beside the guards; they are not citizens, they cannot be held
captive, and they take a share of the plunder — a stack for every two of them. `/war hire
[count]`, or *Hire* on the table.

### Convoys

Goods to another colony, or a Waking World kingdom, with an escort — and the goods are chosen,
not grabbed. The War Room's **convoy tab** holds a manifest: pick the items and how many (a stack
a press, out of a search over everything there is, or the usual goods when nothing is typed), and
the room asks the colony for them the way a hut asks for its minimum stock — a courier brings them
from the warehouse into the War Room's racks, or you carry them over yourself; the tab says what
has come, and what is on the manifest is kept there, out of the couriers' own pick-ups. Then
*Convoy* beside a neighbour on the war table sends what has arrived, with as many War Room guards
as you choose and a courier from the warehouse when one is free — with nothing loaded, the button
opens the bay instead. A convoy takes only the manifest — the knights' swords in the same racks
stay — at most `convoyStacks` stacks, three times that to an ally. It walks the map like a warband
and, arriving, puts the goods in the partner's warehouse and earns `convoyStanding` with them —
the one way standing rises that costs something, takes time, and could be interfered with. At most
`convoysPerWeek` to the same partner. To a kingdom it is a gift that also pays a debt: a reprisal
the kingdom owed you and had not yet sent is forgiven. No road while at war. `/war load <item>
[count]`, `/war unload [item] [count]`, `/war convoy <colony> [guards]`, `/war convoy at <x> <z>
[guards]`, or the convoy tab and *Convoy* on the table.

## The campaign

Once a war is declared, the War Room's guards can **march**. They leave the world the way the
Nether Worker does, walk the map at a minute per 200 blocks (three minutes at the least), and what
happens at the other end depends on who is there:

- **Somebody is watching the town** — a real raid. The warband stands in the defender's Town Hall
  beside any barbarian horde, raises the same boss bar ("Warband of Ironhold — from the north"),
  and is fought by the same guards. Its soldiers are MineColonies raider mobs statted from the
  **attacking** colony's men, not from the defender's raid level, and carry the attacker's name.
  Half of them still standing when the siege's time is up — the next nightfall, by default — is a
  town overrun; fewer is a defence that held.
- **Nobody is** — a number: the warband's strength against the town's, with luck the size of the
  fight. The same numbers Colonist Thieves shows as "an army against 400".

The victor takes what the war was declared for. **Plunder** rolls a loot table by the town's wealth
and the margin and carries it home to the warehouse; **raze** knocks a breach in the wall nearest
the road the army came by (the Builder repairs it). Defeat sends the men home hurt. Marching eats
two rations a man out of the warehouse — short of food they still go, hungry, at three quarters
strength — and every campaign adds weariness to both towns; above 60 the men refuse, peace clears
it. All of it is written in the war log.

`/war march <colony> [guards]`, `/war march at <x> <z> [guards]` (whatever stands there),
`/war targets` (everything a warband could march on, with distance and strength), `/war recall`
(only while they are still on the road), `/war ransom`, `/war campaigns`, `/war log [lines]`.

### Kingdoms

With **The Waking World** installed a warband can march on one of its kingdoms — the war a
single player can actually have. No declaration: a kingdom is nobody's. At its gate the fight is
always a number, the warband against the keep's garrison, engines and arcs of wall as the kingdom
keeps them (the same figures Colonist Thieves shows). Win or lose, the kingdom now hates the
colony's owner — its guards turn on him within reach, its traders refuse him, its king will not see
him — and its standing with him falls; a sack costs twice as much and carries off the keep's gold.

**And the kingdom strikes back.** Some days after an attack its men come to the colony: a real
raid at the walls, fought by the colony's guards, the next time somebody is there — more of them
and sooner after a sack, forgotten if nobody is home for a week. If they have their way they carry
goods out of the warehouse and break the wall. `/war campaigns` says when they are due.

Config: `marchOnKingdoms`, `kingdomStandingCost`, `kingdomsStrikeBack`, `reprisalDays`.

### Camps and villages

A warband can also march on what is nobody's: a raider camp (MineColonies' barbarian, amazon and
desert camps, the pirate ship, a pillager outpost) or a village. A camp overrun is **cleared** —
its spawners smashed, the one thing a warband changes in the world for good — and yields the
raiders' goods; a village only loses what the men carry off. `/war targets` names the nearest of
each; what counts as which is the structure tags `#colonies_at_war:camp` and `:village`, so a
datapack can add any structure. Config: `marchOnCamps`.

### Veterans, and the men who fell

Every guard who fought earns experience on his guard job's skills when he is home — half again
for a victory — so a warband that has been out is worth more (`campaignExperience`). And nobody
dies at this depth: the men who fell in a lost fight are **held** by the colony or kingdom that
beat them, up to half the warband, until a ransom is paid — `/war ransom`, `ransomGold` a head out
of the warehouse, and a colony that holds them gets the gold — or they are let go after
`ransomDays`. `/war campaigns` lists who is held and what he costs. Config: `captives`.

The `campaigns` block of the server config: `siegeInWorld`, `armyLook` (barbarian, norsemen,
pirate, amazon), `siegeNights`, `marchMinutesPer200Blocks`, `marchMinimumMinutes`, `plunderRolls`,
`rationsPerGuard`, `truceDays`, `offerDays`, `mercenaryGold`, `mercenariesPerLevel`, `mercenaryDays`,
`vassalDays`, `tributeStacks`, `allyThreshold`; the `convoys` block: `convoys`, `convoyStacks`,
`convoyStanding`, `convoysPerWeek`. Design notes in [docs/CAMPAIGNS.md](docs/CAMPAIGNS.md).

## Building

See [BUILDING.md](BUILDING.md). Plain `javac`, no Gradle; the blueprints are generated from Python.

Made by Lovkar & Claude.
