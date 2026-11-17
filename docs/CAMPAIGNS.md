# Campaigns — the war itself (0.2.0, skirmish depth)

Until 0.1.6 `/war declare` changed a word and wrote a log line. Nothing marched. This is the
warband: the War Room's guards go out, a battle is fought, they come back with plunder or wounds.

## The shape

A campaign is the thief's expedition with an army in it — and MineColonies already has the
mechanism for a citizen who leaves the world: **`ITravellingManager`**, the same thing the Nether
Worker uses. `startTravellingTo(citizen, warRoom, ticks)` + `entity.remove(DISCARDED)`; the colony
will not respawn a travelling citizen, and `updateEntityIfNecessary()` puts him back where we say
when we call `finishTravellingFor` first. No invisible guard standing in a corner, no mixin, no AI
state we do not own. Travel time = the thief's rule: a minute per 200 blocks, three minutes floor,
each way.

Phases, kept in `Campaigns` (a SavedData per overworld, like `Standing`):

    MARCHING → (BESIEGING) → RETURNING → DONE

At arrival the battle is one of two things, and both feed the same outcome:

- **the defender's colony is loaded and active** → a real MineColonies raid: `ArmyRaidEvent`
  extends `HordeRaidEvent`, sits in the Town Hall beside a barbarian horde, boss bar
  "Warband of <colony> — from the north", the soldiers are MineColonies raider mobs re-statted
  from the **attacker's** guards (never from the defender's raid level, which is what MineColonies
  does by default). Their guards fight it. Survivors decide: wiped out = the defence held; still
  standing when the night ends = the town was overrun. Since 0.3.0 one of them is the warband's
  named captain ("Captain Aldric of Ironhold" — see *Captains* below); the rest are "Soldier of
  Ironhold", a chief who is not the captain included.
- **nobody is there** → the siege is a number. `Battle.roll`: attack against defence with luck
  that scales with the size of the fight, the thief's `Heist.roll` shape.

Attack = per marching guard `4 + min(10, combat/10)` where combat is the mean of Adaptability and
the better of Strength and Agility, plus War Room level ×2, minus a quarter if the warband marched
hungry. Defence = the thief's `strength()` family: guards at home ×4 + military
×2 + walls + raidLevel/10. Same numbers the Hideout already shows as "an army against 400".

## What the victor takes — the war goal

`Standing.Relation.goal` was free text. It now means something:

- **plunder** (default) — a datapack loot table (`loot_table/plunder/colony.json`), rolled by wealth
  and margin, into the attacker's warehouse when the warband is home. Skirmish takes "a little";
  it does not empty anybody's racks.
- **raze** — a breach: the wall blocks nearest the army's approach are knocked down (only with
  `wallsCanBreak`, which already existed for exactly this). The register drops them itself; the
  Builder repairs them with `/war raise`. A bruise, not a death.

Defeat: the warband comes home hurt — health down, nobody dead at skirmish depth.

## Supply and weariness

Marching costs food: two per guard out of the warehouse at muster. Short of it, the men still go,
hungry, at three quarters strength — the report says so. Every campaign adds **weariness** to both
colonies (15 to the one that marched, 10 to the one attacked); it cools three a day, and above 60
the War Room refuses to muster ("the men will not march again so soon"). Peace clears it. That is
the war chest, supply line and war weariness of the wish list in three numbers.

## The war log

`WarLog` keeps the last 200 lines per world: who marched, with how many, what happened, what was
taken. `/war log` reads the ones about the colony you stand in. Both colonies' managers get a chat
line at each turn of a campaign.

## Registering the event — the trap

`EventManager.readFromNBT` builds the type id as `new ResourceLocation("minecolonies", name)` — it
saves only the path. A colony event registered under `colonies_at_war:` is **dropped on every
reload** with "Event is missing registryEntry!". So the event is registered on MineColonies'
registry under **their** namespace with a path that cannot collide: `minecolonies:caw_army`. (NeoForge
does not even warn about the foreign prefix on a custom registry.) The alternative is a siege that
vanishes when you quit. Proven headless: the raid is written and read back through MineColonies'
own `EventManager` as itself, and a server restart remembers every campaign, the log and the standing.

## Two more traps, found on the way

- **`RaidManager.onRaidEventFinished` reads `raidHistories.get(0)`.** MineColonies' own raids write
  a history line when they spawn; this event never did, so on a colony that has never been raided
  the superclass `onFinish` throws out of the colony tick. `ArmyRaidEvent.onFinish` asks
  `getAllRaids()` first and, with no history, cleans up by hand (soldiers, camp fires, boss bar).
- **`RaidManager.getRandomBuilding()` reads `getLastRaid()` without a null check**, and every
  raider calls it to pick the hut it walks at once the camp is struck. On a never-raided colony that
  is an NPE out of every soldier's AI, every tick - the warband stands at its fires all night. So
  `ArmyRaidEvent.onStart` writes the warband its own `RaidHistory` page (the list is private -
  reflection; NeoForge mod modules are open). Honest anyway: the Town Hall's raid info lists it as
  `minecolonies:caw_army`, its dead are counted, citizens lost to it weigh on the town's raid
  difficulty like any raid - which also means a town that repels a warband cleanly gets slightly
  harder barbarian raids afterwards, MineColonies' own adaptive rule. `onRaidEventFinished` is
  still never called: it would announce "the raiders were slain" whatever happened.
- **A guard building's hiring limit is combined.** `GuardBuildingModule.isFull()` counts every
  citizen the building has against the one module's maximum — that is what the Barracks Tower's
  knight, ranger and druid modules each saying "building level" means. 0.1 split the War Room's
  garrison between its knight and ranger modules and so halved it; a level-3 room held three men,
  not six. Both modules now say the whole garrison, and the same fix went into the Wall Tower.

## Proven

`/root/campaigngate`, a probe mod on the headless rig: two colonies, six guards, five campaigns —
a number; a raid saved and loaded while it stood, six soldiers on the field 140 blocks out who
strike camp and walk on the town, four of them struck down (MineColonies' raid history counts
them), the night decided at nightfall as a defence that held; a breach; a recall; and a second raid
that overruns the town — men home visible, hurt or laden each time, plunder in the warehouse, the
log and `/war campaigns` right after a restart, zero warnings from the mod. Camp fires are
MineColonies' own business (a rough shore may get none) and are only noted.

## Watched, not active

`IColony.isActive()` is true for UNLOADED as well — the owner online but far away, the town's
chunks kept loaded by the Town Hall — and an UNLOADED colony ticks no events. A siege laid there
would stand frozen until somebody walked up to it. So the warband fights for real only when the
colony's state is ACTIVE, and a siege that outlives its grace (the nights plus one day) is closed
and settled as a number rather than left to restart when the player comes back.

## Kingdoms — the war a single player can have

Another colony is a rare thing in singleplayer; a Waking World kingdom is not. `compat/Kingdoms`
is a reflection bridge (the thief's, plus two writers) onto Waking World's public `KingdomData`:
centre, tier, standing, wall arcs, catapults, houses. `/war march at <x> <z>` resolves what stands
there — a colony (the declared-war path) or a kingdom (`kingdomAt`, within 120 blocks of the keep,
no declaration). A campaign carries `kind` and `target` beside `defender` (0 for a kingdom).

At a kingdom's gate the fight is always `Battle.roll`: attack against
`(2 + tier*2 + catapults)*4 + tier*4 + min(100, arcs*12)` — the thief's `Profiles.kingdom`
exactly, so "an army against 72" reads the same in both mods. Won or lost:
`KingdomGrowth.favour(-kingdomStandingCost, "war")` and the owner goes into the kingdom's
`angryUntil` map for a day (its guards hunt him, traders refuse, the king will not see him — all
Waking World's own consequences); a sack costs the same again, three days of anger, and plunder from
`plunder/kingdom` by the kingdom's wealth (`25 + tier*12 + houses`). Standing feeds Waking World's
`tierFor`, so a kingdom sacked often enough shrinks.

**Reprisals.** A kingdom has no War Room, so the mod keeps its clock: every attack writes a
`Reprisal` (kingdom, colony, due, expires, size, strength) into the same SavedData; a second attack
folds into it (bigger, not a second raid). Due after `reprisalDays` (half that for a sack),
`size = 2 + tier*2 + catapults` (+`tier*2` for a sack, 3..16), strength at least the kingdom's
defence. It is paid only when the colony is ACTIVE and loaded — a raid nobody sees is not revenge —
as an `ArmyRaidEvent` with `attacker = 0`, `attackerName` the kingdom's, `campaign = -id` (its
captain is "Captain <name> of <kingdom>", his trophy the kingdom's purple and gold); the
event's finish routes negative ids to `reprisalEnded`. The kingdom's men still standing at the end
(`Battle.fought`) carry `plunderRolls*2` stacks (16 each) out of the warehouses and `Breach` the
wall from the direction of the keep. Unpaid for a week, the debt is forgotten; standing at a town
that went quiet past its grace, the raid is closed and the men "go home".

Proven headless (`/root/kingdomgate`, against `wakingworld-0.3.0-beta.4`): a kingdom record with
tier 2 / 3 arcs / 1 engine / 4 houses / standing 20 reads as defence 72 and wealth 53; a march
thrown back costs 10 standing and a day of anger; a sack costs 20 and carries off the keep's gold;
one debt folded from the two attacks (11 soldiers, strength 80, due in a day) is paid as a raid in
Ironhold's Town Hall, eleven "Soldier of Hearthgard" on the field, and with nobody fighting them
they carry 28 items out of the warehouse. `/war targets` and `/war march at` from the console.

## Camps and villages — what is nobody's

`Places` resolves a position through two structure tags (`#colonies_at_war:camp`,
`#colonies_at_war:village`, the thief's approach: chunk-level `getAllStructuresAt`, a piece hit
wins) and finds the nearest of a kind with `findNearestMapStructure` (1200 blocks). Since 0.3.0 the
tags also name other mods' structures, each `"required": false` (Kings & Monsters' ogre camps,
IFC-Integrations' goblin camp and dwarf village, Valarian Conquest's camps, outposts and castles,
the kobold den); `tools/check_tags.py` reads every such id back out of the mod's jar. A place's name
is its structure id's path made readable, a trailing biome word or number dropped
(`ogre_outpost_plains` -> "Ogre Outpost").

Who stands there is counted only when the place is loaded, inside the structure start's box
(inflated by 8) together with the old 48-block reach, every mob once, in this order: Guard
Villagers' guard (entity type `guardvillagers:guard`, looked up with `getOptional` - the entity
registry defaults to the pig), iron golem, armed (`Enemy`, or a main-hand bow or crossbow or an
item whose attribute modifiers add attack damage, which covers other mods' weapons), folk (not
`Enemy`, `Animal`, `WaterAnimal`, `AmbientCreature` or `AbstractGolem`: villagers, kobolds,
dwarves). A camp's defence is `20 + spawners*5 + armed*3`; the spawners are counted by walking the
structure start's bounding box - **by chunk** (`startsForStructure(ChunkPos)`), because the
nearest-structure search hands back a position at y = 0 and `getStructureAt` wants the block
inside the box. A warband that overruns a camp smashes every spawner in it - the camp goes quiet
for good - and rolls `plunder/camp`; a village (`max(20, 12 + golems*15 + guards*villageGuardWeight
+ folk*2 + armed*3)`, the weight 12 by default) only yields `plunder/village`. Nobody holds either
against anybody.

A village's guards fight back (`Campaigns.fightGuards`, the numbers in `Battle.guardsFell` and
`Battle.lostToGuards`). Of the guards, `round(had * (0.5 + 0.5*margin))`, one at least, fall when
the warband wins, `round(had * 0.25 * (1 + margin))` when it is thrown back. The warband loses `had`
men when thrown back and `ceil(had / 2)` when it wins, capped at what it brought: sellswords first
(`mercenaries` goes down), then its own guards, the last of `guards`, who are not killed - a fight
nobody watched does not kill a colonist - but come home at 1 health (`carried`). The report gets
"; N village guards stood against it and M fell; it lost K men (...)". The campaign saves
`villageGuards`, `villageGuardsFell`, `lostToGuards` and `carried` (a 0.2.2 save reads 0), for the
village's reprisal. Guard Villagers puts guards of its own into a village it sees built, so with
that mod installed most villages already have several: a plains village on the rig came to 117 with
its own 6. Note the rig runs with
`generate-structures=false` by default; `camprun.sh` turns it on.

## Veterans and captives

Home from a fight, every guard gets `campaignExperience` (×1.5 for a win) through MineColonies'
own `CitizenExperienceHandler.addExperience`, which lands on the guard job's primary and secondary
skills with the building and intelligence modifiers - `Battle.worth` reads those skills, so a
warband that has been out is stronger. And the men who fell are not dead: `take()` holds
`min(fallen, marched/2)` after a raid fought in the world, or `round(marched * clamp(-margin, 0,
0.5))` after a number, as `Captive`s in the same SavedData - the holder is the colony (id) or the
kingdom (id 0, position), `until = now + ransomDays`. A held man's travelling gets a fresh, longer
clock so MineColonies never brings him back first; `arriveHome` skips him, `sweep` leaves him,
`depart` cannot muster him, `Battle.defence` does not count him. `/war ransom` pays
`ransomGold` a head in gold from the warehouse - to the holding colony's warehouse, or to the
kingdom (+5 standing) - and `free()` brings them home at half health; the clock lets them go for
nothing at `until`. Proven in `campaigngate`: 2 held after a numeric rout (margin -0.4), let go on
the clock; 3 held after the siege where 4 fell (half of six), ransom refused with no gold, paid with
12, everyone home.

## Expeditions — a march with a biome at the far end

The Explorer is a `WorkerBuildingModule` on the War Room (job `colonies_at_war:explorer`,
Adaptability/Agility, one; the Courier's model). He sits in a guard building's combined hiring
count, so `BuildingWarRoom.garrison` adds the explorers hired back on, and `Standing.isGuard`
keeps him out of `Battle.defence`, `depart` and the alarm. An expedition is a `Campaign` of kind
`expedition`: `explore()` gathers the explorer plus `wanted` fit guards through the same
`sendOff` the warband uses, `arrive` starts a clock of `exploreMinutesPerLevel × difficulty`
(the BESIEGING phase, reused), `conclude` rolls `Expeditions.roll`, and `arriveHome` brings
everyone back through `everyone()`.

The country is a survey: every 64 blocks out to `expeditionRange`, `level.getBiome` off the noise
(no chunk is loaded), each sample sorted into a **family** by an ordered list of predicates -
explicit ids first (caves, snow, mushroom, cherry, swamp, desert, plains), vanilla tags after
(jungle, badlands, savanna, mountain, taiga, forest, ocean, beach, river), `wilds` for the rest -
and the nearest of each kept, five minutes. Underground families are read at y = -24 only, the
rest at y = 70 only, so lush caves do not become the nearest thing to every town.

The haul is two rolls. The land: `expedition/<family>` (chest table, `2 + difficulty×2 +
guards/2 + skill/20` rolls, luck `skill/25`), `expedition/wilds` when the family has none. The
beasts: the biome's own `MobSpawnSettings` for MONSTER (`difficulty×2 + guards + 0..2` kills)
and CREATURE (`2 + difficulty + 0..2 + skill/20`), water spawns only where the water is the point
(ocean, river, beach, swamp), the glowing ones only underground; each kill is `type.create(level)`
never added to the world, its `getLootTable()` rolled with `LootContextParamSets.ENTITY` (this
entity, origin, a generic damage source - no player, so no player-only drops), then discarded.
The danger: `strength = 10 + guards×5 + skill` against `danger = difficulty×6 + hostiles`, luck
`±max(8, 0.35×(s+d))`; a loss drops every second stack and sends them home at 35 %, a win at
`1 − 0.1×difficulty`. Proven in `expeditiongate`: a normal trip with two knights to a frozen
shore killed salmon, slimes, spiders, a skeleton, squid, brought 21 stacks (warehouse 64 → 115),
everyone home visible, the explorer up 107 xp; a deadly trip alone was driven out (16 −4 against
50), 13 stacks, home at 35 %.

## Terms — peace, alliance, vassalage

`Standing.Relation` grew an `Offer` (from, kind, terms, gold, until) and a vassalage (lord,
vassal, until); `Standing.state()` reads all the clocks — a truce run out, an offer lapsed, a
vassalage over — and tells the three loose states (PEACE, TENSE, WARM) apart by the standing
alone, so a pair that rises past `allyThreshold` reads WARM without anybody pressing anything.
`campaign/Terms` is the negotiation: `offerPeace` / `offerAlliance` put an offer on the table and
tell the other town's managers; `accept` does the terms — gold out of one warehouse into the other
(refused, with the offer left standing, when the payer is short), `Standing.peace`, the weariness
cleared, every captive either side holds of the other freed ("exchanged at the peace"), and for
`vassal` terms `Campaigns.subjugate`; `refuse` clears it; `breakAlliance` drops the pair to PEACE
at 0. `mayDeclare` is false for allies and across a vassalage; `Standing.ally` refuses at war.

The war goal `conquer` (declared at conquest depth only) makes a won campaign end the war on the
spot: `Campaigns.subjugate` writes a `Vassalage` (lord, vassal, until = now + vassalDays days,
lastDay) beside the campaigns, `Standing.subjugate` sets TRUCE until the vassalage is over and
then `truceDays` more, and the clock pays `tributeStacks` of `takeGoods(vassal)` into the lord's
warehouse once a game day (`lastDay`), logs it, tells both, and frees the vassal when the days are
up. `cannotMarch` refuses a vassal its lord. `/war release` frees one early.

## Depth

`warLevel` finally does something. `WarConfig.depth()` is 0/1/2 for skirmish/siege/conquest and
is read where it matters: `declare` refuses *raze* below siege and *conquer* below conquest,
`resolve` breaches only at siege or deeper and subjugates only at conquest, `hire` refuses below
siege, `Terms.cannotOfferPeace` refuses *vassal* below conquest. Captives are still governed by
`captives`. The default moved from skirmish to **siege**, because that is what every earlier
cut did regardless; a world whose config already says skirmish now gets a real skirmish.

## Sellswords

`Campaigns.sellswords` is a map of colony → (count, until). `hire` takes `mercenaryGold` a man
out of the warehouse (put back if short), caps at `sellswordCap` = War Room level ×
`mercenariesPerLevel`, and resets the `until` clock to `mercenaryDays`. `depart` takes them off
the map and onto the campaign: `Campaign.mercenaries`, `attack += n × SELLSWORD_WORTH (6)`,
`soldiers() = guards + mercenaries` is the horde size of an in-world siege. A win costs
`sellswordsShare`: `max(1, n/2)` stacks off the end of the plunder, never the last one. They are
not citizens: no captives, no wounds, no experience. The clock sends unused ones away when
`until` passes.

## Convoys

A campaign of kind `convoy`: `Campaigns.convoy(colony)` / `convoyTo(kingdom)` take what the
War Room's **manifest** has and the room holds — `ConvoyModule.take(stacks)`, `convoyStacks`
stacks at most (×3 to an ally) — into `plunder`, an escort of `fitGuards`, and a courier — the
first `JobDeliveryman` who is fit — into `courier`; all of them `sendOff` like a warband.
`arriveWithConvoy` delivers into the partner's warehouse and `mend`s the standing by
`convoyStanding` (a kingdom: `Kingdoms.favour`, and any reprisal it owed the colony that has not
yet marched is forgiven), then `turnHome`; home, the escort is unhurt and whatever could not be
delivered goes back to the warehouse. `Standing.convoys` counts departures per "from->to" (a
kingdom keyed by its centre) and `cannotConvoy` refuses past `convoysPerWeek`, at war, or with
one already out; nothing loaded is a reason at send time, not a closed road, so the table's
button can open the bay instead.

The manifest is `ConvoyModule`, a `IPersistentModule` + `ITickingModule` + `IAltersRequiredItems`
on the War Room, and it is MineColonies' minimum stock turned into a one-off order. One
`ItemStack` line a kind, `add(kind, ±count)` under a cap of `convoyStacks × 3` stacks (a line's
stacks are `ceil(count / maxStackSize)`), `loaded(line)` off `InventoryUtils.hasBuildingEnough-
ElseCount` over the hut and its racks. `onColonyTick`: one open `Stack` request a line for the
whole shortfall (`Stack(kind, false, true, EMPTY, short, 1)`, async, matched against
`getOpenRequestsByRequestableType` by `compareItemStacksIgnoreStackSize`), cancelled when the
line is full or gone. `alterItemsToBeKept` keeps the manifest's counts in the room so a courier's
pick-up does not fetch them back. `take` extracts line by line out of `getItemHandlersFromProvider`
under the cap, merges into whole stacks, reduces the lines by what left (an empty line drops),
and calls the couriers off; `clear` does the same with nothing taken. The view carries the cap,
each line and its loaded count through `ItemStack.OPTIONAL_STREAM_CODEC` (which
`check_packets.py` now reads as a field). `WarTableMessage` `LOAD` (item id + signed count) and
`UNLOAD` change it; `/war load` and `/war unload` are the same two verbs from the chat.

Proven in `termsgate`: three towns at conquest depth; two sellswords hired (gold out, the cap and
an empty purse refused), a war to conquer marched with them (strength counts them, the War Room
is empty of them), won — Millbrook a vassal, a truce, nobody may declare, the sellswords' stack
taken, tribute paid out of one warehouse into the other when a day passes, freed when the days
are up; war again and peace for 10 gold offered, the offerer unable to accept his own offer, a
100-gold demand refused as unpayable, 10 gold moved on acceptance with a truce and no weariness;
a white peace refused; Oakford mended past the threshold, an alliance offered and accepted,
neither able to declare; the manifest loaded three lines, capped at twelve stacks and the rest
refused, asked for from the couriers (three open requests), two lines and half the third
"delivered" and counted, the full lines' requests cancelled, a stack of torches taken under
`take(1)` with the rest left wanted, torches taken off the manifest but left in the racks; then
a convoy with two guards that carried the manifest and nothing else — the torches and the
warehouse untouched — delivered and the standing up by eight, the half line kept wanted, the bay
torn up with its request gone, the escort home unhurt, the weekly cap and the closed road at war,
the alliance broken to nothing; everything saved and read back.

## Captains — one man at the head of every raid (0.3.0)

`campaign/Captains`. Every `IColonyRaidEvent` with men in the world gets one captain: the warband,
a kingdom's reprisal, MineColonies' barbarians, pirates (on the ground, by ship, drowned),
Egyptians, Norsemen and Amazons, and whatever raid another mod adds. Nothing hooks into a raid —
MineColonies' events are theirs, and a mixin into each would break every month — so twice a second
each colony's events are copied and looked at from outside, the way `RaidAlarm` finds raiders.

- **One per raid, ever.** A line per raid in the `colonies_at_war_captains` SavedData on the
  overworld, keyed `<dimension>/<colony>/<event>`, says it has had its captain; a raid with a line is
  never given another, dead captain or not. Lines go when the event leaves the colony's event map.
  An old save has no such file: an empty one.
- **Who.** The event's boss raider type (or an `IChiefMobEntity`) at once. Failing that, after 100
  ticks — a ship lands its crew a few at a time — the first (lowest entity id) ordinary melee raider:
  the event's normal raider type or an `IMeleeMobEntity`, never an archer; failing even him, anyone.
  A boss who arrives later is a soldier.
- **Name.** Lang keys, twelve a list: barbarian, pirate (the drowned too), Egyptian, Norse, Amazon,
  and plain names for a warband or an army, which are "Captain <name> of <attacker>". Picked by a
  hash of dimension, colony and event, stepped past any name a captain of another raid on the same
  colony already has. `ArmyRaidEvent.registerEntity` gives a returning captain his own name back,
  the other men "Soldier of <attacker>" (a lang key now, too).
- **Gear.** A ladder: wood/leather 0, stone/chainmail 1, iron 2, diamond 3, netherite 4. The men's
  tier is the best piece any of them has on, and men with nothing tiered count as stone. The captain
  gets a full armour set and a weapon at the men's tier + 1 (an axe for an axe-man, else a sword); a
  weapon already as good stays (MineColonies' chief sword is diamond-tier), and a bow, crossbow,
  spear or the pharaoh's scepter stays with Power I or Sharpness I. Drop chance 0 on the four
  armour slots and the main hand: his kit is not farmable. A chief's own loot table is untouched.
- **Health.** A `colonies_at_war:captain_health` modifier, `ADD_MULTIPLIED_BASE`,
  `captainExtraHealth / 100`. A modifier, not a base: MineColonies re-stats every raider on a
  reload and the warband re-stats on every registration, and both only set the base.
- **Bar.** A `ServerBossEvent`, red, progress = health, for players within 64 blocks, brought up to
  date every 10 ticks. It goes on his death, on `EntityLeaveLevelEvent` (unload, despawn, the raid's
  own clean-up) and when his event is done, cancelled or gone; it comes back on
  `EntityJoinLevelEvent`, and from the scan for a captain whose chunk came in before his colony's raids.
- **The mark.** Compound `colonies_at_war_captain` in the entity's persistent data: colony, event,
  name key, attacker name and id, raid kind, `fell`, `trophy`. It is what brings him back as himself.
- **His fall.** `LivingDeathEvent` at LOWEST, not when cancelled: one line to the colony's players,
  once (`fell`). A `DamageSourceKeys.DESPAWN` death — MineColonies' own after a long night — is no
  fall. `LivingDropsEvent`: the trophy, when the killer (the source's entity, else the kill credit)
  is a player or a citizen whose job `isGuard()`, mob loot is on, and neither the mark nor the saved
  line says it has dropped. A banner, a design per raid kind — a warband's is its colony's flag on its
  team colour — or with `captainTrophy = weapon` a copy of his main hand; named "Banner of <name>"
  or "Weapon of <name>", lore: the raid, the colony, the colony's day.

Proven headless (`tools/capgate`, NeoForge 21.1.249, MineColonies 1.1.1420): MineColonies' own
barbarian raid started through its raid manager, a warband of Ironhold and a chiefless warband of
Stonebrook at once. Each got one named captain — the chief of the first two, the first barbarian of
the third — in iron over stone-axe men (the chiefs keeping their chief swords), drop chances 0,
27/27 on a base of 13.5, 60/60 on 30, 40/40 against his men's 20, and a red bar at 1.0 that read 0.5
at half health; a chief sent to Stonebrook afterwards stayed a soldier. Killed by a fake player's
attack, each dropped exactly one named trophy with both lore lines (the colony's name, the colony's
day) and none of his armour, his bar went, a second death and a second call of the drops hook
dropped nothing, and five seconds later nobody had been promoted in his place. The gate sets
`captainTrophy` itself before each kill, so one run proves both kinds: banners from the two
chiefs, the iron axe from Stonebrook's captain. MineColonies treats a fake player's blow like the weather —
it can wound a raider, never kill him — so the gate's killing blow is `die` with the fake player's
attack as the source.

## Not in this cut

Betrayal of an ally costing standing with every third colony that saw it; a convoy as something
Colonist Thieves can rob on the road (the bridge does not yet name convoys); a Waystation hut and
a Carter of its own; joint war between allies.
