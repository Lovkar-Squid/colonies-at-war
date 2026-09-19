package me.lovkar.war.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import com.minecolonies.core.client.gui.WindowInfo;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.ConvoyModuleView;
import me.lovkar.war.network.WarTableMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The convoy tab: the manifest, and the shelf to fill it from.
 *
 * <p>Above, what the next convoy carries and how much of it is already in the room - a line an
 * item, with a stack more or less at a press. Below, the shelf: a search over every item there
 * is, or a short list of the things colonies actually send each other when nothing is typed, with
 * a <i>Load</i> that puts a stack of it on the manifest. The couriers do the rest; the convoy
 * itself is sent from the war table, beside the neighbour it goes to.</p>
 */
public class ConvoyWindow extends AbstractModuleWindow<ConvoyModuleView> {

    private static final ResourceLocation LAYOUT =
            ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "gui/convoy.xml");
    private static final String KEY = "com.colonies_at_war.gui.convoy.";
    private static final int MOST_FOUND = 80;

    /** What a colony sends a neighbour when nobody has typed anything: food, timber, stone, metal, cloth. */
    private static final List<Item> SHELF = List.of(Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN,
            Items.BAKED_POTATO, Items.APPLE, Items.WHEAT, Items.CARROT, Items.POTATO, Items.OAK_LOG, Items.SPRUCE_LOG,
            Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE_BRICKS, Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT,
            Items.COPPER_INGOT, Items.LEATHER, Items.WHITE_WOOL, Items.STRING, Items.PAPER, Items.GLASS, Items.TORCH,
            Items.EMERALD, Items.DIAMOND);

    private final List<ItemStack> found = new ArrayList<>();
    private String looked = null;
    /** Ticks to leave a pressed button grey before the view's answer is trusted again. */
    private int sent = 0;

    public ConvoyWindow(final ConvoyModuleView view) {
        super(view, LAYOUT);

        final ButtonImage info = findPaneOfTypeByID("info", ButtonImage.class);
        if (info != null) {
            info.setHandler(clicked -> new WindowInfo(moduleView.getBuildingView()).open());
        }
        final ScrollingList manifest = findPaneOfTypeByID("manifest", ScrollingList.class);
        if (manifest != null) {
            manifest.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return moduleView.lines().size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    fillLine(moduleView.lines().get(index), row);
                }
            });
        }
        final ScrollingList items = findPaneOfTypeByID("items", ScrollingList.class);
        if (items != null) {
            items.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return found.size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    fillShelf(found.get(index), row);
                }
            });
        }
        final ButtonImage clear = findPaneOfTypeByID("clear", ButtonImage.class);
        if (clear != null) {
            clear.setHandler(clicked -> {
                send(WarTableMessage.UNLOAD, "", 0);
                clicked.setEnabled(false);
                sent = 20;
            });
        }
        look("");
        header();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        header();
        final TextField search = findPaneOfTypeByID("search", TextField.class);
        if (search != null) {
            look(search.getText());
        }
        if (sent > 0) {
            sent--;
            return;
        }
        final ButtonImage clear = findPaneOfTypeByID("clear", ButtonImage.class);
        if (clear != null) {
            clear.setEnabled(!moduleView.lines().isEmpty());
        }
    }

    private void header() {
        final Text status = findPaneOfTypeByID("status", Text.class);
        if (status == null) {
            return;
        }
        if (moduleView.lines().isEmpty()) {
            status.setText(Component.translatable(KEY + "nothing", moduleView.cap()).withStyle(ChatFormatting.DARK_GRAY));
        } else if (moduleView.loadedStacks() >= moduleView.wantedStacks()) {
            status.setText(Component.translatable(KEY + "ready", moduleView.wantedStacks()).withStyle(ChatFormatting.DARK_GREEN));
        } else {
            status.setText(Component.translatable(KEY + "loading", moduleView.loadedStacks(), moduleView.wantedStacks(), moduleView.cap())
                    .withStyle(ChatFormatting.BLACK));
        }
    }

    /** The shelf: what matches the search, or the usual goods when there is none. */
    private void look(final String typed) {
        final String query = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        if (query.equals(looked)) {
            return;
        }
        looked = query;
        found.clear();
        if (query.isEmpty()) {
            for (final Item item : SHELF) {
                found.add(new ItemStack(item));
            }
            return;
        }
        for (final Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            final ItemStack stack = new ItemStack(item);
            final String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            final ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (name.contains(query) || id.getPath().contains(query)) {
                found.add(stack);
                if (found.size() >= MOST_FOUND) {
                    break;
                }
            }
        }
    }

    private void fillLine(final ConvoyModuleView.Line line, final Pane row) {
        final ItemIcon icon = row.findPaneOfTypeByID("icon", ItemIcon.class);
        if (icon != null) {
            // the count is in the line; a "64" painted over the icon collides with it
            icon.setRenderItemDecorations(false);
            icon.setItem(line.stack());
        }
        final Text what = row.findPaneOfTypeByID("what", Text.class);
        if (what != null) {
            what.setText(Component.literal(line.wanted() + " ").append(line.stack().getHoverName()).withStyle(ChatFormatting.BLACK));
        }
        final Text have = row.findPaneOfTypeByID("have", Text.class);
        if (have != null) {
            have.setText(line.complete()
                    ? Component.translatable(KEY + "here").withStyle(ChatFormatting.DARK_GREEN)
                    : Component.translatable(KEY + "coming", Math.min(line.loaded(), line.wanted()), line.wanted())
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        final int stack = Math.max(1, line.stack().getMaxStackSize());
        final ButtonImage less = row.findPaneOfTypeByID("less", ButtonImage.class);
        if (less != null) {
            less.setEnabled(sent == 0);
            less.setHandler(clicked -> {
                send(WarTableMessage.LOAD, idOf(line.stack()), -stack);
                sent = 10;
            });
        }
        final ButtonImage more = row.findPaneOfTypeByID("more", ButtonImage.class);
        if (more != null) {
            more.setEnabled(sent == 0 && moduleView.wantedStacks() < moduleView.cap());
            more.setHandler(clicked -> {
                send(WarTableMessage.LOAD, idOf(line.stack()), stack);
                sent = 10;
            });
        }
    }

    private void fillShelf(final ItemStack stack, final Pane row) {
        final ItemIcon icon = row.findPaneOfTypeByID("icon", ItemIcon.class);
        if (icon != null) {
            icon.setItem(stack);
        }
        final Text name = row.findPaneOfTypeByID("name", Text.class);
        if (name != null) {
            name.setText(stack.getHoverName().copy().withStyle(ChatFormatting.BLACK));
        }
        final ButtonImage load = row.findPaneOfTypeByID("load", ButtonImage.class);
        if (load != null) {
            load.setEnabled(sent == 0 && moduleView.wantedStacks() < moduleView.cap());
            load.setHandler(clicked -> {
                send(WarTableMessage.LOAD, idOf(stack), Math.max(1, stack.getMaxStackSize()));
                sent = 10;
            });
        }
    }

    private static String idOf(final ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private void send(final String what, final String item, final int count) {
        PacketDistributor.sendToServer(new WarTableMessage(buildingView.getID(), what, buildingView.getID(), count, item));
    }
}
