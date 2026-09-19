package me.lovkar.war.ai;

import com.minecolonies.api.colony.ColonyState;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.IGuardBuilding;
import com.minecolonies.api.colony.colonyEvents.IColonyEntitySpawnEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.entity.ai.combat.CombatAIStates;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.colony.requestsystem.locations.StaticLocation;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import me.lovkar.war.WarConfig;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.WarResearch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Find the Raiders research: while a town is raided, its guards are told where the raiders are.
 *
 * <p>Without it a MineColonies guard finds a raider the way anyone does - by seeing him, within
 * thirty blocks and in line of sight, on a patrol that wanders between the town's own buildings.
 * A raid that comes in from the far side of a big town can be at the warehouse before a single
 * guard tower has noticed. With the research, every three seconds of a raid:</p>
 *
 * <ul>
 *   <li>every guard building on <b>patrol</b> has its next patrol point set to the raider nearest
 *       it, through MineColonies' own {@code setTempNextPatrolPoint} - the same door the guards use
 *       to call each other, so the patrol code walks them there itself, and a rallying banner or a
 *       follow order still wins;</li>
 *   <li>every guard who is idle or napping and has a raider within reach is set on him through
 *       {@code startHelpCitizen}, which is what a citizen's cry for help does: threat on the
 *       table, the fight begun, the nap over;</li>
 *   <li>a guard told to <b>stand</b> somewhere keeps his ground - MineColonies' rule, kept - with
 *       one exception: a raider who has got <b>behind</b> him, nearer the Town Hall than his post,
 *       is his. His post and rally are moved onto that raider until none is behind him, then put
 *       back where they were. This is how the bell meets Colonist Errands' defensive line: the
 *       line stands, and whatever slips through it is hunted;</li>
 *   <li>a guard still on his way runs.</li>
 * </ul>
 *
 * <p>Nothing here is a new AI. It is the alarm bell: the information the guards were missing,
 * handed to code that already knew what to do with it.</p>
 */
public final class RaidAlarm {
    private RaidAlarm() {
    }

    /** How often the bell rings, in ticks. Guards decide every hundred; three seconds is plenty. */
    private static final int EVERY = 60;
    /**
     * How close a raider must be before an idle guard is set on him directly, in blocks: inside
     * the eighty a patrolling guard will chase from where he stands, with room for the raider to
     * move before the guard's next look.
     */
    private static final double REACH = 72.0;
    private static final String PATROL = "com.minecolonies.core.guard.setting.patrol";
    private static final String GUARD = "com.minecolonies.core.guard.setting.guard";
    /** How far inside a post's line a raider must be before the post goes after him, in blocks. */
    private static final double BEHIND = 12.0;
    /** With Hue and Cry, a posted guard also goes after any raider this near his post. */
    private static final double EARSHOT = 48.0;
    private static final String FOLLOW = "com.minecolonies.core.guard.setting.follow";
    private static final String PATROL_MINE = "com.minecolonies.core.guard.setting.patrol_mine";

    private static int ticks = 0;
    /** Raids already announced, by colony: the bell is rung once per raid, not every three seconds. */
    private static final Map<Integer, Set<Integer>> rung = new HashMap<>();
    private static boolean warned = false;
    /**
     * A post the bell has moved onto a raider: where it was and what rally it had, to put back,
     * and where the bell last put it, so that orders given since - by the player, or by another
     * mod standing its line down - are recognised as somebody else's and left alone.
     */
    private static final class Held {
        final IGuardBuilding building;
        final BlockPos post;
        final ILocation rally;
        BlockPos at;
        /** Whether the bell set a rally of its own (only inside the colony: MineColonies clears one outside it). */
        boolean rallied;

        Held(final IGuardBuilding building, final BlockPos post, final ILocation rally) {
            this.building = building;
            this.post = post;
            this.rally = rally;
        }

        IGuardBuilding building() {
            return building;
        }

        BlockPos post() {
            return post;
        }

        ILocation rally() {
            return rally;
        }

        /**
         * Whether the orders the building has now are still the bell's: the rally it set, or no
         * rally at all when it set none. Anything else is somebody else's, and stands.
         */
        boolean ours(final ILocation now) {
            if (at == null) {
                return true;
            }
            if (rallied) {
                return now != null && at.equals(now.getInDimensionLocation());
            }
            return now == null;
        }
    }

    private static final Map<BlockPos, Held> moved = new HashMap<>();

    public static void onServerTick(final ServerTickEvent.Post event) {
        if (++ticks % EVERY != 0 || !WarConfig.findTheRaiders()) {
            return;
        }
        for (final ServerLevel level : event.getServer().getAllLevels()) {
            final List<IColony> colonies;
            try {
                colonies = IColonyManager.getInstance().getColonies(level);
            } catch (final Throwable t) {
                continue;
            }
            for (final IColony colony : colonies) {
                try {
                    ring(level, colony);
                } catch (final Throwable t) {
                    if (!warned) {
                        warned = true;
                        Warfare.LOGGER.warn("[alarm] the raid alarm failed in {}: {}", colony.getName(), t.toString(), t);
                    }
                }
            }
        }
    }

    /** Forgotten with the world. */
    public static void forget() {
        rung.clear();
        moved.clear();
    }

    /** The server is stopping: every moved post goes back before the colony is saved. */
    public static void standDown() {
        for (final Held held : new ArrayList<>(moved.values())) {
            restore(held);
        }
        moved.clear();
    }

    // ------------------------------------------------------------------ the bell

    private static void ring(final ServerLevel level, final IColony colony) {
        if (colony.getState() != ColonyState.ACTIVE || WarResearch.strength(colony, WarResearch.FIND_RAIDERS) <= 0) {
            allClear(colony);
            return;
        }
        final Set<Integer> raids = new HashSet<>();
        final List<LivingEntity> raiders = raiders(colony, raids);
        if (raids.isEmpty()) {
            allClear(colony);
            return;
        }
        announce(colony, raids);
        if (raiders.isEmpty()) {
            // preparing, the horde not in the world yet, or every raider dead with the raid not
            // yet told so: nobody to hunt, so any post the bell moved goes back now
            restoreAll(colony);
            return;
        }
        final boolean hueAndCry = WarResearch.strength(colony, WarResearch.HUE_AND_CRY) > 0;
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (!(building instanceof IGuardBuilding post) || building.getAllAssignedCitizen().isEmpty()) {
                continue;
            }
            final String task = post.getTask();
            if (FOLLOW.equals(task) || PATROL_MINE.equals(task)) {
                continue;                           // the player is already telling them where to be
            }
            if (GUARD.equals(task)) {
                hunt(level, colony, building, post, raiders, hueAndCry);
            } else if (post.getRallyLocation() != null) {
                continue;                           // a banner is raised: the player leads them
            } else if (PATROL.equals(task)) {
                final LivingEntity nearest = nearest(raiders, building.getPosition());
                if (nearest != null) {
                    point(post, nearest.blockPosition());
                }
            }
            for (final ICitizenData data : building.getAllAssignedCitizen()) {
                wake(data, raiders, PATROL.equals(task));
            }
        }
    }

    /**
     * The raid is over, or the bell no longer applies: the point the bell left with every
     * patrolling building is taken back, so the next patrol is an ordinary one and not one more
     * walk to wherever the last raider fell.
     */
    private static void allClear(final IColony colony) {
        if (rung.remove(colony.getID()) == null) {
            return;                                 // the bell never rang here
        }
        try {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                if (!(building instanceof IGuardBuilding post)) {
                    continue;
                }
                if (PATROL.equals(post.getTask())) {
                    post.setTempNextPatrolPoint(null);
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[alarm] could not stand {} down: {}", colony.getName(), t.toString());
        }
        restoreAll(colony);
    }

    /** Every post the bell moved in this colony goes back. */
    private static void restoreAll(final IColony colony) {
        try {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                final Held held = moved.remove(building.getID());
                if (held != null) {
                    restore(held);
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[alarm] could not put {}'s posts back: {}", colony.getName(), t.toString());
        }
    }

    /**
     * A posted building: if a raider is nearer the Town Hall than its post, the post (and a rally,
     * which is what lifts a posted guard's reach to thirty blocks and makes him run) is moved onto
     * the nearest such raider; when nobody is behind the line any more, both go back.
     */
    private static void hunt(final ServerLevel level, final IColony colony, final IBuilding building,
                             final IGuardBuilding post, final List<LivingEntity> raiders, final boolean earshot) {
        AbstractEntityCitizen any = null;
        for (final ICitizenData data : building.getAllAssignedCitizen()) {
            any = data.getEntity().orElse(null);
            if (any != null) {
                break;
            }
        }
        if (any == null) {
            return;                                 // nobody home to move
        }
        final Held held = moved.get(building.getID());
        final BlockPos home = held != null ? held.post() : post.getGuardPos(any);
        if (home == null) {
            return;
        }
        final BlockPos centre = colony.getCenter();
        final double line = flat(home, centre);
        LivingEntity behind = null;
        double best = Double.MAX_VALUE;
        for (final LivingEntity r : raiders) {
            final double d = r.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            // behind the line: nearer the Town Hall than the post is; or, with the second bell
            // researched, simply within earshot of the post
            if (flat(r.blockPosition(), centre) + BEHIND < line || (earshot && d < EARSHOT * EARSHOT)) {
                if (d < best) {
                    best = d;
                    behind = r;
                }
            }
        }
        if (behind == null) {
            if (held != null) {
                moved.remove(building.getID());
                restore(held);
            }
            return;
        }
        if (held == null) {
            moved.put(building.getID(), new Held(post, home, post.getRallyLocation()));
            Warfare.LOGGER.info("[alarm] {}: the post at {} goes after a raider {}", colony.getName(), home.toShortString(),
                    flat(behind.blockPosition(), centre) + BEHIND < line ? "behind it" : "within earshot");
        }
        final Held now = moved.get(building.getID());
        if (now.at != null && !now.ours(post.getRallyLocation())) {
            moved.remove(building.getID());          // somebody gave other orders since: theirs stand
            return;
        }
        final BlockPos at = behind.blockPosition();
        final BlockPos current = post.getGuardPos(any);
        if (current == null || current.distSqr(at) > 36) {
            post.setGuardPos(at);
            // a rally lifts a posted guard's reach to thirty blocks and makes him run - but
            // MineColonies clears a rally outside the colony's own ground, so out there the
            // post alone is moved and the guard fights within his post's reach
            if (colony.isCoordInColony(level, at)) {
                post.setRallyLocation(new StaticLocation(at, level.dimension()));
                now.rallied = true;
            } else {
                post.setRallyLocation(null);
                now.rallied = false;
            }
            now.at = at;
        }
    }

    /**
     * Put a post back - unless somebody else has given the building orders since the bell moved
     * it (the player, or a mod standing its own line down), in which case those orders stand and
     * only the bell's rally, if it is still the one set, is taken away.
     */
    private static void restore(final Held held) {
        try {
            final IGuardBuilding building = held.building();
            final ILocation now = building.getRallyLocation();
            if (held.at != null && !held.ours(now)) {
                return;                             // not our rally any more: not our post to move
            }
            if (!GUARD.equals(building.getTask())) {
                if (held.ours(now)) {
                    building.setRallyLocation(null);
                }
                return;                             // the task changed under us: the new orders stand
            }
            building.setGuardPos(held.post());
            building.setRallyLocation(held.rally());
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[alarm] could not put a post back: {}", t.toString());
        }
    }

    private static double flat(final BlockPos a, final BlockPos b) {
        final double dx = a.getX() - b.getX();
        final double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Point a patrolling building at the raider. The next point is always set, so that a patrol
     * that ends picks the raider again rather than a random building; the current one is only
     * replaced when the raider has moved, because every replacement is a new path for every
     * guard on it.
     */
    private static void point(final IGuardBuilding post, final BlockPos at) {
        final BlockPos current = post.getNextPatrolTarget(false);
        if (current == null || current.distSqr(at) > 36) {
            post.setTempNextPatrolPoint(at);
            post.getNextPatrolTarget(true);         // takes it up now
        }
        post.setTempNextPatrolPoint(at);            // ...and again when this patrol ends
    }

    /** One idle guard: set on the nearest raider if he is within reach, else sent running. */
    private static void wake(final ICitizenData data, final List<LivingEntity> raiders, final boolean patrolling) {
        final AbstractEntityCitizen guard = data.getEntity().orElse(null);
        if (guard == null || !guard.isAlive()) {
            return;                                 // away on campaign, held, or not in the world
        }
        final AbstractJobGuard<?> job = data.getJob(AbstractJobGuard.class);
        if (job == null || !(job.getWorkerAI() instanceof AbstractEntityAIGuard<?, ?> ai)) {
            return;
        }
        final IState state = ai.getState();
        final boolean napping = state == AIWorkerState.GUARD_SLEEP;
        if (state != CombatAIStates.NO_TARGET && !napping) {
            return;                                 // fighting, fleeing, eating, or off duty
        }
        final LivingEntity raider = nearest(raiders, guard.blockPosition());
        if (raider == null) {
            return;
        }
        final boolean near = guard.distanceToSqr(raider) <= REACH * REACH;
        if (near || napping) {
            // a posted guard's canHelp keeps him to his own reach; a napping guard is woken either
            // way, and if the raider is too far to chase he falls back to the patrol point above
            ai.startHelpCitizen(raider);
        }
        if (patrolling && !near && !guard.hasEffect(MobEffects.MOVEMENT_SPEED)) {
            guard.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 0, false, false));
        }
    }

    /** The raiders of every raid the colony is fighting right now, and those raids' ids. */
    private static List<LivingEntity> raiders(final IColony colony, final Set<Integer> raids) {
        final List<LivingEntity> out = new ArrayList<>();
        for (final IColonyEvent event : colony.getEventManager().getEvents().values()) {
            if (!(event instanceof IColonyRaidEvent raid) || !raid.isRaidActive()) {
                continue;
            }
            raids.add(event.getID());
            if (event instanceof IColonyEntitySpawnEvent spawned) {
                for (final Entity e : spawned.getEntities()) {
                    if (e instanceof AbstractEntityMinecoloniesRaider r && r.isAlive()) {
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    private static LivingEntity nearest(final List<LivingEntity> raiders, final BlockPos to) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (final LivingEntity r : raiders) {
            final double d = r.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return best;
    }

    /** Once per raid: the town is told its guards have been called. */
    private static void announce(final IColony colony, final Set<Integer> raids) {
        final Set<Integer> seen = rung.computeIfAbsent(colony.getID(), k -> new HashSet<>());
        seen.retainAll(raids);
        boolean fresh = false;
        for (final Integer id : raids) {
            if (seen.add(id)) {
                fresh = true;
            }
        }
        if (!fresh) {
            return;
        }
        int guards = 0;
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (building instanceof IGuardBuilding) {
                for (final ICitizenData data : building.getAllAssignedCitizen()) {
                    if (data.getEntity().isPresent()) {
                        guards++;
                    }
                }
            }
        }
        try {
            MessageUtils.format(Component.literal("The alarm is raised in " + colony.getName() + ": "
                            + (guards == 1 ? "one guard goes" : guards + " guards go") + " looking for the raiders."))
                    .withPriority(MessageUtils.MessagePriority.IMPORTANT).sendTo(colony).forManagers();
        } catch (final Throwable t) {
            Warfare.LOGGER.debug("[alarm] could not message {}: {}", colony.getName(), t.toString());
        }
        Warfare.LOGGER.info("[alarm] {} - {} guards called to {} raid(s)", colony.getName(), guards, raids.size());
    }
}
