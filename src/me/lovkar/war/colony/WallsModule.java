package me.lovkar.war.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import me.lovkar.war.wall.WallOrders;
import me.lovkar.war.wall.WallPlan;
import me.lovkar.war.wall.WallRegister;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The wall, as the War Room sees it: every piece the colony has ordered, what is standing, and the
 * one number that says whether any of it is working.
 *
 * <p>It keeps nothing of its own. The pieces live in {@link WallPlan} because they belong to the
 * colony rather than to this building - a colony can lose its War Room and still have a wall - and
 * what is actually built is read off the decoration controllers in the world, because the plan
 * knows what was asked for and only the world knows what the Builder has got round to.</p>
 */
public class WallsModule extends AbstractBuildingModule implements IPersistentModule {

    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        final IColony colony = building == null ? null : building.getColony();
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            buf.writeVarInt(0);
            buf.writeVarInt(-1);
            return;
        }
        final List<WallPlan.Piece> pieces = WallPlan.of(level).all(colony.getID());
        buf.writeVarInt(pieces.size());
        for (final WallPlan.Piece piece : pieces) {
            buf.writeUtf(piece.kind().folder());
            buf.writeBlockPos(piece.pos());
            buf.writeVarInt(piece.wanted());
            // a gap has nothing to build, so the "built" int carries whether a tower is standing
            // in it instead - same packet, and the window knows which it is from the kind
            buf.writeInt(piece.kind() == me.lovkar.war.wall.WallKind.GAP
                    ? (WallRegister.gapFilled(colony, piece) ? 1 : 0)
                    : WallOrders.builtLevel(level, piece.pos()));
            buf.writeBoolean(piece.kind() != me.lovkar.war.wall.WallKind.GAP
                    && WallOrders.pending(colony, piece.pos()));
        }
        buf.writeVarInt(WallRegister.score(colony));
    }
}
