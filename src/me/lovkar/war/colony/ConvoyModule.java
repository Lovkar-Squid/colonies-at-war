package me.lovkar.war.colony;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IAltersRequiredItems;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.WorldUtil;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The loading bay: what the next convoy is to carry, and how much of it has reached the War Room.
 *
 * <p>The ask was two things, and they are the same thing: <i>choose what goes</i>, and <i>the
 * goods have to be brought to the War Room first</i>. So the convoy carries what is on this
 * manifest and nothing else, and the manifest is filled the way every other MineColonies hut is
 * filled - the building asks the request system for what it is short of, a courier walks it over
 * from the warehouse, and it sits in the War Room's racks until the convoy leaves. The player can
 * carry it over himself just as well; the manifest only counts, it does not care who brought it.</p>
 *
 * <p>Only the manifest is taken. The same racks hold the knights' swords and whatever the room
 * keeps in stock, and a convoy that took "everything in the War Room" would take those. And the
 * manifest's goods are kept from the couriers' own pick-ups the way a minimum stock is, so the
 * warehouse does not fetch back on Tuesday what it delivered on Monday.</p>
 */
public class ConvoyModule extends AbstractBuildingModule implements IPersistentModule, ITickingModule, IAltersRequiredItems {

    private static final String TAG_MANIFEST = "convoyManifest";

    /** One line a kind of item; the count is how many are wanted, whatever has arrived. */
    private final List<ItemStack> manifest = new ArrayList<>();

    /** The most a manifest may hold: the biggest convoy there is, the one to an ally. */
    public static int capStacks() {
        return Math.max(1, WarConfig.convoyStacks() * 3);
    }

    /** How many stacks a count of this item is: a line of 100 bread is two. */
    public static int stacksOf(final ItemStack kind, final int count) {
        final int max = Math.max(1, kind.getMaxStackSize());
        return (count + max - 1) / max;
    }

    public List<ItemStack> manifest() {
        return manifest;
    }

    /** The stacks on the manifest, wanted rather than arrived. */
    public int wantedStacks() {
        int stacks = 0;
        for (final ItemStack line : manifest) {
            stacks += stacksOf(line, line.getCount());
        }
        return stacks;
    }

    /** How many of a line have reached the room, capped at the line. */
    public int loaded(final ItemStack line) {
        if (building == null) {
            return 0;
        }
        try {
            return InventoryUtils.hasBuildingEnoughElseCount(building, new ItemStorage(line, true), line.getCount());
        } catch (final Throwable t) {
            return 0;
        }
    }

    /** The stacks that have arrived, over the whole manifest. */
    public int loadedStacks() {
        int stacks = 0;
        for (final ItemStack line : manifest) {
            stacks += stacksOf(line, loaded(line));
        }
        return stacks;
    }

    private ItemStack lineOf(final ItemStack kind) {
        for (final ItemStack line : manifest) {
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(line, kind, false, true)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Put {@code count} more of an item on the manifest, or take some off with a negative count.
     *
     * @return how many were actually added or removed, after the cap
     */
    public int add(final ItemStack kind, final int count) {
        if (kind == null || kind.isEmpty() || count == 0) {
            return 0;
        }
        ItemStack line = lineOf(kind);
        if (count < 0) {
            if (line == null) {
                return 0;
            }
            final int off = Math.min(line.getCount(), -count);
            line.shrink(off);
            if (line.isEmpty()) {
                manifest.remove(line);
            }
            markDirty();
            return -off;
        }
        final int room = capStacks() - wantedStacks() + (line == null ? 0 : stacksOf(line, line.getCount()));
        if (room <= 0) {
            return 0;
        }
        final int have = line == null ? 0 : line.getCount();
        final int most = room * Math.max(1, kind.getMaxStackSize()) - have;
        final int on = Math.max(0, Math.min(count, most));
        if (on == 0) {
            return 0;
        }
        if (line == null) {
            line = kind.copyWithCount(on);
            manifest.add(line);
        } else {
            line.grow(on);
        }
        markDirty();
        return on;
    }

    /** Tear the manifest up; the goods stay where they are, the couriers stop. */
    public void clear() {
        manifest.clear();
        cancelRequests(null);
        markDirty();
    }

    /**
     * Take the manifest's goods out of the room for a convoy, at most {@code stacks} stacks,
     * whatever has arrived. Lines are reduced by what was taken; what has not come stays wanted.
     */
    public List<ItemStack> take(final int stacks) {
        final List<ItemStack> out = new ArrayList<>();
        if (building == null || manifest.isEmpty()) {
            return out;
        }
        final List<IItemHandler> handlers = new ArrayList<>();
        try {
            handlers.addAll(InventoryUtils.getItemHandlersFromProvider(building));
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[convoy] could not open the War Room's racks: {}", t.toString());
            return out;
        }
        lines:
        for (final ItemStack line : new ArrayList<>(manifest)) {
            int taken = 0;
            for (final IItemHandler handler : handlers) {
                for (int slot = 0; slot < handler.getSlots() && taken < line.getCount(); slot++) {
                    if (!ItemStackUtils.compareItemStacksIgnoreStackSize(handler.getStackInSlot(slot), line, false, true)) {
                        continue;
                    }
                    final int room = roomFor(out, line, stacks);
                    if (room <= 0) {
                        reduce(line, taken);
                        break lines;
                    }
                    final ItemStack got = handler.extractItem(slot, Math.min(line.getCount() - taken, room), false);
                    if (got.isEmpty()) {
                        continue;
                    }
                    taken += got.getCount();
                    merge(out, got);
                }
            }
            reduce(line, taken);
        }
        // the couriers are called off for what left; what is still wanted asks again next tick
        cancelRequests(null);
        markDirty();
        Warfare.LOGGER.info("[convoy] {} stack(s) taken out of the War Room for a convoy, {} line(s) still wanted",
                out.size(), manifest.size());
        return out;
    }

    private void reduce(final ItemStack line, final int taken) {
        if (taken <= 0) {
            return;
        }
        line.shrink(taken);
        if (line.isEmpty()) {
            manifest.remove(line);
        }
    }

    /** How many more of this kind fit under a cap of {@code stacks} stacks: whole free stacks and the space in half-full ones. */
    private static int roomFor(final List<ItemStack> goods, final ItemStack kind, final int stacks) {
        final int max = Math.max(1, kind.getMaxStackSize());
        int room = Math.max(0, stacks - goods.size()) * max;
        for (final ItemStack stack : goods) {
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, kind, false, true)) {
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }

    private static void merge(final List<ItemStack> goods, ItemStack got) {
        for (final ItemStack stack : goods) {
            if (got.isEmpty()) {
                return;
            }
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, got, false, true) && stack.getCount() < stack.getMaxStackSize()) {
                final int fits = Math.min(got.getCount(), stack.getMaxStackSize() - stack.getCount());
                stack.grow(fits);
                got.shrink(fits);
            }
        }
        if (!got.isEmpty()) {
            goods.add(got);
        }
    }

    // ------------------------------------------------------------------ the couriers

    /**
     * Every colony tick: ask for what is short, the way a minimum stock does, and stop asking for
     * what has come or is no longer wanted. One open request a line, for the whole of the shortfall.
     */
    @Override
    public void onColonyTick(final @NotNull IColony colony) {
        if (building == null || !WorldUtil.isBlockLoaded(colony.getWorld(), building.getPosition())) {
            return;
        }
        try {
            final List<IRequest<? extends Stack>> open = openRequests();
            for (final ItemStack line : manifest) {
                final int short_ = line.getCount() - loaded(line);
                final IRequest<? extends Stack> request = matching(open, line);
                if (short_ > 0 && request == null) {
                    final Stack ask = new Stack(line.copyWithCount(Math.min(short_, line.getMaxStackSize())), false, true,
                            ItemStackUtils.EMPTY, short_, 1);
                    final IToken<?> token = building.createRequest(ask, true);
                    Warfare.LOGGER.info("[convoy] {}'s War Room asks the couriers for {} x{} for the manifest{}",
                            colony.getName(), line.getHoverName().getString(), short_, token == null ? " - refused" : "");
                } else if (short_ <= 0 && request != null) {
                    colony.getRequestManager().updateRequestState(request.getId(), RequestState.CANCELLED);
                    Warfare.LOGGER.info("[convoy] {}'s War Room has its {} - the couriers are called off",
                            colony.getName(), line.getHoverName().getString());
                }
            }
            // a request for something no longer on the manifest
            for (final IRequest<? extends Stack> request : open) {
                if (lineOf(request.getRequest().getStack()) == null) {
                    colony.getRequestManager().updateRequestState(request.getId(), RequestState.CANCELLED);
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[convoy] the manifest could not ask for its goods: {}", t.toString());
        }
    }

    private List<IRequest<? extends Stack>> openRequests() {
        final List<IRequest<? extends Stack>> out = new ArrayList<>();
        final Map<TypeToken<?>, Collection<IToken<?>>> byType = building.getOpenRequestsByRequestableType();
        for (final Map.Entry<TypeToken<?>, Collection<IToken<?>>> entry : byType.entrySet()) {
            for (final IToken<?> token : entry.getValue()) {
                final IRequest<?> request = building.getColony().getRequestManager().getRequestForToken(token);
                if (request != null && request.getRequest() instanceof Stack) {
                    @SuppressWarnings("unchecked")
                    final IRequest<? extends Stack> stack = (IRequest<? extends Stack>) request;
                    out.add(stack);
                }
            }
        }
        return out;
    }

    private static IRequest<? extends Stack> matching(final List<IRequest<? extends Stack>> open, final ItemStack line) {
        for (final IRequest<? extends Stack> request : open) {
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(request.getRequest().getStack(), line, false, true)) {
                return request;
            }
        }
        return null;
    }

    /** Withdraw the open requests - all of them, or the one line's. */
    private void cancelRequests(final ItemStack only) {
        if (building == null) {
            return;
        }
        try {
            for (final IRequest<? extends Stack> request : openRequests()) {
                if (only == null || ItemStackUtils.compareItemStacksIgnoreStackSize(request.getRequest().getStack(), only, false, true)) {
                    building.getColony().getRequestManager().updateRequestState(request.getId(), RequestState.CANCELLED);
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[convoy] could not call the couriers off: {}", t.toString());
        }
    }

    /** The manifest's goods are the room's to keep, not the warehouse's to fetch back. */
    @Override
    public void alterItemsToBeKept(final TriConsumer<Predicate<ItemStack>, Integer, Boolean> consumer) {
        for (final ItemStack line : manifest) {
            final ItemStack kind = line.copyWithCount(1);
            consumer.accept(stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack, kind, false, true),
                    line.getCount(), false);
        }
    }

    // ------------------------------------------------------------------ saved and sent

    @Override
    public void serializeNBT(final HolderLookup.@NotNull Provider provider, final @NotNull CompoundTag tag) {
        final ListTag list = new ListTag();
        for (final ItemStack line : manifest) {
            if (!line.isEmpty()) {
                list.add(line.save(provider));
            }
        }
        tag.put(TAG_MANIFEST, list);
    }

    @Override
    public void deserializeNBT(final HolderLookup.@NotNull Provider provider, final @NotNull CompoundTag tag) {
        manifest.clear();
        final ListTag list = tag.getList(TAG_MANIFEST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final ItemStack line = ItemStack.parseOptional(provider, list.getCompound(i));
            if (!line.isEmpty()) {
                manifest.add(line);
            }
        }
    }

    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(capStacks());
        buf.writeVarInt(manifest.size());
        for (final ItemStack line : manifest) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, line);
            buf.writeVarInt(loaded(line));
        }
    }

    /** The War Room's bay, or null when the colony has no War Room. */
    public static ConvoyModule of(final IBuilding room) {
        if (room == null) {
            return null;
        }
        try {
            return room.getFirstModuleOccurance(ConvoyModule.class);
        } catch (final Throwable t) {
            return null;
        }
    }
}
