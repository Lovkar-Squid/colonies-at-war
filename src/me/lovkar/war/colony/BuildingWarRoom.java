package me.lovkar.war.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

/**
 * The War Room: a table, a map, and the people who march.
 *
 * <p>It hires the way the Barracks hires - that was the ask, and it is also the right answer,
 * because MineColonies already has the machinery. A guard module with a maximum that is a curve on
 * the building level is all it takes: <b>2, 4, 6, 9, 12</b>.</p>
 *
 * <p>The line that matters more than it looks: <b>tower guards defend, War Room guards
 * campaign.</b> Without it every war strips the walls and the answer to every war is "stay home".
 * With it, a colony that empties its towers to march is a colony a thief walks into - which is the
 * two mods feeding each other, and a tension worth playing.</p>
 */
public class BuildingWarRoom extends AbstractBuildingGuards {
    private static final String HUT_NAME = "warroom";
    private static final int MAX_LEVEL = 5;

    public BuildingWarRoom(final IColony colony, final BlockPos pos) {
        super(colony, pos);
    }

    @Override
    public @NotNull String getSchematicName() {
        return HUT_NAME;
    }

    @Override
    public int getMaxBuildingLevel() {
        return MAX_LEVEL;
    }

    /**
     * The standing army: 2, 4, 6, 9, 12, plus whatever the University's drill has bought.
     *
     * <p>A colony with no War Room built has no garrison at all, research or not - the bonus is on
     * top of a building, not instead of one.</p>
     */
    public static int garrison(final IBuilding building) {
        final int byLevel = switch (building.getBuildingLevel()) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 6;
            case 4 -> 9;
            case 5 -> 12;
            default -> 0;
        };
        return byLevel == 0 ? 0 : byLevel + WarResearch.extra(building, WarResearch.DRILL) + explorersHired(building);
    }

    /** How many explorers the room may keep: one, as soon as it stands. */
    public static int explorers(final IBuilding building) {
        return building.getBuildingLevel() == 0 ? 0 : 1;
    }

    /**
     * The explorers actually hired. A guard building's hiring limit is combined across every
     * citizen it has, so without this an explorer would take a soldier's place.
     */
    public static int explorersHired(final IBuilding building) {
        int hired = 0;
        try {
            for (final com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule module
                    : building.getModulesByType(com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule.class)) {
                if (module.getJobEntry() == me.lovkar.war.Warfare.EXPLORER_JOB.get()) {
                    hired += module.getAssignedCitizen().size();
                }
            }
        } catch (final Throwable ignored) {
            // no modules to read: no explorer
        }
        return hired;
    }

    /** The explorer, if one is hired, whether or not he is at home. */
    public static com.minecolonies.api.colony.ICitizenData explorer(final IBuilding building) {
        try {
            for (final com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule module
                    : building.getModulesByType(com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule.class)) {
                if (module.getJobEntry() == me.lovkar.war.Warfare.EXPLORER_JOB.get()
                        && !module.getAssignedCitizen().isEmpty()) {
                    return module.getAssignedCitizen().get(0);
                }
            }
        } catch (final Throwable ignored) {
            // no modules to read: no explorer
        }
        return null;
    }

    @Override
    public int getClaimRadius(final int level) {
        return level >= 4 ? 1 : 0;
    }

    /**
     * The client's half of a guard building.
     *
     * <p>MineColonies' own settings window casts the building view to
     * {@link AbstractBuildingGuards.View} the moment a guard setting renders - the task dropdown
     * reads the patrol targets and the range off it. Hand the client an {@code EmptyView} and the
     * cast throws the moment the player opens the settings tab, which is how this crashed his game.
     * Their own guard tower's view is the same empty subclass; the point is the type, not the
     * contents.</p>
     */
    public static class View extends AbstractBuildingGuards.View {
        public View(final com.minecolonies.api.colony.IColonyView colony,
                    final BlockPos at) {
            super(colony, at);
        }
    }

}
