package me.lovkar.war.network;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.war.Warfare;
import me.lovkar.war.wall.WallOrders;
import me.lovkar.war.wall.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * What the War Room's wall panel may ask for: raise it, mend it, or take a piece off the plan.
 *
 * <p>One packet for all of it, because they are the same decision with a different verb, and
 * because every one of them is re-checked here anyway - who is asking, whether they may manage the
 * colony's huts, and whether the piece is one the colony actually planned. A client that invents a
 * position gets nothing.</p>
 */
public record WallActionMessage(BlockPos building, String what, BlockPos piece, int level)
        implements CustomPacketPayload {

    /** Raise every piece to {@code level}. */
    public static final String UPGRADE_ALL = "upgrade_all";
    /** Rebuild whatever is broken or missing, at the level it is already meant to be. */
    public static final String REPAIR_ALL = "repair_all";
    /** Raise one piece. */
    public static final String UPGRADE_ONE = "upgrade_one";
    /** Stop calling one piece part of the wall. The blocks stay where they are. */
    public static final String FORGET_ONE = "forget_one";

    public static final CustomPacketPayload.Type<WallActionMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "wall_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallActionMessage> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> {
                buf.writeBlockPos(msg.building());
                buf.writeUtf(msg.what());
                buf.writeBlockPos(msg.piece());
                buf.writeVarInt(msg.level());
            }, buf -> new WallActionMessage(buf.readBlockPos(), buf.readUtf(),
                    buf.readBlockPos(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.@NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final WallActionMessage message, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final IColony colony = IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, message.building());
            if (colony == null || !colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
                return;
            }
            final WallPlan plan = WallPlan.of(level);
            final int id = colony.getID();
            final int want = Math.max(1, Math.min(5, message.level()));

            switch (message.what()) {
                case UPGRADE_ALL -> {
                    plan.wantAll(id, want);
                    final int asked = order(level, colony, plan.all(id), want);
                    Warfare.LOGGER.info("[wall] {} raised the whole wall of {} to level {} ({} order(s))",
                            player.getGameProfile().getName(), colony.getName(), want, asked);
                    MessageUtils.format(Component.translatable(
                            "com.colonies_at_war.gui.walls.ordered", asked, want)).sendTo(player);
                }
                case REPAIR_ALL -> {
                    int asked = 0;
                    for (final WallPlan.Piece piece : plan.all(id)) {
                        if (WallOrders.raise(level, colony, piece,
                                WallOrders.builtLevel(level, piece.pos()) > 0
                                        ? WorkOrderType.REPAIR : WorkOrderType.BUILD)) {
                            asked++;
                        }
                    }
                    MessageUtils.format(Component.translatable(
                            "com.colonies_at_war.gui.walls.mending", asked)).sendTo(player);
                }
                case UPGRADE_ONE -> {
                    final WallPlan.Piece piece = plan.at(id, message.piece());
                    if (piece == null) {
                        return;
                    }
                    plan.want(id, piece.pos(), want);
                    order(level, colony, List.of(piece.at(want)), want);
                }
                case FORGET_ONE -> {
                    WallOrders.cancel(colony, message.piece());
                    plan.forget(id, message.piece());
                }
                default -> {
                    // a verb this version does not know: do nothing rather than guess
                }
            }
        });
    }

    /** Ask for every piece that is not already at the level it should be. */
    private static int order(final ServerLevel level, final IColony colony,
                             final List<WallPlan.Piece> pieces, final int want) {
        int asked = 0;
        for (final WallPlan.Piece piece : new ArrayList<>(pieces)) {
            final int built = WallOrders.builtLevel(level, piece.pos());
            if (built == want) {
                continue;                          // already what it should be
            }
            final WorkOrderType type = built > 0 ? WorkOrderType.UPGRADE : WorkOrderType.BUILD;
            if (WallOrders.raise(level, colony, piece.at(want), type)) {
                asked++;
            }
        }
        return asked;
    }
}
