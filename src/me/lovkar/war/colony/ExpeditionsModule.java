package me.lovkar.war.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.entity.citizen.Skill;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.campaign.Expeditions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The country round the colony, as the table shows it: who the Explorer is, whether he is out,
 * and every kind of place he could be sent.
 */
public class ExpeditionsModule extends AbstractBuildingModule implements IPersistentModule {

    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        final IColony colony = building == null ? null : building.getColony();
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            buf.writeBoolean(false);
            buf.writeUtf("");
            buf.writeVarInt(0);
            buf.writeBoolean(false);
            buf.writeUtf("");
            buf.writeBoolean(false);
            buf.writeUtf("");
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            return;
        }
        final ICitizenData explorer = BuildingWarRoom.explorer(building);
        buf.writeBoolean(explorer != null);
        buf.writeUtf(explorer == null ? "" : explorer.getName());
        int skill = 0;
        if (explorer != null) {
            try {
                skill = explorer.getCitizenSkillHandler().getLevel(Skill.Adaptability);
            } catch (final Throwable ignored) {
                // a recruit
            }
        }
        buf.writeVarInt(skill);
        buf.writeBoolean(explorer != null && Standing.onCampaign(explorer));
        final Campaigns.Campaign out = Campaigns.expeditionOut(level, colony.getID());
        buf.writeUtf(out == null ? "" : out.describe());
        buf.writeBoolean(out != null && out.phase == Campaigns.Phase.MARCHING);
        final String cannot = Campaigns.cannotExplore(level, colony);
        buf.writeUtf(cannot == null ? "" : cannot);
        buf.writeVarInt(Campaigns.guardsAtHome(colony));
        List<Expeditions.Destination> country;
        try {
            country = Expeditions.survey(level, colony.getCenter());
        } catch (final Throwable t) {
            country = List.of();
        }
        buf.writeVarInt(country.size());
        for (final Expeditions.Destination d : country) {
            buf.writeUtf(d.family().id());
            buf.writeUtf(d.family().name());
            buf.writeUtf(Expeditions.pretty(d.biome()));
            buf.writeVarInt(d.distance());
            buf.writeUtf(d.direction());
        }
    }
}
