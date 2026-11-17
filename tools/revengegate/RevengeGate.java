package me.lovkar.revengegate;

import com.minecolonies.api.colony.ColonyState;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.mojang.authlib.GameProfile;
import me.lovkar.war.WarConfig;
import me.lovkar.war.campaign.ArmyRaidEvent;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.campaign.Captains;
import me.lovkar.war.campaign.Places;
import me.lovkar.war.campaign.WarLog;
import me.lovkar.war.campaign.WarTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rig gate for raided camps and villages that strike back (C3).
 *
 * <p>Places a vanilla pillager outpost (a camp, with two spawners set into it) and two vanilla
 * villages the way {@code /place structure} does, writing the structure references the world
 * generator would have - the rig generates no structures. Colony A, which somebody is home at,
 * sends warbands straight into the campaign data, arriving now: one that sacks the camp, one that
 * is thrown back from a village with no guards, one thrown back from a village where Guard
 * Villagers' guards stand (its own and four more). Each must leave a debt of the expected size and
 * due day, the guarded village's the bigger. A second attack on the camp folds into the same debt;
 * colony C, where nobody is ever home, attacks the camp too and owes a debt of its own. The debts
 * survive a save and a read-back; a kingdom's record built by hand saves as before, and reads as a
 * kingdom's without its new tags (a 0.2.2 save). Then the clock is pushed past every due day: A's
 * three debts are paid as raids named after their places, each with exactly one captain, the
 * guarded village's men in iron with shields; C's is not paid, and when the clock is pushed past
 * its week it is forgotten.</p>
 */
@Mod("revengegate")
public final class RevengeGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("revengegate");
    private static final int SETUP = 40;
    private static final int PLACE = 100;
    private static final int SEND = 160;
    private static final int TIMEOUT = 6000;
    private static final int GUARDS = 4;
    private static final int SPAWNERS = 2;
    private static final long FIRST_CAMPAIGN = 950001L;
    private static final ChunkPos CAMP_AT = new ChunkPos(-40, 0);
    private static final ChunkPos BARE_AT = new ChunkPos(40, 0);
    private static final ChunkPos GUARDED_AT = new ChunkPos(0, 40);
    /** Colony C: far out and never loaded by the probe, so nobody is ever home there. */
    private static final BlockPos C_AT = new BlockPos(8, 0, -1592);

    private int tick;
    private int stage;
    private int waitUntil;
    private int jumps;
    private MinecraftServer server;
    private ServerLevel level;
    private IColony colonyA;
    private IColony colonyC;
    private BlockPos center;

    private StructureStart campStart;
    private StructureStart bareStart;
    private StructureStart guardedStart;
    private Places.Place camp;
    private Places.Place bare;
    private Places.Place guarded;
    private int spawnersBefore;
    private int guardsThere;

    /** A warband sent by the probe, and what the probe knew of its target the moment it left. */
    private static final class Sent {
        final Campaigns.Campaign c;
        final Places.Place place;
        final BoundingBox box;
        final int defence;
        final int spawners;
        final long at;
        long seen = -1;

        Sent(final Campaigns.Campaign c, final Places.Place place, final BoundingBox box, final int defence, final int spawners,
             final long at) {
            this.c = c;
            this.place = place;
            this.box = box;
            this.defence = defence;
            this.spawners = spawners;
            this.at = at;
        }
    }

    private final List<Sent> sent = new ArrayList<>();
    private Sent sack;
    private Sent bareVillage;
    private Sent guardedVillage;
    private Sent fold;
    private Sent cAttack;
    private Campaigns.Reprisal campDebt;
    private Campaigns.Reprisal bareDebt;
    private Campaigns.Reprisal guardedDebt;
    private Campaigns.Reprisal cDebt;
    private long campDebtId;
    private int campSizeBefore;
    private long campDueBefore;
    private int captainsAt = -1;

    private int fails;
    private int checks;
    private boolean finished;

    public RevengeGate(final IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final Object detail) {
        checks++;
        if (!ok) {
            fails++;
        }
        LOGGER.info("[revengegate] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
    }

    private void onTick(final ServerTickEvent.Post e) {
        if (finished) {
            return;
        }
        tick++;
        server = e.getServer();
        try {
            switch (stage) {
                case 0 -> {
                    if (tick == SETUP) {
                        setUp();
                        stage = 1;
                    }
                }
                case 1 -> {
                    if (tick == PLACE) {
                        place();
                        stage = 2;
                    }
                }
                case 2 -> {
                    if (tick == SEND) {
                        sendFirst();
                        stage = 3;
                    }
                }
                case 3 -> {
                    if (arrived(sack) && arrived(bareVillage) && arrived(guardedVillage)) {
                        firstDebts();
                        saved();
                        table();
                        sendSecond();
                        stage = 4;
                    }
                }
                case 4 -> {
                    if (arrived(fold) && arrived(cAttack)) {
                        folded();
                        kingdom();
                        saved();
                        forgetCampaigns();
                        jumpPastDue();
                        waitUntil = tick + 1200;
                        stage = 5;
                    }
                }
                case 5 -> {
                    if (tick % 10 == 0) {
                        paid();
                    }
                }
                case 6 -> {
                    if (tick % 5 == 0) {
                        captains();
                    }
                }
                case 7 -> {
                    if (tick % 10 == 0) {
                        forgotten();
                    }
                }
                default -> {
                }
            }
            if (!finished && (tick >= TIMEOUT || waitUntil > 0 && tick >= waitUntil)) {
                check("the gate reached its end (stage " + stage + ")", false, "timed out at tick " + tick);
                finish();
            }
        } catch (final Throwable t) {
            LOGGER.error("[revengegate] the probe broke at tick {}", tick, t);
            check("probe runs", false, t.toString());
            finish();
        }
    }

    private void finish() {
        finished = true;
        LOGGER.info("[revengegate] {} checks, {} failed", checks, fails);
        LOGGER.info("[revengegate] RESULT {}", fails == 0 && checks > 0 ? "PASS" : "FAIL");
        server.halt(false);
    }

    // ------------------------------------------------------------------ the world

    private void setUp() {
        level = server.overworld();
        // the rig's world is peaceful, and MineColonies' raiders leave a peaceful world the tick they
        // arrive; daylight held and no mobs of the world's own (capgate's recipe)
        server.setDifficulty(Difficulty.NORMAL, true);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(1000L);
        force(new ChunkPos(0, 0), 8);
        force(CAMP_AT, 5);
        force(BARE_AT, 8);
        force(GUARDED_AT, 8);

        // colony C first, owned by a fake player of its own, and never visited again
        final FakePlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("revengegate-c".getBytes()), "revengegate_c"));
        final BlockPos c = new BlockPos(C_AT.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, C_AT.getX(), C_AT.getZ()), C_AT.getZ());
        owner.setPos(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        colonyC = IColonyManager.getInstance().createColony(level, c, owner, "Revengegate Far", "Medieval Oak");
        check("colony C (nobody home) created", colonyC != null, colonyC == null ? "none" : "id " + colonyC.getID());

        // colony A: not at 0,0, which MineColonies takes for a missing building
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
        center = new BlockPos(8, y, 8);
        final FakePlayer fake = FakePlayerFactory.getMinecraft(level);
        fake.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colonyA = IColonyManager.getInstance().createColony(level, center, fake, "Revengegate", "Medieval Oak");
        check("colony A created", colonyA != null, colonyA == null ? "none" : "id " + colonyA.getID());
        final Block townHall = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall"));
        level.setBlock(center, townHall.defaultBlockState(), 3);
        final BlockEntity te = level.getBlockEntity(center);
        // a Town Hall of level 1: a raid finds its way in by the nearest building with a level
        final IBuilding hall = te instanceof AbstractTileEntityColonyBuilding hut
                ? colonyA.getServerBuildingManager().addNewBuilding(hut, level) : null;
        if (hall != null) {
            hall.setBuildingLevel(1);
        }
        check("colony A has a Town Hall", hall != null && hall.getBuildingLevel() == 1, hall == null ? "none" : "level " + hall.getBuildingLevel());
        // somebody is home at A: the fake owner in the live set by hand (MineColonies refuses a fake
        // player through addCloseSubscriber), where it stays while it stands on a chunk of the colony
        colonyA.getPackageManager().getCloseSubscribers().add(fake);
        LOGGER.info("[revengegate] reprisalDays {}, placesStrikeBack {}, siegeInWorld {}, Guard Villagers {}",
                WarConfig.reprisalDays(), WarConfig.placesStrikeBack(), WarConfig.siegeInWorld(),
                Places.villageGuardType() != null ? "loaded" : "NOT LOADED - the guard checks are skipped");
    }

    private void force(final ChunkPos around, final int radius) {
        for (int cx = around.x - radius; cx <= around.x + radius; cx++) {
            for (int cz = around.z - radius; cz <= around.z + radius; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /**
     * What {@code /place structure} does, and what it leaves out: the start written into its chunk
     * and a reference to it in every chunk it covers (targetgate's recipe).
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
            LOGGER.info("[revengegate] placed {} at chunk {} - box {}", id, at, box);
            return start;
        }
        return null;
    }

    /** Where a structure's first piece stands: its centre, inside a building. */
    private static BlockPos heart(final StructureStart start) {
        return start.getPieces().get(0).getBoundingBox().getCenter();
    }

    private int spawnersIn(final BoundingBox box) {
        int found = 0;
        for (final BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.SPAWNER)) {
                found++;
            }
        }
        return found;
    }

    private void place() {
        campStart = build("minecraft:pillager_outpost", CAMP_AT);
        bareStart = build("minecraft:village_plains", BARE_AT);
        guardedStart = build("minecraft:village_plains", GUARDED_AT);
        check("a pillager outpost and two villages are placed", campStart != null && bareStart != null && guardedStart != null,
                campStart + " / " + bareStart + " / " + guardedStart);
        if (campStart == null || bareStart == null || guardedStart == null) {
            return;
        }
        camp = Places.at(level, heart(campStart));
        bare = Places.at(level, heart(bareStart));
        guarded = Places.at(level, heart(guardedStart));
        check("Places reads the outpost as a camp named 'Pillager Outpost' and both villages as villages",
                camp != null && camp.isCamp() && "Pillager Outpost".equals(camp.name())
                        && bare != null && Places.VILLAGE.equals(bare.kind()) && guarded != null && Places.VILLAGE.equals(guarded.kind()),
                camp + " / " + bare + " / " + guarded);
        // two spawners set into the outpost: a sack smashes them, and the camp remembers it
        final BoundingBox box = campStart.getBoundingBox();
        final int before = spawnersIn(box);
        level.setBlock(new BlockPos(box.minX() + 1, box.minY() + 1, box.minZ() + 1), Blocks.SPAWNER.defaultBlockState(), 3);
        level.setBlock(new BlockPos(box.maxX() - 1, box.minY() + 1, box.maxZ() - 1), Blocks.SPAWNER.defaultBlockState(), 3);
        spawnersBefore = spawnersIn(box);
        check("two spawners stand in the outpost", spawnersBefore == before + SPAWNERS, before + " -> " + spawnersBefore);
    }

    // ------------------------------------------------------------------ the warbands

    @SuppressWarnings("unchecked")
    private static List<Campaigns.Campaign> campaignsOf(final Campaigns data) throws ReflectiveOperationException {
        final Field f = Campaigns.class.getDeclaredField("campaigns");
        f.setAccessible(true);
        return (List<Campaigns.Campaign>) f.get(data);
    }

    @SuppressWarnings("unchecked")
    private static List<Campaigns.Reprisal> reprisalsOf(final Campaigns data) throws ReflectiveOperationException {
        final Field f = Campaigns.class.getDeclaredField("reprisals");
        f.setAccessible(true);
        return (List<Campaigns.Reprisal>) f.get(data);
    }

    /**
     * A warband of two guards that are nobody's (the probe's own ids) at a place now. Its strength
     * decides the fight: more than twice the defence always wins, nothing is always thrown back from
     * a defence of 24 and more.
     */
    private Sent send(final IColony from, final Places.Place place, final StructureStart start, final int attack, final long id)
            throws ReflectiveOperationException {
        final long now = server.overworld().getGameTime();
        final Campaigns.Campaign c = new Campaigns.Campaign();
        c.id = id;
        c.attacker = from.getID();
        c.kind = place.kind();
        c.target = place.pos();
        c.attackerName = from.getName();
        c.defenderName = place.title();
        c.warRoom = from.getCenter();
        c.guards = new int[] {(int) (id % 100000), (int) (id % 100000) + 50};
        c.attack = attack;
        c.goal = "plunder";
        c.departed = now;
        c.arrive = now;
        final Sent s = new Sent(c, place, start.getBoundingBox(), Places.defence(level, place), spawnersIn(start.getBoundingBox()), now);
        campaignsOf(Campaigns.of(server)).add(c);
        sent.add(s);
        LOGGER.info("[revengegate] {} sends a warband of strength {} at {} (defence {}, {} spawners)", from.getName(), attack,
                place.title(), s.defence, s.spawners);
        return s;
    }

    private boolean arrived(final Sent s) {
        if (s == null) {
            return false;
        }
        if (s.c.phase != Campaigns.Phase.MARCHING && s.seen < 0) {
            s.seen = server.overworld().getGameTime();
            LOGGER.info("[revengegate] the warband of {} {} at {}: {}", s.c.attackerName, s.c.won ? "won" : "lost", s.c.defenderName, s.c.report);
        }
        return s.seen >= 0;
    }

    private void sendFirst() throws ReflectiveOperationException {
        if (camp == null || bare == null || guarded == null || colonyA == null || colonyC == null) {
            check("everything to march on and from", false, "missing");
            finish();
            return;
        }
        // the bare village: whatever guards Guard Villagers put in it are taken out
        final EntityType<?> guardType = Places.villageGuardType();
        int removed = 0;
        if (guardType != null) {
            for (final Entity guard : level.getEntitiesOfClass(Entity.class, AABB.of(bareStart.getBoundingBox()).inflate(64),
                    e -> e.getType() == guardType && e.isAlive())) {
                guard.discard();
                removed++;
            }
        }
        // the guarded village: four more of them
        int spawned = 0;
        final BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, guarded.pos());
        for (int i = 0; guardType != null && i < GUARDS; i++) {
            final Entity guard = guardType.create(level);
            if (guard != null) {
                guard.moveTo(at.getX() + 0.5 + i, at.getY(), at.getZ() + 0.5, 0f, 0f);
                if (level.addFreshEntity(guard)) {
                    spawned++;
                }
            }
        }
        guardsThere = Places.defenders(level, guarded).guards();
        LOGGER.info("[revengegate] {} guards taken out of the bare village, {} spawned in the guarded one, {} stand there; bare {}, guarded {}",
                removed, spawned, guardsThere, Places.defenders(level, bare), Places.defenders(level, guarded));
        if (guardType != null) {
            check("the guarded village has its guards and the bare one none", spawned == GUARDS && guardsThere >= GUARDS
                    && Places.defenders(level, bare).guards() == 0, guardsThere + " guarded, " + Places.defenders(level, bare).guards() + " bare");
        }
        // a sack, but not so wide that the debt is at its 16 already: the fold has to have room
        sack = send(colonyA, camp, campStart, 90, FIRST_CAMPAIGN);
        bareVillage = send(colonyA, bare, bareStart, 0, FIRST_CAMPAIGN + 1);
        guardedVillage = send(colonyA, guarded, guardedStart, 0, FIRST_CAMPAIGN + 2);
    }

    // ------------------------------------------------------------------ the debts

    /**
     * What the probe expects a place to field: an eighth of its defence, the hurt done to it (each
     * spawner smashed, every two plunder rolls of a sack - camp wealth 45, village 35, doubled - each
     * guard fallen), two for each guard who leads.
     */
    private static int expectedSize(final Sent s) {
        final int smashed = s.c.won && s.place.isCamp() ? s.spawners : 0;
        final int survivors = s.place.isCamp() ? 0 : Math.max(0, s.c.villageGuards - s.c.villageGuardsFell);
        final int wealth = s.place.isCamp() ? 45 : 35;
        final int rolls = 2 * Math.max(1, Math.round(WarConfig.plunderRolls() * (0.5f + Math.max(0f, s.c.margin) / 2f) + wealth / 40f));
        final int hit = smashed + (s.c.won ? rolls / 2 : 0) + s.c.villageGuardsFell;
        return Math.max(3, Math.min(16, Math.round(s.defence / 8f) + hit + 2 * survivors));
    }

    private static int days(final Sent s) {
        return Math.max(1, s.c.won ? WarConfig.reprisalDays() / 2 : WarConfig.reprisalDays());
    }

    private List<Campaigns.Reprisal> debtsAt(final IColony colony, final Places.Place place, final StructureStart start) {
        final List<Campaigns.Reprisal> out = new ArrayList<>();
        for (final Campaigns.Reprisal r : Campaigns.reprisalsFor(level, colony.getID())) {
            if (r.kind.equals(place.kind()) && r.place.equals(start.getBoundingBox().getCenter())) {
                out.add(r);
            }
        }
        return out;
    }

    /** One debt for this colony and place, of the expected size, due when it should be. */
    private Campaigns.Reprisal debt(final String label, final Sent s, final IColony colony, final StructureStart start) {
        final List<Campaigns.Reprisal> debts = debtsAt(colony, s.place, start);
        final Campaigns.Reprisal r = debts.size() == 1 ? debts.get(0) : null;
        final int size = expectedSize(s);
        final long earliest = s.at + days(s) * 24000L;
        final long latest = s.seen + days(s) * 24000L;
        check(label + " leaves one debt of the expected size and due day",
                r != null && r.size == size && r.due >= earliest && r.due <= latest && r.expires == r.due + 7 * 24000L
                        && r.eventId == 0 && r.sacked == s.c.won && r.placeName.equals(s.place.name()) && !r.onKingdom()
                        && r.colonyName.equals(colony.getName()),
                r == null ? debts.size() + " debts" : r.size + " soldiers (expected " + size + "), due " + r.due + " in ["
                        + earliest + ", " + latest + "], won " + s.c.won + " by " + s.c.margin + ", defence " + s.defence + ", guards "
                        + s.c.villageGuards + "/" + s.c.villageGuardsFell + ", strength " + r.strength + " - " + r.describe());
        return r;
    }

    private void firstDebts() {
        check("the camp was sacked and its spawners smashed", sack.c.won && spawnersIn(campStart.getBoundingBox()) == 0,
                sack.c.report + ", " + spawnersIn(campStart.getBoundingBox()) + " spawners left");
        check("both villages threw the warbands back", !bareVillage.c.won && !guardedVillage.c.won,
                bareVillage.c.report + " / " + guardedVillage.c.report);
        campDebt = debt("the sacked camp", sack, colonyA, campStart);
        bareDebt = debt("the village without guards", bareVillage, colonyA, bareStart);
        guardedDebt = debt("the village with guards", guardedVillage, colonyA, guardedStart);
        if (campDebt != null) {
            check("the camp's debt is due in half the days (a sack) and names the camp", days(sack) == Math.max(1, WarConfig.reprisalDays() / 2)
                            && Places.CAMP.equals(campDebt.kind) && "Raiders of the Pillager Outpost".equals(campDebt.title())
                            && campDebt.guards == 0,
                    campDebt.kind + " '" + campDebt.title() + "', " + campDebt.guards + " guards");
            campDebtId = campDebt.id;
            campSizeBefore = campDebt.size;
            campDueBefore = campDebt.due;
        }
        if (bareDebt != null && guardedDebt != null) {
            check("the villages' debts name the village", "Militia of the Village".equals(bareDebt.title())
                    && "Militia of the Village".equals(guardedDebt.title()), bareDebt.title() + " / " + guardedDebt.title());
            if (Places.villageGuardType() != null) {
                check("Guard Villagers' guards lead the guarded village's answer, every one of them who stood",
                        guardedVillage.c.villageGuards == guardsThere && guardedDebt.guards == guardsThere - guardedVillage.c.villageGuardsFell
                                && guardedDebt.guards > 0 && bareDebt.guards == 0,
                        guardedDebt.guards + " leading of " + guardedVillage.c.villageGuards + " who stood (" + guardsThere + " counted)");
                check("the village with guards owes a bigger reprisal than the one without", guardedDebt.size > bareDebt.size
                                && guardedDebt.strength > bareDebt.strength,
                        guardedDebt.size + " soldiers (strength " + guardedDebt.strength + ") against " + bareDebt.size + " (strength "
                                + bareDebt.strength + ")");
            } else {
                LOGGER.info("[revengegate] SKIP the guard checks: Guard Villagers is not loaded");
            }
        }
    }

    /** The debts in the live data and the same after a save and a read-back. */
    private void saved() throws ReflectiveOperationException {
        final Campaigns data = Campaigns.of(server);
        final List<Campaigns.Reprisal> live = new ArrayList<>(reprisalsOf(data));
        final Campaigns back = Campaigns.FACTORY.deserializer().apply(data.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
        final List<Campaigns.Reprisal> read = reprisalsOf(back);
        int same = 0;
        for (final Campaigns.Reprisal r : live) {
            for (final Campaigns.Reprisal b : read) {
                if (b.id == r.id && b.kind.equals(r.kind) && b.place.equals(r.place) && b.placeName.equals(r.placeName)
                        && b.guards == r.guards && b.colony == r.colony && b.colonyName.equals(r.colonyName) && b.size == r.size
                        && b.strength == r.strength && b.due == r.due && b.expires == r.expires && b.sacked == r.sacked
                        && b.eventId == r.eventId && b.kingdom.equals(r.kingdom) && b.kingdomName.equals(r.kingdomName)) {
                    same++;
                }
            }
        }
        check("the reprisal list survives a save and a read-back (" + live.size() + " debts)", !live.isEmpty() && same == live.size()
                && read.size() == live.size(), same + " of " + live.size() + " read back the same, " + read.size() + " read");
    }

    /** The War Table says what is owed, on the place's own line. */
    private void table() {
        final List<WarTable.Target> targets = WarTable.targets(level, colonyA);
        final List<String> owed = new ArrayList<>();
        for (final WarTable.Target t : targets) {
            if (!t.bond().isEmpty()) {
                owed.add(t.kind() + " " + t.name() + ": " + t.bond());
            }
        }
        final long camps = targets.stream().filter(t -> t.bond().startsWith("the Pillager Outpost will come for you in ")).count();
        final long villages = targets.stream().filter(t -> t.bond().startsWith("the Village will come for you in ")).count();
        check("the War Table lists every owed reprisal ('the Pillager Outpost will come for you in 1 day')",
                camps == 1 && villages == 2 && owed.size() == 3, owed);
    }

    private void sendSecond() throws ReflectiveOperationException {
        fold = send(colonyA, camp, campStart, 0, FIRST_CAMPAIGN + 3);
        cAttack = send(colonyC, camp, campStart, 0, FIRST_CAMPAIGN + 4);
    }

    private void folded() {
        final List<Campaigns.Reprisal> debts = debtsAt(colonyA, camp, campStart);
        final Campaigns.Reprisal r = debts.size() == 1 ? debts.get(0) : null;
        final int raw = expectedSize(fold);
        final int size = Math.min(16, Math.max(raw, campSizeBefore + Math.max(1, raw / 2)));
        check("a second attack on the camp folds into the same debt, bigger", r != null && r.id == campDebtId && r.size == size
                        && campSizeBefore < 16 && r.size > campSizeBefore && r.due == campDueBefore && r.eventId == 0,
                r == null ? debts.size() + " debts at the camp" : "id " + r.id + " (was " + campDebtId + "), " + campSizeBefore + " -> "
                        + r.size + " soldiers (expected " + size + "), due " + campDueBefore + " -> " + r.due);
        campDebt = r;
        cDebt = debt("colony C's attack on the same camp", cAttack, colonyC, campStart);
        check("colony C's debt is its own, not folded into A's", cDebt != null && r != null && cDebt.id != r.id && cDebt.colony == colonyC.getID(),
                cDebt == null ? "none" : "id " + cDebt.id);
    }

    /**
     * A kingdom's record, built by hand (no Waking World here), saves and reads back as it did; and
     * without the new tags - a 0.2.2 save - it reads as a kingdom's, unchanged.
     */
    private void kingdom() throws ReflectiveOperationException {
        final Campaigns data = Campaigns.of(server);
        final Campaigns copy = Campaigns.FACTORY.deserializer().apply(data.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
        final Campaigns.Reprisal k = new Campaigns.Reprisal();
        k.id = 990001L;
        k.kingdom = new BlockPos(500, 70, -300);
        k.kingdomName = "Ironhold";
        k.colony = colonyA.getID();
        k.colonyName = colonyA.getName();
        k.due = 123456L;
        k.expires = 123456L + 7 * 24000L;
        k.size = 9;
        k.strength = 44;
        k.sacked = true;
        reprisalsOf(copy).add(k);
        final CompoundTag tag = copy.save(new CompoundTag(), server.registryAccess());
        CompoundTag written = null;
        for (final Tag t : tag.getList("reprisals", Tag.TAG_COMPOUND)) {
            if (((CompoundTag) t).getLong("id") == k.id) {
                written = (CompoundTag) t;
            }
        }
        check("a kingdom's record is written as a kingdom's", written != null && "kingdom".equals(written.getString("kind"))
                        && written.getLong("kingdom") == k.kingdom.asLong() && "Ironhold".equals(written.getString("kingdomName")),
                written);
        final Campaigns.Reprisal back = find(reprisalsOf(Campaigns.FACTORY.deserializer().apply(tag, server.registryAccess())), k.id);
        check("a kingdom's record reads back as before", sameKingdom(back, k), back == null ? "lost" : back.describe());

        final CompoundTag old = tag.copy();
        for (final Tag t : old.getList("reprisals", Tag.TAG_COMPOUND)) {
            for (final String gone : new String[] {"kind", "place", "placeName", "guards"}) {
                ((CompoundTag) t).remove(gone);
            }
        }
        final Campaigns.Reprisal older = find(reprisalsOf(Campaigns.FACTORY.deserializer().apply(old, server.registryAccess())), k.id);
        check("a 0.2.2 record without a kind reads as the same kingdom's debt", sameKingdom(older, k)
                        && Campaigns.Reprisal.KINGDOM.equals(older.kind),
                older == null ? "lost" : older.kind + ": " + older.describe());
    }

    private static Campaigns.Reprisal find(final List<Campaigns.Reprisal> list, final long id) {
        for (final Campaigns.Reprisal r : list) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    private static boolean sameKingdom(final Campaigns.Reprisal b, final Campaigns.Reprisal k) {
        return b != null && b.onKingdom() && b.kingdom.equals(k.kingdom) && b.kingdomName.equals(k.kingdomName) && b.colony == k.colony
                && b.colonyName.equals(k.colonyName) && b.due == k.due && b.expires == k.expires && b.size == k.size
                && b.strength == k.strength && b.sacked == k.sacked && b.eventId == 0 && b.place.equals(BlockPos.ZERO)
                && b.placeName.isEmpty() && b.guards == 0 && b.from().equals(k.kingdom) && b.describe().equals(k.describe())
                && ("Ironhold is coming for " + k.colonyName + " - the sack will be answered - 9 soldiers").equals(b.describe());
    }

    /** The probe's warbands are not walked home: their guards are nobody, and the clock is about to jump. */
    private void forgetCampaigns() throws ReflectiveOperationException {
        campaignsOf(Campaigns.of(server)).removeIf(c -> c.id >= FIRST_CAMPAIGN && c.id < FIRST_CAMPAIGN + 100);
    }

    // ------------------------------------------------------------------ paying

    private void jumpTo(final long time) {
        server.getWorldData().overworldData().setGameTime(time);
        jumps++;
        LOGGER.info("[revengegate] the clock jumps to {} (colony A {}, colony C {})", time, colonyA.getState(), colonyC.getState());
    }

    private void jumpPastDue() {
        long due = 0;
        for (final Campaigns.Reprisal r : new Campaigns.Reprisal[] {campDebt, bareDebt, guardedDebt, cDebt}) {
            if (r != null) {
                due = Math.max(due, r.due);
            }
        }
        jumpTo(due + 40);
    }

    private ArmyRaidEvent eventOf(final Campaigns.Reprisal r) {
        if (r == null || r.eventId == 0) {
            return null;
        }
        final IColonyEvent event = colonyA.getEventManager().getEventByID(r.eventId);
        return event instanceof ArmyRaidEvent army ? army : null;
    }

    private void paid() {
        final Campaigns.Reprisal[] mine = {campDebt, bareDebt, guardedDebt};
        boolean all = true;
        for (final Campaigns.Reprisal r : mine) {
            all &= r != null && r.eventId != 0;
        }
        if (!all) {
            // a debt with no ground to stand on today is put off a day: push the clock on again
            if (tick % 200 == 0 && jumps < 4) {
                long due = 0;
                for (final Campaigns.Reprisal r : mine) {
                    if (r != null && r.eventId == 0) {
                        due = Math.max(due, r.due);
                    }
                }
                if (due > server.overworld().getGameTime()) {
                    jumpTo(due + 40);
                }
            }
            return;
        }
        check("colony A is active when its debts are paid", colonyA.getState() == ColonyState.ACTIVE, colonyA.getState());
        payment("the camp's debt", campDebt, "Raiders of the Pillager Outpost", "the Pillager Outpost", Places.CAMP, false);
        payment("the bare village's debt", bareDebt, "Militia of the Village", "the Village", Places.VILLAGE, false);
        payment("the guarded village's debt", guardedDebt, "Militia of the Village", "the Village", Places.VILLAGE,
                Places.villageGuardType() != null);
        check("colony C (nobody home) is not paid: its debt waits", cDebt != null && cDebt.eventId == 0
                        && server.overworld().getGameTime() >= cDebt.due && colonyC.getState() != ColonyState.ACTIVE
                        && Campaigns.reprisalsFor(level, colonyC.getID()).contains(cDebt),
                cDebt == null ? "none" : "event " + cDebt.eventId + ", colony C " + colonyC.getState() + ", due " + cDebt.due
                        + ", now " + server.overworld().getGameTime());
        stage = 6;
        waitUntil = tick + 2400;
    }

    private void payment(final String label, final Campaigns.Reprisal r, final String title, final String of, final String kind,
                         final boolean guards) {
        final ArmyRaidEvent event = eventOf(r);
        check(label + " is paid as a raid named after the place", event != null && title.equals(event.title())
                        && of.equals(event.attackerName()) && kind.equals(event.kind()) && event.campaign() == -r.id
                        && event.initialSize() == r.size && event.guarded() == guards,
                event == null ? "no event " + r.eventId : "'" + event.title() + "' of '" + event.attackerName() + "', kind " + event.kind()
                        + ", campaign " + event.campaign() + ", " + event.initialSize() + " soldiers, guarded " + event.guarded());
    }

    private static List<LivingEntity> men(final IColonyRaidEvent raid) {
        final List<LivingEntity> out = new ArrayList<>();
        for (final Entity e : new ArrayList<>(raid.getEntities())) {
            if (e instanceof LivingEntity man && man.isAlive()) {
                out.add(man);
            }
        }
        return out;
    }

    private static List<LivingEntity> captainsOf(final IColonyRaidEvent raid) {
        final List<LivingEntity> out = new ArrayList<>();
        for (final LivingEntity man : men(raid)) {
            if (Captains.isCaptain(man)) {
                out.add(man);
            }
        }
        return out;
    }

    private void captains() {
        final Campaigns.Reprisal[] mine = {campDebt, bareDebt, guardedDebt};
        if (tick % 100 == 0) {
            final StringBuilder line = new StringBuilder();
            for (final Campaigns.Reprisal r : mine) {
                final ArmyRaidEvent event = eventOf(r);
                line.append(r.title()).append(": ").append(event == null ? "gone" : event.getStatus() + " " + men(event).size() + " men "
                        + captainsOf(event).size() + " captain(s)").append("; ");
            }
            LOGGER.info("[revengegate] t={} {}", tick, line);
        }
        if (captainsAt < 0) {
            for (final Campaigns.Reprisal r : mine) {
                final ArmyRaidEvent event = eventOf(r);
                if (event == null || captainsOf(event).isEmpty()) {
                    return;
                }
            }
            // every raid has its captain: a while longer, to see that none gets a second
            captainsAt = tick + 60;
            return;
        }
        if (tick < captainsAt) {
            return;
        }
        captain("the raiders of the Pillager Outpost", eventOf(campDebt), "raiders", "the Pillager Outpost", false);
        captain("the militia of the bare village", eventOf(bareDebt), "militia", "the Village", false);
        captain("the militia of the guarded village", eventOf(guardedDebt), "militia", "the Village", Places.villageGuardType() != null);
        // and now past colony C's week
        jumpTo(cDebt.expires + 40);
        stage = 7;
        waitUntil = tick + 600;
    }

    private void captain(final String label, final ArmyRaidEvent event, final String raid, final String of, final boolean guards) {
        if (event == null) {
            check(label + " has its raid", false, "gone");
            return;
        }
        final List<LivingEntity> men = men(event);
        final List<LivingEntity> marked = captainsOf(event);
        check("exactly one captain leads " + label, marked.size() == 1 && Captains.recorded(colonyA, event.getID()),
                marked.size() + " of " + men.size() + " men");
        if (marked.size() != 1) {
            return;
        }
        final LivingEntity captain = marked.get(0);
        final CompoundTag mark = captain.getPersistentData().getCompound(Captains.TAG);
        final Component title = Captains.title(captain);
        final boolean named = title.getContents() instanceof TranslatableContents tc && "com.colonies_at_war.captain.of".equals(tc.getKey())
                && tc.getArgs().length == 2 && of.equals(tc.getArgs()[1]);
        check("the captain of " + label + " is of the place", raid.equals(mark.getString("raid")) && of.equals(mark.getString("of")) && named,
                mark.getString("raid") + " of '" + mark.getString("of") + "', " + title.getString());
        if (guards) {
            int dressed = 0;
            int others = 0;
            for (final LivingEntity man : men) {
                if (man == captain) {
                    continue;
                }
                others++;
                if (man.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && man.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                        && man.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
                    dressed++;
                }
            }
            check("the guard-led militia's men wear iron and carry shields, and the captain is a tier above",
                    others > 0 && dressed == others && captain.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                    dressed + " of " + others + " in iron with a shield; captain in " + captain.getItemBySlot(EquipmentSlot.CHEST));
        } else {
            final long shields = men.stream().filter(m -> m.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)).count();
            check(label + " carry no guard's shield", shields == 0, shields + " shields");
        }
    }

    // ------------------------------------------------------------------ forgetting

    private void forgotten() {
        if (Campaigns.reprisalsFor(level, colonyC.getID()).contains(cDebt)) {
            return;
        }
        final List<WarLog.Entry> lines = Campaigns.log(level).about(colonyC.getID(), 20);
        final boolean logged = lines.stream().anyMatch(l -> l.text().equals("the Pillager Outpost never came for " + colonyC.getName()));
        check("colony C, nobody home for a week, has its debt forgotten", colonyC.getState() != ColonyState.ACTIVE && logged,
                "colony C " + colonyC.getState() + ", log " + lines.stream().map(WarLog.Entry::text).toList());
        finish();
    }
}
