package me.lovkar.war.campaign;

import com.minecolonies.api.entity.ModEntities;
import net.minecraft.world.entity.EntityType;

import java.util.Locale;

/**
 * What a warband looks like when it stands at somebody's wall.
 *
 * <p>MineColonies' raider mobs, borrowed: they path, fight, respect the raid machinery and are
 * killed by the same guards. A soldier of a colony is a barbarian with a name until this mod has a
 * model of its own, and a server that would rather see shields than furs picks it in the config.</p>
 */
public enum ArmyLook {
    BARBARIAN("barbarian"),
    NORSEMEN("norsemen"),
    PIRATE("pirate"),
    AMAZON("amazon");

    private final String id;

    ArmyLook(final String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ArmyLook of(final String id) {
        if (id != null) {
            for (final ArmyLook look : values()) {
                if (look.id.equalsIgnoreCase(id.trim())) {
                    return look;
                }
            }
        }
        return BARBARIAN;
    }

    public static boolean valid(final Object id) {
        if (!(id instanceof String s)) {
            return false;
        }
        for (final ArmyLook look : values()) {
            if (look.id.equals(s.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }

    public EntityType<?> normal() {
        return switch (this) {
            case BARBARIAN -> ModEntities.BARBARIAN;
            case NORSEMEN -> ModEntities.SHIELDMAIDEN;
            case PIRATE -> ModEntities.PIRATE;
            case AMAZON -> ModEntities.AMAZONSPEARMAN;
        };
    }

    public EntityType<?> archer() {
        return switch (this) {
            case BARBARIAN -> ModEntities.ARCHERBARBARIAN;
            case NORSEMEN -> ModEntities.NORSEMEN_ARCHER;
            case PIRATE -> ModEntities.ARCHERPIRATE;
            case AMAZON -> ModEntities.AMAZON;
        };
    }

    public EntityType<?> boss() {
        return switch (this) {
            case BARBARIAN -> ModEntities.CHIEFBARBARIAN;
            case NORSEMEN -> ModEntities.NORSEMEN_CHIEF;
            case PIRATE -> ModEntities.CHIEFPIRATE;
            case AMAZON -> ModEntities.AMAZONCHIEF;
        };
    }
}
