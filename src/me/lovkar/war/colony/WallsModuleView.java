package me.lovkar.war.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.war.Warfare;
import me.lovkar.war.client.WallsWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The client's copy of the colony's wall. */
public class WallsModuleView extends AbstractBuildingModuleView {

    /**
     * One piece.
     *
     * @param built -1 when nobody is near enough to look, 0 when the ground is still bare
     */
    public record Piece(String kind, BlockPos pos, int wanted, int built, boolean working) {
    }

    private final List<Piece> pieces = new ArrayList<>();
    private int score = -1;

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        pieces.clear();
        final int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            pieces.add(new Piece(buf.readUtf(), buf.readBlockPos(),
                    buf.readVarInt(), buf.readInt(), buf.readBoolean()));
        }
        score = buf.readVarInt();
    }

    public List<Piece> pieces() {
        return pieces;
    }

    /** 0..100, or -1 when the colony has laid no wall of ours at all. */
    public int score() {
        return score;
    }

    /** The lowest level anything is meant to be - what "upgrade all" would raise from. */
    public int lowestWanted() {
        int low = 5;
        for (final Piece piece : pieces) {
            low = Math.min(low, piece.wanted());
        }
        return pieces.isEmpty() ? 0 : low;
    }

    @Override
    public BOWindow getWindow() {
        return new WallsWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "textures/gui/walls.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colonies_at_war.gui.walls.tab");
    }
}
