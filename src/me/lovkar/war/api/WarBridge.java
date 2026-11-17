package me.lovkar.war.api;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.lovkar.war.Warfare;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.colony.Standing;
import me.lovkar.war.wall.WallRegister;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The one class other mods are meant to call.
 *
 * <p>Colonist Thieves reflects on exactly these methods and nothing else, so this file is a
 * contract: <b>a signature here does not change without changing the thief's bridge with it.</b>
 * Adding one is safe - the thief looks each up by name and does without the ones an older war mod
 * has not got - which is how scouting and sabotage arrived without breaking anybody's world.</p>
 * Neither mod depends on the other; each looks for the other at runtime through one small class,
 * the same arrangement that worked for The Waking World reading MineColonies' claims.</p>
 *
 * <p>0.3.0 added the convoy on the road, so a thief can intercept one: {@link #convoysNear} finds
 * the convoys passing within range of a place, {@link #convoyCargo} and {@link #convoyEscort} say
 * what one carries and how many guards ride with it, and {@link #robConvoy} takes part of its
 * goods. Their signatures use only JDK, Minecraft and MineColonies types, so the thief can look
 * them up by name and parameter types without seeing a class of ours. None of them changes
 * standing: the thief calls {@link #offend} himself when his man was caught or seen.</p>
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
     * <b>caught or seen</b>. A clean job never reaches this method, which is Lovkar's first rule
     * expressed as a call that does not happen.</p>
     */
    public static void offend(final IColony mine, final int theirs, final int amount, final String why) {
        Standing.offend(mine, theirs, amount, why);
    }

    /** Openly at war. Robbing them costs nothing further, because there is nothing left to spend. */
    public static boolean atWar(final IColony mine, final int theirs) {
        return Standing.atWar(mine, theirs);
    }

    /**
     * The convoys on the road out within {@code range} blocks of {@code at}, in this level.
     *
     * <p>One {@code long[]} a convoy: {@code {id, fromColony, toColony, escortSize, stacksCarried,
     * x, y, z}} - the receiving colony is 0 when the convoy goes to a Waking World kingdom, and
     * x, y, z is where it is now, on the straight road from its War Room to the target, as far
     * along as the time since it left says. The range is measured flat (x and z): a road is
     * watched from a hill as well as from beside it. A convoy that has arrived, or is on its way
     * home, is not on the list. Empty when there are none, or on any error.</p>
     */
    public static List<long[]> convoysNear(final ServerLevel level, final BlockPos at, final int range) {
        final List<long[]> out = new ArrayList<>();
        if (level == null || at == null || range < 0) {
            return out;
        }
        try {
            final long now = level.getServer().overworld().getGameTime();
            final long most = (long) range * range;
            for (final Campaigns.Campaign c : Campaigns.convoysOnTheRoad(level)) {
                final BlockPos here = c.whereNow(now);
                final long dx = here.getX() - at.getX();
                final long dz = here.getZ() - at.getZ();
                if (dx * dx + dz * dz <= most) {
                    out.add(new long[] {c.id, c.attacker, c.defender, c.guards.length, c.plunder.size(),
                            here.getX(), here.getY(), here.getZ()});
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[bridge] convoysNear: {}", t.toString());
        }
        return out;
    }

    /** A copy of what this convoy carries now; empty when there is no such convoy (or it has arrived). */
    public static List<ItemStack> convoyCargo(final ServerLevel level, final long id) {
        final List<ItemStack> out = new ArrayList<>();
        if (level == null) {
            return out;
        }
        try {
            final Campaigns.Campaign c = Campaigns.byId(level, id);
            if (c != null && c.onConvoy()) {
                for (final ItemStack stack : c.plunder) {
                    out.add(stack.copy());
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[bridge] convoyCargo: {}", t.toString());
        }
        return out;
    }

    /** How many guards ride with this convoy - the courier, who does not fight, not counted - or -1 when unknown. */
    public static int convoyEscort(final ServerLevel level, final long id) {
        if (level == null) {
            return -1;
        }
        try {
            final Campaigns.Campaign c = Campaigns.byId(level, id);
            return c != null && c.onConvoy() ? c.guards.length : -1;
        } catch (final Throwable t) {
            return -1;
        }
    }

    /**
     * Thieves take goods off a convoy on the road out: at most {@code maxStacks} stacks, whole or
     * part, and never more than half of what it set out with, counted over every robbery of it.
     *
     * <p>Returns what was taken, for the thief to carry home; empty when the convoy is not on the
     * road, unknown, or already robbed of its half. The convoy delivers only what is left, and its
     * report and the war log say it was robbed on the road. Standing does not change here.</p>
     */
    public static List<ItemStack> robConvoy(final ServerLevel level, final long id, final int maxStacks) {
        if (level == null) {
            return new ArrayList<>();
        }
        try {
            return Campaigns.rob(level, id, maxStacks);
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[bridge] robConvoy: {}", t.toString());
            return new ArrayList<>();
        }
    }
}
