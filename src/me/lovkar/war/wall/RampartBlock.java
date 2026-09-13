package me.lovkar.war.wall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * The run of the wall itself, with a walk on top. Three of these exist - a palisade of logs, a
 * stone wall, a fortified wall of brick and iron - and they differ only in tier, because a wall's
 * strength should come from what it is made of and nothing else.
 *
 * <p>It is a plain solid block on purpose. Raiders can break it, slowly; engines can break it
 * faster. A wall nobody can lose is a wall nobody bothers attacking.</p>
 */
public class RampartBlock extends Block implements WallPiece {
    private final int tier;

    public RampartBlock(final Properties properties, final int tier) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public int tier() {
        return tier;
    }

    @Override
    public void onPlace(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                        final @NotNull BlockState old, final boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (level instanceof ServerLevel server && !old.is(this)) {
            WallRegister.laid(server, pos, this);
        }
    }

    @Override
    public void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                         final @NotNull BlockState now, final boolean moving) {
        if (level instanceof ServerLevel server && !now.is(this)) {
            WallRegister.pulled(server, pos);
        }
        super.onRemove(state, level, pos, now, moving);
    }
}
