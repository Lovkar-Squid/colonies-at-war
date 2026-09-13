package me.lovkar.war.wall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * The crenellations a guard stands behind.
 *
 * <p>Deliberately <b>not</b> a full block: MineColonies' pathfinding will refuse a walk that is
 * one block wide and walled in, and a guard who will not climb onto the wall is the fastest way to
 * make the whole system look broken. So the parapet is knee-high to a citizen, the walk beside it
 * is clear, and the patrol points are laid on the rampart, never here.</p>
 */
public class ParapetBlock extends Block implements WallPiece {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 10, 16);

    private final int tier;

    public ParapetBlock(final Properties properties, final int tier) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public int tier() {
        return tier;
    }

    /** It is the height of the wall, not its body - worth a third of a rampart. */
    @Override
    public float weight() {
        return 0.35f;
    }

    @Override
    public boolean walkable() {
        return false;
    }

    @Override
    public @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                        final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPE;
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
