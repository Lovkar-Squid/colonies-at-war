package me.lovkar.war.campaign;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import me.lovkar.war.Warfare;
import me.lovkar.war.colony.Standing;
import me.lovkar.war.wall.WallRegister;
import net.minecraft.util.RandomSource;

import java.util.Collection;

/**
 * A battle as a number, for the nights nobody is there to watch.
 *
 * <p>Two sides, one roll, the thief's own shape: luck <b>scales with the size of the fight</b>, so
 * a warband of twelve against a walled town is a real gamble and two men against a hamlet is not.
 * The defence is the same arithmetic the Hideout already shows as "an army against 400" - guards at
 * home count four, the military buildings two, the wall once, and the raid level a tenth - so the
 * number a thief reads on the board is the number the army marches against.</p>
 *
 * @param attack   what marched
 * @param defence  what stood
 * @param luck     the roll, already added to the attack
 * @param won      whether the town was overrun
 * @param margin   how decisively, -1..1
 */
public record Battle(int attack, int defence, int luck, boolean won, float margin) {

    // ------------------------------------------------------------------ the sides

    /** One guard's worth in a fight: four for showing up, up to ten more for knowing how. */
    public static int worth(final ICitizenData guard) {
        int combat = 0;
        try {
            final int adapt = guard.getCitizenSkillHandler().getLevel(Skill.Adaptability);
            final int arms = Math.max(guard.getCitizenSkillHandler().getLevel(Skill.Strength),
                    guard.getCitizenSkillHandler().getLevel(Skill.Agility));
            combat = (adapt + arms) / 2;
        } catch (final Throwable ignored) {
            // a citizen whose skills cannot be read fights as a recruit
        }
        return 4 + Math.min(10, combat / 10);
    }

    /** What a warband is worth: its men, the room that planned it, and whether it ate. */
    public static int attack(final Collection<ICitizenData> warband, final IBuilding warRoom, final boolean hungry) {
        int sum = 0;
        for (final ICitizenData guard : warband) {
            sum += worth(guard);
        }
        sum += warRoom == null ? 0 : warRoom.getBuildingLevel() * 2;
        return hungry ? sum * 3 / 4 : sum;
    }

    /** What stands at home: guards ×4, the military buildings ×2, the wall, a tenth of the raid level. */
    public static int defence(final IColony colony) {
        if (colony == null) {
            return 0;
        }
        int guards = 0;
        int military = 0;
        try {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                if (building.getBuildingLevel() <= 0 || !(building instanceof AbstractBuildingGuards post)) {
                    continue;
                }
                military += building.getBuildingLevel();
                try {
                    for (final ICitizenData guard : post.getAllAssignedCitizen()) {
                        if (Standing.isGuard(guard) && !Standing.onCampaign(guard)
                                && !colony.getTravellingManager().isTravelling(guard)) {
                            guards += worth(guard);
                        }
                    }
                } catch (final Throwable ignored) {
                    // a guard building this build cannot read: its men are not counted, its level is
                }
            }
        } catch (final Throwable t) {
            Warfare.LOGGER.warn("[battle] cannot read {}: {}", colony.getName(), t.toString());
        }
        final int walls = Math.max(0, WallRegister.score(colony));
        int raidLevel = 0;
        try {
            raidLevel = colony.getRaiderManager().getColonyRaidLevel();
        } catch (final Throwable ignored) {
            // no raid level to read
        }
        return guards + military * 2 + walls + raidLevel / 10;
    }

    // ------------------------------------------------------------------ the roll

    /** The fight nobody watched. */
    public static Battle roll(final int attack, final int defence, final RandomSource random) {
        final int swing = Math.max(24, Math.round(0.35f * (attack + defence)));
        final int luck = random.nextInt(2 * swing + 1) - swing;
        final int total = attack + luck;
        final boolean won = total > defence;
        final float margin = clamp((total - defence) / (float) Math.max(1, attack + defence));
        return new Battle(attack, defence, luck, won, margin);
    }

    /**
     * The fight that was watched: the raid ran its night, and this many soldiers were still
     * standing at the end of it. Half the warband alive when the sun comes up is a town overrun;
     * fewer is a defence that held.
     */
    public static Battle fought(final int attack, final int defence, final int alive, final int marched) {
        final float share = marched <= 0 ? 0f : Math.min(1f, alive / (float) marched);
        final boolean won = share >= 0.5f;
        final float margin = clamp((share - 0.5f) * 2f);
        return new Battle(attack, defence, 0, won, margin);
    }

    private static float clamp(final float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    /** How this reads in the log. */
    public String report() {
        return report("the town");
    }

    /** How this reads in the log, for a place that is not a town: "overran the camp". */
    public String report(final String what) {
        return (won ? "overran " + what : "was thrown back")
                + " (" + attack + (luck != 0 ? (luck > 0 ? " +" : " ") + luck : "") + " against " + defence + ")";
    }
}
