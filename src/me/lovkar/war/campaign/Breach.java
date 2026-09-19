package me.lovkar.war.campaign;

import com.minecolonies.api.colony.IColony;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import me.lovkar.war.wall.WallRegister;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A hole in the wall, where the army came from.
 *
 * <p>The <i>raze</i> war goal, at skirmish depth: the register knows every block of the town's own
 * wall, so the breach is the stretch nearest the approach, knocked down to the ground course across
 * a few blocks. The register drops the blocks the moment they go, the score falls with them, and
 * the Builder puts them back with {@code /war raise}. Only with {@code wallsCanBreak}, which is
 * what that setting was for.</p>
 */
public final class Breach {
    private Breach() {
    }

    /** How wide a breach is, in blocks along the wall. */
    private static final int WIDTH = 4;

    /** @return how many blocks came down */
    public static int make(final ServerLevel level, final IColony town, final BlockPos from) {
        if (!WarConfig.wallsCanBreak()) {
            return 0;
        }
        final Set<BlockPos> wall;
        try {
            wall = WallRegister.wallOf(level, town.getID());
        } catch (final Throwable t) {
            return 0;
        }
        if (wall.isEmpty()) {
            return 0;
        }
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (final BlockPos pos : wall) {
            final double d = pos.distSqr(from);
            if (d < best) {
                best = d;
                nearest = pos;
            }
        }
        if (nearest == null) {
            return 0;
        }
        // everything of the wall within WIDTH of the nearest block, top to bottom
        final List<BlockPos> down = new ArrayList<>();
        for (final BlockPos pos : wall) {
            if (Math.abs(pos.getX() - nearest.getX()) <= WIDTH / 2 && Math.abs(pos.getZ() - nearest.getZ()) <= WIDTH / 2) {
                down.add(pos);
            }
        }
        int count = 0;
        for (final BlockPos pos : down) {
            try {
                if (level.isLoaded(pos) && !level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    count++;
                }
            } catch (final Throwable t) {
                Warfare.LOGGER.debug("[breach] could not break {}: {}", pos, t.toString());
            }
        }
        return count;
    }
}
