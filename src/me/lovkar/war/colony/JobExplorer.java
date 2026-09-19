package me.lovkar.war.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import me.lovkar.war.ai.EntityAIWorkExplorer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * The Explorer's job: the one person in the War Room who goes out to look rather than to fight.
 *
 * <p>He keeps nothing of his own. Where he is and when he is due back live in the campaign clock
 * with the warband's, because an expedition <i>is</i> a campaign - a march with a biome at the far
 * end instead of a gate. At home his AI does little: the expedition is decided at the table, not
 * by him.</p>
 */
public class JobExplorer extends AbstractJob<EntityAIWorkExplorer, JobExplorer> {

    /** Transient: what he is doing right now, in plain words, for the table. */
    private String statusLine = "";

    public JobExplorer(final ICitizenData citizen) {
        super(citizen);
    }

    @Override
    public EntityAIWorkExplorer generateAI() {
        return new EntityAIWorkExplorer(this);
    }

    /** The Courier's clothes: a satchel, boots that have been somewhere, and in every pack. */
    @Override
    public @NotNull ResourceLocation getModel() {
        return com.minecolonies.api.client.render.modeltype.ModModelTypes.COURIER_ID;
    }

    /** Waiting at the table for an order is not idleness worth nagging about. */
    @Override
    public int getIdleSeverity(final boolean isDemand) {
        return isDemand ? super.getIdleSeverity(true) : 4;
    }

    public String getStatusLine() {
        return statusLine;
    }

    public void setStatusLine(final String line) {
        this.statusLine = line == null ? "" : line;
    }
}
