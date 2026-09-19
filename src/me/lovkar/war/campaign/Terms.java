package me.lovkar.war.campaign;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.Standing;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Peace, and alliance: both a negotiation, never a button one side presses alone.
 *
 * <p>One side offers - a white peace, gold from them, gold to them, their vassalage, or an
 * alliance - and the other accepts or refuses, by hand, within a few days. Accepting a peace does
 * everything the terms say at once: the gold changes warehouses, every man held on either side
 * goes home (a prisoner exchange), the truce clock starts, and the war weariness on both sides is
 * gone, because that was the point of making it.</p>
 */
public final class Terms {
    private Terms() {
    }

    public static final String WHITE = "white";
    public static final String TRIBUTE = "tribute";
    public static final String PAY = "pay";
    public static final String VASSAL = "vassal";

    /** Standing mended by a peace: enough that the next war takes a few more wrongs. */
    private static final int MENDED_BY_PEACE = 20;

    private static IColony colony(final ServerLevel level, final int id) {
        return IColonyManager.getInstance().getColonyByDimension(id, level.dimension());
    }

    // ------------------------------------------------------------------ peace

    /** Why peace cannot be offered on these terms, or null. */
    public static String cannotOfferPeace(final IColony mine, final int theirs, final String terms, final int gold) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!Standing.atWar(mine, theirs)) {
            return "not at war with colony " + theirs;
        }
        final String t = terms == null ? WHITE : terms.toLowerCase(Locale.ROOT);
        if (!WHITE.equals(t) && !TRIBUTE.equals(t) && !PAY.equals(t) && !VASSAL.equals(t)) {
            return "terms are white, tribute <gold>, pay <gold> or vassal";
        }
        if ((TRIBUTE.equals(t) || PAY.equals(t)) && gold <= 0) {
            return "say how much gold";
        }
        if (VASSAL.equals(t) && WarConfig.depth() < 2) {
            return "vassalage is a conquest-depth term (warLevel)";
        }
        return null;
    }

    /** {@code /war offer}: peace on terms, put on the table for the other side to answer. */
    public static String offerPeace(final ServerLevel level, final IColony mine, final int theirs, final String terms, final int gold) {
        final String no = cannotOfferPeace(mine, theirs, terms, gold);
        if (no != null) {
            return no;
        }
        final String t = terms == null ? WHITE : terms.toLowerCase(Locale.ROOT);
        final IColony them = colony(level, theirs);
        Standing.offer(mine, theirs, Standing.Offer.PEACE, t, gold);
        final Standing.Offer offer = Standing.offerBetween(mine, theirs);
        final String what = offer.describe(true);
        Campaigns.log(level).add(level, mine.getID(), theirs, mine.getName() + " offered " + (them == null ? "colony " + theirs : them.getName())
                + " peace: " + what);
        tell(them, mine.getName() + " offers peace: " + offer.describe(false) + ". Accept it (/war accept " + mine.getID()
                + ", or the war table) or refuse it (/war refuse " + mine.getID() + ") within " + WarConfig.offerDays()
                + (WarConfig.offerDays() == 1 ? " day." : " days."), MessageUtils.MessagePriority.IMPORTANT);
        Campaigns.touch(mine);
        Campaigns.touch(them);
        return null;
    }

    /** Why an alliance cannot be offered, or null. */
    public static String cannotOfferAlliance(final IColony mine, final int theirs) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (theirs == mine.getID()) {
            return "not with yourselves";
        }
        if (Standing.allied(mine, theirs)) {
            return "already allied";
        }
        if (!Standing.mayAlly(mine, theirs)) {
            return "standing is " + Standing.between(mine, theirs) + " - an alliance needs " + WarConfig.allyThreshold()
                    + " and no war, truce or vassalage between you";
        }
        return null;
    }

    /** {@code /war ally}: an alliance, put on the table. */
    public static String offerAlliance(final ServerLevel level, final IColony mine, final int theirs) {
        final String no = cannotOfferAlliance(mine, theirs);
        if (no != null) {
            return no;
        }
        final IColony them = colony(level, theirs);
        Standing.offer(mine, theirs, Standing.Offer.ALLIANCE, "", 0);
        Campaigns.log(level).add(level, mine.getID(), theirs, mine.getName() + " offered " + (them == null ? "colony " + theirs : them.getName())
                + " an alliance");
        tell(them, mine.getName() + " offers an alliance. Accept it (/war accept " + mine.getID() + ", or the war table) or refuse it (/war refuse "
                + mine.getID() + ") within " + WarConfig.offerDays() + (WarConfig.offerDays() == 1 ? " day." : " days."),
                MessageUtils.MessagePriority.IMPORTANT);
        Campaigns.touch(mine);
        Campaigns.touch(them);
        return null;
    }

    /** {@code /war accept}: whatever the other side has put on the table. Returns the reason it failed, or null. */
    public static String accept(final ServerLevel level, final IColony mine, final int theirs) {
        if (mine == null) {
            return "stand in a colony";
        }
        final Standing.Offer offer = Standing.offerBetween(mine, theirs);
        if (offer == null) {
            return "nothing is on the table with colony " + theirs;
        }
        if (offer.from() == mine.getID()) {
            return "that is your own offer - they have to answer it";
        }
        final IColony them = colony(level, theirs);
        if (them == null) {
            return "colony " + theirs + " is not in this world";
        }
        if (!offer.isPeace()) {
            if (!Standing.ally(mine, theirs)) {
                return "an alliance cannot be sworn now";
            }
            Campaigns.log(level).add(level, mine.getID(), theirs, mine.getName() + " and " + them.getName() + " are allied");
            tell(mine, mine.getName() + " and " + them.getName() + " are allied. Convoys between you carry three times the goods.",
                    MessageUtils.MessagePriority.IMPORTANT);
            tell(them, them.getName() + " and " + mine.getName() + " are allied. Convoys between you carry three times the goods.",
                    MessageUtils.MessagePriority.IMPORTANT);
            Warfare.LOGGER.info("[war] {} and {} are allied", mine.getName(), them.getName());
            Campaigns.touch(mine);
            Campaigns.touch(them);
            return null;
        }
        if (!Standing.atWar(mine, theirs)) {
            Standing.clearOffer(mine, theirs);
            return "the war is already over";
        }
        // the terms, from the offerer's side: tribute is gold to the offerer, pay is gold from him
        final IColony offerer = them;
        final IColony acceptor = mine;
        if (TRIBUTE.equals(offer.terms()) || PAY.equals(offer.terms())) {
            final IColony payer = TRIBUTE.equals(offer.terms()) ? acceptor : offerer;
            final IColony payee = payer == acceptor ? offerer : acceptor;
            final String short_ = pay(level, payer, payee, offer.gold());
            if (short_ != null) {
                return short_;
            }
        }
        final String what = offer.describe(false);
        Standing.peace(mine, theirs, MENDED_BY_PEACE);
        Campaigns.peaceMade(level, mine.getID(), theirs);
        final int exchanged = Campaigns.exchangeCaptives(level, mine.getID(), theirs);
        if (VASSAL.equals(offer.terms())) {
            Campaigns.subjugate(level, offerer, acceptor, level.getServer().overworld().getGameTime());
        }
        final String line = mine.getName() + " accepted " + them.getName() + "'s peace: " + what
                + (exchanged > 0 ? "; " + exchanged + (exchanged == 1 ? " man exchanged" : " men exchanged") : "")
                + " - a truce for " + WarConfig.truceDays() + " days";
        Campaigns.log(level).add(level, mine.getID(), theirs, line);
        tell(mine, "Peace with " + them.getName() + ": " + what + ". A truce holds for " + WarConfig.truceDays() + " days"
                + (exchanged > 0 ? ", and the men held on both sides are home." : "."), MessageUtils.MessagePriority.IMPORTANT);
        tell(them, mine.getName() + " has accepted your peace: " + offer.describe(true) + ". A truce holds for " + WarConfig.truceDays()
                + " days" + (exchanged > 0 ? ", and the men held on both sides are home." : "."), MessageUtils.MessagePriority.IMPORTANT);
        Warfare.LOGGER.info("[war] {}", line);
        Campaigns.touch(mine);
        Campaigns.touch(them);
        return null;
    }

    /** {@code /war refuse}: the offer comes off the table, and the other side is told. */
    public static String refuse(final ServerLevel level, final IColony mine, final int theirs) {
        if (mine == null) {
            return "stand in a colony";
        }
        final Standing.Offer offer = Standing.offerBetween(mine, theirs);
        if (offer == null) {
            return "nothing is on the table with colony " + theirs;
        }
        final IColony them = colony(level, theirs);
        final boolean ours = offer.from() == mine.getID();
        Standing.clearOffer(mine, theirs);
        Campaigns.log(level).add(level, mine.getID(), theirs, ours ? mine.getName() + " withdrew its offer"
                : mine.getName() + " refused " + (them == null ? "colony " + theirs : them.getName()) + "'s offer of " + offer.describe(false));
        tell(them, ours ? mine.getName() + " has withdrawn its offer." : mine.getName() + " has refused your offer of " + offer.describe(true) + ".",
                MessageUtils.MessagePriority.IMPORTANT);
        Campaigns.touch(mine);
        Campaigns.touch(them);
        return null;
    }

    /** {@code /war break}: out of the alliance, at the price of the standing it stood on. */
    public static String breakAlliance(final ServerLevel level, final IColony mine, final int theirs) {
        if (mine == null) {
            return "stand in a colony";
        }
        if (!Standing.breakAlliance(mine, theirs)) {
            return "not allied with colony " + theirs;
        }
        final IColony them = colony(level, theirs);
        Campaigns.log(level).add(level, mine.getID(), theirs, mine.getName() + " broke its alliance with " + (them == null ? "colony " + theirs : them.getName()));
        tell(them, mine.getName() + " has broken the alliance. Standing between you is back at nothing.", MessageUtils.MessagePriority.DANGER);
        tell(mine, "The alliance with " + (them == null ? "colony " + theirs : them.getName()) + " is broken. Standing is back at nothing.",
                MessageUtils.MessagePriority.IMPORTANT);
        Campaigns.touch(mine);
        Campaigns.touch(them);
        return null;
    }

    // ------------------------------------------------------------------ gold

    /** Gold out of one warehouse into the other. Returns why it could not be paid, or null. */
    private static String pay(final ServerLevel level, final IColony payer, final IColony payee, final int gold) {
        final List<ItemStack> taken = new ArrayList<>();
        int have = 0;
        try {
            for (final IWareHouse warehouse : payer.getServerBuildingManager().getWareHouses()) {
                for (final IItemHandler handler : InventoryUtils.getItemHandlersFromProvider(warehouse)) {
                    for (int slot = 0; slot < handler.getSlots() && have < gold; slot++) {
                        if (!handler.getStackInSlot(slot).is(Items.GOLD_INGOT)) {
                            continue;
                        }
                        final ItemStack got = handler.extractItem(slot, gold - have, false);
                        if (!got.isEmpty()) {
                            taken.add(got);
                            have += got.getCount();
                        }
                    }
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[war] could not count {}'s gold: {}", payer.getName(), t.toString());
        }
        if (have < gold) {
            final IBuilding room = Campaigns.warRoom(payer);
            for (final ItemStack stack : taken) {
                Plunder.deliver(level, payer, room, List.of(stack));
            }
            return payer.getName() + " cannot pay: the terms are " + gold + " gold and its warehouse holds " + have;
        }
        final List<ItemStack> stacks = new ArrayList<>();
        for (int left = gold; left > 0; left -= 64) {
            stacks.add(new ItemStack(Items.GOLD_INGOT, Math.min(64, left)));
        }
        Plunder.deliver(level, payee, Campaigns.warRoom(payee), stacks);
        return null;
    }

    private static void tell(final IColony colony, final String text, final MessageUtils.MessagePriority priority) {
        if (colony == null) {
            return;
        }
        try {
            MessageUtils.format(Component.literal(text)).withPriority(priority).sendTo(colony).forManagers();
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[war] could not message {}: {}", colony.getName(), t.toString());
        }
    }
}
