package me.lovkar.war.wall;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Asking the Builder for a piece of wall.
 *
 * <p>The same road MineColonies' own build tool takes for a decoration - load the blueprint out of
 * the pack, make a {@link WorkOrderDecoration}, hand it to the colony's work manager - with two
 * differences that matter. The blueprint is loaded <b>asynchronously</b>, because reading it off
 * disk on the server thread stutters a colony of sixty; and the order is only added if nothing is
 * already on its way to the same spot, because a player who presses "upgrade all" twice should get
 * one wall, not two work orders fighting over the same eight blocks.</p>
 */
public final class WallOrders {

    /**
     * The pack the wall lives in.
     *
     * <p>Structurize keys packs by the {@code name} in their pack.json, not by the folder - so
     * this is "Colonies at War" rather than "colonies_at_war". {@link #packName} falls back to
     * whichever pack actually carries our folder if somebody renames it, because a wall that
     * cannot be built because a string changed is a bad way to find out.</p>
     */
    private static final String PACK = "Colonies at War";
    private static String resolved;

    private WallOrders() {
    }

    /** The pack name Structurize knows our blueprints by, looked up once. */
    public static String packName() {
        if (resolved != null) {
            return resolved;
        }
        if (StructurePacks.getStructurePack(PACK) != null) {
            resolved = PACK;
            return resolved;
        }
        for (final StructurePackMeta meta : StructurePacks.getPackMetas()) {
            try {
                if (meta.getName() != null && meta.getName().toLowerCase(java.util.Locale.ROOT)
                        .replace(' ', '_').contains("colonies_at_war")) {
                    resolved = meta.getName();
                    Warfare.LOGGER.info("[wall] blueprints found in pack '{}'", resolved);
                    return resolved;
                }
            } catch (final Throwable ignored) {
                // a pack that will not say its own name is not the one we are looking for
            }
        }
        Warfare.LOGGER.warn("[wall] no structure pack called '{}' - the Builder will have nothing to read", PACK);
        return PACK;
    }

    /**
     * Which way to turn a blueprint so its own east runs the way the wall does.
     *
     * <p>The pieces are drawn running east, so east is no rotation at all and the rest follow the
     * compass. Getting this wrong is not subtle - the wall comes out at right angles to the line
     * the player drew - which is why it is one method with one table rather than arithmetic
     * scattered through the planner.</p>
     */
    public static RotationMirror facing(final Direction along) {
        return switch (along) {
            case EAST -> RotationMirror.NONE;
            case SOUTH -> RotationMirror.R90;
            case WEST -> RotationMirror.R180;
            case NORTH -> RotationMirror.R270;
            default -> RotationMirror.NONE;
        };
    }

    /** True if the colony already has a work order for this spot. */
    public static boolean pending(final IColony colony, final BlockPos pos) {
        try {
            for (final IServerWorkOrder order : colony.getWorkManager().getWorkOrders().values()) {
                if (order.getLocation() != null && order.getLocation().equals(pos)) {
                    return true;
                }
            }
        } catch (final Throwable ignored) {
            // an older work manager: better to risk a duplicate than to refuse to build at all
        }
        return false;
    }

    /**
     * Ask for one piece.
     *
     * @param type  BUILD the first time, UPGRADE to raise one that is already there, REMOVE to
     *              take it down; REPAIR to rebuild what is broken
     * @return false if something is already on its way there
     */
    public static boolean raise(final ServerLevel level, final IColony colony,
                                final WallPlan.Piece piece, final WorkOrderType type) {
        if (colony == null || piece == null) {
            return false;
        }
        if (!piece.kind().buildable()) {
            // a gap is a decision, not a thing: there is no blueprint to hand the Builder, and
            // asking him for one is how a Builder ends up standing in a field forever
            return false;
        }
        if (pending(colony, piece.pos())) {
            return false;
        }
        final String pack = packName();
        final String path = piece.kind().path(piece.wanted());
        StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).thenAccept(blueprint -> {
            if (blueprint == null) {
                Warfare.LOGGER.warn("[wall] {} is not in pack '{}' - nothing ordered", path, pack);
                return;
            }
            // back on the server thread: a work order touches the colony, and the colony is not
            // thread safe. The future finishes on Structurize's own reader.
            level.getServer().execute(() -> {
                try {
                    if (pending(colony, piece.pos())) {
                        return;
                    }
                    final WorkOrderDecoration order = WorkOrderDecoration.create(
                            type, pack, path, piece.kind().translationKey(),
                            piece.pos(), facing(piece.along()), piece.wanted());
                    order.setBlueprint(blueprint, level);
                    colony.getWorkManager().addWorkOrder(order, false);
                    Warfare.LOGGER.info("[wall] ordered {} at {} facing {} for {}",
                            path, piece.pos().toShortString(), piece.along().getName(),
                            colony.getName());
                } catch (final Throwable t) {
                    Warfare.LOGGER.warn("[wall] could not order {} at {}: {}",
                            path, piece.pos().toShortString(), t.toString());
                }
            });
        });
        return true;
    }

    /**
     * Take back the order for a piece nobody wants any more.
     *
     * <p>Half of "I changed my mind" - the other half is forgetting the piece. Without this a
     * planner click that landed in the wrong field would leave the Builder carrying stone across
     * the town for the rest of the week.</p>
     *
     * @return true if there was one to cancel
     */
    public static boolean cancel(final IColony colony, final BlockPos pos) {
        try {
            for (final IServerWorkOrder order : new java.util.ArrayList<>(
                    colony.getWorkManager().getWorkOrders().values())) {
                if (order.getLocation() != null && order.getLocation().equals(pos)) {
                    colony.getWorkManager().removeWorkOrder(order.getID());
                    return true;
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[wall] could not cancel the order at {}: {}",
                    pos.toShortString(), t.toString());
        }
        return false;
    }

    /**
     * Put a piece down at once, the way the build tool does in creative.
     *
     * <p>Not part of the game: this is how a wall gets looked at without waiting an hour for a
     * Builder to carry stone across a valley, and it is behind an operator command for that
     * reason. It is the same placement Structurize itself performs - the same handler, the same
     * iterator - so what it shows is exactly what the Builder would eventually leave behind.</p>
     *
     * @return how many placement phases it took, or -1 if the blueprint could not be read
     */
    public static int paste(final ServerLevel level, final WallPlan.Piece piece) {
        if (piece == null || !piece.kind().buildable()) {
            return 0;                              // a gap pastes as nothing, which is what it is
        }
        try {
            final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint =
                    StructurePacks.getBlueprintFuture(packName(), piece.kind().path(piece.wanted()),
                            level.registryAccess()).get();
            if (blueprint == null) {
                return -1;
            }
            // Turn the blueprint here rather than trusting the handler to do it. The pack hands
            // out ONE cached Blueprint per file, and a handler that quietly fails to rotate it
            // leaves a wall whose north side is right and whose east side is laid across the run -
            // which is exactly what the first live test produced.
            final RotationMirror want = facing(piece.along());
            blueprint.setRotationMirror(want, level);
            Warfare.LOGGER.info("[wall] paste {} at {} facing {}: wanted {}, blueprint is {} ({}x{})",
                    piece.kind().folder(), piece.pos().toShortString(), piece.along().getName(),
                    want, blueprint.getRotationMirror(), blueprint.getSizeX(), blueprint.getSizeZ());
            final com.ldtteam.structurize.placement.structure.CreativeStructureHandler handler =
                    new com.ldtteam.structurize.placement.structure.CreativeStructureHandler(
                            level, piece.pos(), blueprint, want, true);
            final com.ldtteam.structurize.placement.StructurePlacer placer =
                    new com.ldtteam.structurize.placement.StructurePlacer(handler);
            int rounds = 0;
            while (rounds++ < 400) {
                final com.ldtteam.structurize.placement.StructurePhasePlacementResult result =
                        placer.executeStructureStep(level, null, placer.getIterator().getProgressPos(),
                                com.ldtteam.structurize.placement.StructurePlacer.Operation.BLOCK_PLACEMENT,
                                () -> placer.getIterator().increment(
                                        (info, at, handler2) -> false), false);
                if (result.getIteratorPos().equals(
                        com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS)) {
                    break;
                }
            }
            return rounds;
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[wall] could not paste {} at {}: {}",
                    piece.kind().folder(), piece.pos().toShortString(), t.toString());
            return -1;
        }
    }

    /**
     * What is actually standing at a piece's anchor, read off the decoration controller.
     *
     * <p>0 when nothing has been built yet - which is the honest answer while the Builder is still
     * walking over, and the reason the War Room's panel shows "waiting" rather than a level.</p>
     */
    public static int builtLevel(final ServerLevel level, final BlockPos pos) {
        try {
            if (!level.isLoaded(pos)) {
                return -1;                          // nobody is there to look
            }
            final net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof com.minecolonies.core.tileentities.TileEntityDecorationController deco) {
                return WallKind.levelOf(deco.getSchematicName());
            }
        } catch (final Throwable ignored) {
            // an unloaded chunk or a block somebody replaced: treat it as not built
        }
        return 0;
    }
}
