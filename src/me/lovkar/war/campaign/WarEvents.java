package me.lovkar.war.campaign;

import com.minecolonies.api.colony.colonyEvents.registry.ColonyEventTypeRegistryEntry;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The warband, registered as a MineColonies colony event.
 *
 * <p>Under <b>MineColonies' namespace</b>, on purpose: their event manager saves only the path of
 * an event's type id and reads it back as {@code minecolonies:<path>}, so any other prefix is an
 * event that does not survive a reload. See {@link ArmyRaidEvent}.</p>
 */
public final class WarEvents {
    private WarEvents() {
    }

    public static final DeferredRegister<ColonyEventTypeRegistryEntry> COLONY_EVENTS =
            DeferredRegister.create(CommonMinecoloniesAPIImpl.COLONY_EVENT_TYPES, ArmyRaidEvent.TYPE_ID.getNamespace());

    static {
        COLONY_EVENTS.register(ArmyRaidEvent.TYPE_ID.getPath(),
                () -> new ColonyEventTypeRegistryEntry(ArmyRaidEvent::loadFromNBT, ArmyRaidEvent.TYPE_ID, true));
    }

    public static void register(final IEventBus modEventBus) {
        COLONY_EVENTS.register(modEventBus);
    }
}
