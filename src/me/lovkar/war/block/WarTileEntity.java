package me.lovkar.war.block;

import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Block entity of both huts. It exists so our hut blocks are valid for a type we own. */
public class WarTileEntity extends TileEntityColonyBuilding {
    public WarTileEntity(final BlockPos pos, final BlockState state) {
        super(Warfare.BUILDING_BE.get(), pos, state);
    }
}
