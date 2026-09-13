package me.lovkar.war.api;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.lovkar.war.colony.Standing;
import me.lovkar.war.wall.WallRegister;

/**
 * The one class other mods are meant to call.
 *
 * <p>Colonist Thieves reflects on exactly these methods and nothing else, so this file is a
 * contract: <b>a signature here does not change without changing the thief's bridge with it.</b>
 * Adding one is safe - the thief looks each up by name and does without the ones an older war mod
 * has not got - which is how scouting and sabotage arrived without breaking anybody's world.</p>
 * Neither mod depends on the other; each looks for the other at runtime through one small class,
 * the same arrangement that worked for The Waking World reading MineColonies' claims.</p>
 */
public final class WarBridge {
    private WarBridge() {
    }

    /**
     * True if this colony has a War Room standing.
     *
     * <p>The gate on the two jobs that only exist when both mods are installed. A colony with no
     * War Room has nobody who would know what to do with a scout's report, so the Hideout does not
     * offer the job at all - which is a better answer than offering it and having it do nothing.</p>
     */
    public static boolean hasWarRoom(final IColony colony) {
        if (colony == null) {
            return false;
        }
        try {
            for (final com.minecolonies.api.colony.buildings.IBuilding building
                    : colony.getServerBuildingManager().getBuildings().values()) {
                if (building instanceof me.lovkar.war.colony.BuildingWarRoom && building.getBuildingLevel() > 0) {
                    return true;
                }
            }
        } catch (final Throwable ignored) {
            // a MineColonies whose building manager we cannot read: no War Room as far as we know
        }
        return false;
    }

    /** How walled this colony is, 0..100, or -1 if it has laid none of our wall at all. */
    public static int walls(final IColony colony) {
        return WallRegister.score(colony);
    }

    /** True if this guard has marched out with a warband and is defending nothing at home. */
    public static boolean isOnCampaign(final ICitizenData citizen) {
        return Standing.onCampaign(citizen);
    }

    /** How the other colony feels about this one, -100..100. */
    public static int standing(final IColony mine, final int theirs) {
        return Standing.between(mine, theirs);
    }

    /**
     * Something was done to them and they know who did it.
     *
     * <p>This is the only way standing ever falls, and the thief calls it only when his man was
     * <b>caught or seen</b>. A clean job never reaches this method, which is Marko's first rule
     * expressed as a call that does not happen.</p>
     */
    public static void offend(final IColony mine, final int theirs, final int amount, final String why) {
        Standing.offend(mine, theirs, amount, why);
    }

    /** Openly at war. Robbing them costs nothing further, because there is nothing left to spend. */
    public static boolean atWar(final IColony mine, final int theirs) {
        return Standing.atWar(mine, theirs);
    }
}
