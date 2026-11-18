package com.pathtrace.client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Exact 1-block solid grid for shadows beyond the detailed 128-block voxel volume, covering the
 * render distance and the whole world height. Bit-packed: one byte holds 8 blocks along X, so render
 * distance 8 needs 2 MB and 16 needs 8 MB. A second, 8x8x8-block coarse grid lets rays skip
 * empty space. Built from the per-section solid masks {@link EmitterIndex} records while scanning.
 */
final class LodVolume {
    static final int HEIGHT = 256;

    private ByteBuffer bits;          // [z][y][x/8]
    private ByteBuffer coarse;        // [z/8][y/8][x/8]
    private int tex = -1, coarseTex = -1;
    int extent;                       // blocks along X and Z
    int ox, oz;                       // world position of cell (0, *, 0); y origin is 0
    private boolean built;

    int texture() { return tex; }
    int coarseTexture() { return coarseTex; }
    boolean isBuilt() { return built; }

    void invalidate() {
        built = false;
    }

    void update(EmitterIndex index, double cx, double cz, int renderDistanceChunks) {
        int newExtent = renderDistanceChunks <= 8 ? 256 : 512;
        if (newExtent != extent || tex == -1) {
            extent = newExtent;
            allocate();
            built = false;
        }
        int nox = (MathHelper.floor(cx) >> 4 << 4) - extent / 2;
        int noz = (MathHelper.floor(cz) >> 4 << 4) - extent / 2;
        if (!built || Math.abs(nox - ox) >= 32 || Math.abs(noz - oz) >= 32) {
            ox = nox;
            oz = noz;
            rebuildAll(index);
            built = true;
            while (index.pollChangedOccupancy() != null) { /* all included */ }
            return;
        }
        int budget = 256;
        Long k;
        while (budget-- > 0 && (k = index.pollChangedOccupancy()) != null) {
            BlockPos s = BlockPos.fromLong(k);
            if (fillSection(index, s.getX(), s.getY(), s.getZ())) upload(s.getX(), s.getY(), s.getZ());
        }
    }

    private void allocate() {
        bits = BufferUtils.createByteBuffer(extent / 8 * HEIGHT * extent);
        coarse = BufferUtils.createByteBuffer(extent / 8 * (HEIGHT / 8) * (extent / 8));
        if (tex != -1) GL11.glDeleteTextures(tex);
        if (coarseTex != -1) GL11.glDeleteTextures(coarseTex);
        tex = create(extent / 8, HEIGHT, extent);
        coarseTex = create(extent / 8, HEIGHT / 8, extent / 8);
    }

    private void rebuildAll(EmitterIndex index) {
        for (int i = 0; i < bits.capacity(); i++) bits.put(i, (byte) 0);
        for (int i = 0; i < coarse.capacity(); i++) coarse.put(i, (byte) 0);
        int sx0 = ox >> 4, sz0 = oz >> 4, n = extent / 16;
        for (int sx = sx0; sx < sx0 + n; sx++)
            for (int sz = sz0; sz < sz0 + n; sz++)
                for (int sy = 0; sy < 16; sy++) fillSection(index, sx, sy, sz);
        uploadBox(bits, tex, 0, 0, 0, extent / 8, HEIGHT, extent, extent / 8, HEIGHT);
        uploadBox(coarse, coarseTex, 0, 0, 0, extent / 8, HEIGHT / 8, extent / 8, extent / 8, HEIGHT / 8);
    }

    /** Copy one 16^3 section into the grids. Returns false if it's outside the grid. */
    private boolean fillSection(EmitterIndex index, int sx, int sy, int sz) {
        int bx = (sx << 4) - ox, by = sy << 4, bz = (sz << 4) - oz;
        if (bx < 0 || bz < 0 || bx >= extent || bz >= extent || by >= HEIGHT) return false;
        long[] mask = index.occupancy(sx, sy, sz);
        int rowBytes = extent / 8;
        boolean[] coarseSolid = new boolean[8];
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++) {
                int lo = 0, hi = 0;
                if (mask != null) {
                    int bit = (y * 16 + z) * 16;
                    int row = (int) ((mask[bit >> 6] >>> (bit & 63)) & 0xFFFF);
                    lo = row & 0xFF;
                    hi = (row >> 8) & 0xFF;
                }
                int i = ((bz + z) * HEIGHT + (by + y)) * rowBytes + bx / 8;
                bits.put(i, (byte) lo);
                bits.put(i + 1, (byte) hi);
                int c = ((z >> 3) * 2 + (y >> 3)) * 2;
                if (lo != 0) coarseSolid[c] = true;
                if (hi != 0) coarseSolid[c + 1] = true;
            }
        int cw = extent / 8, ch = HEIGHT / 8;
        for (int cz = 0; cz < 2; cz++)
            for (int cy = 0; cy < 2; cy++)
                for (int cx = 0; cx < 2; cx++)
                    coarse.put(((bz / 8 + cz) * ch + (by / 8 + cy)) * cw + bx / 8 + cx,
                            coarseSolid[(cz * 2 + cy) * 2 + cx] ? (byte) 255 : 0);
        return true;
    }

    private void upload(int sx, int sy, int sz) {
        int bx = (sx << 4) - ox, by = sy << 4, bz = (sz << 4) - oz;
        uploadBox(bits, tex, bx / 8, by, bz, 2, 16, 16, extent / 8, HEIGHT);
        uploadBox(coarse, coarseTex, bx / 8, by / 8, bz / 8, 2, 2, 2, extent / 8, HEIGHT / 8);
    }

    private static int create(int w, int h, int d) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, w, h, d, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return id;
    }

    private static void uploadBox(ByteBuffer src, int tex, int x, int y, int z, int w, int h, int d, int rowLength, int imageHeight) {
        ByteBuffer b = src.duplicate();
        b.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, x);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, y);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, z);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }
}
