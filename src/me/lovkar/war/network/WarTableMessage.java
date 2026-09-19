package me.lovkar.war.network;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.ConvoyModule;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import me.lovkar.war.campaign.Campaigns;
import me.lovkar.war.campaign.Terms;
import me.lovkar.war.campaign.WarTable;
import me.lovkar.war.compat.Kingdoms;
import me.lovkar.war.WarConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * What the War Room's table may ask for: march on something, send the Explorer somewhere, call
 * either back, pay a ransom, offer or answer a peace or an alliance, send a convoy, hire sellswords.
 *
 * <p>One packet, a dozen verbs, every one re-checked here: who is asking, whether they may manage
 * the colony's huts, and whether what they point at is really there. A client that invents a
 * target gets a line saying why not.</p>
 *
 * @param building the War Room
 * @param what     the verb
 * @param pos      the target, for a march
 * @param number   guards to send, or the difficulty for an expedition
 * @param text     the biome family, for an expedition
 */
public record WarTableMessage(BlockPos building, String what, BlockPos pos, int number, String text)
        implements CustomPacketPayload {

    public static final String MARCH = "march";
    public static final String RECALL = "recall";
    public static final String RANSOM = "ransom";
    public static final String EXPLORE = "explore";
    public static final String RECALL_EXPEDITION = "recall_expedition";
    /** A white peace, offered to the colony at {@code pos}. */
    public static final String OFFER = "offer";
    /** Whatever the colony at {@code pos} has put on the table, accepted or refused. */
    public static final String ACCEPT = "accept";
    public static final String REFUSE = "refuse";
    /** An alliance, offered to the colony at {@code pos}. */
    public static final String ALLY = "ally";
    /** Goods to the colony or kingdom at {@code pos}, with {@code number} guards. */
    public static final String CONVOY = "convoy";
    /** {@code number} sellswords, hired. */
    public static final String HIRE = "hire";
    /** {@code number} more of the item {@code text} on the convoy's manifest - fewer, when negative. */
    public static final String LOAD = "load";
    /** The manifest torn up. */
    public static final String UNLOAD = "unload";

    public static final CustomPacketPayload.Type<WarTableMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "war_table"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarTableMessage> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> {
                buf.writeBlockPos(msg.building());
                buf.writeUtf(msg.what());
                buf.writeBlockPos(msg.pos());
                buf.writeVarInt(msg.number());
                buf.writeUtf(msg.text());
            }, buf -> new WarTableMessage(buf.readBlockPos(), buf.readUtf(), buf.readBlockPos(), buf.readVarInt(), buf.readUtf()));

    @Override
    public CustomPacketPayload.@NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final WarTableMessage message, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, message.building());
            if (colony == null || !colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
                return;
            }
            final StringBuilder why = new StringBuilder();
            switch (message.what()) {
                case MARCH -> {
                    final Campaigns.Campaign c = WarTable.marchAt(level, colony, message.pos(), Math.max(0, message.number()), why);
                    say(player, c == null ? "The men stay: " + why : c.describe() + ".");
                }
                case RECALL -> say(player, Campaigns.recall(level, colony, why) ? "The warband turns for home." : why.toString());
                case RANSOM -> {
                    final String no = Campaigns.ransom(level, colony);
                    say(player, no == null ? "The ransom is paid and the men are home." : no);
                }
                case EXPLORE -> {
                    final int difficulty = Math.max(1, Math.min(4, message.number() / 100));
                    final int guards = Math.max(0, message.number() % 100);
                    final Campaigns.Campaign c = Campaigns.explore(level, colony, message.text(), difficulty, guards, why);
                    say(player, c == null ? "Nobody goes: " + why : c.describe() + ".");
                }
                case RECALL_EXPEDITION -> say(player, Campaigns.recallExpedition(level, colony, why)
                        ? "The explorer turns for home." : why.toString());
                case OFFER -> {
                    final IColony them = IColonyManager.getInstance().getColonyByPosFromWorld(level, message.pos());
                    final String no = them == null ? "no colony stands there" : Terms.offerPeace(level, colony, them.getID(), Terms.WHITE, 0);
                    say(player, no == null ? "A white peace is offered to " + them.getName() + ". They have " + WarConfig.offerDays()
                            + " days to answer; terms other than a white peace are offered with /war offer." : no);
                }
                case ACCEPT, REFUSE -> {
                    final IColony them = IColonyManager.getInstance().getColonyByPosFromWorld(level, message.pos());
                    final String no = them == null ? "no colony stands there" : ACCEPT.equals(message.what())
                            ? Terms.accept(level, colony, them.getID()) : Terms.refuse(level, colony, them.getID());
                    say(player, no == null ? (ACCEPT.equals(message.what()) ? "Accepted." : "Refused.") : no);
                }
                case ALLY -> {
                    final IColony them = IColonyManager.getInstance().getColonyByPosFromWorld(level, message.pos());
                    final String no = them == null ? "no colony stands there" : Terms.offerAlliance(level, colony, them.getID());
                    say(player, no == null ? "An alliance is offered to " + them.getName() + ". They have " + WarConfig.offerDays() + " days to answer." : no);
                }
                case CONVOY -> {
                    final IColony them = IColonyManager.getInstance().getColonyByPosFromWorld(level, message.pos());
                    final Campaigns.Campaign c;
                    if (them != null) {
                        c = Campaigns.convoy(level, colony, them.getID(), Math.max(0, message.number()), why);
                    } else {
                        final Object kingdom = Kingdoms.at(level, message.pos());
                        c = kingdom == null ? null : Campaigns.convoyTo(level, colony, kingdom, Math.max(0, message.number()), why);
                        if (kingdom == null) {
                            why.append("nothing to send a convoy to there");
                        }
                    }
                    say(player, c == null ? "The convoy stays: " + why : c.describe() + ".");
                }
                case LOAD, UNLOAD -> {
                    final IBuilding room = colony.getServerBuildingManager().getBuilding(message.building());
                    final ConvoyModule bay = ConvoyModule.of(room);
                    if (bay == null) {
                        say(player, "No War Room stands there.");
                    } else if (UNLOAD.equals(message.what())) {
                        bay.clear();
                        say(player, "The manifest is torn up; whatever came stays in the War Room.");
                    } else {
                        final Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(message.text()));
                        if (item == null || item == Items.AIR) {
                            say(player, "No such item: " + message.text());
                        } else {
                            final int changed = bay.add(new ItemStack(item), message.number());
                            final String name = new ItemStack(item).getHoverName().getString();
                            if (changed == 0 && message.number() > 0) {
                                say(player, "The manifest is full: " + ConvoyModule.capStacks() + " stacks at most.");
                            } else {
                                say(player, (changed >= 0 ? changed + " " + name + " put on the manifest - "
                                        : -changed + " " + name + " taken off - ")
                                        + bay.wantedStacks() + " of " + ConvoyModule.capStacks() + " stacks.");
                            }
                        }
                    }
                }
                case HIRE -> {
                    final int count = Math.max(1, message.number());
                    final String no = Campaigns.hire(level, colony, count);
                    say(player, no == null ? count + (count == 1 ? " sellsword hired - " : " sellswords hired - ")
                            + Campaigns.sellswordsWaiting(level, colony.getID()) + " waiting at the War Room." : no);
                }
                default -> {
                    // a verb this version does not know: do nothing rather than guess
                }
            }
        });
    }

    private static void say(final ServerPlayer player, final String text) {
        try {
            MessageUtils.format(Component.literal(text)).sendTo(player);
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[table] could not answer {}: {}", player.getGameProfile().getName(), t.toString());
        }
    }
}
