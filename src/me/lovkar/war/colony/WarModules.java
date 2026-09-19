package me.lovkar.war.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.guardtype.registry.ModGuardTypes;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.GuardBuildingModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.moduleviews.CombinedHiringLimitModuleView;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;

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

    /**
     * The tower's own men: one per level, like every guard tower in the game.
     *
     * <p>The limit is <b>combined</b>, and both modules say the whole of it. MineColonies'
     * {@code GuardBuildingModule.isFull()} counts every citizen the building has, of every kind,
     * against the one module's maximum - that is what the "combined hiring limit" view means, and
     * it is how the Barracks Tower's knight, ranger and druid modules each say "building level".
     * Splitting the garrison between the two modules, as 0.1 did, halved it: a level-3 tower whose
     * knights said 2 and whose rangers said 2 held two men, not three. The mix is the player's.</p>
     */
    public static int towerGarrison(final com.minecolonies.api.colony.buildings.IBuilding b) {
        return b.getBuildingLevel() == 0 ? 0 : b.getBuildingLevel() + WarResearch.extra(b, WarResearch.WATCHMEN);
    }

    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> TOWER_KNIGHT =
            new BuildingEntry.ModuleProducer<>("walltower_knight",
                    () -> new GuardBuildingModule(ModGuardTypes.knight.get(), true, WarModules::towerGarrison),
                    () -> CombinedHiringLimitModuleView::new);

    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> TOWER_RANGER =
            new BuildingEntry.ModuleProducer<>("walltower_ranger",
                    () -> new GuardBuildingModule(ModGuardTypes.ranger.get(), true, WarModules::towerGarrison),
                    () -> CombinedHiringLimitModuleView::new);

    /**
     * The Explorer: one, from the first level. Adaptability is the craft of coming back, Agility
     * of getting there. He sits in a guard building's combined count, so {@link
     * BuildingWarRoom#garrison} adds him back rather than let him cost a soldier.
     */
    public static final BuildingEntry.ModuleProducer<WorkerBuildingModule, WorkerBuildingModuleView> EXPLORER =
            new BuildingEntry.ModuleProducer<>("warroom_explorer",
                    () -> new WorkerBuildingModule(me.lovkar.war.Warfare.EXPLORER_JOB.get(), Skill.Adaptability,
                            Skill.Agility, false, BuildingWarRoom::explorers),
                    () -> WorkerBuildingModuleView::new);

    /** The table itself: who to march on, who is out, who is held. */
    public static final BuildingEntry.ModuleProducer<WarTableModule, WarTableModuleView> WAR_TABLE =
            new BuildingEntry.ModuleProducer<>("warroom_table", WarTableModule::new, () -> WarTableModuleView::new);

    /** The loading bay: what the next convoy carries, brought to the room by the couriers. */
    public static final BuildingEntry.ModuleProducer<ConvoyModule, ConvoyModuleView> CONVOY =
            new BuildingEntry.ModuleProducer<>("warroom_convoy", ConvoyModule::new, () -> ConvoyModuleView::new);

    /** The country round the town, and the Explorer to send into it. */
    public static final BuildingEntry.ModuleProducer<ExpeditionsModule, ExpeditionsModuleView> EXPEDITIONS =
            new BuildingEntry.ModuleProducer<>("warroom_expeditions", ExpeditionsModule::new, () -> ExpeditionsModuleView::new);

    /** The garrison: 2 / 4 / 6 / 9 / 12 - the whole of it on each module, see above. */
    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> WARROOM_KNIGHT =
            new BuildingEntry.ModuleProducer<>("warroom_knight",
                    () -> new GuardBuildingModule(ModGuardTypes.knight.get(), true, BuildingWarRoom::garrison),
                    () -> CombinedHiringLimitModuleView::new);

    public static final BuildingEntry.ModuleProducer<GuardBuildingModule, CombinedHiringLimitModuleView> WARROOM_RANGER =
            new BuildingEntry.ModuleProducer<>("warroom_ranger",
                    () -> new GuardBuildingModule(ModGuardTypes.ranger.get(), true, BuildingWarRoom::garrison),
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
