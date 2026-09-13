package me.lovkar.war.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.guardtype.registry.ModGuardTypes;
import com.minecolonies.core.colony.buildings.modules.GuardBuildingModule;
import com.minecolonies.core.colony.buildings.moduleviews.CombinedHiringLimitModuleView;

/**
 * The modules of the two buildings.
 *
 * <p>Both hire MineColonies' own guards - knights and rangers - so there is no new job, no new AI,
 * no new equipment economy and no new gear to balance. All that changes is <b>how many</b>, and
 * that is a one-line function of the building level, which is exactly the idiom every MineColonies
 * guard building already uses.</p>
 */
public final class WarModules {
    private WarModules() {
    }

    /** The tower's own men: one per level, like every guard tower in the game. */
    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> TOWER_KNIGHT =
            new BuildingEntry.ModuleProducer<>("walltower_knight",
                    () -> new GuardBuildingModule(ModGuardTypes.knight.get(), true,
                            b -> b.getBuildingLevel() == 0 ? 0
                                    : (b.getBuildingLevel() + 1) / 2
                                      + (WarResearch.extra(b, WarResearch.WATCHMEN) + 1) / 2),
                    () -> CombinedHiringLimitModuleView::new);

    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> TOWER_RANGER =
            new BuildingEntry.ModuleProducer<>("walltower_ranger",
                    () -> new GuardBuildingModule(ModGuardTypes.ranger.get(), true,
                            b -> b.getBuildingLevel() == 0 ? 0
                                    : b.getBuildingLevel() / 2 + 1
                                      + WarResearch.extra(b, WarResearch.WATCHMEN) / 2),
                    () -> CombinedHiringLimitModuleView::new);

    /** The garrison: 2 / 4 / 6 / 9 / 12, split between the two kinds. */
    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> WARROOM_KNIGHT =
            new BuildingEntry.ModuleProducer<>("warroom_knight",
                    () -> new GuardBuildingModule(ModGuardTypes.knight.get(), true, BuildingWarRoom::knights),
                    () -> CombinedHiringLimitModuleView::new);

    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> WARROOM_RANGER =
            new BuildingEntry.ModuleProducer<>("warroom_ranger",
                    () -> new GuardBuildingModule(ModGuardTypes.ranger.get(), true, BuildingWarRoom::rangers),
                    () -> CombinedHiringLimitModuleView::new);

    /**
     * The wall the colony has decided on, from the table it was decided at.
     *
     * <p>On the War Room rather than on a hut of its own, because a wall is not a place anybody
     * works - it is something the colony orders, and this is where a colony orders things.</p>
     */
    public static final BuildingEntry.ModuleProducer<WallsModule, WallsModuleView> WALLS =
            new BuildingEntry.ModuleProducer<>("warroom_walls",
                    WallsModule::new, () -> WallsModuleView::new);
}
