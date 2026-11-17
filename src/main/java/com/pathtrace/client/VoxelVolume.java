package com.pathtrace.client;

import com.pathtrace.PTConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * A 128^3 block grid around the camera, uploaded as a 3D texture the shaders ray-march.
 * <ul>
 *   <li>voxel texel: RGB = block colour, A = 128 (solid) | colourIndex << 4 | light level</li>
 *   <li>coarse texture: one texel per 4x4x4 cell, non-zero if any block in it is solid (empty-space skipping)</li>
 *   <li>height texture: world height per column, so rays leaving the grid know whether they reach the sky</li>
 *   <li>mask textures A/B: for non-full blocks (slabs, stairs, fences, torches, plants...) a 4x4x4 shape,
 *       64 bits over 8 bytes (bit = x + 4y + 16z); all zero = full block</li>
 * </ul>
 */
final class VoxelVolume {
    static final int N = 128;
    static final int C = N / 4;
    private static final int MAX_DIRTY_BLOCKS = 16384;
    private static final int MAX_DIRTY_BOXES = 512;

    private final ByteBuffer voxels = BufferUtils.createByteBuffer(N * N * N * 4);
    private final ByteBuffer maskA = BufferUtils.createByteBuffer(N * N * N * 4);
    private final ByteBuffer maskB = BufferUtils.createByteBuffer(N * N * N * 4);
    private final java.util.List<AxisAlignedBB> boxes = new java.util.ArrayList<>();
    private final ByteBuffer heights = BufferUtils.createByteBuffer(N * N * 4);
    private final ByteBuffer coarse = BufferUtils.createByteBuffer(C * C * C);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private final LinkedHashSet<Long> dirtyBlocks = new LinkedHashSet<>();
    private final ArrayDeque<int[]> dirtyBoxes = new ArrayDeque<>();

    private int tex3d = -1, coarseTex = -1, heightTex = -1, maskTexA = -1, maskTexB = -1;
    private int ox, oy, oz;
    private boolean valid, rebuilding, heightsDirty;
    private int bx, by, bz, buildZ, refreshZ;
    private World world;

    boolean isValid() { return valid; }
    boolean isFor(World w) { return w == world; }
    int originX() { return ox; }
    int originY() { return oy; }
    int originZ() { return oz; }
    int texture() { return tex3d; }
    int maskTextureA() { return maskTexA; }
    int maskTextureB() { return maskTexB; }
    int coarseTexture() { return coarseTex; }
    int heightTexture() { return heightTex; }

    void invalidate() {
        valid = false;
        rebuilding = false;
        world = null;
        dirtyBlocks.clear();
        dirtyBoxes.clear();
    }

    void markBlock(BlockPos p) {
        if (dirtyBlocks.size() < MAX_DIRTY_BLOCKS) dirtyBlocks.add(p.toLong());
    }

    void markBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (dirtyBoxes.size() >= MAX_DIRTY_BOXES) dirtyBoxes.poll();
        dirtyBoxes.add(new int[]{x1, y1, z1, x2, y2, z2});
    }

    void update(World w, double px, double py, double pz) {
        if (tex3d == -1) createTextures();
        if (w != world) {
            invalidate();
            world = w;
        }
        int tx = ((MathHelper.floor(px) >> 3) << 3) - N / 2;
        int ty = ((MathHelper.floor(py) >> 3) << 3) - N / 2;
        int tz = ((MathHelper.floor(pz) >> 3) << 3) - N / 2;

        if (!rebuilding && (!valid || Math.abs(tx - ox) >= 16 || Math.abs(ty - oy) >= 16 || Math.abs(tz - oz) >= 16)) {
            rebuilding = true;
            bx = tx; by = ty; bz = tz;
            buildZ = 0;
        }

        if (rebuilding) {
            int end = Math.min(N, buildZ + PTConfig.rebuildSlicesPerFrame);
            for (int z = buildZ; z < end; z++) fillSlice(bx, by, bz, z);
            buildZ = end;
            if (buildZ >= N) {
                ox = bx; oy = by; oz = bz;
                for (int cz = 0; cz < C; cz++)
                    for (int cy = 0; cy < C; cy++)
                        for (int cx = 0; cx < C; cx++) computeCoarse(cx, cy, cz);
                uploadVoxelBox(0, 0, 0, N, N, N);
                uploadCoarseBox(0, 0, 0, C, C, C);
                uploadHeights();
                valid = true;
                rebuilding = false;
                refreshZ = 0;
            }
            return;
        }

        heightsDirty = false;
        processDirty();
        for (int i = 0; i < PTConfig.refreshSlicesPerFrame; i++) {
            int z = refreshZ;
            fillSlice(ox, oy, oz, z);
            uploadVoxelBox(0, 0, z, N, N, 1);
            int cz = z >> 2;
            for (int cy = 0; cy < C; cy++)
                for (int cx = 0; cx < C; cx++) computeCoarse(cx, cy, cz);
            uploadCoarseBox(0, 0, cz, C, C, 1);
            refreshZ = (refreshZ + 1) % N;
        }
        if (heightsDirty) uploadHeights();
    }

    // ------------------------------------------------------------------ filling

    private void processDirty() {
        int budget = 2048;
        Iterator<Long> it = dirtyBlocks.iterator();
        while (budget-- > 0 && it.hasNext()) {
            BlockPos p = BlockPos.fromLong(it.next());
            it.remove();
            int x = p.getX() - ox, y = p.getY() - oy, z = p.getZ() - oz;
            if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) continue;
            fillVoxel(ox, oy, oz, x, y, z);
            uploadVoxelBox(x, y, z, 1, 1, 1);
            computeCoarse(x >> 2, y >> 2, z >> 2);
            uploadCoarseBox(x >> 2, y >> 2, z >> 2, 1, 1, 1);
            fillHeight(ox, oz, x, z);
        }

        int boxes = 2;
        while (boxes-- > 0 && !dirtyBoxes.isEmpty()) {
            int[] b = dirtyBoxes.poll();
            int x0 = Math.max(0, b[0] - ox), x1 = Math.min(N - 1, b[3] - ox);
            int y0 = Math.max(0, b[1] - oy), y1 = Math.min(N - 1, b[4] - oy);
            int z0 = Math.max(0, b[2] - oz), z1 = Math.min(N - 1, b[5] - oz);
            if (x0 > x1 || y0 > y1 || z0 > z1) {
                boxes++; // nothing to do, doesn't cost budget
                continue;
            }
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) fillVoxel(ox, oy, oz, x, y, z);
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) fillHeight(ox, oz, x, z);
            uploadVoxelBox(x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
            int cx0 = x0 >> 2, cx1 = x1 >> 2, cy0 = y0 >> 2, cy1 = y1 >> 2, cz0 = z0 >> 2, cz1 = z1 >> 2;
            for (int cz = cz0; cz <= cz1; cz++)
                for (int cy = cy0; cy <= cy1; cy++)
                    for (int cx = cx0; cx <= cx1; cx++) computeCoarse(cx, cy, cz);
            uploadCoarseBox(cx0, cy0, cz0, cx1 - cx0 + 1, cy1 - cy0 + 1, cz1 - cz0 + 1);
        }
    }

    private void fillSlice(int baseX, int baseY, int baseZ, int z) {
        for (int x = 0; x < N; x++) {
            fillHeight(baseX, baseZ, x, z);
            for (int y = 0; y < N; y++) fillVoxel(baseX, baseY, baseZ, x, y, z);
        }
    }

    private void fillHeight(int baseX, int baseZ, int x, int z) {
        pos.setPos(baseX + x, 0, baseZ + z);
        int h = world.getHeight(pos).getY();
        int hi = (z * N + x) * 4;
        heights.put(hi, (byte) Math.max(0, Math.min(255, h)));
        heights.put(hi + 1, (byte) 0);
        heights.put(hi + 2, (byte) 0);
        heights.put(hi + 3, (byte) 255);
        heightsDirty = true;
    }

    private void clearVoxel(int idx) {
        voxels.putInt(idx, 0);
        maskA.putInt(idx, 0);
        maskB.putInt(idx, 0);
    }

    private void fillVoxel(int baseX, int baseY, int baseZ, int x, int y, int z) {
        int key = (z * N + y) * N + x;
        int idx = key * 4;
        int wy = baseY + y;
        if (wy < 0 || wy > 255) {
            clearVoxel(idx);
            return;
        }
        pos.setPos(baseX + x, wy, baseZ + z);
        IBlockState s = world.getBlockState(pos);
        Material m = s.getMaterial();
        if (m == Material.AIR) {
            clearVoxel(idx);
            return;
        }
        int light = Math.max(0, Math.min(15, s.getLightValue(world, pos)));
        boolean full = s.isOpaqueCube() || m == Material.LEAVES;
        long shape = 0L;
        if (!full) {
            // glass and liquids let light through (lava etc. still count as a full glowing block)
            boolean passThrough = m == Material.GLASS || m.isLiquid() || m == Material.PORTAL;
            if (!passThrough) shape = partialShape(s, m);
            if (shape == 0L && light == 0) {
                clearVoxel(idx);
                return;
            }
            if (shape == -1L) shape = 0L;   // shape fills the whole block: treat as full
        }
        int color = light > 0 ? LightColors.forBlock(s.getBlock()) : 0;
        int c = s.getMapColor(world, pos).colorValue;
        if (c == 0) c = 0x808080;
        voxels.put(idx, (byte) (c >> 16));
        voxels.put(idx + 1, (byte) (c >> 8));
        voxels.put(idx + 2, (byte) c);
        voxels.put(idx + 3, (byte) (128 | (color << 4) | light));
        for (int j = 0; j < 4; j++) {
            maskA.put(idx + j, (byte) (shape >>> (8 * j)));
            maskB.put(idx + j, (byte) (shape >>> (8 * (j + 4))));
        }
    }

    /**
     * 4x4x4 occupancy of a non-full block from its collision boxes (or its outline for things you
     * walk through, like plants and torches). Cut-out blocks (plants, bars...) get a checkerboard so
     * they cast lighter, dappled shadows. 0 = nothing, -1 = completely full.
     */
    private long partialShape(IBlockState s, Material m) {
        boxes.clear();
        boolean dither;
        try {
            dither = s.getBlock().canRenderInLayer(s, BlockRenderLayer.CUTOUT)
                    || s.getBlock().canRenderInLayer(s, BlockRenderLayer.CUTOUT_MIPPED);
            s.addCollisionBoxToList(world, pos, new AxisAlignedBB(pos).grow(0.01), boxes, null, false);
            if (boxes.isEmpty()) {
                AxisAlignedBB bb = s.getBoundingBox(world, pos);
                if (bb == null || bb == Block.NULL_AABB) return 0L;
                boxes.add(bb.offset(pos));
                dither = dither || m == Material.PLANTS || m == Material.VINE;
            }
        } catch (Throwable t) {
            return 0L;
        }
        long bits = 0L;
        int set = 0;
        for (int mz = 0; mz < 4; mz++)
            for (int my = 0; my < 4; my++)
                for (int mx = 0; mx < 4; mx++) {
                    if (dither && ((mx + my + mz) & 1) != 0) continue;
                    double cx = pos.getX() + (mx + 0.5) * 0.25;
                    double cy = pos.getY() + (my + 0.5) * 0.25;
                    double cz = pos.getZ() + (mz + 0.5) * 0.25;
                    for (AxisAlignedBB b : boxes) {
                        if (cx > b.minX && cx < b.maxX && cy > b.minY && cy < b.maxY && cz > b.minZ && cz < b.maxZ) {
                            bits |= 1L << (mx + 4 * my + 16 * mz);
                            set++;
                            break;
                        }
                    }
                }
        if (set == 64) return -1L;
        if (set == 0) {
            // thinner than a quarter block (carpet, pressure plate, torch...): use its nearest cells
            for (AxisAlignedBB b : boxes) {
                int x0 = cellOf(b.minX - pos.getX()), x1 = cellOf(b.maxX - pos.getX() - 1e-4);
                int y0 = cellOf(b.minY - pos.getY()), y1 = cellOf(b.maxY - pos.getY() - 1e-4);
                int z0 = cellOf(b.minZ - pos.getZ()), z1 = cellOf(b.maxZ - pos.getZ() - 1e-4);
                for (int mz = z0; mz <= z1; mz++)
                    for (int my = y0; my <= y1; my++)
                        for (int mx = x0; mx <= x1; mx++) bits |= 1L << (mx + 4 * my + 16 * mz);
            }
        }
        return bits;
    }

    private static int cellOf(double v) {
        return Math.max(0, Math.min(3, (int) Math.floor(v * 4.0)));
    }

    private void computeCoarse(int cx, int cy, int cz) {
        byte v = 0;
        search:
        for (int z = cz * 4; z < cz * 4 + 4; z++)
            for (int y = cy * 4; y < cy * 4 + 4; y++)
                for (int x = cx * 4; x < cx * 4 + 4; x++)
                    if ((voxels.get((((z * N + y) * N + x) * 4) + 3) & 0x80) != 0) {
                        v = (byte) 255;
                        break search;
                    }
        coarse.put((cz * C + cy) * C + cx, v);
    }

    // ------------------------------------------------------------------ GL

    private void createTextures() {
        tex3d = create3D(GL11.GL_RGBA8, GL11.GL_RGBA, N);
        maskTexA = create3D(GL11.GL_RGBA8, GL11.GL_RGBA, N);
        maskTexB = create3D(GL11.GL_RGBA8, GL11.GL_RGBA, N);
        coarseTex = create3D(GL30.GL_R8, GL11.GL_RED, C);

        heightTex = GlStateManager.generateTexture();
        GlStateManager.bindTexture(heightTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, N, N, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    private static int create3D(int internal, int format, int size) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, internal, size, size, size, 0,
                format, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return id;
    }

    private static void setUnpack(int rowLength, int imageHeight, int skipX, int skipY, int skipZ, int align) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipX);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipY);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, skipZ);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, align);
    }

    private static void resetUnpack() {
        setUnpack(0, 0, 0, 0, 0, 4);
    }

    private void uploadVoxelBox(int x, int y, int z, int w, int h, int d) {
        ByteBuffer b = voxels.duplicate();
        b.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex3d);
        setUnpack(N, N, x, y, z, 4);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
        ByteBuffer ma = maskA.duplicate();
        ma.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, maskTexA);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ma);
        ByteBuffer mb = maskB.duplicate();
        mb.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, maskTexB);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, mb);
        resetUnpack();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private void uploadCoarseBox(int x, int y, int z, int w, int h, int d) {
        ByteBuffer b = coarse.duplicate();
        b.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, coarseTex);
        setUnpack(C, C, x, y, z, 1);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
        resetUnpack();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private void uploadHeights() {
        ByteBuffer b = heights.duplicate();
        b.clear();
        resetUnpack();
        GlStateManager.bindTexture(heightTex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, N, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
    }
}
