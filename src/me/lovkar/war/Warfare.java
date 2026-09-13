package me.lovkar.war;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.items.ItemBlockHut;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import me.lovkar.war.block.BlockHutWallTower;
import me.lovkar.war.block.BlockHutWarRoom;
import me.lovkar.war.block.WarTileEntity;
import me.lovkar.war.colony.BuildingWallTower;
import me.lovkar.war.colony.BuildingWarRoom;
import me.lovkar.war.colony.WarModules;
import me.lovkar.war.wall.ParapetBlock;
import me.lovkar.war.wall.RampartBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Colonies at War - walls you can build, towers your guards walk, and a room where the marching
 * is decided.
 *
 * <p>Three things MineColonies has no answer for today, in the order they matter. A <b>wall</b>
 * that is a thing the game knows about rather than a thing something has to guess at, because its
 * pieces register themselves. A <b>Wall Tower</b> whose guards patrol that wall, using nothing but
 * MineColonies' own public patrol API. And a <b>War Room</b> that hires a garrison the way the
 * Barracks does, where the level is how many.</p>
 *
 * <p>Standing and war live in {@link me.lovkar.war.colony.Standing}, and two rules are built into
 * that class rather than trusted to discipline: standing only ever falls when somebody is caught,
 * and nothing in this mod ever declares a war by itself.</p>
 */
@Mod(Warfare.MODID)
public class Warfare {
    public static final String MODID = "colonies_at_war";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static final String WALL_TOWER_HUT = "blockhutwalltower";
    public static final String WAR_ROOM_HUT = "blockhutwarroom";
    public static final ResourceLocation WALL_TOWER_ID = ResourceLocation.fromNamespaceAndPath(MODID, "walltower");
    public static final ResourceLocation WAR_ROOM_ID = ResourceLocation.fromNamespaceAndPath(MODID, "warroom");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<BuildingEntry> BUILDINGS =
            DeferredRegister.create(CommonMinecoloniesAPIImpl.BUILDINGS, MODID);

    // ------------------------------------------------------------------ the wall

    /**
     * Three tiers, and the tier is the strength. A palisade is what a young colony can afford; a
     * fortified wall is what it has by the time anybody wants to take it. The cosmetics could come
     * from Domum Ornamentum later, but the score comes from the tier and never from the skin -
     * otherwise a palisade painted like granite defends like granite.
     */
    private static BlockBehaviour.Properties wall(final MapColor colour, final float hardness,
                                                  final float blast, final SoundType sound) {
        return BlockBehaviour.Properties.of().mapColor(colour).strength(hardness, blast)
                .sound(sound).requiresCorrectToolForDrops();
    }

    public static final DeferredBlock<RampartBlock> PALISADE = BLOCKS.register("palisade",
            () -> new RampartBlock(wall(MapColor.WOOD, 2.5f, 4.0f, SoundType.WOOD), 1));
    public static final DeferredBlock<ParapetBlock> PALISADE_PARAPET = BLOCKS.register("palisade_parapet",
            () -> new ParapetBlock(wall(MapColor.WOOD, 2.0f, 3.0f, SoundType.WOOD), 1));
    public static final DeferredBlock<RampartBlock> STONE_RAMPART = BLOCKS.register("stone_rampart",
            () -> new RampartBlock(wall(MapColor.STONE, 4.0f, 9.0f, SoundType.STONE), 2));
    public static final DeferredBlock<ParapetBlock> STONE_PARAPET = BLOCKS.register("stone_parapet",
            () -> new ParapetBlock(wall(MapColor.STONE, 3.5f, 8.0f, SoundType.STONE), 2));
    public static final DeferredBlock<RampartBlock> FORTIFIED_RAMPART = BLOCKS.register("fortified_rampart",
            () -> new RampartBlock(wall(MapColor.DEEPSLATE, 6.0f, 14.0f, SoundType.DEEPSLATE_BRICKS), 3));
    public static final DeferredBlock<ParapetBlock> FORTIFIED_PARAPET = BLOCKS.register("fortified_parapet",
            () -> new ParapetBlock(wall(MapColor.DEEPSLATE, 5.5f, 12.0f, SoundType.DEEPSLATE_BRICKS), 3));

    private static DeferredItem<Item> blockItem(final String name, final DeferredBlock<? extends Block> block) {
        return ITEMS.register(name, () -> new net.minecraft.world.item.BlockItem(block.get(), new Item.Properties()));
    }

    public static final DeferredItem<Item> PALISADE_ITEM = blockItem("palisade", PALISADE);
    public static final DeferredItem<Item> PALISADE_PARAPET_ITEM = blockItem("palisade_parapet", PALISADE_PARAPET);
    public static final DeferredItem<Item> STONE_RAMPART_ITEM = blockItem("stone_rampart", STONE_RAMPART);
    public static final DeferredItem<Item> STONE_PARAPET_ITEM = blockItem("stone_parapet", STONE_PARAPET);
    public static final DeferredItem<Item> FORTIFIED_RAMPART_ITEM = blockItem("fortified_rampart", FORTIFIED_RAMPART);
    public static final DeferredItem<Item> FORTIFIED_PARAPET_ITEM = blockItem("fortified_parapet", FORTIFIED_PARAPET);

    /**
     * The Wall Planner: mark two corners and the colony raises a wall between them.
     *
     * <p>A tool rather than a block, and deliberately cheap - it costs a map and some paper,
     * because what it does is draw a line, and the wall itself is paid for a block at a time by
     * the Builder like every other building in the game.</p>
     */
    public static final DeferredItem<Item> WALL_PLANNER = ITEMS.register("wall_planner",
            () -> new me.lovkar.war.item.WallPlannerItem(new Item.Properties()));

    // ------------------------------------------------------------------ the buildings

    public static final DeferredBlock<BlockHutWallTower> BLOCK_HUT_WALL_TOWER =
            BLOCKS.register(WALL_TOWER_HUT, BlockHutWallTower::new);
    public static final DeferredItem<Item> ITEM_HUT_WALL_TOWER =
            ITEMS.register(WALL_TOWER_HUT, () -> new ItemBlockHut(BLOCK_HUT_WALL_TOWER.get(), new Item.Properties()));
    public static final DeferredBlock<BlockHutWarRoom> BLOCK_HUT_WAR_ROOM =
            BLOCKS.register(WAR_ROOM_HUT, BlockHutWarRoom::new);
    public static final DeferredItem<Item> ITEM_HUT_WAR_ROOM =
            ITEMS.register(WAR_ROOM_HUT, () -> new ItemBlockHut(BLOCK_HUT_WAR_ROOM.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarTileEntity>> BUILDING_BE =
            BLOCK_ENTITIES.register("colonybuilding",
                    () -> BlockEntityType.Builder.of(WarTileEntity::new,
                            BLOCK_HUT_WALL_TOWER.get(), BLOCK_HUT_WAR_ROOM.get()).build(null));

    public static final DeferredHolder<BuildingEntry, BuildingEntry> WALL_TOWER =
            BUILDINGS.register(WALL_TOWER_ID.getPath(),
                    () -> new BuildingEntry.Builder()
                            .setBuildingBlock(BLOCK_HUT_WALL_TOWER.get())
                            .setBuildingProducer(BuildingWallTower::new)
                            .setBuildingViewProducer(() -> BuildingWallTower.View::new)
                            .setRegistryName(WALL_TOWER_ID)
                            .addBuildingModuleProducer(WarModules.TOWER_KNIGHT)
                            .addBuildingModuleProducer(WarModules.TOWER_RANGER)
                            // MineColonies' OWN producer, never a replacement: AbstractBuildingGuards
                            // looks this exact object up with getModule(), so a substitute is a null
                            // and an NPE in the guard AI every tick. Our own setting is added INTO it,
                            // in BuildingWallTower.
                            .addBuildingModuleProducer(BuildingModules.GUARD_SETTINGS)
                            .addBuildingModuleProducer(BuildingModules.GUARD_ENTITY_LIST)
                            .addBuildingModuleProducer(BuildingModules.GUARD_TOOL)
                            // the men on a wall sleep on the wall: the blueprints give every level
                            // its bunks, and a guard module that works at home needs somewhere to
                            .addBuildingModuleProducer(BuildingModules.BED)
                            .addBuildingModuleProducer(BuildingModules.MIN_STOCK)
                            .addBuildingModuleProducer(BuildingModules.STATS_MODULE)
                            .createBuildingEntry());

    public static final DeferredHolder<BuildingEntry, BuildingEntry> WAR_ROOM =
            BUILDINGS.register(WAR_ROOM_ID.getPath(),
                    () -> new BuildingEntry.Builder()
                            .setBuildingBlock(BLOCK_HUT_WAR_ROOM.get())
                            .setBuildingProducer(BuildingWarRoom::new)
                            .setBuildingViewProducer(() -> BuildingWarRoom.View::new)
                            .setRegistryName(WAR_ROOM_ID)
                            .addBuildingModuleProducer(WarModules.WARROOM_KNIGHT)
                            .addBuildingModuleProducer(WarModules.WARROOM_RANGER)
                            .addBuildingModuleProducer(WarModules.WALLS)
                            .addBuildingModuleProducer(BuildingModules.GUARD_SETTINGS)
                            .addBuildingModuleProducer(BuildingModules.GUARD_ENTITY_LIST)
                            .addBuildingModuleProducer(BuildingModules.GUARD_TOOL)
                            .addBuildingModuleProducer(BuildingModules.BED)
                            .addBuildingModuleProducer(BuildingModules.MIN_STOCK)
                            .addBuildingModuleProducer(BuildingModules.STATS_MODULE)
                            .createBuildingEntry());

    private static final ResourceKey<CreativeModeTab> HUTS_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath("minecolonies", "mchuts"));

    public Warfare(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        BUILDINGS.register(modEventBus);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, WarConfig.SPEC);
        modEventBus.addListener(EventPriority.HIGH, Warfare::registerCapabilities);
        modEventBus.addListener(Warfare::addToCreativeTab);
        modEventBus.addListener(Warfare::registerPayloads);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WarCommands::register);
        LOGGER.info("Colonies at War loaded - walls, wall towers and the War Room");
    }

    /**
     * What the War Room's wall panel may ask the server to do.
     *
     * <p>One packet, four verbs, and every one of them re-checked on the server: a request from a
     * client is a request, never an instruction.</p>
     */
    private static void registerPayloads(final net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                me.lovkar.war.network.WallActionMessage.TYPE,
                me.lovkar.war.network.WallActionMessage.STREAM_CODEC,
                me.lovkar.war.network.WallActionMessage::handle);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BUILDING_BE.get(),
                (be, side) -> be.getItemHandlerCap(side));
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(HUTS_TAB)) {
            return;
        }
        event.accept(ITEM_HUT_WALL_TOWER.get());
        event.accept(ITEM_HUT_WAR_ROOM.get());
        event.accept(PALISADE_ITEM.get());
        event.accept(PALISADE_PARAPET_ITEM.get());
        event.accept(STONE_RAMPART_ITEM.get());
        event.accept(STONE_PARAPET_ITEM.get());
        event.accept(FORTIFIED_RAMPART_ITEM.get());
        event.accept(FORTIFIED_PARAPET_ITEM.get());
        event.accept(WALL_PLANNER.get());
    }
}
