package me.lovkar.war.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.WallsModuleView;
import me.lovkar.war.network.WallActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The wall, from the table it is decided at.
 *
 * <p>One button does the thing the whole feature exists for: <b>raise the whole wall a level</b>.
 * Thirty work orders from one press, queued behind whatever the Builder is doing, and the panel
 * then says honestly which pieces are up, which are being worked on and which are still a line on
 * a map.</p>
 */
public class WallsWindow extends AbstractModuleWindow<WallsModuleView> {

    private static final ResourceLocation LAYOUT =
            ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "gui/walls.xml");

    public WallsWindow(final WallsModuleView view) {
        super(view, LAYOUT);

        final ScrollingList list = findPaneOfTypeByID("pieces", ScrollingList.class);
        if (list != null) {
            list.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return moduleView.pieces().size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    fill(moduleView.pieces().get(index), row);
                }
            });
        }

        header();

        final int next = Math.min(5, moduleView.lowestWanted() + 1);
        final ButtonImage raise = findPaneOfTypeByID("raise", ButtonImage.class);
        if (raise != null) {
            raise.setText(Component.translatable("com.colonies_at_war.gui.walls.raise", next));
            raise.setEnabled(!moduleView.pieces().isEmpty() && moduleView.lowestWanted() < 5);
            raise.setHandler(clicked -> {
                send(WallActionMessage.UPGRADE_ALL, BlockPos.ZERO, next);
                clicked.setEnabled(false);
            });
        }
        final ButtonImage mend = findPaneOfTypeByID("mend", ButtonImage.class);
        if (mend != null) {
            mend.setEnabled(!moduleView.pieces().isEmpty());
            mend.setHandler(clicked -> {
                send(WallActionMessage.REPAIR_ALL, BlockPos.ZERO, 0);
                clicked.setEnabled(false);
            });
        }
    }

    /** What the wall is worth, which is the only number anybody came here for. */
    private void header() {
        final Text score = findPaneOfTypeByID("score", Text.class);
        if (score == null) {
            return;
        }
        int up = 0;
        for (final WallsModuleView.Piece piece : moduleView.pieces()) {
            if (piece.built() >= piece.wanted()) {
                up++;
            }
        }
        score.setText(Component.translatable("com.colonies_at_war.gui.walls.score",
                        Math.max(0, moduleView.score()), up, moduleView.pieces().size())
                .withStyle(moduleView.score() >= 70 ? ChatFormatting.DARK_GREEN
                        : moduleView.score() >= 35 ? ChatFormatting.GOLD : ChatFormatting.DARK_RED));
    }

    private void fill(final WallsModuleView.Piece piece, final Pane row) {
        final Text what = row.findPaneOfTypeByID("what", Text.class);
        if (what != null) {
            what.setText(Component.translatable("com.colonies_at_war.gui.walls.piece",
                    Component.translatable("com.colonies_at_war.piece." + piece.kind()),
                    piece.pos().getX(), piece.pos().getZ()));
        }
        final Text state = row.findPaneOfTypeByID("state", Text.class);
        if (state != null) {
            state.setText(describe(piece));
        }
        final ButtonImage one = row.findPaneOfTypeByID("one", ButtonImage.class);
        if (one != null) {
            // What the button offers depends on the plan, not on what the Builder has got to:
            // a piece that is meant to be level 5 has nothing left to raise, whatever is standing.
            // While a Builder is on his way there is nothing useful to press at all.
            final int next = Math.min(5, piece.wanted() + 1);
            final boolean canRaise = piece.wanted() < 5 && !GAP.equals(piece.kind());
            one.setText(Component.translatable(canRaise
                    ? "com.colonies_at_war.gui.walls.one" : "com.colonies_at_war.gui.walls.drop"));
            one.setEnabled(!piece.working());
            one.setHandler(clicked -> {
                send(canRaise ? WallActionMessage.UPGRADE_ONE : WallActionMessage.FORGET_ONE,
                        piece.pos(), next);
                clicked.setEnabled(false);
            });
        }
    }

    /** The one kind that is an absence rather than a thing. */
    private static final String GAP = "wallgap";

    private static MutableComponent describe(final WallsModuleView.Piece piece) {
        if (GAP.equals(piece.kind())) {
            // built==1 means something is standing in it; the wall counts that stretch either way
            // only while it is
            return piece.built() > 0
                    ? Component.translatable("com.colonies_at_war.gui.walls.gap_filled")
                            .withStyle(ChatFormatting.DARK_GREEN)
                    : Component.translatable("com.colonies_at_war.gui.walls.gap_open")
                            .withStyle(ChatFormatting.GOLD);
        }
        if (piece.working()) {
            return Component.translatable("com.colonies_at_war.gui.walls.building", piece.wanted())
                    .withStyle(ChatFormatting.DARK_AQUA);
        }
        if (piece.built() < 0) {
            return Component.translatable("com.colonies_at_war.gui.walls.far")
                    .withStyle(ChatFormatting.DARK_GRAY);
        }
        if (piece.built() == 0) {
            return Component.translatable("com.colonies_at_war.gui.walls.waiting", piece.wanted())
                    .withStyle(ChatFormatting.GOLD);
        }
        if (piece.built() < piece.wanted()) {
            return Component.translatable("com.colonies_at_war.gui.walls.partway",
                    piece.built(), piece.wanted()).withStyle(ChatFormatting.GOLD);
        }
        return Component.translatable("com.colonies_at_war.gui.walls.up", piece.built())
                .withStyle(ChatFormatting.DARK_GREEN);
    }

    private void send(final String what, final BlockPos piece, final int level) {
        PacketDistributor.sendToServer(new WallActionMessage(buildingView.getID(), what, piece, level));
    }
}
