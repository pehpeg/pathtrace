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
    private boolean built, rebuilding;
    private int fox, foz, rebuildPos;   // origin the CPU copy is being filled for

    int texture() { return tex; }
    int coarseTexture() { return coarseTex; }
    boolean isBuilt() { return built; }

    void invalidate() {
        built = false;
        rebuilding = false;
        seen.clear();
        fading.clear();
    }

    void update(EmitterIndex index, double cx, double cz, int renderDistanceChunks) {
        int newExtent = renderDistanceChunks <= 8 ? 256 : 512;
        if (newExtent != extent || tex == -1) {
            extent = newExtent;
            allocate();
            built = false;
            rebuilding = false;
        }
        int nox = (MathHelper.floor(cx) >> 4 << 4) - extent / 2;
        int noz = (MathHelper.floor(cz) >> 4 << 4) - extent / 2;
        if (rebuilding) {
            // moved: refill the grid for the new origin a bit per frame into the CPU copy, while the
            // shaders keep using the old one; switch over when complete
            long deadline = System.nanoTime() + 1_500_000L;
            int n = extent / 16, total = n * n * 16;
            while (rebuildPos < total && System.nanoTime() < deadline) {
                int i = rebuildPos++;
                int sy = i % 16, sz = (i / 16) % n, sx = i / 16 / n;
                fillSection(index, (fox >> 4) + sx, sy, (foz >> 4) + sz);
            }
            if (rebuildPos >= total) {
                uploadBox(bits, tex, 0, 0, 0, extent / 8, HEIGHT, extent, extent / 8, HEIGHT);
                uploadBox(coarse, coarseTex, 0, 0, 0, extent / 8, HEIGHT / 8, extent / 8, extent / 8, HEIGHT / 8);
                ox = fox;
                oz = foz;
                rebuilding = false;
            }
            return;
        }
        if (!built) {
            ox = fox = nox;
            oz = foz = noz;
            rebuildAll(index);
            built = true;
            while (index.pollChangedOccupancy() != null) { /* all included */ }
            return;
        }
        if (Math.abs(nox - ox) >= 32 || Math.abs(noz - oz) >= 32) {
            fox = nox;
            foz = noz;
            rebuilding = true;
            rebuildPos = 0;
            while (index.pollChangedOccupancy() != null) { /* the refill reads everything fresh */ }
            return;
        }
        int budget = 256;
        long deadline = System.nanoTime() + 1_000_000L;
        Long k;
        while (budget-- > 0 && System.nanoTime() < deadline && (k = index.pollChangedOccupancy()) != null) {
            BlockPos s = BlockPos.fromLong(k);
            long col = colKey(s.getX(), s.getZ());
            // the first data from a chunk column (it just loaded): its shadows fade in
            if (!seen.contains(col) && index.occupancy(s.getX(), s.getY(), s.getZ()) != null && !fading.containsKey(col))
                fading.put(col, 0f);
            if (fillSection(index, s.getX(), s.getY(), s.getZ())) upload(s.getX(), s.getY(), s.getZ());
        }
        stepFades();
    }

    // ---- fade-in of newly loaded chunks: their coarse texels go 55 -> 255 over FADE_FRAMES
    private static final int FADE_FRAMES = 45;
    private final java.util.HashSet<Long> seen = new java.util.HashSet<>();
    private final java.util.HashMap<Long, Float> fading = new java.util.HashMap<>();

    private static long colKey(int sx, int sz) {
        return ((long) sx << 32) ^ (sz & 0xFFFFFFFFL);
    }

    private byte presence(long col) {
        Float f = fading.get(col);
        return (byte) (f == null ? 255 : 55 + Math.round(200 * f));
    }

    private void stepFades() {
        if (fading.isEmpty()) return;
        int cw = extent / 8, ch = HEIGHT / 8;
        java.util.Iterator<java.util.Map.Entry<Long, Float>> it = fading.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, Float> e = it.next();
            float f = Math.min(1f, e.getValue() + 1f / FADE_FRAMES);
            long col = e.getKey();
            int sx = (int) (col >> 32), sz = (int) col;
            int bx = (sx << 4) - ox, bz = (sz << 4) - oz;
            if (f >= 1f) it.remove(); else e.setValue(f);
            if (bx < 0 || bz < 0 || bx >= extent || bz >= extent) continue;
            byte v = presence(col);
            for (int cz = bz / 8; cz < bz / 8 + 2; cz++)
                for (int cy = 0; cy < ch; cy++)
                    for (int cx = bx / 8; cx < bx / 8 + 2; cx++) {
                        int i = (cz * ch + cy) * cw + cx;
                        if (coarse.get(i) != 0) coarse.put(i, v);
                    }
            uploadBox(coarse, coarseTex, bx / 8, 0, bz / 8, 2, ch, 2, cw, ch);
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
        int sx0 = fox >> 4, sz0 = foz >> 4, n = extent / 16;
        for (int sx = sx0; sx < sx0 + n; sx++)
            for (int sz = sz0; sz < sz0 + n; sz++)
                for (int sy = 0; sy < 16; sy++) fillSection(index, sx, sy, sz);
        uploadBox(bits, tex, 0, 0, 0, extent / 8, HEIGHT, extent, extent / 8, HEIGHT);
        uploadBox(coarse, coarseTex, 0, 0, 0, extent / 8, HEIGHT / 8, extent / 8, extent / 8, HEIGHT / 8);
    }

    /** Copy one 16^3 section into the grids. Returns false if it's outside the grid. */
    private boolean fillSection(EmitterIndex index, int sx, int sy, int sz) {
        int bx = (sx << 4) - fox, by = sy << 4, bz = (sz << 4) - foz;
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
                            coarseSolid[(cz * 2 + cy) * 2 + cx] ? presence(colKey(sx, sz)) : 0);
        if (mask != null) seen.add(colKey(sx, sz));
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
