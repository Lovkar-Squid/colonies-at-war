package me.lovkar.targetgate;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.war.WarConfig;
import me.lovkar.war.campaign.Battle;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.campaign.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rig gate for raid targets from other mods (C2).
 *
 * <p>Guard Villagers, Kobolds, IFC-Integrations and Valarian Conquest are loaded beside the mod.
 * The gate reads the two structure tags and finds the loaded mods' structures in them (and the
 * ids of a mod that is not loaded simply absent); places a vanilla village, a Valarian bandit camp
 * and an IFC goblin camp (Valarian's bandit ruins when IFC is not loaded) the way
 * {@code /place structure} does - the rig generates no structures - and writes the structure
 * references the world generator would have written, so {@code Places.at} finds them; counts the
 * village's defence, spawns four of Guard Villagers' guards in it (Guard Villagers has put guards of
 * its own there already) and counts again; then sends a warband of a colony at the guarded
 * village by hand (a campaign put straight into the campaign data, arriving now), and checks what
 * it recorded about the guards - and that it survives a save and a read-back, and that an older
 * save without those fields reads 0.</p>
 *
 * <p>Kings &amp; Monsters is never loaded (it needs GeckoLib and more), and IFC-Integrations 4.0.1
 * will not load without Minecraft Comes Alive: their ids are proven by tools/check_tags.py against
 * their jars. The kobold den is tag-checked only: its own structure type finds no place for itself
 * on a flat world.</p>
 */
@Mod("targetgate")
public final class TargetGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("targetgate");
    private static final int SETUP = 40;
    private static final int PLACE = 100;
    private static final int COUNT = 140;
    private static final int MARCH = 160;
    private static final int TIMEOUT = 1200;
    private static final int GUARDS = 4;
    private static final long CAMPAIGN_ID = 900001L;
    /** Chunk of each site: far enough apart that no box overlaps another. */
    private static final ChunkPos VILLAGE_AT = new ChunkPos(40, 0);
    private static final ChunkPos BANDITS_AT = new ChunkPos(-40, 0);
    private static final ChunkPos GOBLINS_AT = new ChunkPos(0, -40);

    private static final List<String> CAMPS = List.of("ifcintegrations:goblin_camp", "valarian_conquest:bandit_camp",
            "valarian_conquest:bandit_ruins", "valarian_conquest:bandit_ruins_2", "valarian_conquest:neutral_tent_camp",
            "valarian_conquest:neutral_tent_camp_2", "valarian_conquest:neutral_outpost", "valarian_conquest:barathian_outpost",
            "valarian_conquest:barathian_castle", "valarian_conquest:bohemian_outpost", "valarian_conquest:burgundian_outpost",
            "valarian_conquest:burgundian_castle", "valarian_conquest:hospitaller_outpost", "valarian_conquest:hospitaller_castle",
            "valarian_conquest:orleanian_outpost", "valarian_conquest:orleanian_castle", "valarian_conquest:templar_outpost",
            "valarian_conquest:templar_castle", "valarian_conquest:valarian_outpost", "valarian_conquest:valarian_castle",
            "valarian_conquest:visgothian_outpost", "valarian_conquest:visgothian_castle", "minecolonies:barbarian_camp",
            "minecraft:pillager_outpost");
    private static final List<String> VILLAGES = List.of("kobolds:kobold_den", "ifcintegrations:dwarf_village",
            "minecraft:village_plains", "minecraft:village_desert");

    private int tick;
    private MinecraftServer server;
    private ServerLevel level;
    private IColony colony;
    private BlockPos center;
    private Places.Place village;
    /** The placed Valarian bandit camp, where the armed are counted. */
    private Places.Place bandits;
    /** The guards standing in the village when the warband comes: Guard Villagers' own and ours. */
    private int guardsThere;
    private Campaigns.Campaign campaign;
    private int fails;
    private int checks;
    private boolean finished;

    public TargetGate(final IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final Object detail) {
        checks++;
        if (!ok) {
            fails++;
        }
        LOGGER.info("[targetgate] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
    }

    private void onTick(final ServerTickEvent.Post e) {
        if (finished) {
            return;
        }
        tick++;
        server = e.getServer();
        try {
            if (tick == SETUP) {
                setUp();
                tags();
            } else if (tick == PLACE) {
                place();
            } else if (tick == COUNT) {
                guards();
                armed();
            } else if (tick == MARCH) {
                march();
            } else if (tick > MARCH && tick % 10 == 0 && campaign != null && campaign.phase != Campaigns.Phase.MARCHING) {
                arrived();
                finish();
            }
            if (!finished && tick >= TIMEOUT) {
                check("the warband arrived at the village", false, campaign == null ? "no campaign" : campaign.phase);
                finish();
            }
        } catch (final Throwable t) {
            LOGGER.error("[targetgate] the probe broke at tick {}", tick, t);
            check("probe runs", false, t.toString());
            finish();
        }
    }

    private void finish() {
        finished = true;
        LOGGER.info("[targetgate] {} checks, {} failed", checks, fails);
        LOGGER.info("[targetgate] RESULT {}", fails == 0 && checks > 0 ? "PASS" : "FAIL");
        server.halt(false);
    }

    // ------------------------------------------------------------------ the tags

    private void setUp() {
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        force(new ChunkPos(0, 0), 3);
        force(VILLAGE_AT, 8);
        force(BANDITS_AT, 5);
        force(GOBLINS_AT, 5);
    }

    private void force(final ChunkPos around, final int radius) {
        for (int cx = around.x - radius; cx <= around.x + radius; cx++) {
            for (int cz = around.z - radius; cz <= around.z + radius; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    private Set<String> tag(final ResourceLocation id) {
        final Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        final Set<String> out = new HashSet<>();
        registry.getTag(TagKey.create(Registries.STRUCTURE, id)).ifPresent((HolderSet.Named<Structure> set) -> {
            for (final Holder<Structure> holder : set) {
                out.add(String.valueOf(registry.getKey(holder.value())));
            }
        });
        return out;
    }

    /** Whether the mod an id belongs to is loaded; an id of a mod that is not must be simply absent. */
    private static boolean loaded(final String id) {
        return ModList.get().isLoaded(id.substring(0, id.indexOf(':')));
    }

    private void tags() {
        final Set<String> camps = tag(Places.CAMP_TAG);
        final Set<String> villages = tag(Places.VILLAGE_TAG);
        for (final String mod : new String[] {"guardvillagers", "kobolds", "ifcintegrations", "valarian_conquest"}) {
            LOGGER.info("[targetgate] {} {}", mod, ModList.get().isLoaded(mod) ? "loaded" : "NOT LOADED - its ids are proven by tools/check_tags.py only");
        }
        final List<String> campsMissing = CAMPS.stream().filter(c -> loaded(c) != camps.contains(c)).toList();
        check("the camp tag resolves every loaded mod's camps (goblin camp, Valarian camps, outposts, castles) and no absent one", campsMissing.isEmpty(),
                camps.size() + " structures, wrong " + campsMissing);
        final List<String> villagesMissing = VILLAGES.stream().filter(v -> loaded(v) != villages.contains(v)).toList();
        check("the village tag resolves every loaded mod's villages (kobold den, dwarf village, vanilla) and no absent one", villagesMissing.isEmpty(),
                villages.size() + " structures, wrong " + villagesMissing);
        final boolean noOgres = camps.stream().noneMatch(c -> c.startsWith("kingsandmonsters:"));
        check("Kings & Monsters (not loaded) leaves the camp tag loadable and simply absent", noOgres && !camps.isEmpty(), camps.size());
        final boolean noOverlap = camps.stream().noneMatch(villages::contains);
        check("no structure is both a camp and a village", noOverlap, "");
        check("Guard Villagers' guard is found by its id", Places.villageGuardType() != null, Places.VILLAGE_GUARD);
        check("names read the structure's own name",
                "Ogre Outpost".equals(Places.nameOf(ResourceLocation.parse("kingsandmonsters:ogre_outpost_plains")))
                        && "Ogre Outpost".equals(Places.nameOf(ResourceLocation.parse("kingsandmonsters:ogre_outpost_swamp")))
                        && "Ogre Kings Fort".equals(Places.nameOf(ResourceLocation.parse("kingsandmonsters:ogre_kings_fort")))
                        && "Bandit Ruins".equals(Places.nameOf(ResourceLocation.parse("valarian_conquest:bandit_ruins_2")))
                        && "Neutral Tent Camp".equals(Places.nameOf(ResourceLocation.parse("valarian_conquest:neutral_tent_camp_2")))
                        && "Barbarian Camp".equals(Places.nameOf(ResourceLocation.parse("minecolonies:barbarian_camp")))
                        && "Desert Camp".equals(Places.nameOf(ResourceLocation.parse("minecolonies:desert_camp")))
                        && "Village".equals(Places.nameOf(ResourceLocation.parse("minecraft:village_plains"))),
                Places.nameOf(ResourceLocation.parse("kingsandmonsters:ogre_outpost_plains")) + ", "
                        + Places.nameOf(ResourceLocation.parse("valarian_conquest:bandit_ruins_2")) + ", "
                        + Places.nameOf(ResourceLocation.parse("minecraft:village_plains")));
    }

    // ------------------------------------------------------------------ placing structures

    /**
     * What {@code /place structure} does, and what it leaves out: the start written into its chunk
     * and a reference to it in every chunk it covers, as the world generator writes them - without
     * those the structure is blocks and nothing in the game knows it is there.
     */
    private StructureStart build(final String id, final ChunkPos near) {
        final Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        final Structure structure = registry.get(ResourceLocation.parse(id));
        if (structure == null) {
            return null;
        }
        final ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (int attempt = 0; attempt < 6; attempt++) {
            final ChunkPos at = new ChunkPos(near.x + attempt, near.z);
            final StructureStart start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(),
                    level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(), at, 0, level, b -> true);
            if (!start.isValid()) {
                continue;
            }
            final BoundingBox box = start.getBoundingBox();
            final ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
            final ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
            level.getChunk(at.x, at.z).setStartForStructure(structure, start);
            level.getChunk(at.x, at.z).addReferenceForStructure(structure, at.toLong());
            ChunkPos.rangeClosed(min, max).forEach(p -> {
                if (!p.equals(at)) {
                    level.getChunk(p.x, p.z).addReferenceForStructure(structure, at.toLong());
                }
            });
            ChunkPos.rangeClosed(min, max).forEach(p -> start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
                    new BoundingBox(p.getMinBlockX(), level.getMinBuildHeight(), p.getMinBlockZ(), p.getMaxBlockX(),
                            level.getMaxBuildHeight(), p.getMaxBlockZ()), p));
            LOGGER.info("[targetgate] placed {} at chunk {} - box {}", id, at, box);
            return start;
        }
        return null;
    }

    /** Where a structure's first piece stands: its centre, inside a building. */
    private static BlockPos heart(final StructureStart start) {
        return start.getPieces().get(0).getBoundingBox().getCenter();
    }

    private void place() {
        final StructureStart plains = build("minecraft:village_plains", VILLAGE_AT);
        check("a vanilla village is placed", plains != null, "");
        if (plains != null) {
            village = Places.at(level, heart(plains));
            check("Places reads the placed village as a village with its name",
                    village != null && Places.VILLAGE.equals(village.kind()) && "Village".equals(village.name()),
                    village == null ? "nothing" : village.kind() + " '" + village.name() + "' " + village.structure());
        }
        bandits = camp("valarian_conquest:bandit_camp", BANDITS_AT, "Bandit Camp");
        if (ModList.get().isLoaded("ifcintegrations")) {
            camp("ifcintegrations:goblin_camp", GOBLINS_AT, "Goblin Camp");
        } else {
            camp("valarian_conquest:bandit_ruins_2", GOBLINS_AT, "Bandit Ruins");
        }
        // the colony the warband marches from: not at 0,0, which MineColonies takes for a missing building
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
        center = new BlockPos(8, y, 8);
        final FakePlayer fake = FakePlayerFactory.getMinecraft(level);
        fake.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(level, center, fake, "Targetgate", "Medieval Oak");
        check("colony created", colony != null, colony == null ? "none" : "id " + colony.getID());
    }

    private Places.Place camp(final String id, final ChunkPos near, final String name) {
        final StructureStart start = build(id, near);
        check(id + " is placed", start != null, "");
        if (start == null) {
            return null;
        }
        final Places.Place camp = Places.at(level, heart(start));
        check("Places reads the placed " + id + " as a camp named '" + name + "'",
                camp != null && camp.isCamp() && name.equals(camp.name()) && id.equals(String.valueOf(camp.structure())),
                camp == null ? "nothing" : camp.kind() + " '" + camp.name() + "' " + camp.structure() + ", defence "
                        + Places.defence(level, camp) + ", title " + camp.title());
        return camp;
    }

    // ------------------------------------------------------------------ the guards

    private void guards() {
        if (village == null) {
            check("a village to guard", false, "none placed");
            return;
        }
        check("the village's ground is loaded", level.isPositionEntityTicking(village.pos()), village.pos().toShortString());
        final Places.Defenders before = Places.defenders(level, village);
        final int without = Places.defence(level, village, before);
        final EntityType<?> type = Places.villageGuardType();
        int spawned = 0;
        final BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, village.pos());
        for (int i = 0; type != null && i < GUARDS; i++) {
            final Entity guard = type.create(level);
            if (guard != null) {
                guard.moveTo(at.getX() + 0.5 + i, at.getY(), at.getZ() + 0.5, 0f, 0f);
                if (level.addFreshEntity(guard)) {
                    spawned++;
                }
            }
        }
        check("four guardvillagers:guard entities are spawned in the village", spawned == GUARDS, spawned);
        final Places.Defenders after = Places.defenders(level, village);
        final int with = Places.defence(level, village, after);
        guardsThere = after.guards();
        // Guard Villagers puts guards of its own into a village it sees built: the four come on top
        check("the village counts its guards, each once", after.guards() == before.guards() + GUARDS
                        && after.folk() == before.folk() && after.armed() == before.armed() && after.golems() == before.golems(),
                "before " + before + ", after " + after);
        check("the guarded village reads a higher defence, " + WarConfig.villageGuardWeight() + " a guard",
                with > without && (without > 20 ? with - without == GUARDS * WarConfig.villageGuardWeight()
                        : with >= 12 + GUARDS * WarConfig.villageGuardWeight()),
                without + " without guards, " + with + " with " + GUARDS);
    }

    /**
     * A camp counts whoever is armed in it, whatever mod he comes from: a villager handed an iron
     * sword (no hostile mob, so only the weapon makes him one), an empty-handed vindicator (hostile)
     * and one of Guard Villagers' guards each add three; a cow adds nothing.
     */
    private void armed() {
        if (bandits == null) {
            check("a camp to count the armed in", false, "none placed");
            return;
        }
        final Places.Defenders before = Places.defenders(level, bandits);
        final int without = Places.defence(level, bandits, before);
        final BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bandits.pos());
        final Mob smith = EntityType.VILLAGER.create(level);
        smith.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        final EntityType<?> guardType = Places.villageGuardType();
        final Entity[] newcomers = {smith, EntityType.VINDICATOR.create(level), EntityType.COW.create(level),
                guardType == null ? null : guardType.create(level)};
        int spawned = 0;
        for (int i = 0; i < newcomers.length; i++) {
            if (newcomers[i] != null) {
                newcomers[i].moveTo(at.getX() + 0.5 + i, at.getY(), at.getZ() + 0.5, 0f, 0f);
                if (level.addFreshEntity(newcomers[i])) {
                    spawned++;
                }
            }
        }
        final Places.Defenders after = Places.defenders(level, bandits);
        final int with = Places.defence(level, bandits, after);
        check("a camp counts its armed whatever they are (a villager with a sword, a vindicator, a village guard; not a cow)",
                spawned == newcomers.length && after.armed() == before.armed() + 2 && after.guards() == before.guards() + 1
                        && with - without == 3 * 3,
                "spawned " + spawned + ", before " + before + " (" + without + "), after " + after + " (" + with + ")");
    }

    // ------------------------------------------------------------------ the warband

    @SuppressWarnings("unchecked")
    private static List<Campaigns.Campaign> campaignsOf(final Campaigns data) throws ReflectiveOperationException {
        final Field f = Campaigns.class.getDeclaredField("campaigns");
        f.setAccessible(true);
        return (List<Campaigns.Campaign>) f.get(data);
    }

    private static Campaigns.Campaign find(final Campaigns data) throws ReflectiveOperationException {
        for (final Campaigns.Campaign c : campaignsOf(data)) {
            if (c.id == CAMPAIGN_ID) {
                return c;
            }
        }
        return null;
    }

    /**
     * A warband of two guards and one sellsword, at the village now. Its two guards are ids no
     * citizen has: the guards it loses are only counted, and come home to nobody.
     */
    private void march() throws ReflectiveOperationException {
        if (village == null || colony == null) {
            check("a village and a colony to march from", false, village + " / " + colony);
            return;
        }
        final long now = server.overworld().getGameTime();
        final Campaigns.Campaign c = new Campaigns.Campaign();
        c.id = CAMPAIGN_ID;
        c.attacker = colony.getID();
        c.kind = Places.VILLAGE;
        c.target = village.pos();
        c.attackerName = colony.getName();
        c.defenderName = village.title();
        c.warRoom = center;
        c.guards = new int[] {90001, 90002};
        c.mercenaries = 1;
        c.attack = 60;
        c.goal = "plunder";
        c.departed = now;
        c.arrive = now;
        campaignsOf(Campaigns.of(server)).add(c);
        campaign = c;
        LOGGER.info("[targetgate] warband of {} sent at {} with strength {}", c.attackerName, c.defenderName, c.attack);
    }

    private void arrived() throws ReflectiveOperationException {
        final Campaigns.Campaign c = campaign;
        LOGGER.info("[targetgate] the warband {}: {}", c.won ? "won" : "lost", c.report);
        check("the warband fought at the village", c.fought && c.phase == Campaigns.Phase.RETURNING, c.phase + ", fought " + c.fought);
        final int had = guardsThere;
        check("the campaign records the village's guards", had >= GUARDS && c.villageGuards == had, c.villageGuards + " of " + had);
        final int fell = Battle.guardsFell(had, c.won, c.margin);
        check("the campaign records how many of them fell", c.villageGuardsFell == fell && fell <= had
                        && (c.won ? 2 * fell >= had - 1 : 4 * fell <= had + 2), c.villageGuardsFell + " of " + had + ", won " + c.won + ", margin " + c.margin);
        final int lost = Math.min(3, c.won ? (had + 1) / 2 : had);
        check("the warband loses men by the guards' count, never more than it had, sellswords first",
                c.lostToGuards == lost && c.mercenaries == 0 && c.carried == lost - 1,
                "lost " + c.lostToGuards + ", sellswords left " + c.mercenaries + ", guards carried " + c.carried);
        check("the battle report names the village guards",
                c.report.contains(had + " village guards stood against it and " + c.villageGuardsFell + " fell"), c.report);

        // a save and a read-back
        final Campaigns data = Campaigns.of(server);
        final CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
        final Campaigns.Campaign back = find(Campaigns.FACTORY.deserializer().apply(saved, server.registryAccess()));
        check("the guards survive a save and a read-back", back != null && back.villageGuards == c.villageGuards
                        && back.villageGuardsFell == c.villageGuardsFell && back.lostToGuards == c.lostToGuards
                        && back.carried == c.carried && back.report.equals(c.report),
                back == null ? "lost" : back.villageGuards + "/" + back.villageGuardsFell + "/" + back.lostToGuards + "/" + back.carried);

        // a 0.2.2 save: none of the four tags
        final CompoundTag old = saved.copy();
        for (final String key : old.getAllKeys()) {
            if (old.get(key) instanceof ListTag list && list.getElementType() == Tag.TAG_COMPOUND) {
                for (final Tag t : list) {
                    final CompoundTag one = (CompoundTag) t;
                    for (final String gone : new String[] {"villageGuards", "villageGuardsFell", "lostToGuards", "carried"}) {
                        one.remove(gone);
                    }
                }
            }
        }
        final Campaigns.Campaign older = find(Campaigns.FACTORY.deserializer().apply(old, server.registryAccess()));
        check("an older save without them reads 0", older != null && older.villageGuards == 0 && older.villageGuardsFell == 0
                        && older.lostToGuards == 0 && older.carried == 0 && older.attackerName.equals(c.attackerName),
                older == null ? "lost" : older.villageGuards + "/" + older.villageGuardsFell + "/" + older.lostToGuards + "/" + older.carried);
    }
}
