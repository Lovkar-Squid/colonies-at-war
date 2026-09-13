package me.lovkar.war.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.war.Warfare;
import net.minecraft.resources.ResourceLocation;

/**
 * The war mod's corner of the University's Combat tree (data/colonies_at_war/researches).
 *
 * <p>Same shape as the thieves' branch and Voyager's: a constant per effect id, levels declared in
 * the JSON, strength read from the colony. MineColonies locks both huts for free - it derives the
 * effect id from the block's registry name - so the two {@code effects/blockhut*} files are the
 * whole of the unlock.</p>
 *
 * <p>Everything here is read where it is used rather than cached, because all three are read at
 * most a few times a second: a hiring limit when somebody opens the hut, and a wall score behind
 * its own cache.</p>
 */
public final class WarResearch {

    /** Unlocks the War Room; its strength is the highest level it may be built to. */
    public static final ResourceLocation WAR_ROOM = effect("blockhutwarroom");
    /** Unlocks the Wall Tower, likewise. */
    public static final ResourceLocation WALL_TOWER = effect("blockhutwalltower");
    /** Extra soldiers the War Room may keep, on top of what its level allows. */
    public static final ResourceLocation DRILL = effect("drill");
    /** Extra guards a Wall Tower may keep. */
    public static final ResourceLocation WATCHMEN = effect("watchmen");
    /** Fraction added to what a colony's walls are worth. */
    public static final ResourceLocation MASONRY = effect("masonry");

    private WarResearch() {
    }

    private static ResourceLocation effect(final String name) {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "effects/" + name);
    }

    public static double strength(final IColony colony, final ResourceLocation effect) {
        if (colony == null) {
            return 0.0;
        }
        try {
            return colony.getResearchManager().getResearchEffects().getEffectStrength(effect);
        } catch (final Throwable t) {
            return 0.0;                           // no University, no research manager, no bonus
        }
    }

    /** Whole soldiers, from a building rather than a colony - what the hiring modules have. */
    public static int extra(final IBuilding building, final ResourceLocation effect) {
        if (building == null) {
            return 0;
        }
        return (int) Math.round(strength(building.getColony(), effect));
    }
}
