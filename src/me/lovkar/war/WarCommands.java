package me.lovkar.war;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.lovkar.war.colony.Standing;
import me.lovkar.war.wall.WallRegister;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /war} - read the wall, read the standing, and declare or end a war <b>by hand</b>.
 *
 * <p>The declare subcommand is deliberately a command a person types (and, later, a button a
 * person presses). Nothing in this mod calls it. That is the rule, and putting the only entry
 * point behind a human keystroke is how the rule is kept rather than merely intended.</p>
 */
public final class WarCommands {
    private WarCommands() {
    }

    public static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("war")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> here(ctx.getSource()))
                .then(Commands.literal("walls").executes(ctx -> here(ctx.getSource())))
                .then(Commands.literal("standing")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> standing(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "colony")))))
                .then(Commands.literal("declare")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .then(Commands.argument("goal", StringArgumentType.greedyString())
                                        .executes(ctx -> declare(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "colony"),
                                                StringArgumentType.getString(ctx, "goal"))))))
                .then(Commands.literal("peace")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> peace(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "colony")))))
                .then(Commands.literal("plan")
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(ctx -> plan(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "shape"),
                                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                                        IntegerArgumentType.getInteger(ctx, "z1"),
                                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                                        IntegerArgumentType.getInteger(ctx, "z2")))))))))
                .then(Commands.literal("plot").executes(ctx -> plot(ctx.getSource())))
                .then(Commands.literal("raise")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 5))
                                .executes(ctx -> raise(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("forget").executes(ctx -> forget(ctx.getSource())))
                .then(Commands.literal("paste").executes(ctx -> paste(ctx.getSource())))
                .then(Commands.literal("grow").executes(ctx -> hand(ctx.getSource(), true)))
                .then(Commands.literal("slot").executes(ctx -> hand(ctx.getSource(), false)))
                .then(Commands.literal("undo").executes(ctx -> undo(ctx.getSource())))
                .then(Commands.literal("planner")
                        .then(Commands.argument("mode",
                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (final me.lovkar.war.item.WallPlannerItem.Mode mode
                                            : me.lovkar.war.item.WallPlannerItem.Mode.values()) {
                                        builder.suggest(mode.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> planner(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "mode"))))));
    }

    /**
     * {@code /war plan ring|line x1 z1 x2 z2} - the Wall Planner, without the planner.
     *
     * <p>The same two marks the item takes, typed instead of clicked. It exists because a wall is
     * the one thing in this mod that is easier to get wrong by a block than by a mile, and a
     * command can be run from a script and read back exactly; and because a server operator
     * laying out a town should not have to hold a tool.</p>
     */
    private static int plan(final CommandSourceStack src, final String shape,
                            final int x1, final int z1, final int x2, final int z2) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in the colony the wall is for"));
            return 0;
        }
        final net.minecraft.server.level.ServerLevel level = src.getLevel();
        final int y = BlockPos.containing(src.getPosition()).getY();
        final BlockPos a = new BlockPos(x1, y, z1);
        final BlockPos b = new BlockPos(x2, y, z2);
        final boolean ring = !"line".equalsIgnoreCase(shape);
        final java.util.List<me.lovkar.war.wall.WallPlan.Piece> pieces = ring
                ? me.lovkar.war.wall.WallLayout.ring(a, b, net.minecraft.core.Direction.EAST)
                : me.lovkar.war.wall.WallLayout.line(a, b);

        final me.lovkar.war.wall.WallPlan plan = me.lovkar.war.wall.WallPlan.of(level);
        int laid = 0;
        for (final me.lovkar.war.wall.WallPlan.Piece piece : pieces) {
            if (plan.add(colony.getID(), piece)
                    && me.lovkar.war.wall.WallOrders.raise(level, colony, piece,
                    com.minecolonies.api.colony.workorders.WorkOrderType.BUILD)) {
                laid++;
            }
        }
        final int asked = laid;
        src.sendSuccess(() -> Component.literal("planned " + pieces.size() + " piece(s), "
                + asked + " ordered from the Builder"), false);
        return 1;
    }

    /** {@code /war plot} - what the colony's wall plan holds, piece by piece. */
    private static int plot(final CommandSourceStack src) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final net.minecraft.server.level.ServerLevel level = src.getLevel();
        final java.util.List<me.lovkar.war.wall.WallPlan.Piece> pieces =
                me.lovkar.war.wall.WallPlan.of(level).all(colony.getID());
        if (pieces.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no wall planned yet"), false);
            return 1;
        }
        final StringBuilder out = new StringBuilder(colony.getName() + "'s wall: "
                + pieces.size() + " piece(s), score " + WallRegister.score(colony));
        for (final me.lovkar.war.wall.WallPlan.Piece piece : pieces) {
            out.append("\n  ").append(piece.kind().folder())
                    .append(" at ").append(piece.pos().toShortString())
                    .append(" facing ").append(piece.along().getName())
                    .append("  want ").append(piece.wanted())
                    .append("  built ").append(me.lovkar.war.wall.WallOrders.builtLevel(level, piece.pos()));
        }
        src.sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    /** {@code /war raise <level>} - the War Room's button, from the keyboard. */
    private static int raise(final CommandSourceStack src, final int level) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final net.minecraft.server.level.ServerLevel world = src.getLevel();
        final me.lovkar.war.wall.WallPlan plan = me.lovkar.war.wall.WallPlan.of(world);
        plan.wantAll(colony.getID(), level);
        int asked = 0;
        for (final me.lovkar.war.wall.WallPlan.Piece piece : plan.all(colony.getID())) {
            final int built = me.lovkar.war.wall.WallOrders.builtLevel(world, piece.pos());
            if (built == level) {
                continue;
            }
            if (me.lovkar.war.wall.WallOrders.raise(world, colony, piece,
                    built > 0 ? com.minecolonies.api.colony.workorders.WorkOrderType.UPGRADE
                            : com.minecolonies.api.colony.workorders.WorkOrderType.BUILD)) {
                asked++;
            }
        }
        final int n = asked;
        src.sendSuccess(() -> Component.literal("the whole wall to level " + level
                + " - " + n + " order(s)"), false);
        return 1;
    }

    /**
     * {@code /war paste} - put the whole planned wall down at once.
     *
     * <p>An operator's tool and a developer's, not part of the game: it is how a wall gets looked
     * at without waiting for a Builder to carry the stone. What it places is exactly what the
     * Builder would eventually leave, because it is Structurize's own placement.</p>
     */
    private static int paste(final CommandSourceStack src) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final net.minecraft.server.level.ServerLevel level = src.getLevel();
        final java.util.List<me.lovkar.war.wall.WallPlan.Piece> pieces =
                me.lovkar.war.wall.WallPlan.of(level).all(colony.getID());
        int done = 0;
        for (final me.lovkar.war.wall.WallPlan.Piece piece : pieces) {
            if (me.lovkar.war.wall.WallOrders.paste(level, piece) > 0) {
                done++;
            }
        }
        final int n = done;
        src.sendSuccess(() -> Component.literal("pasted " + n + " of " + pieces.size()
                + " piece(s); wall score is now " + WallRegister.score(colony)), false);
        return 1;
    }

    /** {@code /war forget} - drop the plan. The blocks stay; the colony stops calling them a wall. */
    /**
     * Do by command what the Wall Planner does by right-click.
     *
     * <p>Both go through the same two methods, so this is the feature and not a copy of it. It
     * exists because a right-click in the world cannot be driven from outside the game - Minecraft
     * reads raw mouse input once the pointer is grabbed - so without a command the grow and slot
     * modes could never be exercised on a running world. It is also simply a nicer way to lay a
     * long wall: stand where you want the next piece, look the way it should go, and repeat.</p>
     */
    private static int hand(final CommandSourceStack src, final boolean growing) {
        final net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (final Throwable t) {
            src.sendFailure(Component.literal("a player has to be standing there"));
            return 0;
        }
        final net.minecraft.core.BlockPos at = player.blockPosition();
        if (growing) {
            me.lovkar.war.item.WallPlannerItem.grow(player, player.serverLevel(), at);
        } else {
            me.lovkar.war.item.WallPlannerItem.slot(player, player.serverLevel(), at);
        }
        return 1;
    }

    /**
     * Set the held Wall Planner's mode from the keyboard.
     *
     * <p>Sneak-and-right-click cycles it in the hand, which is how a player will use it. This
     * exists because that gesture cannot be driven from outside the game, so without it the grow
     * and slot modes could never be exercised on a running world - and a mode nobody can reach in
     * a test is a mode that ships broken.</p>
     */
    private static int planner(final CommandSourceStack src, final String name) {
        final me.lovkar.war.item.WallPlannerItem.Mode mode =
                me.lovkar.war.item.WallPlannerItem.Mode.byId(name.toLowerCase(java.util.Locale.ROOT));
        if (mode == null) {
            src.sendFailure(Component.literal("ring, line, grow or slot"));
            return 0;
        }
        final net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (final Throwable t) {
            src.sendFailure(Component.literal("a player has to be holding it"));
            return 0;
        }
        for (final net.minecraft.world.item.ItemStack held : new net.minecraft.world.item.ItemStack[]{
                player.getMainHandItem(), player.getOffhandItem()}) {
            if (held.getItem() instanceof me.lovkar.war.item.WallPlannerItem) {
                me.lovkar.war.item.WallPlannerItem.setMode(held, mode);
                src.sendSuccess(() -> Component.translatable(mode.key()), false);
                return 1;
            }
        }
        src.sendFailure(Component.literal("hold the Wall Planner first"));
        return 0;
    }

    /**
     * Take back the last piece laid.
     *
     * <p>Growing a wall is done one piece at a time and by eye, so the wrong piece goes down
     * sooner or later - and until now the only way out was {@code /war forget}, which throws away
     * the whole wall. This drops the last piece and cancels whatever the Builder had been told
     * about it, and nothing else.</p>
     */
    private static int undo(final CommandSourceStack src) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final me.lovkar.war.wall.WallPlan plan = me.lovkar.war.wall.WallPlan.of(src.getLevel());
        final me.lovkar.war.wall.WallPlan.Piece last = plan.last(colony.getID());
        if (last == null) {
            src.sendFailure(Component.literal("no wall planned yet"));
            return 0;
        }
        me.lovkar.war.wall.WallOrders.cancel(colony, last.pos());
        plan.forget(colony.getID(), last.pos());
        plan.setDirty();
        me.lovkar.war.Warfare.LOGGER.info("[wall] took back the {} at {} for {}",
                last.kind().folder(), last.pos().toShortString(), colony.getName());
        src.sendSuccess(() -> Component.literal("took back the " + last.kind().folder()
                + " at " + last.pos().getX() + ", " + last.pos().getY() + ", " + last.pos().getZ()),
                false);
        return 1;
    }

    private static int forget(final CommandSourceStack src) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final me.lovkar.war.wall.WallPlan plan = me.lovkar.war.wall.WallPlan.of(src.getLevel());
        final java.util.List<me.lovkar.war.wall.WallPlan.Piece> pieces =
                new java.util.ArrayList<>(plan.all(colony.getID()));
        int cancelled = 0;
        for (final me.lovkar.war.wall.WallPlan.Piece piece : pieces) {
            // forgetting has to take the work order back too, or the Builder spends the week
            // building a wall nobody wants any more
            if (me.lovkar.war.wall.WallOrders.cancel(colony, piece.pos())) {
                cancelled++;
            }
            plan.forget(colony.getID(), piece.pos());
        }
        final int taken = cancelled;
        src.sendSuccess(() -> Component.literal("forgot " + pieces.size() + " piece(s) of wall and "
                + "took back " + taken + " order(s)"), false);
        return 1;
    }

    /** The colony the command was typed in, or null. */
    private static IColony colonyAt(final CommandSourceStack source) {
        return IColonyManager.getInstance().getColonyByPosFromWorld(source.getLevel(),
                BlockPos.containing(source.getPosition()));
    }

    private static int here(final CommandSourceStack src) {
        final IColony colony = colonyAt(src);
        if (colony == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final int walls = WallRegister.score(colony);
        src.sendSuccess(() -> Component.literal(colony.getName() + ": wall "
                + (walls < 0 ? "none of ours - measured from the ground instead" : walls + " / 100")), false);
        return Math.max(0, walls);
    }

    private static int standing(final CommandSourceStack src, final int theirs) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final int value = Standing.between(mine, theirs);
        final Standing.State state = Standing.state(mine, theirs);
        final boolean may = Standing.mayDeclare(mine, theirs);
        src.sendSuccess(() -> Component.literal(mine.getName() + " and colony " + theirs + ": standing " + value
                + ", " + state + (may ? " - war may be declared" : "")), false);
        return value;
    }

    private static int declare(final CommandSourceStack src, final int theirs, final String goal) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        if (!Standing.declare(mine, theirs, goal)) {
            src.sendFailure(Component.literal("not yet: standing is "
                    + Standing.between(mine, theirs) + " and the two are " + Standing.state(mine, theirs)));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(mine.getName() + " is at war with colony " + theirs
                + " - goal: " + goal), true);
        return 1;
    }

    private static int peace(final CommandSourceStack src, final int theirs) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        Standing.peace(mine, theirs, 20);
        src.sendSuccess(() -> Component.literal(mine.getName() + " and colony " + theirs
                + " are at peace - a truce holds for seven days"), true);
        return 1;
    }
}
