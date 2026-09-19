package me.lovkar.war.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import com.minecolonies.core.client.gui.WindowInfo;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.ConvoyModuleView;
import me.lovkar.war.colony.WarTableModuleView;
import me.lovkar.war.network.WarTableMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The war, from the table it is decided at.
 *
 * <p>Everything a warband could march on, nearest first, with a button beside each that is only
 * lit when the men could actually go - and the reason in small print when they could not, so the
 * answer to "why is it grey" is on the same line. A second button beside each is whatever else
 * there is to do with that neighbour: offer or accept a peace, offer or accept an alliance, send
 * a convoy. One button chooses how many go; the warband out, the men held or the sellswords
 * waiting, and the last lines of the war log sit above and below.</p>
 */
public class WarTableWindow extends AbstractModuleWindow<WarTableModuleView> {

    private static final ResourceLocation LAYOUT =
            ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "gui/wartable.xml");
    private static final String KEY = "com.colonies_at_war.gui.wartable.";

    /** How many guards the next march or convoy takes: 0 is everyone who is fit. */
    private int wanted = 0;
    /** Ticks to leave a pressed button grey before the view's answer is trusted again. */
    private int sent = 0;

    public WarTableWindow(final WarTableModuleView view) {
        super(view, LAYOUT);

        // the red "?" MineColonies puts on every hut window, here on the tab as well - the same book
        final ButtonImage info = findPaneOfTypeByID("info", ButtonImage.class);
        if (info != null) {
            info.setHandler(clicked -> new WindowInfo(moduleView.getBuildingView()).open());
        }

        final ScrollingList list = findPaneOfTypeByID("targets", ScrollingList.class);
        if (list != null) {
            list.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return moduleView.targets().size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    fill(moduleView.targets().get(index), row);
                }
            });
        }
        final ScrollingList log = findPaneOfTypeByID("log", ScrollingList.class);
        if (log != null) {
            log.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return moduleView.log().size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    final Text line = row.findPaneOfTypeByID("line", Text.class);
                    if (line != null) {
                        line.setText(Component.literal(moduleView.log().get(index)));
                    }
                }
            });
        }
        header();
        buttons();

        final ButtonImage recall = findPaneOfTypeByID("recall", ButtonImage.class);
        if (recall != null) {
            recall.setHandler(clicked -> {
                send(WarTableMessage.RECALL, BlockPos.ZERO, 0, "");
                clicked.setEnabled(false);
                sent = 20;
            });
        }
        final ButtonImage ransom = findPaneOfTypeByID("ransom", ButtonImage.class);
        if (ransom != null) {
            ransom.setHandler(clicked -> {
                send(WarTableMessage.RANSOM, BlockPos.ZERO, 0, "");
                clicked.setEnabled(false);
                sent = 20;
            });
        }
        final ButtonImage hire = findPaneOfTypeByID("hire", ButtonImage.class);
        if (hire != null) {
            hire.setHandler(clicked -> {
                send(WarTableMessage.HIRE, BlockPos.ZERO, 1, "");
                clicked.setEnabled(false);
                sent = 20;
            });
        }
        final ButtonImage guards = findPaneOfTypeByID("guards", ButtonImage.class);
        if (guards != null) {
            guardsLabel(guards);
            guards.setHandler(clicked -> {
                // all, 1, 2, ... up to everyone at home, then all again
                wanted = wanted >= moduleView.guardsAtHome() ? 0 : wanted + 1;
                guardsLabel(clicked instanceof ButtonImage b ? b : guards);
            });
        }
    }

    private void guardsLabel(final ButtonImage button) {
        button.setText(wanted == 0
                ? Component.translatable(KEY + "guards_all", moduleView.guardsAtHome())
                : Component.translatable(KEY + "guards", wanted, moduleView.guardsAtHome()));
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
        buttons();
        final ButtonImage guards = findPaneOfTypeByID("guards", ButtonImage.class);
        if (guards != null && wanted > moduleView.guardsAtHome()) {
            wanted = 0;
            guardsLabel(guards);
        }
    }

    private void buttons() {
        final ButtonImage recall = findPaneOfTypeByID("recall", ButtonImage.class);
        if (recall != null) {
            recall.setEnabled(moduleView.canRecall());
        }
        final ButtonImage ransom = findPaneOfTypeByID("ransom", ButtonImage.class);
        if (ransom != null) {
            ransom.setEnabled(moduleView.held() > 0);
        }
        final ButtonImage hire = findPaneOfTypeByID("hire", ButtonImage.class);
        if (hire != null) {
            hire.setEnabled(moduleView.canHire());
        }
    }

    private void header() {
        final Text state = findPaneOfTypeByID("state", Text.class);
        if (state != null) {
            state.setText(Component.translatable(KEY + "state",
                            moduleView.weariness(), moduleView.tooWeary(), moduleView.guardsAtHome())
                    .withStyle(moduleView.weariness() >= moduleView.tooWeary() ? ChatFormatting.DARK_RED : ChatFormatting.BLACK));
        }
        final Text warband = findPaneOfTypeByID("warband", Text.class);
        if (warband != null) {
            warband.setText(moduleView.warband().isEmpty()
                    ? Component.translatable(KEY + "home").withStyle(ChatFormatting.DARK_GRAY)
                    : Component.literal(moduleView.warband()).withStyle(ChatFormatting.DARK_AQUA));
        }
        final Text held = findPaneOfTypeByID("held", Text.class);
        if (held != null) {
            if (moduleView.held() > 0) {
                held.setText(Component.translatable(KEY + "held", moduleView.held(), moduleView.held() * moduleView.ransomGold())
                        .withStyle(ChatFormatting.DARK_RED));
            } else if (moduleView.sellswords() > 0) {
                held.setText(Component.translatable(KEY + "sellswords", moduleView.sellswords(), moduleView.sellswordCap())
                        .withStyle(ChatFormatting.DARK_PURPLE));
            } else {
                held.setText(Component.translatable(KEY + "nobody_held", moduleView.sellswordGold()).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    private void fill(final WarTableModuleView.Target target, final Pane row) {
        final Text who = row.findPaneOfTypeByID("who", Text.class);
        if (who != null) {
            who.setText(Component.literal(target.name())
                    .withStyle(target.atWar() ? ChatFormatting.DARK_RED : "allied".equals(target.state()) ? ChatFormatting.DARK_GREEN
                            : ChatFormatting.BLACK));
        }
        final Text detail = row.findPaneOfTypeByID("detail", Text.class);
        if (detail != null) {
            detail.setText(Component.translatable(KEY + "detail", target.distance(), target.direction(), target.strength())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        final Text detail2 = row.findPaneOfTypeByID("detail2", Text.class);
        if (detail2 != null) {
            if (!target.canMarch() && !"colony".equals(target.kind())) {
                detail2.setText(Component.literal(target.reason()).withStyle(ChatFormatting.GOLD));
            } else if ("colony".equals(target.kind())) {
                detail2.setText(Component.translatable(KEY + "standing", target.standing(),
                        Component.translatable(KEY + "state." + (target.state().isEmpty() ? "peace" : target.state())))
                        .withStyle(target.atWar() ? ChatFormatting.DARK_RED : "allied".equals(target.state()) ? ChatFormatting.DARK_GREEN
                                : ChatFormatting.DARK_GRAY));
            } else if ("kingdom".equals(target.kind())) {
                detail2.setText(Component.translatable(KEY + "kingdom", target.standing()).withStyle(ChatFormatting.DARK_GRAY));
            } else {
                detail2.setText(Component.translatable(KEY + "nobodys").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        final ButtonImage march = row.findPaneOfTypeByID("march", ButtonImage.class);
        if (march != null) {
            march.setEnabled(target.canMarch() && sent == 0);
            march.setHandler(clicked -> {
                send(WarTableMessage.MARCH, target.pos(), wanted, "");
                clicked.setEnabled(false);
                sent = 20;
            });
        }
        final ButtonImage deal = row.findPaneOfTypeByID("deal", ButtonImage.class);
        final String dealing = deal == null ? "" : deal(target, deal);
        final Text detail3 = row.findPaneOfTypeByID("detail3", Text.class);
        if (detail3 != null) {
            if (target.hasOffer()) {
                detail3.setText(Component.translatable(KEY + (target.ours() ? "offered" : "offers"), target.offer())
                        .withStyle(ChatFormatting.DARK_BLUE));
            } else if (!target.bond().isEmpty()) {
                detail3.setText(Component.literal(target.bond()).withStyle(ChatFormatting.DARK_PURPLE));
            } else if ("convoy".equals(dealing) && target.canConvoy() && loadedStacks() > 0) {
                // the one button that is lit is Convoy: say what it would send, before why March is not
                detail3.setText(Component.translatable(KEY + "loaded", loadedStacks()).withStyle(ChatFormatting.DARK_GREEN));
            } else if (!target.canMarch() && "colony".equals(target.kind())) {
                detail3.setText(Component.literal(target.reason()).withStyle(ChatFormatting.GOLD));
            } else if ("convoy".equals(dealing) && !target.canConvoy()) {
                detail3.setText(Component.literal(target.convoy()).withStyle(ChatFormatting.GOLD));
            } else if ("convoy".equals(dealing)) {
                detail3.setText(Component.translatable(KEY + "nothing_loaded").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                detail3.setText(Component.empty());
            }
        }
    }

    /**
     * The second button is whatever else there is to do with this neighbour: answer an offer
     * of theirs, offer a peace while at war, offer an alliance when the standing has earned it,
     * or send a convoy the rest of the time - and when nothing is loaded for one, open the bay
     * instead, because the goods have to be brought to the room before anything can leave it.
     * A camp or a village has nothing to deal with.
     */
    private String deal(final WarTableModuleView.Target target, final ButtonImage button) {
        final String verb;
        final String label;
        boolean enabled = sent == 0;
        if (!"colony".equals(target.kind()) && !"kingdom".equals(target.kind())) {
            button.setVisible(false);
            return "";
        }
        button.setVisible(true);
        if (target.hasOffer() && !target.ours()) {
            verb = WarTableMessage.ACCEPT;
            label = "accept";
        } else if (target.hasOffer()) {
            verb = WarTableMessage.REFUSE;
            label = "withdraw";
        } else if (target.atWar()) {
            verb = WarTableMessage.OFFER;
            label = "peace";
        } else if ("warm".equals(target.state())) {
            verb = WarTableMessage.ALLY;
            label = "ally";
        } else {
            verb = WarTableMessage.CONVOY;
            label = "convoy";
            enabled = enabled && target.canConvoy();
            if (enabled && loadedStacks() == 0) {
                button.setText(Component.translatable(KEY + label));
                button.setEnabled(true);
                button.setHandler(clicked -> {
                    final ConvoyModuleView bay = buildingView.getModuleViewByType(ConvoyModuleView.class);
                    if (bay != null) {
                        new ConvoyWindow(bay).open();
                    }
                });
                return label;
            }
        }
        button.setText(Component.translatable(KEY + label));
        button.setEnabled(enabled);
        final String send = verb;
        button.setHandler(clicked -> {
            send(send, target.pos(), WarTableMessage.CONVOY.equals(send) ? wanted : 0, "");
            clicked.setEnabled(false);
            sent = 20;
        });
        return label;
    }

    /** The stacks waiting in the bay, off the convoy tab's own view. */
    private int loadedStacks() {
        final ConvoyModuleView bay = buildingView.getModuleViewByType(ConvoyModuleView.class);
        return bay == null ? 0 : bay.loadedStacks();
    }

    private void send(final String what, final BlockPos pos, final int number, final String text) {
        PacketDistributor.sendToServer(new WarTableMessage(buildingView.getID(), what, pos, number, text));
    }
}
