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

/**
 * Clustered block lights. The area around the camera (out to the render distance) is split into
 * 8x8x8-block cells; each cell lists the (up to) 16 lights that matter most inside it. The shaders
 * look up the cell of a pixel and only loop over those lights, so the number of lights in the
 * world barely matters and distant lights work the same as near ones.
 * <ul>
 *   <li>light texture: 2 texels per light: (position relative to grid origin, radius), (rgb intensity)</li>
 *   <li>cell texture: 4 texels per cell holding 16 light indices (-1 = none)</li>
 * </ul>
 */
final class LightGrid {
    static final int CELL = 8;
    static final int K = 16;
    static final int MAX_LIGHTS = 4096;
    private static final int LIGHT_TEX_W = 256;      // 128 lights per row
    private static final int LIGHT_TEX_H = MAX_LIGHTS / 128;
    private static final int CELL_TEX_W = 1024;

    int gx, gy, gz;            // cells per axis
    int ox, oy, oz;            // world block position of the grid's min corner
    int lightCount;
    int cellTexH;
    private int lightTex = -1, cellTex = -1;
    private int builtVersion = -1;
    private double builtStrength = -1;
    private int framesSinceBuild;

    private final float[] lx = new float[MAX_LIGHTS], ly = new float[MAX_LIGHTS], lz = new float[MAX_LIGHTS];
    private final int[] llight = new int[MAX_LIGHTS], lcolor = new int[MAX_LIGHTS];
    private final double[] ld2 = new double[MAX_LIGHTS];
    private int[] cellIdx = new int[0];
    private float[] cellScore = new float[0];
    private final FloatBuffer lightBuf = BufferUtils.createFloatBuffer(LIGHT_TEX_W * LIGHT_TEX_H * 4);
    private FloatBuffer cellBuf;

    int lightTexture() { return lightTex; }
    int cellTexture() { return cellTex; }
    float lightTexWidth() { return LIGHT_TEX_W; }
    float lightTexHeight() { return LIGHT_TEX_H; }
    float cellTexWidth() { return CELL_TEX_W; }

    void invalidate() {
        builtVersion = -1;
    }

    void update(EmitterIndex emitters, double cx, double cy, double cz, int renderDistanceChunks) {
        int half = MathHelper.clamp(renderDistanceChunks * 16 + 16, 64, 256);
        int ngx = 2 * half / CELL, ngy = 32, ngz = ngx;
        int nox = (MathHelper.floor(cx) >> 3 << 3) - half;
        int noy = (MathHelper.floor(cy) >> 3 << 3) - ngy * CELL / 2;
        int noz = (MathHelper.floor(cz) >> 3 << 3) - half;
        boolean resized = ngx != gx || ngy != gy || ngz != gz;
        boolean moved = Math.abs(nox - ox) >= 2 * CELL || Math.abs(noy - oy) >= 2 * CELL || Math.abs(noz - oz) >= 2 * CELL;
        framesSinceBuild++;
        boolean changed = emitters.version != builtVersion && framesSinceBuild >= 8;
        boolean strengthChanged = PTConfig.pointLightStrength != builtStrength;
        if (lightTex != -1 && !resized && !moved && !changed && !strengthChanged) return;

        if (resized || lightTex == -1) {
            gx = ngx; gy = ngy; gz = ngz;
            allocate();
        }
        if (resized || moved || lightTex == -1) {
            ox = nox; oy = noy; oz = noz;
            emitters.ensureScanned(ox - 16, oz - 16, ox + gx * CELL + 16, oz + gz * CELL + 16);
            emitters.prune((int) cx, (int) cz, half + 64);
        }
        rebuild(emitters, cx, cy, cz);
        builtVersion = emitters.version;
        builtStrength = PTConfig.pointLightStrength;
        framesSinceBuild = 0;
    }

    private void allocate() {
        int cells = gx * gy * gz;
        cellIdx = new int[cells * K];
        cellScore = new float[cells * K];
        cellTexH = (cells * 4 + CELL_TEX_W - 1) / CELL_TEX_W;
        cellBuf = BufferUtils.createFloatBuffer(CELL_TEX_W * cellTexH * 4);
        if (lightTex == -1) lightTex = makeTex(LIGHT_TEX_W, LIGHT_TEX_H);
        if (cellTex != -1) GlStateManager.deleteTexture(cellTex);
        cellTex = makeTex(CELL_TEX_W, cellTexH);
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

    private void rebuild(EmitterIndex emitters, double cx, double cy, double cz) {
        // 1. collect lights in the grid (plus their reach), nearest first if there are too many
        lightCount = 0;
        final int[] overflow = {0};
        int pad = 16;
        emitters.forEach(ox - pad, oy - pad, oz - pad, ox + gx * CELL + pad, oy + gy * CELL + pad, oz + gz * CELL + pad,
                (x, y, z, light, color) -> {
                    double d2 = (x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) + (z + 0.5 - cz) * (z + 0.5 - cz);
                    int i = lightCount;
                    if (i == MAX_LIGHTS) {
                        overflow[0]++;
                        // replace the farthest light if this one is nearer
                        int far = 0;
                        for (int j = 1; j < MAX_LIGHTS; j++) if (ld2[j] > ld2[far]) far = j;
                        if (ld2[far] <= d2) return;
                        i = far;
                    } else {
                        lightCount++;
                    }
                    lx[i] = x + 0.5f - ox; ly[i] = y + 0.5f - oy; lz[i] = z + 0.5f - oz;
                    llight[i] = light; lcolor[i] = color; ld2[i] = d2;
                });

        // 2. per cell, keep the K lights with the highest estimated contribution
        Arrays.fill(cellIdx, -1);
        Arrays.fill(cellScore, 0f);
        float strength = (float) PTConfig.pointLightStrength;
        for (int i = 0; i < lightCount; i++) {
            float r = llight[i] + 0.5f;
            int c0x = Math.max(0, (int) Math.floor((lx[i] - r) / CELL)), c1x = Math.min(gx - 1, (int) Math.floor((lx[i] + r) / CELL));
            int c0y = Math.max(0, (int) Math.floor((ly[i] - r) / CELL)), c1y = Math.min(gy - 1, (int) Math.floor((ly[i] + r) / CELL));
            int c0z = Math.max(0, (int) Math.floor((lz[i] - r) / CELL)), c1z = Math.min(gz - 1, (int) Math.floor((lz[i] + r) / CELL));
            for (int cz2 = c0z; cz2 <= c1z; cz2++)
                for (int cy2 = c0y; cy2 <= c1y; cy2++)
                    for (int cx2 = c0x; cx2 <= c1x; cx2++) {
                        // distance from the light to the nearest point of the cell
                        float dx = Math.max(0, Math.max(cx2 * CELL - lx[i], lx[i] - (cx2 + 1) * CELL));
                        float dy = Math.max(0, Math.max(cy2 * CELL - ly[i], ly[i] - (cy2 + 1) * CELL));
                        float dz = Math.max(0, Math.max(cz2 * CELL - lz[i], lz[i] - (cz2 + 1) * CELL));
                        float d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 >= r * r) continue;
                        float score = llight[i] / (1f + 0.3f * d2);
                        int base = (((cz2 * gy) + cy2) * gx + cx2) * K;
                        int min = base;
                        for (int k = base + 1; k < base + K; k++) if (cellScore[k] < cellScore[min]) min = k;
                        if (score > cellScore[min]) {
                            cellScore[min] = score;
                            cellIdx[min] = i;
                        }
                    }
        }

        // 3. upload
        lightBuf.clear();
        for (int i = 0; i < LIGHT_TEX_W * LIGHT_TEX_H / 2; i++) {
            if (i < lightCount) {
                float[] rgb = LightColors.RGB[Math.min(lcolor[i], LightColors.RGB.length - 1)];
                float k = llight[i] / 15f * strength;
                lightBuf.put(lx[i]).put(ly[i]).put(lz[i]).put(llight[i] + 0.5f);
                lightBuf.put(rgb[0] * k).put(rgb[1] * k).put(rgb[2] * k).put(0f);
            } else {
                for (int j = 0; j < 8; j++) lightBuf.put(0f);
            }
        }
        lightBuf.flip();
        GlStateManager.bindTexture(lightTex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, LIGHT_TEX_W, LIGHT_TEX_H, GL11.GL_RGBA, GL11.GL_FLOAT, lightBuf);

        // cell lists, strongest first so the shader can stop at the first -1
        cellBuf.clear();
        int cells = gx * gy * gz;
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
            for (int k = 0; k < K; k++) cellBuf.put(k < n ? tmp[k] : -1f);
        }
        while (cellBuf.position() < cellBuf.capacity()) cellBuf.put(-1f);
        cellBuf.flip();
        GlStateManager.bindTexture(cellTex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, CELL_TEX_W, cellTexH, GL11.GL_RGBA, GL11.GL_FLOAT, cellBuf);
    }
}
