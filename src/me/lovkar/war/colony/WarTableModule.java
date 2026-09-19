package me.lovkar.war.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import me.lovkar.war.WarConfig;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.campaign.WarLog;
import me.lovkar.war.campaign.WarTable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The war, as the War Room's table shows it: who could be marched on, who is out, who is held.
 *
 * <p>Keeps nothing of its own - the campaigns live in the colony's clock and the targets in the
 * world - and hands the client only what it may point at. What the client then asks for comes
 * back through {@code WarTableMessage} and is re-resolved and re-checked on the server.</p>
 */
public class WarTableModule extends AbstractBuildingModule implements IPersistentModule {

    private static final int LOG_LINES = 6;

    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        final IColony colony = building == null ? null : building.getColony();
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeUtf("");
            buf.writeBoolean(false);
            buf.writeVarInt(0);         // held
            buf.writeVarInt(0);         // ransom gold
            buf.writeVarInt(0);         // sellswords
            buf.writeVarInt(0);         // a sellsword's price
            buf.writeBoolean(false);    // can hire
            buf.writeVarInt(0);         // sellsword cap
            buf.writeVarInt(0);         // targets
            buf.writeVarInt(0);         // log lines
            return;
        }
        buf.writeVarInt(Campaigns.weariness(level, colony.getID()));
        buf.writeVarInt(Campaigns.TOO_WEARY);
        buf.writeVarInt(Campaigns.guardsAtHome(colony));
        final Campaigns.Campaign out = Campaigns.outFrom(level, colony.getID());
        buf.writeUtf(out == null ? "" : out.describe());
        buf.writeBoolean(out != null && out.phase == Campaigns.Phase.MARCHING);
        buf.writeVarInt(Campaigns.captivesOf(level, colony.getID()).size());
        buf.writeVarInt(WarConfig.ransomGold());
        final int swords = Campaigns.sellswordsWaiting(level, colony.getID());
        buf.writeVarInt(swords);
        buf.writeVarInt(WarConfig.mercenaryGold());
        buf.writeBoolean(Campaigns.cannotHire(level, colony, 1) == null);
        buf.writeVarInt(Campaigns.sellswordCap(colony));
        List<WarTable.Target> targets;
        try {
            targets = WarTable.targets(level, colony);
        } catch (final Throwable t) {
            targets = List.of();
        }
        buf.writeVarInt(targets.size());
        for (final WarTable.Target target : targets) {
            buf.writeUtf(target.kind());
            buf.writeVarInt(target.id());
            buf.writeBlockPos(target.pos());
            buf.writeUtf(target.name());
            buf.writeVarInt(target.distance());
            buf.writeUtf(target.direction());
            buf.writeVarInt(target.strength());
            buf.writeInt(target.standing());
            buf.writeBoolean(target.atWar());
            buf.writeUtf(target.reason());
            buf.writeUtf(target.state());
            buf.writeUtf(target.offer());
            buf.writeBoolean(target.ours());
            buf.writeUtf(target.convoy());
            buf.writeUtf(target.bond());
        }
        final List<WarLog.Entry> lines = Campaigns.log(level).about(colony.getID(), LOG_LINES);
        buf.writeVarInt(lines.size());
        for (final WarLog.Entry entry : lines) {
            buf.writeUtf(entry.line());
        }
    }
}
