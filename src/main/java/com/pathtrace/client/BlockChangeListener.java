package com.pathtrace.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Pushes block changes and chunk loads straight into the voxel volume and the light-source
 * index, so the lighting reacts immediately instead of waiting for a background rescan.
 */
final class BlockChangeListener implements IWorldEventListener {
    private final World world;
    private final VoxelVolume volume;
    private final EmitterIndex emitters;

    BlockChangeListener(World world, VoxelVolume volume, EmitterIndex emitters) {
        this.world = world;
        this.volume = volume;
        this.emitters = emitters;
    }

    private boolean trackEmitters() {
        if (!emitters.isFor(world)) emitters.reset(world);
        return true;
    }

    @Override
    public void notifyBlockUpdate(World worldIn, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
        if (worldIn != world) return;
        if (volume.isFor(worldIn)) volume.markBlock(pos);
        if (trackEmitters()) emitters.markBlock(pos);
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        long size = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (size <= 64) return;   // single block changes come through notifyBlockUpdate
        if (volume.isFor(world)) volume.markBox(x1, y1, z1, x2, y2, z2); // chunk (re)loads
        if (trackEmitters()) emitters.markRange(x1, y1, z1, x2, y2, z2);
    }

    @Override
    public void notifyLightSet(BlockPos pos) {}

    @Override
    public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent soundIn, SoundCategory category,
                                         double x, double y, double z, float volume, float pitch) {}

    @Override
    public void playRecord(SoundEvent soundIn, BlockPos pos) {}

    @Override
    public void spawnParticle(int particleID, boolean ignoreRange, double xCoord, double yCoord, double zCoord,
                              double xSpeed, double ySpeed, double zSpeed, int... parameters) {}

    @Override
    public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x, double y, double z,
                              double xSpeed, double ySpeed, double zSpeed, int... parameters) {}

    @Override
    public void onEntityAdded(Entity entityIn) {}

    @Override
    public void onEntityRemoved(Entity entityIn) {}

    @Override
    public void broadcastSound(int soundID, BlockPos pos, int data) {}

    @Override
    public void playEvent(EntityPlayer player, int type, BlockPos blockPosIn, int data) {}

    @Override
    public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
}
