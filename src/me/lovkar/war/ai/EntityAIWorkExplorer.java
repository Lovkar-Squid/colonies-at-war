package me.lovkar.war.ai;

import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import me.lovkar.war.colony.BuildingWarRoom;
import me.lovkar.war.colony.JobExplorer;
import me.lovkar.war.colony.Standing;
import org.jetbrains.annotations.NotNull;

/**
 * The Explorer at home: at the table, over the map, out for a look round the room.
 *
 * <p>Deliberately small. An expedition is ordered at the table and run by the campaign clock,
 * which takes him out of the world the way it takes the warband - through MineColonies' own
 * travelling manager - and brings him back with the haul. While he is gone there is no entity for
 * this AI to run in. What is left for it is the hours between: walk to the War Room, and wander
 * near it so he looks like he is doing something, because he is - he is waiting for orders.</p>
 */
public class EntityAIWorkExplorer extends AbstractEntityAIBasic<JobExplorer, BuildingWarRoom> {

    private static final int TICK_DELAY = 40;
    /** How far from the table he wanders, in blocks. */
    private static final int ROOM = 12;

    public EntityAIWorkExplorer(final @NotNull JobExplorer job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, 10),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::atTheTable, TICK_DELAY),
                new AITarget<IAIState>(AIWorkerState.WANDER, this::wander, TICK_DELAY));
    }

    @Override
    public Class<BuildingWarRoom> getExpectedBuildingClass() {
        return BuildingWarRoom.class;
    }

    @Override
    protected int getActionsDoneUntilDumping() {
        return 1;
    }

    private IAIState atTheTable() {
        if (Standing.onCampaign(worker.getCitizenData())) {
            // a reload can bring him back standing in the room in the middle of an expedition;
            // the clock will take him out again on its next look, and until then he keeps still
            job.setStatusLine("on an expedition");
            return AIWorkerState.IDLE;
        }
        if (!walkToBuilding()) {
            return getState();
        }
        job.setStatusLine("at the table, waiting for orders");
        return AIWorkerState.WANDER;
    }

    private IAIState wander() {
        if (Standing.onCampaign(worker.getCitizenData())) {
            return AIWorkerState.START_WORKING;
        }
        if (worker.getRandom().nextInt(4) == 0) {
            EntityNavigationUtils.walkToRandomPosAround(worker, building.getPosition(), ROOM, 0.6);
        }
        if (worker.getRandom().nextInt(12) == 0) {
            return AIWorkerState.START_WORKING;     // back to the table now and then
        }
        return getState();
    }
}
