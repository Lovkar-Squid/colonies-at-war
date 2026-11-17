package me.lovkar.roadgate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.mojang.authlib.GameProfile;
import me.lovkar.war.Warfare;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.campaign.Plunder;
import me.lovkar.war.campaign.WarLog;
import me.lovkar.war.colony.ConvoyModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rig gate for the convoy on the road (C4), the half of the thieves' interception that lives here.
 *
 * <p>Two colonies 400 blocks apart, each with a town hall and a War Room, the receiver with a
 * warehouse. The sender hires two knights into its War Room, puts 128 iron and 32 bread in the
 * room and on the manifest, and sends a convoy through {@code Campaigns.convoy}, the war table's
 * own call. The convoy's clock is then set half way, and the gate calls {@code WarBridge} the way
 * Colonist Thieves does - by name and parameter types, through reflection - to find it mid-road
 * (and not from outside the range), read its cargo and escort, and rob it twice: at most half of
 * what it set out with, however it is asked. The campaign data is saved and read back mid-road,
 * and read as an older save without the new tags. Then the convoy is brought to its arrival: the
 * receiver gets only what is left, and the report and the war log say it was robbed on the road.
 * The six older bridge methods still answer, and standing does not move until the convoy arrives.</p>
 */
@Mod("roadgate")
public final class RoadGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadgate");
    private static final int SETUP = 40;
    private static final int HIRE = 60;
    private static final int SEND = 140;
    private static final int TIMEOUT = 1400;
    private static final int DISTANCE = 400;
    private static final int IRON = 128;
    private static final int BREAD = 32;
    private static final int TOTAL = IRON + BREAD;
    private static final String BRIDGE = "me.lovkar.war.api.WarBridge";

    private int tick;
    private MinecraftServer server;
    private ServerLevel level;
    private IColony from;
    private IColony to;
    private IBuilding fromRoom;
    private IBuilding toRoom;
    private IBuilding toWarehouse;
    private final List<ICitizenData> knights = new ArrayList<>();
    private Campaigns.Campaign convoy;
    private int standingBefore;
    private int fails;
    private int checks;
    private boolean finished;

    public RoadGate(final IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final Object detail) {
        checks++;
        if (!ok) {
            fails++;
        }
        LOGGER.info("[roadgate] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
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
            } else if (tick == HIRE) {
                hireAndLoad();
            } else if (tick == SEND) {
                send();
            } else if (tick > SEND && convoy != null && convoy.phase != Campaigns.Phase.MARCHING) {
                arrived();
                finish();
            }
            if (!finished && (tick >= TIMEOUT || tick > SEND && convoy == null)) {
                check("the convoy arrived before the time ran out", false, convoy == null ? "no convoy" : convoy.phase);
                finish();
            }
        } catch (final Throwable t) {
            LOGGER.error("[roadgate] the probe broke at tick {}", tick, t);
            check("probe runs", false, t.toString());
            finish();
        }
    }

    private void finish() {
        finished = true;
        LOGGER.info("[roadgate] {} checks, {} failed", checks, fails);
        LOGGER.info("[roadgate] RESULT {}", fails == 0 && checks > 0 ? "PASS" : "FAIL");
        server.halt(false);
    }

    // ------------------------------------------------------------------ the bridge, as the thief sees it

    private static Object bridge(final String name, final Class<?>[] types, final Object... args) throws ReflectiveOperationException {
        final Method m = Class.forName(BRIDGE).getMethod(name, types);
        return m.invoke(null, args);
    }

    @SuppressWarnings("unchecked")
    private List<long[]> near(final BlockPos at, final int range) throws ReflectiveOperationException {
        return (List<long[]>) bridge("convoysNear", new Class<?>[] {ServerLevel.class, BlockPos.class, int.class}, level, at, range);
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> cargo(final long id) throws ReflectiveOperationException {
        return (List<ItemStack>) bridge("convoyCargo", new Class<?>[] {ServerLevel.class, long.class}, level, id);
    }

    private int escort(final long id) throws ReflectiveOperationException {
        return (Integer) bridge("convoyEscort", new Class<?>[] {ServerLevel.class, long.class}, level, id);
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> rob(final long id, final int maxStacks) throws ReflectiveOperationException {
        return (List<ItemStack>) bridge("robConvoy", new Class<?>[] {ServerLevel.class, long.class, int.class}, level, id, maxStacks);
    }

    private int standing() throws ReflectiveOperationException {
        return (Integer) bridge("standing", new Class<?>[] {IColony.class, int.class}, from, to.getID());
    }

    private static long[] row(final List<long[]> rows, final long id) {
        for (final long[] r : rows) {
            if (r[0] == id) {
                return r;
            }
        }
        return null;
    }

    private static int count(final List<ItemStack> goods, final Item item) {
        int n = 0;
        for (final ItemStack s : goods) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the two colonies

    private void setUp() {
        level = server.overworld();
        final BlockPos home = surface(8, 8);
        final BlockPos market = surface(8 + DISTANCE, 8);
        for (final BlockPos c : new BlockPos[] {home, market}) {
            for (int cx = -3; cx <= 3; cx++) {
                for (int cz = -3; cz <= 3; cz++) {
                    level.setChunkForced((c.getX() >> 4) + cx, (c.getZ() >> 4) + cz, true);
                }
            }
        }
        from = colony(home, "Roadgate Home", "roadgate_a");
        to = colony(market, "Roadgate Market", "roadgate_b");
        check("two colonies are created", from != null && to != null && from.getID() != to.getID(),
                (from == null ? "none" : from.getID()) + " and " + (to == null ? "none" : to.getID()));
        if (from == null || to == null) {
            return;
        }
        final Block townHall = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall"));
        final Block warehouse = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhutwarehouse"));
        hut(from, home, townHall);
        hut(to, market, townHall);
        fromRoom = hut(from, home.offset(10, 0, 0), Warfare.BLOCK_HUT_WAR_ROOM.get());
        toRoom = hut(to, market.offset(10, 0, 0), Warfare.BLOCK_HUT_WAR_ROOM.get());
        toWarehouse = hut(to, market.offset(-10, 0, 0), warehouse);
        check("both colonies have a War Room standing, the receiver a warehouse",
                Campaigns.warRoom(from) != null && Campaigns.warRoom(to) != null && toWarehouse != null,
                fromRoom + " / " + toRoom + " / " + toWarehouse);
    }

    private BlockPos surface(final int x, final int z) {
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    private IColony colony(final BlockPos center, final String name, final String player) {
        final FakePlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(player.getBytes()), player));
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        return IColonyManager.getInstance().createColony(level, center, owner, name, "Medieval Oak");
    }

    private IBuilding hut(final IColony colony, final BlockPos pos, final Block block) {
        level.setBlock(pos, block.defaultBlockState(), 3);
        final BlockEntity te = level.getBlockEntity(pos);
        if (!(te instanceof AbstractTileEntityColonyBuilding hut)) {
            LOGGER.error("[roadgate] no hut block entity at {} ({})", pos, te);
            return null;
        }
        IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        if (building == null) {
            building = colony.getServerBuildingManager().addNewBuilding(hut, level);
        }
        if (building != null && building.getBuildingLevel() < 1) {
            building.setBuildingLevel(1);
        }
        LOGGER.info("[roadgate] {} at {} in {}: {}", BuiltInRegistries.BLOCK.getKey(block), pos, colony.getName(),
                building == null ? "not registered" : building.getClass().getSimpleName() + " level " + building.getBuildingLevel());
        return building;
    }

    // ------------------------------------------------------------------ the escort and the load

    private void hireAndLoad() {
        if (fromRoom == null) {
            check("a War Room to hire into", false, "none");
            return;
        }
        final WorkerBuildingModule module = fromRoom.getFirstModuleOccurance(WorkerBuildingModule.class);
        for (int i = 0; i < 2; i++) {
            final ICitizenData data = from.getCitizenManager().createAndRegisterCivilianData();
            from.getCitizenManager().spawnOrCreateCitizen(data, level, fromRoom.getPosition().offset(2, 1, 2 + i));
            final boolean ok = module.assignCitizen(data);
            LOGGER.info("[roadgate] {} hired: {} -> {}", data.getName(), ok, data.getJob() == null ? "no job" : data.getJob().getClass().getSimpleName());
            knights.add(data);
        }
        InventoryUtils.addItemStackToProvider(fromRoom, new ItemStack(Items.IRON_INGOT, 64));
        InventoryUtils.addItemStackToProvider(fromRoom, new ItemStack(Items.IRON_INGOT, 64));
        InventoryUtils.addItemStackToProvider(fromRoom, new ItemStack(Items.BREAD, BREAD));
        final ConvoyModule bay = ConvoyModule.of(fromRoom);
        final int on = bay == null ? 0 : bay.add(new ItemStack(Items.IRON_INGOT), IRON) + bay.add(new ItemStack(Items.BREAD), BREAD);
        check("128 iron and 32 bread are on the manifest and in the War Room", on == TOTAL && bay.loadedStacks() == 3,
                "on the manifest " + on + ", stacks loaded " + (bay == null ? "no bay" : bay.loadedStacks()));
    }

    // ------------------------------------------------------------------ on the road

    private void send() throws ReflectiveOperationException {
        if (from == null || to == null || fromRoom == null) {
            check("a convoy can be sent", false, "no colonies");
            return;
        }
        check("two knights are at home and fit", Campaigns.guardsAtHome(from) == 2, Campaigns.guardsAtHome(from));
        standingBefore = standing();
        final StringBuilder why = new StringBuilder();
        convoy = Campaigns.convoy(level, from, to.getID(), 0, why);
        check("Campaigns.convoy sends the convoy", convoy != null, why);
        if (convoy == null) {
            return;
        }
        final Campaigns.Campaign c = convoy;
        final long id = c.id;
        check("it sets out with the whole manifest, 160 items, and two guards", c.setOutWith == TOTAL
                && Plunder.count(c.plunder) == TOTAL && c.guards.length == 2 && c.onTheRoad(server.overworld().getGameTime()),
                c.setOutWith + " set out, " + Plunder.describe(c.plunder) + ", " + c.guards.length + " guards");

        // half way: the clock moved back by half the trip
        final long now = server.overworld().getGameTime();
        final long trip = c.arrive - c.departed;
        c.departed = now - trip / 2;
        c.arrive = c.departed + trip;
        final BlockPos mid = new BlockPos((c.warRoom.getX() + c.target.getX()) / 2, (c.warRoom.getY() + c.target.getY()) / 2,
                (c.warRoom.getZ() + c.target.getZ()) / 2);
        LOGGER.info("[roadgate] convoy {} from {} to {}, trip {} ticks, half way at {}", id, c.warRoom.toShortString(),
                c.target.toShortString(), trip, mid.toShortString());

        final long[] here = row(near(mid, 8), id);
        check("convoysNear finds it half way along the road", here != null && Math.abs(here[5] - mid.getX()) <= 2
                        && Math.abs(here[7] - mid.getZ()) <= 2,
                here == null ? "not found" : here[5] + "," + here[6] + "," + here[7] + " vs " + mid.toShortString());
        check("its row is id, sender, receiver, escort, stacks", here != null && here[1] == from.getID() && here[2] == to.getID()
                        && here[3] == 2 && here[4] == c.plunder.size(),
                here == null ? "none" : java.util.Arrays.toString(here));
        check("convoysNear does not find it from its War Room (64 blocks)", row(near(c.warRoom, 64), id) == null, c.warRoom.toShortString());
        check("convoysNear does not find it from the target (64 blocks)", row(near(c.target, 64), id) == null, c.target.toShortString());
        check("convoysNear finds it 60 blocks off the road with range 64, not with range 50",
                row(near(mid.offset(0, 0, 60), 64), id) != null && row(near(mid.offset(0, 0, 60), 50), id) == null, "");

        final List<ItemStack> load = cargo(id);
        check("convoyCargo matches what was sent", Plunder.count(load) == TOTAL && count(load, Items.IRON_INGOT) == IRON
                && count(load, Items.BREAD) == BREAD, Plunder.describe(load));
        load.get(0).shrink(10);
        check("convoyCargo hands out copies", Plunder.count(c.plunder) == TOTAL, Plunder.count(c.plunder));
        check("convoyEscort is the two guards", escort(id) == 2, escort(id));
        check("an unknown convoy: no cargo, escort -1, nothing to rob", cargo(987654L).isEmpty() && escort(987654L) == -1
                && rob(987654L, 5).isEmpty(), "");

        final List<ItemStack> first = rob(id, 1);
        final int took = Plunder.count(first);
        check("robConvoy takes at most the stacks asked and at most half", first.size() == 1 && took > 0 && took <= TOTAL / 2,
                Plunder.describe(first));
        final List<ItemStack> second = rob(id, 10);
        final int more = Plunder.count(second);
        check("a second robConvoy stops at exactly half of what it set out with", took + more == TOTAL / 2,
                took + " then " + more + " (" + Plunder.describe(second) + ")");
        final List<ItemStack> third = rob(id, 10);
        check("a third robConvoy takes nothing", third.isEmpty(), Plunder.describe(third));
        check("the convoy keeps the rest and records the theft", c.robbed == TOTAL / 2 && Plunder.count(c.plunder) == TOTAL / 2
                && Plunder.count(cargo(id)) == TOTAL / 2 && c.setOutWith == TOTAL, c.robbed + " robbed, " + Plunder.describe(c.plunder));
        check("what was taken is the convoy's own goods", count(first, Items.IRON_INGOT) + count(first, Items.BREAD) == took
                && count(second, Items.IRON_INGOT) + count(second, Items.BREAD) == more, "");
        check("convoysNear still finds it, with fewer stacks", row(near(mid, 8), id) != null && row(near(mid, 8), id)[4] == c.plunder.size(),
                c.plunder.size());
        check("standing does not move when a convoy is found, read or robbed", standing() == standingBefore,
                standingBefore + " -> " + standing());

        olderSix();
        saveAndReadBack(id, now);

        // and on to the far end: due now, the campaign tick arrives it within a second
        c.departed = now - trip;
        c.arrive = now;
    }

    private void olderSix() throws ReflectiveOperationException {
        check("hasWarRoom still answers", Boolean.TRUE.equals(bridge("hasWarRoom", new Class<?>[] {IColony.class}, from))
                && Boolean.FALSE.equals(bridge("hasWarRoom", new Class<?>[] {IColony.class}, (Object) null)), "");
        final int walls = (Integer) bridge("walls", new Class<?>[] {IColony.class}, from);
        check("walls still answers", walls >= -1 && walls <= 100, walls);
        check("isOnCampaign still answers: the escort is out",
                Boolean.TRUE.equals(bridge("isOnCampaign", new Class<?>[] {ICitizenData.class}, knights.get(0))), knights.get(0).getName());
        check("standing still answers", standingBefore >= -100 && standingBefore <= 100, standingBefore);
        check("atWar still answers", Boolean.FALSE.equals(bridge("atWar", new Class<?>[] {IColony.class, int.class}, from, to.getID())), "");
        Class.forName(BRIDGE).getMethod("offend", IColony.class, int.class, int.class, String.class);
    }

    private void saveAndReadBack(final long id, final long now) {
        final Campaigns data = Campaigns.of(server);
        final CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
        final Campaigns read = Campaigns.FACTORY.deserializer().apply(saved, server.registryAccess());
        final Campaigns.Campaign back = find(read, id);
        check("mid-road, a save and a read-back keep the theft", back != null && back.robbed == TOTAL / 2
                        && back.setOutWith == TOTAL && Plunder.count(back.plunder) == TOTAL / 2 && back.onTheRoad(now),
                back == null ? "lost" : back.robbed + " robbed of " + back.setOutWith + ", carrying " + Plunder.count(back.plunder));
        final CompoundTag old = saved.copy();
        for (final String key : old.getAllKeys()) {
            if (old.get(key) instanceof ListTag list && list.getElementType() == Tag.TAG_COMPOUND) {
                for (final Tag t : list) {
                    ((CompoundTag) t).remove("robbed");
                    ((CompoundTag) t).remove("setOutWith");
                }
            }
        }
        final Campaigns.Campaign older = find(Campaigns.FACTORY.deserializer().apply(old, server.registryAccess()), id);
        check("an older save without the tags reads not robbed, set out with what it holds",
                older != null && older.robbed == 0 && older.setOutWith == 0 && older.setOut() == TOTAL / 2,
                older == null ? "lost" : older.robbed + " / " + older.setOut());
    }

    private static Campaigns.Campaign find(final Campaigns data, final long id) {
        try {
            final java.lang.reflect.Field f = Campaigns.class.getDeclaredField("campaigns");
            f.setAccessible(true);
            for (final Object o : (List<?>) f.get(data)) {
                if (o instanceof Campaigns.Campaign c && c.id == id) {
                    return c;
                }
            }
        } catch (final ReflectiveOperationException e) {
            LOGGER.error("[roadgate] could not read the campaigns", e);
        }
        return null;
    }

    // ------------------------------------------------------------------ the far end

    private void arrived() throws ReflectiveOperationException {
        final Campaigns.Campaign c = convoy;
        final long id = c.id;
        LOGGER.info("[roadgate] the convoy arrived: {} - {}", c.phase, c.report);
        check("the convoy arrived and turned home", c.phase == Campaigns.Phase.RETURNING && c.plunder.isEmpty(), c.phase);
        check("the report says delivered, robbed on the road",
                c.report.startsWith("delivered") && c.report.contains("robbed on the road: 80 of its 160 items taken"), c.report);
        String line = null;
        for (final WarLog.Entry entry : Campaigns.log(level).about(to.getID(), 5)) {
            if (entry.text().contains("convoy reached")) {
                line = entry.text();
            }
        }
        check("the war log line says the convoy was robbed on the road",
                line != null && line.contains("robbed on the road: 80 of its 160 items taken") && !line.contains("com.colonies_at_war"), line);
        int got = 0;
        final List<ItemStack> there = new ArrayList<>();
        for (final IBuilding b : new IBuilding[] {toWarehouse, toRoom}) {
            for (final IItemHandler handler : InventoryUtils.getItemHandlersFromProvider(b)) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    final ItemStack s = handler.getStackInSlot(slot);
                    if (s.is(Items.IRON_INGOT) || s.is(Items.BREAD)) {
                        got += s.getCount();
                        there.add(s.copy());
                    }
                }
            }
        }
        check("the receiver gets only what is left, 80 of 160", got == TOTAL / 2, got + ": " + Plunder.describe(there));
        check("an arrived convoy is not on the road and cannot be robbed",
                row(near(c.target, 2000), id) == null && rob(id, 10).isEmpty() && escort(id) == 2, "");
        final int after = standing();
        check("standing rose on arrival only (the delivery), as before", after > standingBefore, standingBefore + " -> " + after);
        bridge("offend", new Class<?>[] {IColony.class, int.class, int.class, String.class}, from, to.getID(), 3, "roadgate");
        check("offend still answers: standing falls", standing() < after, after + " -> " + standing());
    }
}
