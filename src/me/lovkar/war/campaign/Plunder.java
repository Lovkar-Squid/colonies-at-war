package me.lovkar.war.campaign;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.util.InventoryUtils;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * What a warband carries home.
 *
 * <p>A <b>datapack loot table</b>, {@code colonies_at_war:plunder/colony}, the same decision the
 * thief made on day one: a pack decides what sacking a town is worth without touching Java. At
 * skirmish depth plunder is "a little" - it is rolled, not taken out of anybody's racks - which is
 * also what lets a war be fought against a colony whose owner is asleep in another time zone.</p>
 *
 * <p>Wealth carries the rolls: a rich town rolls more, a decisive victory rolls better.</p>
 */
public final class Plunder {
    private Plunder() {
    }

    public static final ResourceLocation TABLE = ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "plunder/colony");

    public static final ResourceLocation KINGDOM_TABLE = ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "plunder/kingdom");

    public static List<ItemStack> roll(final ServerLevel level, final IColony sacked, final float margin,
                                       final boolean doubled, final RandomSource random) {
        return roll(level, TABLE, wealth(sacked), sacked.getCenter(), margin, doubled, random);
    }

    /** The same roll for any place with a wealth and a loot table of its own - a kingdom. */
    public static List<ItemStack> roll(final ServerLevel level, final ResourceLocation tableId, final int wealth,
                                       final BlockPos origin, final float margin, final boolean doubled,
                                       final RandomSource random) {
        final List<ItemStack> out = new ArrayList<>();
        final LootTable table;
        try {
            table = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[plunder] no loot table {} - the warband comes home empty", tableId);
            return out;
        }
        if (table == null || table == LootTable.EMPTY) {
            Warfare.LOGGER.warn("[plunder] loot table {} is empty - the warband comes home empty", tableId);
            return out;
        }
        final LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(origin))
                .withLuck(Math.max(0f, margin) * 4f + wealth / 25f)
                .create(LootContextParamSets.CHEST);
        int rolls = Math.max(1, Math.round(WarConfig.plunderRolls() * (0.5f + Math.max(0f, margin) / 2f)
                + wealth / 40f));
        if (doubled) {
            rolls *= 2;
        }
        for (int i = 0; i < rolls; i++) {
            out.addAll(table.getRandomItems(params, random));
        }
        return out;
    }

    /** A town's wealth in the thief's units: warehouses ×6 a level, every other hut once, citizens once. */
    public static int wealth(final IColony colony) {
        int wealth = 0;
        try {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                final int level = building.getBuildingLevel();
                if (level <= 0) {
                    continue;
                }
                wealth += building instanceof IWareHouse ? level * 6 : level;
            }
            wealth += colony.getCitizenManager().getCurrentCitizenCount();
        } catch (final Throwable ignored) {
            // an unreadable town is a poor one
        }
        return Math.min(100, wealth);
    }

    /**
     * Into the warehouse, failing that into the War Room, failing that onto its doorstep.
     *
     * @return how many stacks went into a container rather than the ground
     */
    public static int deliver(final ServerLevel level, final IColony home, final IBuilding warRoom,
                              final List<ItemStack> goods) {
        int stored = 0;
        for (final ItemStack stack : goods) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean in = false;
            try {
                for (final IWareHouse warehouse : home.getServerBuildingManager().getWareHouses()) {
                    if (InventoryUtils.addItemStackToProvider(warehouse, stack.copy())) {
                        in = true;
                        break;
                    }
                }
                if (!in && warRoom != null && InventoryUtils.addItemStackToProvider(warRoom, stack.copy())) {
                    in = true;
                }
            } catch (final Throwable t) {
                Warfare.LOGGER.debug("[plunder] could not store {}: {}", stack, t.toString());
            }
            if (in) {
                stored++;
            } else {
                final BlockPos at = warRoom == null ? home.getCenter() : warRoom.getPosition();
                Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, stack.copy());
            }
        }
        return stored;
    }

    /** One line for the log: "48 items - iron ingot, bread, oak planks". */
    public static String describe(final List<ItemStack> goods) {
        if (goods.isEmpty()) {
            return "nothing";
        }
        int count = 0;
        final List<String> names = new ArrayList<>();
        for (final ItemStack stack : goods) {
            count += stack.getCount();
            final String name = stack.getHoverName().getString();
            if (names.size() < 4 && !names.contains(name)) {
                names.add(name);
            }
        }
        return count + " item(s) - " + String.join(", ", names) + (goods.size() > 4 ? ", ..." : "");
    }
}
