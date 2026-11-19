package com.pathtrace.client;

import com.pathtrace.PathTraceMod;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Pixel-accurate shapes of non-full blocks for shadows: each distinct block state gets a 16x16x16
 * "brick" (one cell per texture pixel), made by rasterising its baked model's quads and keeping only
 * texture pixels that aren't transparent. So iron bars, fences, plants etc. let light through
 * their gaps. Bricks live in one 3D texture (32 x 32 bricks of 16^3 = 1024 shapes).
 * Blocks without a regular model (chests...) fall back to their collision box.
 */
final class ShapeAtlas {
    static final int RES = 16;
    static final int BRICKS = 32;                // per row/column
    static final int MAX = BRICKS * BRICKS;
    static final int TEX_W = BRICKS * RES;       // 512
    static final int SAMPLES = 40;               // rasterisation samples per quad edge

    /** Shape id meaning: 0 = nothing solid, -1 = completely full, k > 0 = brick k - 1. */
    private final HashMap<IBlockState, Integer> cache = new HashMap<>();
    private final ByteBuffer brick = BufferUtils.createByteBuffer(RES * RES * RES);
    private final boolean[] cells = new boolean[RES * RES * RES];
    private final List<AxisAlignedBB> boxes = new ArrayList<>();
    private int tex = -1;
    private int count;

    private ByteBuffer atlasPixels;
    private int atlasW, atlasH;

    int texture() {
        if (tex == -1) create();
        return tex;
    }

    void clear() {
        cache.clear();
        count = 0;
        atlasPixels = null;
    }

    int shapeFor(World world, BlockPos pos, IBlockState state) {
        IBlockState actual;
        try {
            actual = state.getActualState(world, pos);
        } catch (Throwable t) {
            actual = state;
        }
        Integer cached = cache.get(actual);
        if (cached != null) return cached;
        int id;
        try {
            id = build(world, pos, actual);
        } catch (Throwable t) {
            PathTraceMod.LOG.debug("PathTrace: no shape for " + actual, t);
            id = 0;
        }
        cache.put(actual, id);
        return id;
    }

    private int build(World world, BlockPos pos, IBlockState actual) {
        java.util.Arrays.fill(cells, false);
        // Blocks drawn by a tile-entity renderer (chests, signs, beds, skulls, banners...) have no real
        // model: asking for one gives the "missing" cube, whose faces rasterise to a hollow full-block
        // shell that traps every ray starting on the chest and blacks it out. Use their boxes instead.
        boolean modelled = actual.getRenderType() == EnumBlockRenderType.MODEL;
        if (modelled) {
            IBakedModel m = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(actual);
            modelled = m != Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelManager().getMissingModel();
        }
        boolean any = modelled && rasteriseModel(world, pos, actual);
        if (!any) any = rasteriseBoxes(world, pos, actual);
        if (!any) return 0;
        int n = 0;
        for (boolean c : cells) if (c) n++;
        if (n == cells.length) return -1;
        if (count >= MAX) return 0;            // atlas full: this shape won't cast shadows
        int id = count++;
        brick.clear();
        for (int i = 0; i < cells.length; i++) brick.put(cells[i] ? (byte) 255 : 0);
        brick.flip();
        int bx = (id % BRICKS) * RES, by = (id / BRICKS) * RES;
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture());
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, bx, by, 0, RES, RES, RES, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, brick);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return id + 1;
    }

    /** Lay the model's quads into the grid, skipping transparent texture pixels. */
    private boolean rasteriseModel(World world, BlockPos pos, IBlockState actual) {
        if (atlasPixels == null) readAtlas();
        Minecraft mc = Minecraft.getMinecraft();
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        IBlockState ext = actual;
        try {
            ext = actual.getBlock().getExtendedState(actual, world, pos);
        } catch (Throwable ignored) {
        }
        boolean any = false;
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(ext, null, 0L));
        for (EnumFacing f : EnumFacing.values()) quads.addAll(model.getQuads(ext, f, 0L));
        float[] x = new float[4], y = new float[4], z = new float[4], u = new float[4], v = new float[4];
        for (BakedQuad q : quads) {
            VertexFormat fmt = q.getFormat();
            int stride = fmt.getIntegerSize();
            int uvOff = fmt.getUvOffsetById(0) / 4;
            int[] data = q.getVertexData();
            for (int i = 0; i < 4; i++) {
                int b = i * stride;
                x[i] = Float.intBitsToFloat(data[b]);
                y[i] = Float.intBitsToFloat(data[b + 1]);
                z[i] = Float.intBitsToFloat(data[b + 2]);
                u[i] = Float.intBitsToFloat(data[b + uvOff]);
                v[i] = Float.intBitsToFloat(data[b + uvOff + 1]);
            }
            // A face lying exactly on a cell border (the top of a 15/16 block like grass path or farmland,
            // a slab's top...) must fill the cell on the block's side of it, not the empty one above:
            // otherwise the empty layer over the block counts as solid and every ray skimming the
            // floor gets blocked at block edges (dark lines along floor seams). So sample half a cell
            // inwards, against the face's outward normal.
            float ax = x[1] - x[0], ay = y[1] - y[0], az = z[1] - z[0];
            float bx = x[2] - x[0], by = y[2] - y[0], bz = z[2] - z[0];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float in = nl > 1e-9f ? 0.5f / RES / nl : 0f;
            nx *= in; ny *= in; nz *= in;
            for (int si = 0; si < SAMPLES; si++) {
                float s = (si + 0.5f) / SAMPLES;
                for (int ti = 0; ti < SAMPLES; ti++) {
                    float t = (ti + 0.5f) / SAMPLES;
                    // bilinear over the quad: v0..v1 along s, v3..v2 opposite edge, t across
                    float w0 = (1 - s) * (1 - t), w1 = s * (1 - t), w2 = s * t, w3 = (1 - s) * t;
                    float pu = u[0] * w0 + u[1] * w1 + u[2] * w2 + u[3] * w3;
                    float pv = v[0] * w0 + v[1] * w1 + v[2] * w2 + v[3] * w3;
                    if (alphaAt(pu, pv) < 128) continue;
                    float px = x[0] * w0 + x[1] * w1 + x[2] * w2 + x[3] * w3;
                    float py = y[0] * w0 + y[1] * w1 + y[2] * w2 + y[3] * w3;
                    float pz = z[0] * w0 + z[1] * w1 + z[2] * w2 + z[3] * w3;
                    int cx = cell(px - nx), cy = cell(py - ny), cz = cell(pz - nz);
                    cells[(cz * RES + cy) * RES + cx] = true;
                    any = true;
                }
            }
        }
        return any;
    }

    private boolean rasteriseBoxes(World world, BlockPos pos, IBlockState actual) {
        boxes.clear();
        actual.addCollisionBoxToList(world, pos, new AxisAlignedBB(pos).grow(0.01), boxes, null, true);
        if (boxes.isEmpty()) {
            AxisAlignedBB bb = actual.getBoundingBox(world, pos);
            if (bb == null || bb == Block.NULL_AABB) return false;
            boxes.add(bb.offset(pos));
        }
        boolean any = false;
        for (AxisAlignedBB b : boxes) {
            int x0 = cell(b.minX - pos.getX()), x1 = cell(b.maxX - pos.getX() - 1e-4);
            int y0 = cell(b.minY - pos.getY()), y1 = cell(b.maxY - pos.getY() - 1e-4);
            int z0 = cell(b.minZ - pos.getZ()), z1 = cell(b.maxZ - pos.getZ() - 1e-4);
            for (int cz = z0; cz <= z1; cz++)
                for (int cy = y0; cy <= y1; cy++)
                    for (int cx = x0; cx <= x1; cx++) {
                        cells[(cz * RES + cy) * RES + cx] = true;
                        any = true;
                    }
        }
        return any;
    }

    private static int cell(double v) {
        return Math.max(0, Math.min(RES - 1, (int) Math.floor(v * RES)));
    }

    private int alphaAt(float u, float v) {
        if (atlasPixels == null) return 255;
        int px = Math.max(0, Math.min(atlasW - 1, (int) (u * atlasW)));
        int py = Math.max(0, Math.min(atlasH - 1, (int) (v * atlasH)));
        return atlasPixels.get((py * atlasW + px) * 4 + 3) & 255;
    }

    /** Read the block texture atlas back from the GPU once, for its alpha channel. */
    private void readAtlas() {
        try {
            int id = Minecraft.getMinecraft().getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).getGlTextureId();
            GlStateManager.bindTexture(id);
            atlasW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            atlasH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            ByteBuffer buf = BufferUtils.createByteBuffer(atlasW * atlasH * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            atlasPixels = buf;
        } catch (Throwable t) {
            PathTraceMod.LOG.warn("PathTrace: couldn't read the block atlas; shapes ignore transparency", t);
            atlasPixels = null;
            atlasW = atlasH = 0;
        }
    }

    private void create() {
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, TEX_W, TEX_W, RES, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }
}
