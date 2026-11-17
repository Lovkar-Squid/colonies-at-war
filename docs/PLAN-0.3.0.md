# Colonies at War 0.3.0 — plan

Round 2 of the Discord ideas: raid targets from other mods (Enderking90's list), raids with
personality (NikoChilv0), and the convoy as something Colonist Thieves can rob. The owner answered
the four design questions on 16 Nov 2026; this file is those answers turned into chunks.
Every chunk keeps the mod's rules: no compile or load dependency on another mod (other mods are
reached through registry ids, tags and reflection only), MineColonies addon gotchas (see
BUILDING.md and the comments in the code), `WarBridge` is a contract (methods are only ever added),
and every new player-facing text is English with a lang key.

Version: **0.3.0** (`resources/META-INF/neoforge.mods.toml`). Each chunk adds its part to
`docs/CHANGELOG-0.3.0.md` in the voice of the earlier changelogs (prose, no marketing), and to
`docs/CAMPAIGNS.md` where it changes how campaigns work.

## Test rig (Windows)

- Build: `powershell -NoProfile -ExecutionPolicy Bypass -File "D:/Claude/addon-rig/build-addon.ps1" -Repo "D:/Claude/caw-030/repo" [-Probe <name>]`
  writes `colonies_at_war-<version>.jar` in the repo root and, with `-Probe`, `D:/Claude/addon-rig/probes/<name>.jar`
  from `tools/<name>/*.java` + `tools/<name>/res/` (the probe's own `META-INF/neoforge.mods.toml`, modId = the
  probe name, depending on `colonies_at_war` and `minecolonies`, side SERVER).
- Run: `powershell -NoProfile -ExecutionPolicy Bypass -File "D:/Claude/addon-rig/run-rig.ps1" -Name <name> -Jars "<jar>,<jar>" [-Mode <word>] [-Config <dir>] [-ErrorMatch <regex>]`
  (one comma-separated string; wildcards allowed, e.g. `D:/Claude/caw-030/repo/colonies_at_war-*.jar`). A NeoForge
  21.1.249 dedicated server (flat world, `generate-structures=false`, no players, online-mode off, port 25669)
  with MineColonies 1.1.1420, Structurize, BlockUI, Domum Ornamentum and Multipiston always loaded; a fresh world
  every run. The probe logs `[<name>] CHECK <what>: OK|FAIL <detail>` lines and last `[<name>] RESULT PASS|FAIL`,
  then halts the server (`server.halt(false)`). Exit 0 only on RESULT PASS, no FAIL line and no ERROR/Exception
  line matching `-ErrorMatch` (default: the probe name; pass `(?i)colonies_at_war|me\.lovkar\.war|<name>`).
  One run at a time (a lock file). Full log in `D:/Claude/addon-rig/riglogs/<name>[-<mode>]-latest.log`.
  `D:/Claude/addon-rig/probesrc/rigboot/RigBoot.java` is the smallest working probe;
  `D:/Claude/voyager-040/repo/tools/colonytest/ColonyTest.java` builds a colony with citizens and buildings on this
  same rig (copy what you need, keep probes self-contained).
- Other mods' jars for reading ids and for loading on the rig: `D:/Claude/caw-compat-jars/<mod>/` (Guard Villagers
  2.4.12, Kobolds 4.0.2, IFC-Integrations 4.0.1, Valarian Conquest 4.2.2 load with nothing else; Kings & Monsters
  1.2.2 needs GeckoLib and more, so it is read, never loaded). They are third-party: never copy them into the repo.
- Static checks: `bash "D:/Claude/caw-030/check-all.sh"` runs every `tools/check_*.py` (block ids, packets, views,
  lang, walls) against the pack's jars.

## C1 — named captains on every raid

Every raid on a colony is led by one named captain: MineColonies' own raids (barbarians, pirates, Egyptians,
Norsemen, Amazons, drowned pirates — whatever `IColonyRaidEvent` MineColonies runs) and every `ArmyRaidEvent`
(warbands, kingdom reprisals, and the camp/village reprisals of C3).

- Exactly one captain per raid event (colony id + event id). Prefer the event's boss raider type when one spawns;
  otherwise promote the first ordinary melee raider. A later boss does not create a second captain.
- A name from per-culture name lists (barbarian, pirate, Egyptian, Norse, Amazon; a warband or army uses its
  attacker's name: "Captain Aldric of Ironhold"), picked deterministically from the event so it survives a reload.
  `ArmyRaidEvent`'s "Captain of <attacker>" becomes that named form.
- Better gear: a full armour set and a weapon one tier above his men (no drop of armour or weapon: the gear is not
  farmable), and more health (config).
- A boss bar (`ServerBossEvent`, red, progress = health) shown to players within 64 blocks of him; gone when he dies,
  despawns, unloads or the raid ends; back after a server restart when his entity loads (keep the captain mark and
  his name in the entity's persistent data).
- When he falls to a player or a colony guard: a trophy drops — his banner by default (a banner in the raid's
  colours, named "Banner of <name>", lore: which raid on which colony and the colony day he fell), his weapon as
  the config alternative. One trophy per captain, never twice (a reload or a second death event drops nothing).
- Colony members get one chat line when he takes the field ("<name> leads the <raid> against <colony>!") and one
  when he falls.
- Config in `WarConfig` (server toml): captains on/off (default on), health bonus, boss bar on/off, trophy
  `banner|weapon` (default banner).
- Rig probe `tools/capgate`: builds a colony, starts a MineColonies raid through MineColonies' own raid API and an
  `ArmyRaidEvent`; checks one captain per event with a name, gear and the health bonus, the boss bar registered,
  kills each captain with a fake player's damage, checks one named trophy with lore in the drops and the boss bar
  removed, and that a second captain never appears in the same event.

## C2 — raid targets from other mods, Guard Villagers

- Structure tags `#colonies_at_war:camp` and `#colonies_at_war:village` get the other mods' structures, every
  entry `"required": false` so nothing changes without the mod:
  - camps: `kingsandmonsters:ogre_outpost_plains`, `kingsandmonsters:ogre_outpost_swamp`,
    `kingsandmonsters:ogre_kings_fort`, `ifcintegrations:goblin_camp`, `valarian_conquest:bandit_camp`,
    `valarian_conquest:bandit_ruins`, `valarian_conquest:bandit_ruins_2`, `valarian_conquest:neutral_tent_camp`,
    `valarian_conquest:neutral_tent_camp_2`, and the faction outposts and castles
    (`valarian_conquest:<faction>_outpost|castle`, faction = barathian, bohemian, burgundian, hospitaller,
    orleanian, templar, valarian, visgothian, plus `neutral_outpost`);
  - villages: `kobolds:kobold_den`, `ifcintegrations:dwarf_village`.
  The ids were read from the mods' jars (Kings & Monsters 1.2.2, Kobolds 4.0.2, IFC-Integrations 4.0.1, Valarian
  Conquest 4.2.2). A `tools/check_tags.py` reads every id back out of the jars in `D:/Claude/caw-compat-jars`
  (or `WAR_COMPAT` env) and fails on a typo.
- A camp's defence counts its armed defenders whatever mod they come from (a hostile mob, or any mob holding a
  weapon); a village counts golems, Guard Villagers' guards and its folk (villagers, kobolds, dwarves: the
  non-hostile living mobs in the structure's box). Names in `/war targets` and the War Table read the structure's
  own name (its id path made readable: "Ogre Outpost Plains" -> "Ogre Outpost"), not "a camp".
- **Guard Villagers, fully** (reached by the entity id `guardvillagers:guard` only, no class link):
  - each guard counts in a village's defence like a golem (config weight, default 12);
  - a warband that wins against a village with guards loses men by the guards' count (they fight), and the
    battle report names them ("three village guards fell");
  - when Guard Villagers is installed, a sacked village's guards become the core of its reprisal in C3
    (C2 only records how many guards the village had and how many fell, on the campaign, saved).
- Plunder: modded camps use `plunder/camp`, modded villages `plunder/village` (no new loot tables needed); bog iron
  and other mod items stay out (no item ids of other mods in our loot tables).
- Rig probe `tools/targetgate` (with Guard Villagers, Kobolds, IFC-Integrations and Valarian Conquest loaded):
  the two tags resolve the loaded mods' structures (kobold den and dwarf village as villages, goblin camp and the
  Valarian camps as camps); a placed vanilla village with spawned Guard Villagers guards reads a higher defence
  than without; `Places.at` on a placed modded camp (place it with the structure's own template or
  `/place structure`) resolves it as a camp with its readable name.

## C3 — raided camps and villages strike back

Like Waking World kingdoms (the `Reprisal` in `Campaigns` is the model and stays as it is for kingdoms):

- A warband that attacks a camp or a village (won or lost) leaves a debt: the place's survivors come for the colony
  some days later (`reprisalDays`, half for a sack), as an `ArmyRaidEvent` named after the place ("Raiders of the
  Ogre Outpost", "Militia of <village name>"), led by a C1 captain.
- Bigger raid, bigger reprisal: size from the place's defence and how hard it was hit (spawners smashed, plunder
  rolls, men lost), clamped 3..16 like a kingdom's; a second attack on the same place folds into the same debt.
- With Guard Villagers installed, a village's reprisal is led by its surviving guards (their count from C2 sets the
  core of the size, their look: iron gear and shields).
- Paid only when the colony is ACTIVE and loaded (nobody home, no revenge); unpaid for a week, forgotten — the same
  rules and config as the kingdom reprisals (`reprisalDays`, the week).
- Saved and read back with the old saves (a 0.2.2 save loads; kingdom reprisals keep working).
- `/war targets` and the War Table list owed reprisals ("the Ogre Outpost will come for you in 2 days").
- Rig probe `tools/revengegate`: a camp and a village attacked headlessly; both debts written with the right size
  and due day; a second attack folds; time advanced: each is paid as a raid with the place's name and a captain;
  a colony left without players for a week forgets the debt; save and read back.

## C4 — convoys on the road, for the thieves (bridge)

`WarBridge` gets new methods (the existing six stay exactly as they are) so Colonist Thieves can see and rob a
convoy on the road:

- `convoysNear(ServerLevel level, BlockPos at, int range)` -> `List<long[]>` or another plain-JDK shape: id, the
  sending colony, the receiving colony (0 for a kingdom), the escort size, the stacks carried, and where the convoy
  is now (interpolated on its road from the War Room to the target between departure and arrival), only convoys
  still on the road out and within `range` of `at`;
- `convoyCargo(ServerLevel, long id)` -> a copy of what it carries;
- `robConvoy(ServerLevel, long id, int maxStacks)` -> `List<ItemStack>` taken out of the convoy's goods (never more
  than half of what it carries, whole stacks or parts), recorded on the campaign so the receiving colony gets only
  what is left and the war log says the convoy was robbed on the road;
- `convoyEscort(ServerLevel, long id)` -> how many guards ride with it (the thief's catch risk).
- The convoy's report and the war log line say it was robbed; standing never changes here (the thief calls
  `offend` himself only when his man was caught or seen).
- Rig probe `tools/roadgate`: two colonies, a convoy sent with a manifest; `convoysNear` finds it on the road at
  the right place and not outside the range; `robConvoy` takes at most half; the receiver gets the rest; the war
  log line; save and read back mid-road.

## Colonist Thieves 0.2.0 — intercepting a convoy (other repo)

Built in `D:/Claude/thieves-020/repo` after C4: the Hideout can send a thief at a Colonies at War convoy passing
within range; he steals part of the cargo; the escort can catch him (a bigger escort, a bigger risk); standing falls
only when he is caught or seen. Through `thieves/compat/War` with new reflected methods (the five lookups it has
stay as they are; each new one is optional, so an older Colonies at War simply offers no convoys).
