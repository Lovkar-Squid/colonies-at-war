package me.lovkar.war.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.GuardPatrolModeSetting;
import com.minecolonies.core.colony.buildings.modules.settings.SettingKey;
import me.lovkar.war.Warfare;
import me.lovkar.war.wall.WallPiece;
import me.lovkar.war.wall.WallRegister;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The Wall Tower: a guard post that stands on a wall, and whose men walk it.
 *
 * <p>MineColonies already has everything this needs, in public API and without a single mixin.
 * {@code addPatrolTarget}, {@code resetPatrolTargets} and the {@code PATROL_MODE} setting with its
 * MANUAL value are what the guard AI reads; <b>a wall patrol is simply a list of patrol points
 * laid along the walk.</b> The tower supplies the points; MineColonies does all the walking, the
 * watching and the fighting.</p>
 *
 * <p>The points come out of the {@link WallRegister}, not out of the world - the register already
 * knows the shape of the wall, so the tower never scans anything. A point is dropped every
 * {@link #SPACING} blocks along the run, on the walk above a piece guards can actually stand on,
 * which is why the parapet is deliberately not a full block.</p>
 *
 * <p>Its level is how many guards it holds, the same idiom as every other guard tower - see
 * {@link WarModules}. A man on a wall also sees further, which is the real reason to build one
 * rather than another tower on the ground.</p>
 */
public class BuildingWallTower extends AbstractBuildingGuards {
    private static final String HUT_NAME = "walltower";
    private static final int MAX_LEVEL = 5;

    /**
     * Whether this tower's men walk the wall.
     *
     * <p>On, the tower lays a line of patrol points along the wall it stands on and puts the guards
     * on manual patrol, which is the whole point of building up there. Off, it is an ordinary guard
     * tower with a good view and MineColonies decides where the men go - which is what you want for
     * a tower on a gate, or while a stretch of wall is still being built.</p>
     */
    public static final ISettingKey<BoolSetting> WALL_PATROL =
            new SettingKey<>(BoolSetting.class,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Warfare.MODID, "wallpatrol"));

    /** A patrol point every this many blocks of wall. */
    private static final int SPACING = 8;
    /** At most this much wall is followed out from one tower. */
    private static final int REACH = 240;
    /** The patrol is worked out again this often, so a wall that grows is walked. */
    private static final int RELAY_TICKS = 2400;

    private long relaidAt = -RELAY_TICKS;
    /** Whether the points currently on this tower are ours, so switching off can take them away. */
    private boolean laidWall = false;

    public BuildingWallTower(final IColony colony, final BlockPos pos) {
        super(colony, pos);
    }

    /**
     * Put {@link #WALL_PATROL} into MineColonies' own settings module.
     *
     * <p>Not a settings module of our own, which is what the first attempt did: the guard AI calls
     * {@code getModule(BuildingModules.GUARD_SETTINGS)} with that exact producer object, so a
     * building that carries a substitute gets a null back and throws an NPE out of
     * {@code CitizenAI.shouldEat} every tick. The module has to be theirs; only the row is ours.</p>
     *
     * <p>Called before the module reads its NBT, because {@code SettingsModule.deserializeNBT}
     * restores a saved value only for a key the map already holds - add the setting afterwards and
     * every reload silently resets it to the default.</p>
     */
    private void addWallSetting() {
        final com.minecolonies.api.colony.buildings.modules.ISettingsModule settings =
                getModule(com.minecolonies.core.colony.buildings.modules.BuildingModules.GUARD_SETTINGS);
        if (settings != null && settings.getOptionalSetting(WALL_PATROL).isEmpty()) {
            settings.with(WALL_PATROL, new BoolSetting(true));
        }
    }

    @Override
    public void deserializeNBT(final net.minecraft.core.HolderLookup.@NotNull Provider provider,
                               final net.minecraft.nbt.@NotNull CompoundTag compound) {
        addWallSetting();
        super.deserializeNBT(provider, compound);
    }

    @Override
    public void onPlacement() {
        super.onPlacement();
        addWallSetting();
    }

    /**
     * Also here, and deliberately: this is the one call that is guaranteed to happen before the
     * player sees the settings tab, whatever the colony's tick schedule has been doing.
     */
    @Override
    public void serializeToView(final net.minecraft.network.@NotNull RegistryFriendlyByteBuf buf,
                                final boolean fullSync) {
        addWallSetting();
        super.serializeToView(buf, fullSync);
    }

    @Override
    public @NotNull String getSchematicName() {
        return HUT_NAME;
    }

    @Override
    public int getMaxBuildingLevel() {
        return MAX_LEVEL;
    }

    /** A man on a wall sees further. This, and not the score, is the reason to build up there. */
    @Override
    public int getBonusVision() {
        return Math.min(10, getBuildingLevel() * 2);
    }

    @Override
    public int getClaimRadius(final int level) {
        return level >= 3 ? 1 : 0;
    }

    @Override
    public void onUpgradeComplete(final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint, final int level) {
        super.onUpgradeComplete(blueprint, level);
        relaidAt = -RELAY_TICKS;
    }

    @Override
    public void onColonyTick(final @NotNull IColony colony) {
        super.onColonyTick(colony);
        addWallSetting();
        if (!(colony.getWorld() instanceof ServerLevel level)) {
            return;
        }
        final long now = level.getGameTime();
        if (now - relaidAt < RELAY_TICKS) {
            return;
        }
        relaidAt = now;
        layPatrol(level, colony);
    }

    /**
     * Work out where the guards walk, and tell MineColonies.
     *
     * <p>Nothing here fights the game's own systems: the list goes in through the public
     * {@code addPatrolTarget}, and the mode is set to manual so the guard AI walks it in order.
     * Turning the mode back to automatic in the hut window leaves an ordinary guard tower - the
     * choice stays the player's.</p>
     */
    public void layPatrol(final ServerLevel level, final IColony colony) {
        if (!getSettingValueOrDefault(WALL_PATROL, true)) {
            // switched off: hand the tower back to MineColonies, points and all
            if (laidWall) {
                resetPatrolTargets();
                laidWall = false;
                markDirty();
            }
            return;
        }
        final List<BlockPos> run = WallRegister.run(level, colony.getID(), getPosition(), REACH);
        if (run.isEmpty()) {
            return;
        }
        final List<BlockPos> points = new ArrayList<>();
        int since = SPACING;
        for (final BlockPos at : run) {
            if (++since < SPACING) {
                continue;
            }
            final BlockPos walk = walkAbove(level, at);
            if (walk == null) {
                continue;
            }
            points.add(walk);
            since = 0;
        }
        if (points.isEmpty()) {
            return;
        }
        resetPatrolTargets();
        for (final BlockPos point : points) {
            addPatrolTarget(point);
        }
        laidWall = true;
        // set, not trigger: trigger() flips to the next value, so calling it on every relay
        // walked the mode back and forth between automatic and manual every two minutes.
        final GuardPatrolModeSetting mode = getSetting(PATROL_MODE);
        if (mode != null && !GuardPatrolModeSetting.MANUAL.equals(mode.getValue())) {
            mode.set(GuardPatrolModeSetting.MANUAL);
            markDirty();
        }
        Warfare.LOGGER.info("[wall] tower at {} walks {} point(s) of wall", getPosition(), points.size());
    }

    /** How many points of wall this tower is currently walking. */
    public int wallPoints() {
        return patrolTargets.size();
    }

    /** The walk above a wall block, if a citizen can stand there. */
    private BlockPos walkAbove(final ServerLevel level, final BlockPos wall) {
        final BlockState state = level.getBlockState(wall);
        if (!(state.getBlock() instanceof WallPiece piece) || !piece.walkable()) {
            return null;
        }
        final BlockPos above = wall.above();
        if (!level.getBlockState(above).isAir() || !level.getBlockState(above.above()).isAir()) {
            return null;
        }
        return above;
    }

    /**
     * The client's half of a guard building.
     *
     * <p>MineColonies' own settings window casts the building view to
     * {@link AbstractBuildingGuards.View} the moment a guard setting renders - the task dropdown
     * reads the patrol targets and the range off it. Hand the client an {@code EmptyView} and the
     * cast throws the moment the player opens the settings tab, which is how this crashed his game.
     * Their own guard tower's view is the same empty subclass; the point is the type, not the
     * contents.</p>
     */
    public static class View extends AbstractBuildingGuards.View {
        public View(final com.minecolonies.api.colony.IColonyView colony,
                    final BlockPos at) {
            super(colony, at);
        }
    }

}
