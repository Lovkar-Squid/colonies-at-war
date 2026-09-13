package me.lovkar.war.item;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import me.lovkar.war.Warfare;
import me.lovkar.war.wall.WallKind;
import me.lovkar.war.wall.WallLayout;
import me.lovkar.war.wall.WallOrders;
import me.lovkar.war.wall.WallPlan;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The Wall Planner: mark two corners and the colony raises a wall between them.
 *
 * <p>This is the answer to the only real objection to building a curtain wall a piece at a time -
 * that a ring round a town is thirty trips with the build tool. Two clicks lay the whole ring,
 * corners and a gateway included, as ordinary Builder work orders that queue behind whatever else
 * the colony is doing. The build tool still works for anybody who wants to place one piece by
 * hand; this is the same wall, laid faster.</p>
 *
 * <p><b>What the clicks do.</b> Sneak and right-click switches mode (and, in the two marking
 * modes, clears a mark that is already down). The four modes are:</p>
 *
 * <ul>
 *   <li><b>ring</b> - mark two corners, get a closed rectangle with a gateway and stairs.</li>
 *   <li><b>run</b> - mark two points, get the straight wall between them.</li>
 *   <li><b>grow</b> - one click, one piece. The wall extends the way you are <i>looking</i>, so
 *       you walk your own line and lay it as you go. Look along the run to carry on; look right
 *       to turn a corner. This is the answer to "I do not want a rectangle".</li>
 *   <li><b>slot</b> - click a stretch of wall and it becomes a gap; click the gap again and it
 *       goes back to being wall. The Builder is sent to take
 *       down that one piece and nothing else, and a Wall Tower, guard tower or gatehouse goes in
 *       the hole. Works on wall that is already standing, which is the whole point - you do not
 *       have to decide where the towers go before you build.</li>
 * </ul>
 *
 * <p>The marks live on the stack rather than in a map keyed by player, so a planner handed to
 * somebody else hands over the plan with it, and a server restart does not forget where the first
 * corner was.</p>
 */
public class WallPlannerItem extends Item {

    private static final String TAG_MARK = "wall_mark";
    private static final String TAG_RING = "wall_ring";
    private static final String TAG_MODE = "wall_mode";

    /** How far from a planned piece a click still counts as pointing at it. */
    private static final double REACH = 8.0;

    /** What the planner does with a click. */
    public enum Mode {
        RING("ring"), LINE("line"), GROW("grow"), SLOT("slot");

        private final String id;

        Mode(final String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String key() {
            return "com.colonies_at_war.planner.mode." + id;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static Mode byId(final String name) {
            for (final Mode mode : values()) {
                if (mode.id.equals(name)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public WallPlannerItem(final Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public @NotNull InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();
        if (level.isClientSide || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        final ItemStack stack = context.getItemInHand();
        final BlockPos clicked = context.getClickedPos();

        final Mode mode = mode(stack);

        if (player.isShiftKeyDown()) {
            if (mark(stack) != null) {
                setMark(stack, null);
                say(player, Component.translatable("com.colonies_at_war.planner.cleared"));
            } else {
                final Mode now = mode.next();
                setMode(stack, now);
                say(player, Component.translatable(now.key()));
            }
            return InteractionResult.CONSUME;
        }

        if (mode == Mode.GROW) {
            grow(player, (ServerLevel) level, clicked);
            return InteractionResult.CONSUME;
        }
        if (mode == Mode.SLOT) {
            slot(player, (ServerLevel) level, clicked);
            return InteractionResult.CONSUME;
        }

        final BlockPos first = mark(stack);
        if (first == null) {
            setMark(stack, clicked);
            say(player, Component.translatable("com.colonies_at_war.planner.marked",
                    clicked.getX(), clicked.getZ()));
            return InteractionResult.CONSUME;
        }

        setMark(stack, null);
        lay(player, (ServerLevel) level, first, clicked, mode == Mode.RING);
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------ growing it by hand

    /**
     * One click, one piece, laid the way the player is looking.
     *
     * <p>The direction comes from the player rather than from the block he clicked, because a wall
     * is a line and a player walking it already knows which way it goes. Looking along the run
     * carries it on; looking to the right turns a corner; looking any other way is refused with
     * the reason, since the pieces are drawn clockwise and a left corner has no blueprint.</p>
     */
    public static void grow(final ServerPlayer player, final ServerLevel level, final BlockPos clicked) {
        final IColony colony = colonyFor(player, level, clicked);
        if (colony == null) {
            return;
        }
        final WallPlan plan = WallPlan.of(level);
        final WallPlan.Piece last = plan.last(colony.getID());
        final Direction facing = player.getDirection();

        if (last == null) {
            // nothing yet: start here, heading the way he is looking, with the way up built in
            final WallPlan.Piece first = new WallPlan.Piece(clicked, WallKind.STAIR, facing, 1);
            if (order(player, level, colony, plan, first)) {
                say(player, Component.translatable("com.colonies_at_war.planner.started",
                        facing.getName()).withStyle(ChatFormatting.DARK_GREEN));
            }
            return;
        }

        final Direction along = last.along();
        if (facing == along) {
            final WallPlan.Piece next = new WallPlan.Piece(
                    WallLayout.tipAfter(last), WallKind.SEGMENT, along, last.wanted());
            if (order(player, level, colony, plan, next)) {
                say(player, Component.translatable("com.colonies_at_war.planner.grew",
                        next.pos().getX(), next.pos().getZ()).withStyle(ChatFormatting.DARK_GREEN));
            }
            return;
        }
        if (facing == WallLayout.rightOf(along)) {
            final BlockPos corner = WallLayout.cornerAfter(last);
            if (corner == null) {
                say(player, Component.translatable("com.colonies_at_war.planner.turn_needs_a_piece")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            final WallPlan.Piece turn = new WallPlan.Piece(corner, WallKind.CORNER, facing, last.wanted());
            if (order(player, level, colony, plan, turn)) {
                say(player, Component.translatable("com.colonies_at_war.planner.turned",
                        facing.getName()).withStyle(ChatFormatting.DARK_GREEN));
            }
            return;
        }
        say(player, Component.translatable("com.colonies_at_war.planner.no_left_turn",
                along.getName(), WallLayout.rightOf(along).getName()).withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ making room for a tower

    /**
     * Turn a stretch of wall into a gap, and send the Builder to clear it.
     *
     * <p>The piece is not merely forgotten: if something is standing there, the Builder gets a
     * <b>remove</b> order for that one decoration, so he takes down exactly the stretch that is
     * in the way and leaves the rest of the run alone. What goes in the hole is the player's
     * choice - our Wall Tower carries the walk through, a MineColonies guard tower or gatehouse
     * simply stands in it - and either way {@link me.lovkar.war.wall.WallRegister} keeps counting
     * that bearing as walled while the building is there.</p>
     */
    public static void slot(final ServerPlayer player, final ServerLevel level, final BlockPos clicked) {
        final IColony colony = colonyFor(player, level, clicked);
        if (colony == null) {
            return;
        }
        final WallPlan plan = WallPlan.of(level);
        final WallPlan.Piece hit = plan.nearest(colony.getID(), clicked, REACH);

        if (hit == null) {
            // no wall near the click: reserve the next slot instead, so a tower can be planned
            // before the wall reaches it
            final WallPlan.Piece last = plan.last(colony.getID());
            if (last == null) {
                say(player, Component.translatable("com.colonies_at_war.planner.nothing_here")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            final WallPlan.Piece gap = new WallPlan.Piece(
                    WallLayout.tipAfter(last), WallKind.GAP, last.along(), last.wanted());
            plan.add(colony.getID(), gap);
            plan.setDirty();
            say(player, Component.translatable("com.colonies_at_war.planner.slot_reserved",
                    gap.pos().getX(), gap.pos().getZ()).withStyle(ChatFormatting.DARK_GREEN));
            return;
        }
        if (hit.kind() == WallKind.GAP) {
            // the same click closes it again: a slot opened by mistake, or wanted somewhere else
            // after all, must not leave a hole that nothing in the game can mend
            if (me.lovkar.war.wall.WallRegister.gapFilled(colony, hit)) {
                say(player, Component.translatable("com.colonies_at_war.planner.slot_occupied")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            final WallPlan.Piece back =
                    new WallPlan.Piece(hit.pos(), WallKind.SEGMENT, hit.along(), hit.wanted());
            WallOrders.cancel(colony, hit.pos());
            if (!plan.replace(colony.getID(), hit.pos(), back)) {
                plan.add(colony.getID(), back);
            }
            plan.setDirty();
            WallOrders.raise(level, colony, back, WorkOrderType.BUILD);
            Warfare.LOGGER.info("[wall] {} closed the slot at {} for {}",
                    player.getGameProfile().getName(), hit.pos().toShortString(), colony.getName());
            say(player, Component.translatable("com.colonies_at_war.planner.slot_closed",
                    hit.pos().getX(), hit.pos().getZ()).withStyle(ChatFormatting.DARK_GREEN));
            return;
        }
        if (hit.kind() == WallKind.CORNER) {
            say(player, Component.translatable("com.colonies_at_war.planner.slot_not_corner")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        final BlockPos slotAt = WallLayout.slotAnchor(hit);
        // whatever was on its way there is no longer wanted, and whatever is standing comes down
        WallOrders.cancel(colony, hit.pos());
        WallOrders.raise(level, colony, hit, WorkOrderType.REMOVE);

        final WallPlan.Piece gap = new WallPlan.Piece(slotAt, WallKind.GAP, hit.along(), hit.wanted());
        if (!plan.replace(colony.getID(), hit.pos(), gap)) {
            plan.add(colony.getID(), gap);
        }
        plan.setDirty();
        Warfare.LOGGER.info("[wall] {} opened a slot at {} for {} (was {})",
                player.getGameProfile().getName(), slotAt.toShortString(),
                colony.getName(), hit.kind().folder());
        say(player, Component.translatable("com.colonies_at_war.planner.slot_made",
                slotAt.getX(), slotAt.getZ()).withStyle(ChatFormatting.DARK_GREEN));
    }

    /** The colony that owns this spot, or null with the reason already said. */
    private static IColony colonyFor(final ServerPlayer player, final ServerLevel level, final BlockPos at) {
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, at);
        if (colony == null) {
            say(player, Component.translatable("com.colonies_at_war.planner.no_colony")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (!colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
            say(player, Component.translatable("com.colonies_at_war.planner.not_yours")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return colony;
    }

    /** Write one piece into the plan and ask the Builder for it. */
    private static boolean order(final ServerPlayer player, final ServerLevel level,
                                 final IColony colony, final WallPlan plan, final WallPlan.Piece piece) {
        if (!plan.add(colony.getID(), piece)) {
            say(player, Component.translatable("com.colonies_at_war.planner.full", 1)
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        plan.setDirty();
        if (piece.kind().buildable()) {
            WallOrders.raise(level, colony, piece, WorkOrderType.BUILD);
        }
        return true;
    }

    /** Work out the pieces, tell the colony it wants them, and ask the Builder for each one. */
    private static void lay(final ServerPlayer player, final ServerLevel level,
                            final BlockPos a, final BlockPos b, final boolean ring) {
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, a);
        if (colony == null) {
            say(player, Component.translatable("com.colonies_at_war.planner.no_colony")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
            say(player, Component.translatable("com.colonies_at_war.planner.not_yours")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // the gate goes on the side the town hall is on, because that is the side people walk in
        final Direction gateOn = ring ? towards(colony.getCenter(), a, b) : null;
        final List<WallPlan.Piece> pieces = ring ? WallLayout.ring(a, b, gateOn) : WallLayout.line(a, b);

        final WallPlan plan = WallPlan.of(level);
        int laid = 0;
        int full = 0;
        for (final WallPlan.Piece piece : pieces) {
            if (!plan.add(colony.getID(), piece)) {
                full++;
                continue;
            }
            if (WallOrders.raise(level, colony, piece, WorkOrderType.BUILD)) {
                laid++;
            }
        }
        plan.setDirty();

        Warfare.LOGGER.info("[wall] {} laid {} piece(s) of wall for {}",
                player.getGameProfile().getName(), laid, colony.getName());
        say(player, Component.translatable("com.colonies_at_war.planner.laid", laid,
                pieces.size() - laid).withStyle(ChatFormatting.DARK_GREEN));
        if (full > 0) {
            say(player, Component.translatable("com.colonies_at_war.planner.full", full)
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** Which side of the marked rectangle the colony's centre is nearest - that side gets the gate. */
    private static Direction towards(final BlockPos centre, final BlockPos a, final BlockPos b) {
        final int x0 = Math.min(a.getX(), b.getX());
        final int x1 = Math.max(a.getX(), b.getX());
        final int z0 = Math.min(a.getZ(), b.getZ());
        final int z1 = Math.max(a.getZ(), b.getZ());
        final int west = Math.abs(centre.getX() - x0);
        final int east = Math.abs(centre.getX() - x1);
        final int north = Math.abs(centre.getZ() - z0);
        final int south = Math.abs(centre.getZ() - z1);
        final int best = Math.min(Math.min(west, east), Math.min(north, south));
        if (best == north) {
            return Direction.EAST;                 // the north side is the one running east
        }
        if (best == east) {
            return Direction.SOUTH;
        }
        if (best == south) {
            return Direction.WEST;
        }
        return Direction.NORTH;
    }

    // ------------------------------------------------------------------ what the stack remembers

    private static CompoundTag data(final ItemStack stack) {
        final CustomData held = stack.get(DataComponents.CUSTOM_DATA);
        return held == null ? new CompoundTag() : held.copyTag();
    }

    public static BlockPos mark(final ItemStack stack) {
        final CompoundTag tag = data(stack);
        return tag.contains(TAG_MARK) ? BlockPos.of(tag.getLong(TAG_MARK)) : null;
    }

    private static void setMark(final ItemStack stack, final BlockPos pos) {
        final CompoundTag tag = data(stack);
        if (pos == null) {
            tag.remove(TAG_MARK);
        } else {
            tag.putLong(TAG_MARK, pos.asLong());
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * What this planner does with a click.
     *
     * <p>Falls back to the old ring/run boolean for a planner made before there were four modes,
     * so a tool already in somebody's chest keeps doing what it did.</p>
     */
    public static Mode mode(final ItemStack stack) {
        final CompoundTag tag = data(stack);
        if (tag.contains(TAG_MODE)) {
            final Mode named = Mode.byId(tag.getString(TAG_MODE));
            if (named != null) {
                return named;
            }
        }
        return !tag.contains(TAG_RING) || tag.getBoolean(TAG_RING) ? Mode.RING : Mode.LINE;
    }

    public static void setMode(final ItemStack stack, final Mode mode) {
        final CompoundTag tag = data(stack);
        tag.putString(TAG_MODE, mode.id());
        tag.remove(TAG_RING);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void say(final ServerPlayer player, final Component what) {
        player.displayClientMessage(what, false);
    }

    @Override
    public void appendHoverText(final @NotNull ItemStack stack, final TooltipContext context,
                                final @NotNull List<Component> lines, final @NotNull TooltipFlag flag) {
        lines.add(Component.translatable("com.colonies_at_war.planner.desc").withStyle(ChatFormatting.GRAY));
        final Mode mode = mode(stack);
        lines.add(Component.translatable(mode.key()).withStyle(ChatFormatting.DARK_AQUA));
        lines.add(Component.translatable("com.colonies_at_war.planner.help." + mode.id())
                .withStyle(ChatFormatting.GRAY));
        final BlockPos mark = mark(stack);
        if (mark != null && (mode == Mode.RING || mode == Mode.LINE)) {
            lines.add(Component.translatable("com.colonies_at_war.planner.marked",
                    mark.getX(), mark.getZ()).withStyle(ChatFormatting.DARK_GREEN));
        }
    }
}
