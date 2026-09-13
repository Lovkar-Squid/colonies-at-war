package me.lovkar.war.block;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.war.Warfare;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The hut block of the Wall Tower. */
public class BlockHutWallTower extends AbstractBlockHut<BlockHutWallTower> {

    @Override
    public String getHutName() {
        return Warfare.WALL_TOWER_HUT;
    }

    @Override
    public BuildingEntry getBuildingEntry() {
        return Warfare.WALL_TOWER.get();
    }

    @Override
    public @NotNull ResourceLocation getRegistryName() {
        return ResourceLocation.fromNamespaceAndPath(Warfare.MODID, Warfare.WALL_TOWER_HUT);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        final TileEntityColonyBuilding te = Warfare.BUILDING_BE.get().create(pos, state);
        if (te != null) {
            te.registryName = getBuildingEntry().getRegistryName();
        }
        return te;
    }
}
