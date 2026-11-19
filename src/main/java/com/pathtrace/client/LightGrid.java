package com.pathtrace.client;

import com.pathtrace.PTConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Clustered block lights. The area around the camera (out to the render distance) is split into
 * 8x8x8-block cells; each cell lists the (up to) 32 lights that matter most inside it. The shaders
 * look up the cell of a pixel and only loop over those lights, so the number of lights in the
 * world barely matters and distant lights work the same as near ones.
 * <ul>
 *   <li>light texture: 2 texels per light: (position relative to grid origin, radius),
 *       (rgb intensity, offset code: which way the light was pushed out of its block)</li>
 *   <li>cell texture: 8 texels per cell holding 32 light indices (-1 = none)</li>
 * </ul>
 * Rebuilding (whenever lights change or the camera moves a couple of cells) runs on a background
 * thread: only collecting the lights and uploading the result happen on the render thread, so
 * chunks loading and blocks changing don't stall frames.
 * </ul>
 */
final class LightGrid {
    static final int CELL = 8;
    static final int K = 32;
    static final int MAX_LIGHTS = 4096;
    private static final int LIGHT_TEX_W = 256;      // 128 lights per row
    private static final int LIGHT_TEX_H = MAX_LIGHTS / 128;
    private static final int CELL_TEX_W = 1024;
    /** How far a light is pushed out of a full glowing block, towards its open faces. Must match common.glsl. */
    static final float PUSH = 0.8f;
    /** Light falloff 1 / (1 + FALLOFF d^2); must match common.glsl. */
    static final float FALLOFF = 0.18f;
    /** Average of the shaders' wrap term, for light added back without a surface normal. */
    private static final float RESID_WRAP = 0.6f;

    // ---- what the shaders currently see (textures + the grid they were built for)
    int gx, gy, gz;            // cells per axis
    int ox, oy, oz;            // world block position of the grid's min corner
    int cellTexH;
    private int residTexH;
    private int lightTex = -1, cellTex = -1, residTex = -1;

    // ---- rebuild bookkeeping
    private int builtVersion = -1;
    private double builtStrength = -1;
    private int framesSinceBuild;
    private Job pending;            // being computed on the worker
    private Future<?> pendingFuture;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PathTrace light grid");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    int lightTexture() { return lightTex; }
    int cellTexture() { return cellTex; }
    int residualTexture() { return residTex; }
    float residualTexHeight() { return residTexH; }
    float lightTexWidth() { return LIGHT_TEX_W; }
    float lightTexHeight() { return LIGHT_TEX_H; }
    float cellTexWidth() { return CELL_TEX_W; }

    void invalidate() {
        builtVersion = -1;
    }

    /** One rebuild: inputs collected on the render thread, outputs filled by the worker. */
    private static final class Job {
        int gx, gy, gz, ox, oy, oz;
        int version;
        double strength;
        int lightCount;
        float[] lx, ly, lz, lbx, lby, lbz, lreach;
        int[] llight, lcolor, loffset, lfaces;
        java.util.HashMap<Long, long[]> solid;   // section solid masks around the grid (for residual shadows)
        int cellTexH, residTexH;
        FloatBuffer lightBuf, cellBuf, residBuf;
        volatile boolean done;
    }

    // reusable output buffers (only one job at a time)
    private FloatBuffer lightBuf = BufferUtils.createFloatBuffer(LIGHT_TEX_W * LIGHT_TEX_H * 4);
    private FloatBuffer cellBuf, residBuf;
    private int bufCells = -1;

    void update(EmitterIndex emitters, double cx, double cy, double cz, int renderDistanceChunks) {
        framesSinceBuild++;
        // a finished rebuild: upload it (cheap) and make it current
        if (pending != null) {
            if (!pending.done) return;
            upload(pending);
            pending = null;
            pendingFuture = null;
        }

        int half = MathHelper.clamp(renderDistanceChunks * 16 + 16, 64, 256);
        int ngx = 2 * half / CELL, ngy = 16, ngz = ngx;   // 128 blocks tall around the camera
        int nox = (MathHelper.floor(cx) >> 3 << 3) - half;
        int noy = (MathHelper.floor(cy) >> 3 << 3) - ngy * CELL / 2;
        int noz = (MathHelper.floor(cz) >> 3 << 3) - half;
        boolean resized = ngx != gx || ngy != gy || ngz != gz;
        boolean moved = Math.abs(nox - ox) >= 2 * CELL || Math.abs(noy - oy) >= 2 * CELL || Math.abs(noz - oz) >= 2 * CELL;
        boolean changed = emitters.version != builtVersion && framesSinceBuild >= 8;
        boolean strengthChanged = PTConfig.pointLightStrength != builtStrength;
        if (LightColors.update()) strengthChanged = true;
        if (lightTex != -1 && !resized && !moved && !changed && !strengthChanged) return;

        Job j = new Job();
        j.gx = ngx; j.gy = ngy; j.gz = ngz;
        if (resized || moved || lightTex == -1) {
            j.ox = nox; j.oy = noy; j.oz = noz;
            emitters.ensureScanned(nox - 16, noz - 16, nox + ngx * CELL + 16, noz + ngz * CELL + 16);
            emitters.prune((int) cx, (int) cz, half + 64);
        } else {
            j.ox = ox; j.oy = oy; j.oz = oz;
        }
        j.version = emitters.version;
        j.strength = PTConfig.pointLightStrength;
        collect(j, emitters, cx, cy, cz);
        j.solid = emitters.snapshotOccupancy(j.ox - 16, j.oz - 16, j.ox + j.gx * CELL + 16, j.oz + j.gz * CELL + 16);
        int cells = j.gx * j.gy * j.gz;
        j.cellTexH = (cells * (K / 4) + CELL_TEX_W - 1) / CELL_TEX_W;
        j.residTexH = (cells * 8 + CELL_TEX_W - 1) / CELL_TEX_W;
        if (bufCells != cells) {
            cellBuf = BufferUtils.createFloatBuffer(CELL_TEX_W * j.cellTexH * 4);
            residBuf = BufferUtils.createFloatBuffer(CELL_TEX_W * j.residTexH * 4);
            bufCells = cells;
        }
        j.lightBuf = lightBuf; j.cellBuf = cellBuf; j.residBuf = residBuf;
        pending = j;
        framesSinceBuild = 0;
        builtVersion = j.version;
        builtStrength = j.strength;
        if (lightTex == -1) {   // the very first build: needed before anything can render, do it now
            compute(j);
            upload(j);
            pending = null;
            return;
        }
        final Job job = j;
        pendingFuture = WORKER.submit(() -> {
            try {
                compute(job);
            } catch (Throwable t) {
                com.pathtrace.PathTraceMod.LOG.warn("PathTrace: light grid rebuild failed", t);
            }
            job.done = true;
        });
    }

    /** Render thread: copy the lights near the grid out of the emitter index (nearest first if too many). */
    private static void collect(Job j, EmitterIndex emitters, double cx, double cy, double cz) {
        final int pad = 16;
        final int ox = j.ox, oy = j.oy, oz = j.oz;
        final float[][] tmp = {new float[1024 * 8]};
        final int[] n = {0};
        emitters.forEach(ox - pad, oy - pad, oz - pad, ox + j.gx * CELL + pad, oy + j.gy * CELL + pad, oz + j.gz * CELL + pad,
                (x, y, z, light, color, offset, faces) -> {
                    if (n[0] * 8 + 8 > tmp[0].length) tmp[0] = Arrays.copyOf(tmp[0], tmp[0].length * 2);
                    float[] t = tmp[0];
                    int o = n[0]++ * 8;
                    float px = x + 0.5f, py = y + 0.5f, pz = z + 0.5f;
                    t[o] = x; t[o + 1] = y; t[o + 2] = z; t[o + 3] = light; t[o + 4] = color; t[o + 5] = offset; t[o + 6] = faces;
                    t[o + 7] = (float) ((px - cx) * (px - cx) + (py - cy) * (py - cy) + (pz - cz) * (pz - cz));
                });
        int total = n[0];
        float[] t = tmp[0];
        Integer[] order = null;
        int count = Math.min(total, MAX_LIGHTS);
        if (total > MAX_LIGHTS) {   // keep the nearest MAX_LIGHTS (one sort, not a scan per extra light)
            order = new Integer[total];
            for (int i = 0; i < total; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Float.compare(t[a * 8 + 7], t[b * 8 + 7]));
        }
        j.lightCount = count;
        j.lx = new float[count]; j.ly = new float[count]; j.lz = new float[count];
        j.lbx = new float[count]; j.lby = new float[count]; j.lbz = new float[count]; j.lreach = new float[count];
        j.llight = new int[count]; j.lcolor = new int[count]; j.loffset = new int[count]; j.lfaces = new int[count];
        for (int i = 0; i < count; i++) {
            int o = (order == null ? i : order[i]) * 8;
            int x = (int) t[o], y = (int) t[o + 1], z = (int) t[o + 2];
            int offset = (int) t[o + 5];
            float px = x + 0.5f, py = y + 0.5f, pz = z + 0.5f;
            if (offset != 13) {
                float dx = offset % 3 - 1, dy = (offset / 3) % 3 - 1, dz = offset / 9 - 1;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                px += dx / len * PUSH;
                py += dy / len * PUSH;
                pz += dz / len * PUSH;
            }
            j.lx[i] = px - ox; j.ly[i] = py - oy; j.lz[i] = pz - oz;
            j.lbx[i] = x + 0.5f - ox; j.lby[i] = y + 0.5f - oy; j.lbz[i] = z + 0.5f - oz;
            j.llight[i] = (int) t[o + 3]; j.lcolor[i] = (int) t[o + 4]; j.loffset[i] = offset; j.lfaces[i] = (int) t[o + 6];
            j.lreach[i] = j.llight[i] + 0.5f;
        }
    }

    /** Worker thread: per-cell light lists, residuals, and the texture contents. No GL here. */
    private static void compute(Job j) {
        final int gx = j.gx, gy = j.gy, gz = j.gz;
        final int cells = gx * gy * gz;
        final int lightCount = j.lightCount;
        final float[] lx = j.lx, ly = j.ly, lz = j.lz, lreach = j.lreach;
        final int[] llight = j.llight, lcolor = j.lcolor;
        int[] cellIdx = new int[cells * K];
        float[] cellScore = new float[cells * K];
        Arrays.fill(cellIdx, -1);
        float strength = (float) j.strength;

        // per cell, keep the K lights with the highest estimated contribution
        for (int i = 0; i < lightCount; i++) {
            float r = lreach[i];
            int c0x = Math.max(0, (int) Math.floor((lx[i] - r) / CELL)), c1x = Math.min(gx - 1, (int) Math.floor((lx[i] + r) / CELL));
            int c0y = Math.max(0, (int) Math.floor((ly[i] - r) / CELL)), c1y = Math.min(gy - 1, (int) Math.floor((ly[i] + r) / CELL));
            int c0z = Math.max(0, (int) Math.floor((lz[i] - r) / CELL)), c1z = Math.min(gz - 1, (int) Math.floor((lz[i] + r) / CELL));
            for (int cz2 = c0z; cz2 <= c1z; cz2++)
                for (int cy2 = c0y; cy2 <= c1y; cy2++)
                    for (int cx2 = c0x; cx2 <= c1x; cx2++) {
                        if (!emitsInto(j, i, cx2, cy2, cz2)) continue;
                        float dx = Math.max(0, Math.max(cx2 * CELL - lx[i], lx[i] - (cx2 + 1) * CELL));
                        float dy = Math.max(0, Math.max(cy2 * CELL - ly[i], ly[i] - (cy2 + 1) * CELL));
                        float dz = Math.max(0, Math.max(cz2 * CELL - lz[i], lz[i] - (cz2 + 1) * CELL));
                        float d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 >= r * r) continue;
                        float score = llight[i] / (1f + FALLOFF * d2);
                        int base = (((cz2 * gy) + cy2) * gx + cx2) * K;
                        int min = base;
                        for (int k = base + 1; k < base + K; k++) if (cellScore[k] < cellScore[min]) min = k;
                        if (score > cellScore[min]) {
                            cellScore[min] = score;
                            cellIdx[min] = i;
                        }
                    }
        }

        // lights that reach a cell but didn't make its list: their (unshadowed) light at the cell's
        // 8 corners, so the shader can add it back smoothly instead of the light vanishing at the border
        float[] resid = new float[cells * 8 * 4];
        for (int i = 0; i < lightCount; i++) {
            float r = lreach[i];
            float[] rgb = LightColors.RGB[Math.min(lcolor[i], LightColors.RGB.length - 1)];
            float k = llight[i] / 15f * strength * RESID_WRAP;
            int c0x = Math.max(0, (int) Math.floor((lx[i] - r) / CELL)), c1x = Math.min(gx - 1, (int) Math.floor((lx[i] + r) / CELL));
            int c0y = Math.max(0, (int) Math.floor((ly[i] - r) / CELL)), c1y = Math.min(gy - 1, (int) Math.floor((ly[i] + r) / CELL));
            int c0z = Math.max(0, (int) Math.floor((lz[i] - r) / CELL)), c1z = Math.min(gz - 1, (int) Math.floor((lz[i] + r) / CELL));
            for (int cz2 = c0z; cz2 <= c1z; cz2++)
                for (int cy2 = c0y; cy2 <= c1y; cy2++)
                    for (int cx2 = c0x; cx2 <= c1x; cx2++) {
                        if (!emitsInto(j, i, cx2, cy2, cz2)) continue;
                        int c = (cz2 * gy + cy2) * gx + cx2;
                        int base = c * K;
                        if (cellIdx[base + K - 1] < 0) continue;      // list not full: nothing was dropped
                        boolean listed = false;
                        for (int q = base; q < base + K; q++) if (cellIdx[q] == i) { listed = true; break; }
                        if (listed) continue;
                        for (int corner = 0; corner < 8; corner++) {
                            float px = (cx2 + (corner & 1)) * CELL, py = (cy2 + ((corner >> 1) & 1)) * CELL, pz = (cz2 + (corner >> 2)) * CELL;
                            float dx = px - lx[i], dy = py - ly[i], dz = pz - lz[i];
                            float d2 = dx * dx + dy * dy + dz * dz;
                            if (d2 >= r * r) continue;
                            // this light is added back without per-pixel shadows, so at least check that
                            // nothing solid is between it and the corner (lava under the ground glowing
                            // through it otherwise). Corners are shared by neighbouring cells: stays smooth.
                            if (blocked(j, lx[i], ly[i], lz[i], j.lbx[i], j.lby[i], j.lbz[i], px, py, pz)) continue;
                            float d = (float) Math.sqrt(d2);
                            float x = Math.max(0f, 1f - d / r);
                            float f = k * x * x / (1f + FALLOFF * d2);
                            int o = (c * 8 + corner) * 4;
                            resid[o] += rgb[0] * f; resid[o + 1] += rgb[1] * f; resid[o + 2] += rgb[2] * f; resid[o + 3] = 1f;
                        }
                    }
        }
        // corner 0's w: 1 if the cell has a residual, plus 2 * the number of lights in its list
        for (int c = 0; c < cells; c++) {
            int o = c * 8 * 4;
            boolean any = false;
            for (int corner = 0; corner < 8; corner++) if (resid[o + corner * 4 + 3] > 0f) { any = true; break; }
            int n = 0;
            for (int q = c * K; q < c * K + K; q++) if (cellIdx[q] >= 0) n++;
            resid[o + 3] = (any ? 1f : 0f) + 2f * n;
        }
        FloatBuffer rb = j.residBuf;
        rb.clear();
        rb.put(resid);
        while (rb.position() < rb.capacity()) rb.put(0f);
        rb.flip();

        FloatBuffer lb = j.lightBuf;
        lb.clear();
        for (int i = 0; i < LIGHT_TEX_W * LIGHT_TEX_H / 2; i++) {
            if (i < lightCount) {
                float[] rgb = LightColors.RGB[Math.min(lcolor[i], LightColors.RGB.length - 1)];
                float k = llight[i] / 15f * strength;
                lb.put(lx[i]).put(ly[i]).put(lz[i]).put(lreach[i]);
                lb.put(rgb[0] * k).put(rgb[1] * k).put(rgb[2] * k).put((float) (j.loffset[i] + 32 * llight[i] + 512 * j.lfaces[i]));
            } else {
                for (int q = 0; q < 8; q++) lb.put(0f);
            }
        }
        lb.flip();

        // cell lists, strongest first so the shader can stop at the first -1
        FloatBuffer cb = j.cellBuf;
        cb.clear();
        int[] tmp = new int[K];
        float[] tmpS = new float[K];
        for (int c = 0; c < cells; c++) {
            int base = c * K;
            int n = 0;
            for (int k = 0; k < K; k++) if (cellIdx[base + k] >= 0) { tmp[n] = cellIdx[base + k]; tmpS[n] = cellScore[base + k]; n++; }
            for (int a = 1; a < n; a++) {         // insertion sort, descending score
                int ti = tmp[a]; float ts = tmpS[a]; int b = a - 1;
                while (b >= 0 && tmpS[b] < ts) { tmp[b + 1] = tmp[b]; tmpS[b + 1] = tmpS[b]; b--; }
                tmp[b + 1] = ti; tmpS[b + 1] = ts;
            }
            for (int k = 0; k < K; k++) cb.put(k < n ? tmp[k] : -1f);
        }
        while (cb.position() < cb.capacity()) cb.put(-1f);
        cb.flip();
    }

    /** Render thread: put a finished job's data into the textures and make its grid current. */
    private void upload(Job j) {
        if (lightTex == -1) lightTex = makeTex(LIGHT_TEX_W, LIGHT_TEX_H);
        if (cellTex == -1 || j.cellTexH != cellTexH) {
            if (cellTex != -1) GlStateManager.deleteTexture(cellTex);
            cellTex = makeTex(CELL_TEX_W, j.cellTexH);
        }
        if (residTex == -1 || j.residTexH != residTexH) {
            if (residTex != -1) GlStateManager.deleteTexture(residTex);
            residTex = makeTex(CELL_TEX_W, j.residTexH);
        }
        GlStateManager.bindTexture(lightTex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, LIGHT_TEX_W, LIGHT_TEX_H, GL11.GL_RGBA, GL11.GL_FLOAT, j.lightBuf);
        GlStateManager.bindTexture(cellTex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, CELL_TEX_W, j.cellTexH, GL11.GL_RGBA, GL11.GL_FLOAT, j.cellBuf);
        GlStateManager.bindTexture(residTex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, CELL_TEX_W, j.residTexH, GL11.GL_RGBA, GL11.GL_FLOAT, j.residBuf);
        gx = j.gx; gy = j.gy; gz = j.gz;
        ox = j.ox; oy = j.oy; oz = j.oz;
        cellTexH = j.cellTexH;
        residTexH = j.residTexH;
    }

    private static int makeTex(int w, int h) {
        int id = GlStateManager.generateTexture();
        GlStateManager.bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        return id;
    }

    /** Is any solid block between light point (lx..) and point (px..)? (grid-local coordinates; the light's own block doesn't count) */
    private static boolean blocked(Job j, float lx, float ly, float lz, float bx, float by, float bz, float px, float py, float pz) {
        int ownX = (int) Math.floor(bx), ownY = (int) Math.floor(by), ownZ = (int) Math.floor(bz);
        float dx = px - lx, dy = py - ly, dz = pz - lz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3f) return false;
        dx /= len; dy /= len; dz /= len;
        int x = (int) Math.floor(lx), y = (int) Math.floor(ly), z = (int) Math.floor(lz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        float tdx = Math.abs(1f / (Math.abs(dx) < 1e-6f ? 1e-6f : dx)), tdy = Math.abs(1f / (Math.abs(dy) < 1e-6f ? 1e-6f : dy)), tdz = Math.abs(1f / (Math.abs(dz) < 1e-6f ? 1e-6f : dz));
        float tx = (dx > 0 ? (x + 1 - lx) : (lx - x)) * tdx, ty = (dy > 0 ? (y + 1 - ly) : (ly - y)) * tdy, tz = (dz > 0 ? (z + 1 - lz) : (lz - z)) * tdz;
        float t = 0;
        for (int step = 0; step < 64 && t < len - 0.5f; step++) {
            if (!(x == ownX && y == ownY && z == ownZ) && solidAt(j, x + j.ox, y + j.oy, z + j.oz)) return true;
            if (tx < ty && tx < tz) { x += sx; t = tx; tx += tdx; }
            else if (ty < tz) { y += sy; t = ty; ty += tdy; }
            else { z += sz; t = tz; tz += tdz; }
        }
        return false;
    }

    private static boolean solidAt(Job j, int wx, int wy, int wz) {
        if (wy < 0 || wy > 255 || j.solid == null) return false;
        long[] m = j.solid.get(EmitterIndex.key(wx >> 4, wy >> 4, wz >> 4));
        if (m == null) return false;
        int bit = ((wy & 15) * 16 + (wz & 15)) * 16 + (wx & 15);
        return (m[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    /**
     * Light culling: a glowing block only shines out of its open faces, so a cell lying entirely
     * behind all of them can't get any of its light (e.g. glowstone in a wall, seen from the room
     * on the other side). Lights with no face info (torches, ...) shine everywhere.
     */
    private static boolean emitsInto(Job j, int i, int cx, int cy, int cz) {
        int m = j.lfaces[i];
        if (m == 0) return true;
        float x0 = cx * CELL, x1 = x0 + CELL, y0 = cy * CELL, y1 = y0 + CELL, z0 = cz * CELL, z1 = z0 + CELL;
        float bx = j.lbx[i], by = j.lby[i], bz = j.lbz[i];
        return ((m & 1) != 0 && y0 < by - 0.45f) || ((m & 2) != 0 && y1 > by + 0.45f)
                || ((m & 4) != 0 && z0 < bz - 0.45f) || ((m & 8) != 0 && z1 > bz + 0.45f)
                || ((m & 16) != 0 && x0 < bx - 0.45f) || ((m & 32) != 0 && x1 > bx + 0.45f);
    }
}
