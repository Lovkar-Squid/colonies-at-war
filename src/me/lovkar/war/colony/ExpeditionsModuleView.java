package me.lovkar.war.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.war.Warfare;
import me.lovkar.war.client.ExpeditionsWindow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The client's copy of the country round the colony. */
public class ExpeditionsModuleView extends AbstractBuildingModuleView {

    /** One kind of place within reach: the family, the biome actually found, how far, which way. */
    public record Country(String family, String name, String biome, int distance, String direction) {
    }

    private boolean hasExplorer;
    private String explorerName = "";
    private int explorerSkill;
    private boolean explorerOut;
    private String expedition = "";
    private boolean canRecall;
    private String cannot = "";
    private int guardsAtHome;
    private final List<Country> country = new ArrayList<>();

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        hasExplorer = buf.readBoolean();
        explorerName = buf.readUtf();
        explorerSkill = buf.readVarInt();
        explorerOut = buf.readBoolean();
        expedition = buf.readUtf();
        canRecall = buf.readBoolean();
        cannot = buf.readUtf();
        guardsAtHome = buf.readVarInt();
        country.clear();
        final int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            country.add(new Country(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readUtf()));
        }
    }

    public boolean hasExplorer() {
        return hasExplorer;
    }

    public String explorerName() {
        return explorerName;
    }

    public int explorerSkill() {
        return explorerSkill;
    }

    public boolean explorerOut() {
        return explorerOut;
    }

    /** What the expedition out is doing, or "" when none is. */
    public String expedition() {
        return expedition;
    }

    public boolean canRecall() {
        return canRecall;
    }

    /** Why nothing can be sent right now, or "" when something can. */
    public String cannot() {
        return cannot;
    }

    public int guardsAtHome() {
        return guardsAtHome;
    }

    public List<Country> country() {
        return country;
    }

    @Override
    public BOWindow getWindow() {
        return new ExpeditionsWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "textures/gui/expedition.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colonies_at_war.gui.expeditions.tab");
    }
}
