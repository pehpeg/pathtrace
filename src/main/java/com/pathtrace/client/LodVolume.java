package com.pathtrace.client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Coarse "level of detail" block grid for shadows beyond the detailed 128-block voxel volume.
 * 128^3 cells of 2 blocks (render distance up to 8) or 4 blocks (beyond), covering the whole
 * world height and the render distance horizontally. A cell is solid when most of it is solid.
 * Built from the per-section occupancy masks that {@link EmitterIndex} records while scanning.
 */
final class LodVolume {
    static final int SIZE = 128;

    private final ByteBuffer cells = BufferUtils.createByteBuffer(SIZE * SIZE * SIZE);
    private int tex = -1;
    int cell = 2;              // blocks per cell
    int ox, oz;                // world block position of cell (0, *, 0); y origin is always 0
    private boolean built;

    int texture() { return tex; }
    boolean isBuilt() { return built; }

    void invalidate() {
        built = false;
    }

    void update(EmitterIndex index, double cx, double cz, int renderDistanceChunks) {
        if (tex == -1) create();
        int newCell = renderDistanceChunks <= 8 ? 2 : 4;
        int extent = SIZE * newCell;
        int nox = (MathHelper.floor(cx) >> 4 << 4) - extent / 2;
        int noz = (MathHelper.floor(cz) >> 4 << 4) - extent / 2;
        if (!built || newCell != cell || Math.abs(nox - ox) >= 32 || Math.abs(noz - oz) >= 32) {
            cell = newCell;
            ox = nox;
            oz = noz;
            rebuildAll(index);
            built = true;
            while (index.pollChangedOccupancy() != null) { /* all included */ }
            return;
        }
        // incremental: sections that changed since last frame
        int budget = 256;
        Long k;
        while (budget-- > 0 && (k = index.pollChangedOccupancy()) != null) {
            BlockPos s = BlockPos.fromLong(k);
            fillSection(index, s.getX(), s.getY(), s.getZ(), true);
        }
    }

    private void rebuildAll(EmitterIndex index) {
        for (int i = 0; i < cells.capacity(); i++) cells.put(i, (byte) 0);
        int sx0 = ox >> 4, sz0 = oz >> 4, n = SIZE * cell / 16;
        for (int sx = sx0; sx < sx0 + n; sx++)
            for (int sz = sz0; sz < sz0 + n; sz++)
                for (int sy = 0; sy < 16; sy++) fillSection(index, sx, sy, sz, false);
        upload(0, 0, 0, SIZE, SIZE, SIZE);
    }

    /** Write one 16^3 section into the cell grid. */
    private void fillSection(EmitterIndex index, int sx, int sy, int sz, boolean uploadNow) {
        int bx = (sx << 4) - ox, by = sy << 4, bz = (sz << 4) - oz;
        if (bx < 0 || bz < 0 || bx >= SIZE * cell || bz >= SIZE * cell || by >= SIZE * cell) return;
        long[] mask = index.occupancy(sx, sy, sz);
        int per = 16 / cell;                       // cells per section edge: 8 or 4
        int c0x = bx / cell, c0y = by / cell, c0z = bz / cell;
        for (int z = 0; z < per; z++)
            for (int y = 0; y < per; y++)
                for (int x = 0; x < per; x++) {
                    boolean solid;
                    if (mask == null) {
                        solid = false;
                    } else if (cell == 2) {
                        int b = (z * 8 + y) * 8 + x;
                        solid = (mask[b >> 6] & (1L << (b & 63))) != 0;
                    } else {                       // 4-block cell = 2x2x2 of the 2-block cells
                        int count = 0;
                        for (int dz = 0; dz < 2; dz++)
                            for (int dy = 0; dy < 2; dy++)
                                for (int dx = 0; dx < 2; dx++) {
                                    int b = ((z * 2 + dz) * 8 + (y * 2 + dy)) * 8 + (x * 2 + dx);
                                    if ((mask[b >> 6] & (1L << (b & 63))) != 0) count++;
                                }
                        solid = count >= 5;
                    }
                    cells.put(((c0z + z) * SIZE + (c0y + y)) * SIZE + (c0x + x), solid ? (byte) 255 : 0);
                }
        if (uploadNow) upload(c0x, c0y, c0z, per, per, per);
    }

    private void create() {
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIZE, SIZE, SIZE, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private void upload(int x, int y, int z, int w, int h, int d) {
        ByteBuffer b = cells.duplicate();
        b.clear();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, SIZE);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, SIZE);
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
