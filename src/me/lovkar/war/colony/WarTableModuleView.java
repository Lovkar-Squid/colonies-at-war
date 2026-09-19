package me.lovkar.war.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.war.Warfare;
import me.lovkar.war.client.WarTableWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The client's copy of the war table. */
public class WarTableModuleView extends AbstractBuildingModuleView {

    /** Somewhere a warband could march, as the server described it. */
    public record Target(String kind, int id, BlockPos pos, String name, int distance, String direction, int strength,
                         int standing, boolean atWar, String reason, String state, String offer, boolean ours, String convoy,
                         String bond) {
        public boolean canMarch() {
            return reason.isEmpty();
        }

        public boolean canConvoy() {
            return convoy.isEmpty();
        }

        public boolean hasOffer() {
            return !offer.isEmpty();
        }
    }

    private int weariness;
    private int tooWeary = 60;
    private int guardsAtHome;
    private String warband = "";
    private boolean canRecall;
    private int held;
    private int ransomGold;
    private int sellswords;
    private int sellswordGold;
    private boolean canHire;
    private int sellswordCap;
    private final List<Target> targets = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        weariness = buf.readVarInt();
        tooWeary = buf.readVarInt();
        guardsAtHome = buf.readVarInt();
        warband = buf.readUtf();
        canRecall = buf.readBoolean();
        held = buf.readVarInt();
        ransomGold = buf.readVarInt();
        sellswords = buf.readVarInt();
        sellswordGold = buf.readVarInt();
        canHire = buf.readBoolean();
        sellswordCap = buf.readVarInt();
        targets.clear();
        final int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            targets.add(new Target(buf.readUtf(), buf.readVarInt(), buf.readBlockPos(), buf.readUtf(), buf.readVarInt(),
                    buf.readUtf(), buf.readVarInt(), buf.readInt(), buf.readBoolean(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readBoolean(), buf.readUtf(), buf.readUtf()));
        }
        log.clear();
        final int lines = buf.readVarInt();
        for (int i = 0; i < lines; i++) {
            log.add(buf.readUtf());
        }
    }

    public int weariness() {
        return weariness;
    }

    public int tooWeary() {
        return tooWeary;
    }

    public int guardsAtHome() {
        return guardsAtHome;
    }

    /** What the warband out is doing, or "" when none is. */
    public String warband() {
        return warband;
    }

    public boolean canRecall() {
        return canRecall;
    }

    public int held() {
        return held;
    }

    public int ransomGold() {
        return ransomGold;
    }

    public int sellswords() {
        return sellswords;
    }

    public int sellswordGold() {
        return sellswordGold;
    }

    public boolean canHire() {
        return canHire;
    }

    public int sellswordCap() {
        return sellswordCap;
    }

    public List<Target> targets() {
        return targets;
    }

    public List<String> log() {
        return log;
    }

    @Override
    public BOWindow getWindow() {
        return new WarTableWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "textures/gui/wartable.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colonies_at_war.gui.wartable.tab");
    }
}
