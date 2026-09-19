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
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.world.item.ItemStack;

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
                                .executes(ctx -> offer(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "colony"), "white", 0))))
                .then(Commands.literal("offer")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> offer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"), "white", 0))
                                .then(Commands.literal("white")
                                        .executes(ctx -> offer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"), "white", 0)))
                                .then(Commands.literal("vassal")
                                        .executes(ctx -> offer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"), "vassal", 0)))
                                .then(Commands.literal("tribute")
                                        .then(Commands.argument("gold", IntegerArgumentType.integer(1, 4096))
                                                .executes(ctx -> offer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"),
                                                        "tribute", IntegerArgumentType.getInteger(ctx, "gold")))))
                                .then(Commands.literal("pay")
                                        .then(Commands.argument("gold", IntegerArgumentType.integer(1, 4096))
                                                .executes(ctx -> offer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"),
                                                        "pay", IntegerArgumentType.getInteger(ctx, "gold")))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> answer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"), true))))
                .then(Commands.literal("refuse")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> answer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"), false))))
                .then(Commands.literal("ally")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> ally(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony")))))
                .then(Commands.literal("break")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> breakAlliance(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony")))))
                .then(Commands.literal("release")
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> release(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony")))))
                .then(Commands.literal("load")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(ctx -> load(ctx.getSource(), ItemArgument.getItem(ctx, "item"), 0))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                        .executes(ctx -> load(ctx.getSource(), ItemArgument.getItem(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("unload")
                        .executes(ctx -> unload(ctx.getSource(), null, 0))
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(ctx -> unload(ctx.getSource(), ItemArgument.getItem(ctx, "item"), 0))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                        .executes(ctx -> unload(ctx.getSource(), ItemArgument.getItem(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("hire")
                        .executes(ctx -> hire(ctx.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> hire(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("convoy")
                        .then(Commands.literal("at")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> convoyAt(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z"), 0))
                                                .then(Commands.argument("guards", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> convoyAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                IntegerArgumentType.getInteger(ctx, "guards")))))))
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> convoy(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"), 0))
                                .then(Commands.argument("guards", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> convoy(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "colony"),
                                                IntegerArgumentType.getInteger(ctx, "guards"))))))
                .then(Commands.literal("march")
                        .then(Commands.literal("at")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> marchAt(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z"), 0))
                                                .then(Commands.argument("guards", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> marchAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                IntegerArgumentType.getInteger(ctx, "guards")))))))
                        .then(Commands.argument("colony", IntegerArgumentType.integer())
                                .executes(ctx -> march(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "colony"), 0))
                                .then(Commands.argument("guards", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> march(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "colony"),
                                                IntegerArgumentType.getInteger(ctx, "guards"))))))
                .then(Commands.literal("targets").executes(ctx -> targets(ctx.getSource())))
                .then(Commands.literal("explore")
                        .then(Commands.argument("biome", StringArgumentType.string())
                                .executes(ctx -> explore(ctx.getSource(), StringArgumentType.getString(ctx, "biome"), "normal", 0))
                                .then(Commands.argument("difficulty", StringArgumentType.word())
                                        .executes(ctx -> explore(ctx.getSource(), StringArgumentType.getString(ctx, "biome"),
                                                StringArgumentType.getString(ctx, "difficulty"), 0))
                                        .then(Commands.argument("guards", IntegerArgumentType.integer(0, 64))
                                                .executes(ctx -> explore(ctx.getSource(), StringArgumentType.getString(ctx, "biome"),
                                                        StringArgumentType.getString(ctx, "difficulty"),
                                                        IntegerArgumentType.getInteger(ctx, "guards")))))))
                .then(Commands.literal("biomes").executes(ctx -> biomes(ctx.getSource())))
                .then(Commands.literal("ransom").executes(ctx -> ransom(ctx.getSource())))
                .then(Commands.literal("recall").executes(ctx -> recall(ctx.getSource())))
                .then(Commands.literal("campaigns").executes(ctx -> campaigns(ctx.getSource())))
                .then(Commands.literal("log").executes(ctx -> log(ctx.getSource(), 12))
                        .then(Commands.argument("lines", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> log(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "lines")))))
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
        final String want = goal == null ? "" : goal.trim().toLowerCase(java.util.Locale.ROOT);
        if (!want.isEmpty() && !"plunder".equals(want) && !"raze".equals(want) && !"conquer".equals(want)) {
            src.sendFailure(Component.literal("the goal is plunder, raze or conquer"));
            return 0;
        }
        if ("raze".equals(want) && WarConfig.depth() < 1) {
            src.sendFailure(Component.literal("raze is a siege-depth goal and this world is at skirmish depth (warLevel)"));
            return 0;
        }
        if ("conquer".equals(want) && WarConfig.depth() < 2) {
            src.sendFailure(Component.literal("conquer is a conquest-depth goal and this world is at " + WarConfig.warLevel() + " depth (warLevel)"));
            return 0;
        }
        if (!Standing.declare(mine, theirs, want)) {
            src.sendFailure(Component.literal("not yet: standing is "
                    + Standing.between(mine, theirs) + " and the two are " + Standing.state(mine, theirs)));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(mine.getName() + " is at war with colony " + theirs
                + " - goal: " + goal), true);
        return 1;
    }

    /**
     * {@code /war offer <colony> [white|tribute <gold>|pay <gold>|vassal]} - peace is a negotiation:
     * one side puts terms on the table, the other accepts or refuses them. {@code /war peace} is
     * the same thing with no terms at all.
     */
    private static int offer(final CommandSourceStack src, final int theirs, final String terms, final int gold) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final String no = me.lovkar.war.campaign.Terms.offerPeace(src.getLevel(), mine, theirs, terms, gold);
        if (no != null) {
            src.sendFailure(Component.literal(no));
            return 0;
        }
        final Standing.Offer offer = Standing.offerBetween(mine, theirs);
        src.sendSuccess(() -> Component.literal(mine.getName() + " offers colony " + theirs + " peace: "
                + (offer == null ? terms : offer.describe(true)) + " - they have " + WarConfig.offerDays() + " days to answer"), true);
        return 1;
    }

    /** {@code /war accept|refuse <colony>} - the other side's offer, answered. */
    private static int answer(final CommandSourceStack src, final int theirs, final boolean yes) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final String no = yes ? me.lovkar.war.campaign.Terms.accept(src.getLevel(), mine, theirs)
                : me.lovkar.war.campaign.Terms.refuse(src.getLevel(), mine, theirs);
        if (no != null) {
            src.sendFailure(Component.literal(no));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(yes ? "accepted - " + mine.getName() + " and colony " + theirs + " are now "
                + Standing.state(mine, theirs).name().toLowerCase(java.util.Locale.ROOT) : "refused"), true);
        return 1;
    }

    /** {@code /war ally <colony>} - an alliance, offered. Standing has to have earned it. */
    private static int ally(final CommandSourceStack src, final int theirs) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final String no = me.lovkar.war.campaign.Terms.offerAlliance(src.getLevel(), mine, theirs);
        if (no != null) {
            src.sendFailure(Component.literal(no));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(mine.getName() + " offers colony " + theirs + " an alliance - they have "
                + WarConfig.offerDays() + " days to answer"), true);
        return 1;
    }

    private static int breakAlliance(final CommandSourceStack src, final int theirs) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final String no = me.lovkar.war.campaign.Terms.breakAlliance(src.getLevel(), mine, theirs);
        if (no != null) {
            src.sendFailure(Component.literal(no));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("the alliance with colony " + theirs + " is broken - standing is back at 0"), true);
        return 1;
    }

    /** {@code /war release <colony>} - a lord lets its vassal go before its time. */
    private static int release(final CommandSourceStack src, final int theirs) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        if (!me.lovkar.war.campaign.Campaigns.release(src.getLevel(), mine, theirs)) {
            src.sendFailure(Component.literal("colony " + theirs + " is not your vassal"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("colony " + theirs + " is released from its vassalage"), true);
        return 1;
    }

    /** {@code /war hire [count]} - sellswords, in gold, who wait at the War Room for the next warband. */
    private static int hire(final CommandSourceStack src, final int count) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final String no = me.lovkar.war.campaign.Campaigns.hire(src.getLevel(), mine, count);
        if (no != null) {
            src.sendFailure(Component.literal(no));
            return 0;
        }
        final int waiting = me.lovkar.war.campaign.Campaigns.sellswordsWaiting(src.getLevel(), mine.getID());
        src.sendSuccess(() -> Component.literal(count + (count == 1 ? " sellsword hired - " : " sellswords hired - ") + waiting
                + " waiting at the War Room for " + WarConfig.mercenaryDays() + " days"), true);
        return 1;
    }

    /** {@code /war convoy <colony> [guards]} - goods to another colony, with an escort, for standing. */
    private static int convoy(final CommandSourceStack src, final int theirs, final int guards) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final StringBuilder why = new StringBuilder();
        final me.lovkar.war.campaign.Campaigns.Campaign c = me.lovkar.war.campaign.Campaigns.convoy(src.getLevel(), mine, theirs, guards, why);
        if (c == null) {
            src.sendFailure(Component.literal("the convoy stays: " + why));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(c.describe()), true);
        return 1;
    }

    /** {@code /war load <item> [count]} - a stack of it, or the count, on the next convoy's manifest. */
    private static int load(final CommandSourceStack src, final ItemInput item, final int count) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final me.lovkar.war.colony.ConvoyModule bay = me.lovkar.war.colony.ConvoyModule.of(me.lovkar.war.campaign.Campaigns.warRoom(mine));
        if (bay == null) {
            src.sendFailure(Component.literal("no War Room standing - nowhere to load a convoy"));
            return 0;
        }
        final ItemStack kind = new ItemStack(item.getItem());
        final int on = bay.add(kind, count <= 0 ? Math.max(1, kind.getMaxStackSize()) : count);
        if (on <= 0) {
            src.sendFailure(Component.literal("the manifest is full: " + me.lovkar.war.colony.ConvoyModule.capStacks() + " stacks at most"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(on + " " + kind.getHoverName().getString() + " put on the manifest - "
                + bay.wantedStacks() + " of " + me.lovkar.war.colony.ConvoyModule.capStacks()
                + " stacks; the couriers bring it to the War Room, the war table sends it"), true);
        return 1;
    }

    /** {@code /war unload [item] [count]} - off the manifest: the item, the count, or all of it. */
    private static int unload(final CommandSourceStack src, final ItemInput item, final int count) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final me.lovkar.war.colony.ConvoyModule bay = me.lovkar.war.colony.ConvoyModule.of(me.lovkar.war.campaign.Campaigns.warRoom(mine));
        if (bay == null) {
            src.sendFailure(Component.literal("no War Room standing"));
            return 0;
        }
        if (item == null) {
            bay.clear();
            src.sendSuccess(() -> Component.literal("the manifest is torn up; whatever came stays in the War Room"), true);
            return 1;
        }
        final ItemStack kind = new ItemStack(item.getItem());
        final int off = -bay.add(kind, count <= 0 ? -4096 : -count);
        if (off <= 0) {
            src.sendFailure(Component.literal("no " + kind.getHoverName().getString() + " on the manifest"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(off + " " + kind.getHoverName().getString() + " taken off the manifest - "
                + bay.wantedStacks() + " of " + me.lovkar.war.colony.ConvoyModule.capStacks() + " stacks"), true);
        return 1;
    }

    /** {@code /war convoy at <x> <z> [guards]} - goods to a Waking World kingdom: a gift, and a debt paid. */
    private static int convoyAt(final CommandSourceStack src, final int x, final int z, final int guards) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final BlockPos at = new BlockPos(x, 64, z);
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(src.getLevel(), at);
        if (colony != null) {
            return convoy(src, colony.getID(), guards);
        }
        final Object kingdom = me.lovkar.war.compat.Kingdoms.at(src.getLevel(), at);
        if (kingdom == null) {
            src.sendFailure(Component.literal("nothing to send a convoy to at " + x + ", " + z + " - no colony"
                    + (me.lovkar.war.compat.Kingdoms.present() ? ", no kingdom" : "")));
            return 0;
        }
        final StringBuilder why = new StringBuilder();
        final me.lovkar.war.campaign.Campaigns.Campaign c = me.lovkar.war.campaign.Campaigns.convoyTo(src.getLevel(), mine, kingdom, guards, why);
        if (c == null) {
            src.sendFailure(Component.literal("the convoy stays: " + why));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(c.describe()), true);
        return 1;
    }

    // ------------------------------------------------------------------ the warband

    /**
     * {@code /war march <colony> [guards]} - the War Room's guards go.
     *
     * <p>Like {@code declare}, a thing a person does. The war has to exist first, by hand; this
     * only decides that the men leave now, and how many.</p>
     */
    private static int march(final CommandSourceStack src, final int theirs, final int guards) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final StringBuilder why = new StringBuilder();
        final me.lovkar.war.campaign.Campaigns.Campaign c =
                me.lovkar.war.campaign.Campaigns.march(src.getLevel(), mine, theirs, guards, why);
        if (c == null) {
            src.sendFailure(Component.literal("the men stay: " + why));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(c.describe() + " - strength " + c.attack
                + ", there in about " + Math.max(1, Math.round((c.arrive - c.departed) / 1200f)) + " min"), true);
        return c.guards.length;
    }

    /**
     * {@code /war march at <x> <z> [guards]} - march on whatever stands there: another colony, or
     * a Waking World kingdom. The colony path is the same as {@code /war march <colony>}, war
     * declared and all; a kingdom needs no declaration, it is nobody's.
     */
    private static int marchAt(final CommandSourceStack src, final int x, final int z, final int guards) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final BlockPos at = new BlockPos(x, 64, z);
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(src.getLevel(), at);
        if (colony != null) {
            return march(src, colony.getID(), guards);
        }
        final Object kingdom = me.lovkar.war.compat.Kingdoms.at(src.getLevel(), at);
        final StringBuilder why = new StringBuilder();
        final me.lovkar.war.campaign.Campaigns.Campaign c;
        final int against;
        if (kingdom != null) {
            c = me.lovkar.war.campaign.Campaigns.marchOn(src.getLevel(), mine, kingdom, guards, why);
            against = me.lovkar.war.compat.Kingdoms.defence(kingdom);
        } else {
            final me.lovkar.war.campaign.Places.Place place = me.lovkar.war.campaign.Places.at(src.getLevel(), at);
            if (place == null) {
                src.sendFailure(Component.literal("nothing to march on at " + x + ", " + z + " - no colony, no camp, no village"
                        + (me.lovkar.war.compat.Kingdoms.present() ? ", no kingdom" : "")));
                return 0;
            }
            c = me.lovkar.war.campaign.Campaigns.marchOnPlace(src.getLevel(), mine, place, guards, why);
            against = me.lovkar.war.campaign.Places.defence(src.getLevel(), place);
        }
        if (c == null) {
            src.sendFailure(Component.literal("the men stay: " + why));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(c.describe() + " - strength " + c.attack + " against " + against
                + ", there in about " + Math.max(1, Math.round((c.arrive - c.departed) / 1200f)) + " min"), true);
        return c.guards.length;
    }

    /** {@code /war targets} - everything a warband could march on from here, with distance and strength. */
    private static int explore(final CommandSourceStack src, final String biome, final String difficulty, final int guards) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final int d = me.lovkar.war.campaign.Expeditions.difficulty(difficulty);
        if (d == 0) {
            src.sendFailure(Component.literal("difficulty is easy, normal, hard or deadly"));
            return 0;
        }
        final StringBuilder why = new StringBuilder();
        final me.lovkar.war.campaign.Campaigns.Campaign c =
                me.lovkar.war.campaign.Campaigns.explore(src.getLevel(), mine, biome, d, guards, why);
        if (c == null) {
            src.sendFailure(Component.literal(why.toString()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(c.describe()), false);
        return 1;
    }

    private static int biomes(final CommandSourceStack src) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final java.util.List<me.lovkar.war.campaign.Expeditions.Destination> found =
                me.lovkar.war.campaign.Expeditions.survey(src.getLevel(), mine.getCenter());
        if (found.isEmpty()) {
            src.sendSuccess(() -> Component.literal("nothing within " + WarConfig.expeditionRange() + " blocks - not even the town's own country"), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal("within " + WarConfig.expeditionRange() + " blocks of " + mine.getName()
                + " (/war explore <name> [easy|normal|hard|deadly] [guards]):"), false);
        for (final me.lovkar.war.campaign.Expeditions.Destination d : found) {
            src.sendSuccess(() -> Component.literal("  " + d.family().id() + " - " + d.describe()), false);
        }
        final String no = me.lovkar.war.campaign.Campaigns.cannotExplore(src.getLevel(), mine);
        if (no != null) {
            src.sendSuccess(() -> Component.literal("(" + no + ")"), false);
        }
        return 1;
    }

    private static int targets(final CommandSourceStack src) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        int shown = 0;
        final BlockPos home = mine.getCenter();
        for (final IColony colony : IColonyManager.getInstance().getColonies(src.getLevel())) {
            if (colony.getID() == mine.getID()) {
                continue;
            }
            final int distance = (int) Math.sqrt(colony.getCenter().distSqr(home));
            final String state = Standing.state(mine, colony.getID()).name().toLowerCase(java.util.Locale.ROOT);
            src.sendSuccess(() -> Component.literal("colony " + colony.getID() + " " + colony.getName() + " - " + distance
                    + " blocks " + colony.getCenter().toShortString() + ", standing " + Standing.between(mine, colony.getID())
                    + " " + state + ", strength " + me.lovkar.war.campaign.Battle.defence(colony)), false);
            shown++;
        }
        for (final Object kingdom : me.lovkar.war.compat.Kingdoms.all(src.getLevel())) {
            final BlockPos centre = me.lovkar.war.compat.Kingdoms.centre(kingdom);
            final int distance = (int) Math.sqrt(centre.distSqr(home));
            src.sendSuccess(() -> Component.literal("kingdom " + me.lovkar.war.compat.Kingdoms.name(kingdom) + " - " + distance
                    + " blocks " + centre.toShortString() + ", tier " + me.lovkar.war.compat.Kingdoms.tier(kingdom)
                    + ", standing " + me.lovkar.war.compat.Kingdoms.standing(kingdom)
                    + ", strength " + me.lovkar.war.compat.Kingdoms.defence(kingdom)), false);
            shown++;
        }
        for (final String kind : new String[] {me.lovkar.war.campaign.Places.CAMP, me.lovkar.war.campaign.Places.VILLAGE}) {
            final me.lovkar.war.campaign.Places.Place place = me.lovkar.war.campaign.Places.nearest(src.getLevel(), home, kind);
            if (place == null) {
                continue;
            }
            final int distance = (int) Math.sqrt(place.pos().distSqr(home));
            src.sendSuccess(() -> Component.literal("nearest " + kind + ": " + place.title() + " - " + distance + " blocks, strength "
                    + me.lovkar.war.campaign.Places.defence(src.getLevel(), place)), false);
            shown++;
        }
        if (shown == 0) {
            src.sendSuccess(() -> Component.literal("nothing to march on in this world"), false);
        }
        return shown;
    }

    /** {@code /war ransom} - buy every held man back, in gold out of the warehouse. */
    private static int ransom(final CommandSourceStack src) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final String no = me.lovkar.war.campaign.Campaigns.ransom(src.getLevel(), mine);
        if (no != null) {
            src.sendFailure(Component.literal(no));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("the ransom is paid and the men are home"), true);
        return 1;
    }

    private static int recall(final CommandSourceStack src) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final StringBuilder why = new StringBuilder();
        if (!me.lovkar.war.campaign.Campaigns.recall(src.getLevel(), mine, why)) {
            src.sendFailure(Component.literal(why.toString()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("the warband turns for home"), true);
        return 1;
    }

    private static int campaigns(final CommandSourceStack src) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final java.util.List<me.lovkar.war.campaign.Campaigns.Campaign> list =
                me.lovkar.war.campaign.Campaigns.involving(src.getLevel(), mine.getID());
        final int weary = me.lovkar.war.campaign.Campaigns.weariness(src.getLevel(), mine.getID());
        src.sendSuccess(() -> Component.literal(mine.getName() + ": weariness " + weary + " / "
                + me.lovkar.war.campaign.Campaigns.TOO_WEARY + (list.isEmpty() ? ", no warband out or in" : "")), false);
        final long now = src.getLevel().getServer().overworld().getGameTime();
        for (final me.lovkar.war.campaign.Campaigns.Campaign c : list) {
            final long due = switch (c.phase) {
                case MARCHING -> c.arrive;
                case BESIEGING -> c.siegeUntil;
                case RETURNING -> c.home;
                case DONE -> 0;
            };
            final String when = due <= 0 ? "" : " (" + Math.max(0, Math.round((due - now) / 1200f)) + " min)";
            src.sendSuccess(() -> Component.literal("  " + c.describe() + when), false);
        }
        final java.util.List<me.lovkar.war.campaign.Campaigns.Reprisal> debts =
                me.lovkar.war.campaign.Campaigns.reprisalsFor(src.getLevel(), mine.getID());
        for (final me.lovkar.war.campaign.Campaigns.Reprisal r : debts) {
            final String when = r.eventId != 0 ? "" : r.due > now ? " (in " + Math.max(1, Math.round((r.due - now) / 1200f)) + " min)"
                    : " (any time somebody is home)";
            src.sendSuccess(() -> Component.literal("  " + r.describe() + when), false);
        }
        final java.util.List<me.lovkar.war.campaign.Campaigns.Captive> held =
                me.lovkar.war.campaign.Campaigns.captivesOf(src.getLevel(), mine.getID());
        for (final me.lovkar.war.campaign.Campaigns.Captive k : held) {
            src.sendSuccess(() -> Component.literal("  " + k.describe() + " - " + me.lovkar.war.WarConfig.ransomGold()
                    + " gold (/war ransom), or let go in " + Math.max(1, Math.round((k.until - now) / 1200f)) + " min"), false);
        }
        final int swords = me.lovkar.war.campaign.Campaigns.sellswordsWaiting(src.getLevel(), mine.getID());
        if (swords > 0) {
            src.sendSuccess(() -> Component.literal("  " + swords + (swords == 1 ? " sellsword waits" : " sellswords wait")
                    + " at the War Room for the next warband"), false);
        }
        for (final me.lovkar.war.campaign.Campaigns.Vassalage v : me.lovkar.war.campaign.Campaigns.vassalagesOf(src.getLevel(), mine.getID())) {
            src.sendSuccess(() -> Component.literal("  " + v.describe(now)), false);
        }
        for (final IColony other : IColonyManager.getInstance().getColonies(src.getLevel())) {
            if (other.getID() == mine.getID()) {
                continue;
            }
            final Standing.Offer offer = Standing.offerBetween(mine, other.getID());
            if (offer != null) {
                final boolean ours = offer.from() == mine.getID();
                src.sendSuccess(() -> Component.literal("  " + (ours ? "offered " + other.getName() + " " : other.getName() + " offers ")
                        + offer.describe(ours) + (ours ? " - waiting for their answer" : " - /war accept " + other.getID() + " or /war refuse " + other.getID())
                        + " (" + Math.max(1, Math.round((offer.until() - now) / 1200f)) + " min)"), false);
            }
        }
        return list.size() + debts.size() + held.size();
    }

    private static int log(final CommandSourceStack src, final int lines) {
        final IColony mine = colonyAt(src);
        if (mine == null) {
            src.sendFailure(Component.literal("stand in a colony"));
            return 0;
        }
        final java.util.List<me.lovkar.war.campaign.WarLog.Entry> entries =
                me.lovkar.war.campaign.Campaigns.log(src.getLevel()).about(mine.getID(), lines);
        if (entries.isEmpty()) {
            src.sendSuccess(() -> Component.literal(mine.getName() + " has no wars in its history"), false);
            return 0;
        }
        for (final me.lovkar.war.campaign.WarLog.Entry e : entries) {
            src.sendSuccess(() -> Component.literal(e.line()), false);
        }
        return entries.size();
    }
}
