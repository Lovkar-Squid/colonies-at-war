package me.lovkar.war.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import com.minecolonies.core.client.gui.WindowInfo;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.ExpeditionsModuleView;
import me.lovkar.war.network.WarTableMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The country, from the table.
 *
 * <p>Every kind of place within reach, nearest first, with the biome that was actually found
 * and how far it is; a button for how hard the trip should be and one for how many guards go
 * along; and Go beside each. The Explorer's name and skill sit at the top, and when nothing can
 * be sent - no Explorer, or he is out - the reason is written where the Go buttons are grey.</p>
 */
public class ExpeditionsWindow extends AbstractModuleWindow<ExpeditionsModuleView> {

    private static final ResourceLocation LAYOUT =
            ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "gui/expeditions.xml");
    private static final String[] DIFFICULTY = {"easy", "normal", "hard", "deadly"};

    private int difficulty = 2;
    private int guards = 0;
    /** Ticks to leave a pressed button grey before the view's answer is trusted again. */
    private int sent = 0;

    public ExpeditionsWindow(final ExpeditionsModuleView view) {
        super(view, LAYOUT);

        // the red "?" MineColonies puts on every hut window, here on the tab as well - the same book
        final ButtonImage info = findPaneOfTypeByID("info", ButtonImage.class);
        if (info != null) {
            info.setHandler(clicked -> new WindowInfo(moduleView.getBuildingView()).open());
        }

        final ScrollingList list = findPaneOfTypeByID("country", ScrollingList.class);
        if (list != null) {
            list.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return moduleView.country().size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    fill(moduleView.country().get(index), row);
                }
            });
        }
        header();

        final ButtonImage recall = findPaneOfTypeByID("recall", ButtonImage.class);
        if (recall != null) {
            recall.setEnabled(moduleView.canRecall());
            recall.setHandler(clicked -> {
                send(WarTableMessage.RECALL_EXPEDITION, 0, "");
                clicked.setEnabled(false);
                sent = 20;
            });
        }
        final ButtonImage difficultyButton = findPaneOfTypeByID("difficulty", ButtonImage.class);
        if (difficultyButton != null) {
            difficultyLabel(difficultyButton);
            difficultyButton.setHandler(clicked -> {
                difficulty = difficulty >= 4 ? 1 : difficulty + 1;
                difficultyLabel(clicked instanceof ButtonImage b ? b : difficultyButton);
            });
        }
        final ButtonImage guardsButton = findPaneOfTypeByID("guards", ButtonImage.class);
        if (guardsButton != null) {
            guardsLabel(guardsButton);
            guardsButton.setHandler(clicked -> {
                guards = guards >= moduleView.guardsAtHome() ? 0 : guards + 1;
                guardsLabel(clicked instanceof ButtonImage b ? b : guardsButton);
            });
        }
    }

    private void difficultyLabel(final ButtonImage button) {
        button.setText(Component.translatable("com.colonies_at_war.gui.expeditions.difficulty." + DIFFICULTY[difficulty - 1]));
    }

    private void guardsLabel(final ButtonImage button) {
        button.setText(Component.translatable("com.colonies_at_war.gui.expeditions.guards", guards));
    }

    /** The view is updated in place when the server answers; the header has to follow it. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        header();
        if (sent > 0) {
            sent--;
            return;
        }
        final ButtonImage recall = findPaneOfTypeByID("recall", ButtonImage.class);
        if (recall != null) {
            recall.setEnabled(moduleView.canRecall());
        }
        final ButtonImage guardsButton = findPaneOfTypeByID("guards", ButtonImage.class);
        if (guardsButton != null && guards > moduleView.guardsAtHome()) {
            guards = 0;
            guardsLabel(guardsButton);
        }
    }

    private void header() {
        final Text explorer = findPaneOfTypeByID("explorer", Text.class);
        if (explorer != null) {
            explorer.setText(moduleView.hasExplorer()
                    ? Component.translatable("com.colonies_at_war.gui.expeditions.explorer", moduleView.explorerName())
                            .withStyle(ChatFormatting.BLACK)
                    : Component.translatable("com.colonies_at_war.gui.expeditions.no_explorer").withStyle(ChatFormatting.DARK_RED));
        }
        final Text status = findPaneOfTypeByID("status", Text.class);
        if (status != null) {
            if (!moduleView.expedition().isEmpty()) {
                status.setText(Component.literal(moduleView.expedition()).withStyle(ChatFormatting.DARK_AQUA));
            } else if (!moduleView.cannot().isEmpty()) {
                status.setText(Component.literal(moduleView.cannot()).withStyle(ChatFormatting.GOLD));
            } else {
                status.setText(Component.translatable("com.colonies_at_war.gui.expeditions.ready", moduleView.explorerSkill(),
                        moduleView.guardsAtHome()).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    private void fill(final ExpeditionsModuleView.Country country, final Pane row) {
        final Text where = row.findPaneOfTypeByID("where", Text.class);
        if (where != null) {
            where.setText(Component.literal(country.name().equalsIgnoreCase(country.biome())
                    ? country.name() : country.name() + " (" + country.biome() + ")").withStyle(ChatFormatting.BLACK));
        }
        final Text detail = row.findPaneOfTypeByID("detail", Text.class);
        if (detail != null) {
            detail.setText(Component.translatable("com.colonies_at_war.gui.expeditions.away", country.distance(),
                    country.direction()).withStyle(ChatFormatting.DARK_GRAY));
        }
        final ButtonImage go = row.findPaneOfTypeByID("go", ButtonImage.class);
        if (go != null) {
            go.setEnabled(moduleView.cannot().isEmpty() && sent == 0);
            go.setHandler(clicked -> {
                send(WarTableMessage.EXPLORE, difficulty * 100 + guards, country.family());
                clicked.setEnabled(false);
                sent = 20;
            });
        }
    }

    private void send(final String what, final int number, final String text) {
        PacketDistributor.sendToServer(new WarTableMessage(buildingView.getID(), what, BlockPos.ZERO, number, text));
    }
}
