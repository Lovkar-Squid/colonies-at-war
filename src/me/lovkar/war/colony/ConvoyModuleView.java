package me.lovkar.war.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.war.Warfare;
import me.lovkar.war.client.ConvoyWindow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The client's copy of the manifest: what the next convoy carries, and how much of it is here. */
public class ConvoyModuleView extends AbstractBuildingModuleView {

    /** One line: the kind and how many are wanted, and how many have reached the room. */
    public record Line(ItemStack stack, int loaded) {
        public int wanted() {
            return stack.getCount();
        }

        public boolean complete() {
            return loaded >= wanted();
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private int cap = 1;

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        lines.clear();
        cap = buf.readVarInt();
        final int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            final ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            lines.add(new Line(stack, buf.readVarInt()));
        }
    }

    public List<Line> lines() {
        return lines;
    }

    /** The most stacks a manifest may carry - the convoy to an ally. */
    public int cap() {
        return cap;
    }

    public int wantedStacks() {
        int stacks = 0;
        for (final Line line : lines) {
            stacks += ConvoyModule.stacksOf(line.stack(), line.wanted());
        }
        return stacks;
    }

    public int loadedStacks() {
        int stacks = 0;
        for (final Line line : lines) {
            stacks += ConvoyModule.stacksOf(line.stack(), Math.min(line.loaded(), line.wanted()));
        }
        return stacks;
    }

    @Override
    public BOWindow getWindow() {
        return new ConvoyWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "textures/gui/convoy.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colonies_at_war.gui.convoy.tab");
    }
}
