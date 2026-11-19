package com.pathtrace.client;

import com.pathtrace.PTConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
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
 *   <li>voxel texel: RGB = block colour, A = 128 (solid) | colourIndex << 4 | light level;
 *       A = 64 marks stained glass: not solid, but light passing through takes its colour;
 *       A = 96 marks water: light passing through loses some of it per block</li>
 *   <li>coarse texture: one texel per 4x4x4 cell, non-zero if any block in it is solid (empty-space skipping)</li>
 *   <li>height texture: world height per column, so rays leaving the grid know whether they reach the sky</li>
 *   <li>shape texture: for non-full blocks (slabs, stairs, fences, torches, plants...) the number of
 *       their 16x16x16 shape in {@link ShapeAtlas} (R + G * 256, 0 = full block)</li>
 * </ul>
 */
final class VoxelVolume {
    static final int N = 128;
    static final int C = N / 4;
    private static final int MAX_DIRTY_BLOCKS = 16384;
    private static final int MAX_DIRTY_BOXES = 512;

    private final ByteBuffer voxels = BufferUtils.createByteBuffer(N * N * N * 4);
    private final ByteBuffer shapeIds = BufferUtils.createByteBuffer(N * N * N * 4);
    final ShapeAtlas shapes = new ShapeAtlas();
    private final ByteBuffer heights = BufferUtils.createByteBuffer(N * N * 4);
    private final ByteBuffer coarse = BufferUtils.createByteBuffer(C * C * C);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private final LinkedHashSet<Long> dirtyBlocks = new LinkedHashSet<>();
    private final ArrayDeque<int[]> dirtyBoxes = new ArrayDeque<>();

    private int tex3d = -1, coarseTex = -1, heightTex = -1, shapeIdTex = -1;
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
    int shapeIdTexture() { return shapeIdTex; }
    int coarseTexture() { return coarseTex; }
    int heightTexture() { return heightTex; }
    /** Texel offset of the window: world block ox lives in texel ox & 127 (the textures repeat). */
    float wrapX() { return wrap(ox); }
    float wrapY() { return wrap(oy); }
    float wrapZ() { return wrap(oz); }

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

    /**
     * The textures are addressed toroidally: world block (x, y, z) always lives in texel
     * (x & 127, y & 127, z & 127). Moving the window then only needs the newly exposed slab filled
     * (the texels it reuses held the slab that just left), instead of rebuilding all 2 million blocks.
     */
    void update(World w, double px, double py, double pz) {
        if (tex3d == -1) createTextures();
        if (w != world) {
            invalidate();
            world = w;
        }
        int tx = ((MathHelper.floor(px) >> 3) << 3) - N / 2;
        int ty = ((MathHelper.floor(py) >> 3) << 3) - N / 2;
        int tz = ((MathHelper.floor(pz) >> 3) << 3) - N / 2;

        if (!rebuilding && scrollAxis < 0) {
            if (!valid || Math.abs(tx - ox) >= N / 2 || Math.abs(ty - oy) >= N / 2 || Math.abs(tz - oz) >= N / 2) {
                rebuilding = true;          // first time / teleported: fill everything
                bx = tx; by = ty; bz = tz;
                buildZ = 0;
            } else if (Math.abs(tx - ox) >= 16) startScroll(0, tx);
            else if (Math.abs(tz - oz) >= 16) startScroll(2, tz);
            else if (Math.abs(ty - oy) >= 16) startScroll(1, ty);
        }

        if (rebuilding) {
            int end = Math.min(N, buildZ + PTConfig.rebuildSlicesPerFrame);
            for (int z = buildZ; z < end; z++) fillSlice(bx, by, bz, z);
            buildZ = end;
            if (buildZ >= N) {
                ox = bx; oy = by; oz = bz;
                for (int cz = 0; cz < C; cz++)
                    for (int cy = 0; cy < C; cy++)
                        for (int cx = 0; cx < C; cx++) computeCoarseTexel(cx, cy, cz);
                uploadAllVoxels();
                uploadAllCoarse();
                uploadHeights();
                valid = true;
                rebuilding = false;
                refreshZ = 0;
            }
            return;
        }

        heightsDirty = false;
        if (scrollAxis >= 0) stepScroll();
        processDirty();
        // background re-scan: an eighth of a slice (16 x 128 blocks) per frame, so it never spikes a frame
        if (PTConfig.refreshSlicesPerFrame > 0) {
            for (int i = 0; i < PTConfig.refreshSlicesPerFrame; i++) {
                int z = refreshZ, x0 = refreshPart * (N / REFRESH_EVERY);
                for (int x = x0; x < x0 + N / REFRESH_EVERY; x++) {
                    fillHeightWorld(ox + x, oz + z);
                    for (int y = 0; y < N; y++) fillVoxelWorld(ox + x, oy + y, oz + z);
                }
                uploadWorldBox(ox + x0, oy, oz + z, N / REFRESH_EVERY, N, 1);
                refreshCoarse(ox + x0, oy, oz + z, N / REFRESH_EVERY, N, 1);
                if (++refreshPart == REFRESH_EVERY) {
                    refreshPart = 0;
                    refreshZ = (refreshZ + 1) % N;
                }
            }
        }
        if (heightsDirty) uploadHeights();
    }

    /** Background re-scan (a safety net; block and chunk changes arrive as events): one slice every this many frames. */
    private static final int REFRESH_EVERY = 8;
    private int refreshPart;

    // scrolling state: which axis, its new origin, the world range [scrollFrom, scrollTo) still to fill
    private int scrollAxis = -1, scrollTarget, scrollFrom, scrollTo, scrollStart;

    private void startScroll(int axis, int target) {
        int cur = axis == 0 ? ox : axis == 1 ? oy : oz;
        scrollAxis = axis;
        scrollTarget = target;
        if (target > cur) { scrollFrom = cur + N; scrollTo = target + N; }
        else { scrollFrom = target; scrollTo = cur; }
        scrollStart = scrollFrom;
    }

    private void stepScroll() {
        // slices of 128x128 blocks: at least one per frame, then as many as fit in ~2 ms
        long deadline = System.nanoTime() + 2_000_000L;
        boolean first = true;
        while (scrollFrom < scrollTo && (first || System.nanoTime() < deadline)) {
            first = false;
            int s = scrollFrom++;
            // one 1-thick slab at world coordinate s on the scroll axis, across the window on the others
            int x0 = ox, y0 = oy, z0 = oz, w = N, h = N, d = N;
            if (scrollAxis == 0) { x0 = s; w = 1; }
            else if (scrollAxis == 1) { y0 = s; h = 1; }
            else { z0 = s; d = 1; }
            for (int z = z0; z < z0 + d; z++)
                for (int y = y0; y < y0 + h; y++)
                    for (int x = x0; x < x0 + w; x++) fillVoxelWorld(x, y, z);
            if (scrollAxis != 1)
                for (int z = z0; z < z0 + d; z++)
                    for (int x = x0; x < x0 + w; x++) fillHeightWorld(x, z);
            uploadWorldBox(x0, y0, z0, w, h, d);
        }
        if (scrollFrom >= scrollTo) {
            if (scrollAxis == 0) ox = scrollTarget; else if (scrollAxis == 1) oy = scrollTarget; else oz = scrollTarget;
            // coarse cells of the filled slab
            int n = scrollTo - scrollStart;
            if (scrollAxis == 0) refreshCoarse(scrollStart, oy, oz, n, N, N);
            else if (scrollAxis == 1) refreshCoarse(ox, scrollStart, oz, N, n, N);
            else refreshCoarse(ox, oy, scrollStart, N, N, n);
            scrollAxis = -1;
        }
    }

    // ------------------------------------------------------------------ filling

    private final java.util.HashMap<Long, int[]> sectionBoxes = new java.util.HashMap<>();

    private void processDirty() {
        // single changed blocks (water/lava flowing, leaves, crops... can be hundreds per frame):
        // refill each, but upload once per 16^3 section they touch, not once per block
        int budget = 2048;
        long until = System.nanoTime() + 1_000_000L;
        Iterator<Long> it = dirtyBlocks.iterator();
        sectionBoxes.clear();
        while (budget-- > 0 && it.hasNext() && System.nanoTime() < until) {
            BlockPos p = BlockPos.fromLong(it.next());
            it.remove();
            int x = p.getX() - ox, y = p.getY() - oy, z = p.getZ() - oz;
            if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) continue;
            fillVoxelWorld(p.getX(), p.getY(), p.getZ());
            fillHeightWorld(p.getX(), p.getZ());
            long key = new BlockPos(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4).toLong();
            int[] b = sectionBoxes.get(key);
            if (b == null) sectionBoxes.put(key, new int[]{p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()});
            else {
                b[0] = Math.min(b[0], p.getX()); b[1] = Math.min(b[1], p.getY()); b[2] = Math.min(b[2], p.getZ());
                b[3] = Math.max(b[3], p.getX()); b[4] = Math.max(b[4], p.getY()); b[5] = Math.max(b[5], p.getZ());
            }
        }
        for (int[] b : sectionBoxes.values()) {
            uploadWorldBox(b[0], b[1], b[2], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1);
            refreshCoarse(b[0], b[1], b[2], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1);
        }

        // changed areas (chunk loads arrive as whole 16x256x16 columns): at most ~8k blocks per frame,
        // big boxes are split into horizontal layers so a chunk load never costs one long frame
        int blockBudget = 8192;
        long deadline = System.nanoTime() + 1_500_000L;
        while (blockBudget > 0 && !dirtyBoxes.isEmpty() && System.nanoTime() < deadline) {
            int[] b = dirtyBoxes.peek();
            int x0 = Math.max(ox, b[0]), x1 = Math.min(ox + N - 1, b[3]);
            int y0 = Math.max(oy, b[1]), y1 = Math.min(oy + N - 1, b[4]);
            int z0 = Math.max(oz, b[2]), z1 = Math.min(oz + N - 1, b[5]);
            if (x0 > x1 || y0 > y1 || z0 > z1) { dirtyBoxes.poll(); continue; }
            int layer = (x1 - x0 + 1) * (z1 - z0 + 1);
            int layers = Math.max(1, Math.min(y1 - y0 + 1, blockBudget / Math.max(1, layer)));
            int ye = y0 + layers - 1;
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= ye; y++)
                    for (int x = x0; x <= x1; x++) fillVoxelWorld(x, y, z);
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) fillHeightWorld(x, z);
            uploadWorldBox(x0, y0, z0, x1 - x0 + 1, layers, z1 - z0 + 1);
            refreshCoarse(x0, y0, z0, x1 - x0 + 1, layers, z1 - z0 + 1);
            blockBudget -= layers * layer;
            if (ye >= y1) dirtyBoxes.poll();
            else b[1] = ye + 1;   // the rest of this box next time
        }
    }

    private void fillSlice(int baseX, int baseY, int baseZ, int z) {
        for (int x = 0; x < N; x++) {
            fillHeightWorld(baseX + x, baseZ + z);
            for (int y = 0; y < N; y++) fillVoxelWorld(baseX + x, baseY + y, baseZ + z);
        }
    }

    private static int wrap(int v) {
        return v & (N - 1);
    }

    private void fillHeightWorld(int wx, int wz) {
        pos.setPos(wx, 0, wz);
        int h = world.getHeight(pos).getY();
        int hi = (wrap(wz) * N + wrap(wx)) * 4;
        heights.put(hi, (byte) Math.max(0, Math.min(255, h)));
        heights.put(hi + 1, (byte) 0);
        heights.put(hi + 2, (byte) 0);
        heights.put(hi + 3, (byte) 255);
        heightsDirty = true;
    }

    private void clearVoxel(int idx) {
        voxels.putInt(idx, 0);
        shapeIds.putInt(idx, 0);
    }

    private void fillVoxelWorld(int wx, int wy, int wz) {
        int key = (wrap(wz) * N + wrap(wy)) * N + wrap(wx);
        int idx = key * 4;
        if (wy < 0 || wy > 255) {
            clearVoxel(idx);
            return;
        }
        pos.setPos(wx, wy, wz);
        IBlockState s = world.getBlockState(pos);
        Material m = s.getMaterial();
        if (m == Material.AIR) {
            clearVoxel(idx);
            return;
        }
        int light = Math.max(0, Math.min(15, s.getLightValue(world, pos)));
        boolean full = s.isOpaqueCube() || m == Material.LEAVES;
        int shape = 0;                       // 0 = full block, k > 0 = ShapeAtlas brick k - 1
        if (!full) {
            // glass and liquids let light through (lava etc. still count as a full glowing block)
            boolean passThrough = m == Material.GLASS || m.isLiquid() || m == Material.PORTAL;
            if (m == Material.GLASS && light == 0) {
                int gc = s.getMapColor(world, pos).colorValue;    // stained glass has its dye colour
                if (gc == 0) {
                    clearVoxel(idx);                           // clear glass: nothing to do
                } else {
                    voxels.put(idx, (byte) (gc >> 16));
                    voxels.put(idx + 1, (byte) (gc >> 8));
                    voxels.put(idx + 2, (byte) gc);
                    voxels.put(idx + 3, (byte) 64);
                    shapeIds.putInt(idx, 0);
                }
                return;
            }
            if (m == Material.WATER) {
                // water: lets light through but absorbs some per block, more red than blue, so the
                // bottom of a lake or the sea is dimmer and bluer the deeper it is (A = 96: "absorber")
                voxels.put(idx, (byte) 230);     // linear 0.80, 0.90, 0.96 per block
                voxels.put(idx + 1, (byte) 243);
                voxels.put(idx + 2, (byte) 250);
                voxels.put(idx + 3, (byte) 96);
                shapeIds.putInt(idx, 0);
                return;
            }
            int id = passThrough ? 0 : shapes.shapeFor(world, pos, s);
            if (id == 0 && light == 0) {
                clearVoxel(idx);
                return;
            }
            shape = Math.max(0, id);         // -1 (completely full) -> full block
        }
        int color = light > 0 ? LightColors.forBlock(s.getBlock()) : 0;
        int c = s.getMapColor(world, pos).colorValue;
        if (c == 0) c = 0x808080;
        voxels.put(idx, (byte) (c >> 16));
        voxels.put(idx + 1, (byte) (c >> 8));
        voxels.put(idx + 2, (byte) c);
        voxels.put(idx + 3, (byte) (128 | (color << 4) | light));
        shapeIds.put(idx, (byte) shape);
        shapeIds.put(idx + 1, (byte) (shape >> 8));
        shapeIds.put(idx + 2, (byte) 0);
        shapeIds.put(idx + 3, (byte) 0);
    }

    /** Recompute + upload the coarse cells covering a world box. */
    private void refreshCoarse(int x, int y, int z, int w, int h, int d) {
        int cx0 = x >> 2, cx1 = (x + w - 1) >> 2, cy0 = y >> 2, cy1 = (y + h - 1) >> 2, cz0 = z >> 2, cz1 = (z + d - 1) >> 2;
        for (int cz = cz0; cz <= cz1; cz++)
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) computeCoarseTexel(cx & (C - 1), cy & (C - 1), cz & (C - 1));
        uploadWorldCoarse(cx0, cy0, cz0, cx1 - cx0 + 1, cy1 - cy0 + 1, cz1 - cz0 + 1);
    }

    /** cx, cy, cz: coarse texel coordinates (each covers texels 4c..4c+3, which are 4 aligned world blocks). */
    private void computeCoarseTexel(int cx, int cy, int cz) {
        byte v = 0;
        search:
        for (int z = cz * 4; z < cz * 4 + 4; z++)
            for (int y = cy * 4; y < cy * 4 + 4; y++)
                for (int x = cx * 4; x < cx * 4 + 4; x++)
                    if ((voxels.get((((z * N + y) * N + x) * 4) + 3) & 0xC0) != 0) {   // solid or tinted
                        v = (byte) 255;
                        break search;
                    }
        coarse.put((cz * C + cy) * C + cx, v);
    }

    /** Upload a world box of voxels, split where it wraps around the texture edges. */
    private void uploadWorldBox(int x, int y, int z, int w, int h, int d) {
        w = Math.min(w, N); h = Math.min(h, N); d = Math.min(d, N);
        int tx = wrap(x), ty = wrap(y), tz = wrap(z);
        int w1 = Math.min(w, N - tx), h1 = Math.min(h, N - ty), d1 = Math.min(d, N - tz);
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
            int bx0 = a == 0 ? tx : 0, bw = a == 0 ? w1 : w - w1;
            int by0 = b == 0 ? ty : 0, bh = b == 0 ? h1 : h - h1;
            int bz0 = c == 0 ? tz : 0, bd = c == 0 ? d1 : d - d1;
            if (bw > 0 && bh > 0 && bd > 0) uploadVoxelBox(bx0, by0, bz0, bw, bh, bd);
        }
    }

    private void uploadWorldCoarse(int cx, int cy, int cz, int w, int h, int d) {
        w = Math.min(w, C); h = Math.min(h, C); d = Math.min(d, C);
        int tx = cx & (C - 1), ty = cy & (C - 1), tz = cz & (C - 1);
        int w1 = Math.min(w, C - tx), h1 = Math.min(h, C - ty), d1 = Math.min(d, C - tz);
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
            int bx0 = a == 0 ? tx : 0, bw = a == 0 ? w1 : w - w1;
            int by0 = b == 0 ? ty : 0, bh = b == 0 ? h1 : h - h1;
            int bz0 = c == 0 ? tz : 0, bd = c == 0 ? d1 : d - d1;
            if (bw > 0 && bh > 0 && bd > 0) uploadCoarseBox(bx0, by0, bz0, bw, bh, bd);
        }
    }

    private void uploadAllVoxels() { uploadVoxelBox(0, 0, 0, N, N, N); }
    private void uploadAllCoarse() { uploadCoarseBox(0, 0, 0, C, C, C); }

    // ------------------------------------------------------------------ GL

    private void createTextures() {
        tex3d = create3D(GL11.GL_RGBA8, GL11.GL_RGBA, N);
        shapeIdTex = create3D(GL11.GL_RGBA8, GL11.GL_RGBA, N);
        coarseTex = create3D(GL30.GL_R8, GL11.GL_RED, C);

        heightTex = GlStateManager.generateTexture();
        GlStateManager.bindTexture(heightTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, N, N, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    private static int create3D(int internal, int format, int size) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
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

    /** Time spent in texture uploads (reported separately by /pathtrace perf), reset by the renderer. */
    long uploadNanos;

    private void uploadVoxelBox(int x, int y, int z, int w, int h, int d) {
        long t0 = System.nanoTime();
        uploadVoxelBoxGL(x, y, z, w, h, d);
        uploadNanos += System.nanoTime() - t0;
    }

    private void uploadVoxelBoxGL(int x, int y, int z, int w, int h, int d) {
        ByteBuffer b = voxels.duplicate();
        b.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex3d);
        setUnpack(N, N, x, y, z, 4);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
        ByteBuffer ids = shapeIds.duplicate();
        ids.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, shapeIdTex);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ids);
        resetUnpack();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private void uploadCoarseBox(int x, int y, int z, int w, int h, int d) {
        long t0 = System.nanoTime();
        uploadCoarseBoxGL(x, y, z, w, h, d);
        uploadNanos += System.nanoTime() - t0;
    }

    private void uploadCoarseBoxGL(int x, int y, int z, int w, int h, int d) {
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
