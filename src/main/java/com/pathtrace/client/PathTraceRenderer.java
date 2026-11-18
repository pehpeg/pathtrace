package com.pathtrace.client;

import com.pathtrace.PTConfig;
import com.pathtrace.PathTraceMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector4f;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Frame pipeline (after the world is drawn, in RenderWorldLastEvent):
 * <ol>
 *   <li>copy the frame's colour (fullbright = albedo) and depth</li>
 *   <li>gbuffer: exact surface position + normal per pixel (view ray traced through the voxel grid)</li>
 *   <li>probe: trace a small sphere of rays from the eye (used to light the hand)</li>
 *   <li>trace: diffuse bounces and sky light (indirect) at reduced resolution, blended with the
 *       reprojected history</li>
 *   <li>denoise: 4 a-trous wavelet passes over the indirect light</li>
 *   <li>direct: sun and point light shadows at full resolution, one fixed ray per block-texture pixel,
 *       no randomness (so no noise and no smearing)</li>
 *   <li>auto exposure: average log luminance, smoothed over time</li>
 *   <li>composite: edge-aware upsample, multiply by albedo, tonemap</li>
 * </ol>
 * Then, in RenderHandEvent, the hand is drawn by us (fullbright) and relit with the probe.
 */
final class PathTraceRenderer {
    private static final String VSH = "quad.vsh";

    private final VoxelVolume volume = new VoxelVolume();
    private final LightCollector lights = new LightCollector();
    private final EmitterIndex emitters = new EmitterIndex();
    private final LightGrid lightGrid = new LightGrid();
    private final LodVolume lod = new LodVolume();

    private ShaderProgram bloomPreSh, bloomDownSh, bloomUpSh, gbufferSh, traceSh, atrousSh, directSh, compositeSh, probeSh, handSh, lumSh, exposureSh;
    private boolean shaderFailed;

    private int width = -1, height = -1, traceW = -1, traceH = -1;
    private int sceneTex = -1, depthTex = -1;
    private final RenderTarget[] accum = new RenderTarget[2];
    private final RenderTarget[] filt = new RenderTarget[2];
    private final RenderTarget[] probe = new RenderTarget[2];
    private final RenderTarget[] expo = new RenderTarget[2];
    private RenderTarget lum;
    private RenderTarget direct;
    private RenderTarget gbuffer;
    private static final int BLOOM_LEVELS = 5;
    private final RenderTarget[] bloomDown = new RenderTarget[BLOOM_LEVELS];
    private final RenderTarget[] bloomUp = new RenderTarget[BLOOM_LEVELS];
    private int cur;
    private int frame;
    private boolean hasHistory;
    private long lastNanos;

    private boolean frameReady;
    private int lastProbeTex, lastExposureTex;

    private final FloatBuffer proj = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer view = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer invProj = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer invView = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer prevProj = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer prevView = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer handProj = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer handInvProj = BufferUtils.createFloatBuffer(16);
    private final Matrix4f tmp = new Matrix4f();
    private final Matrix4f tmpInv = new Matrix4f();

    private double prevX, prevY, prevZ;
    private final float[] prevEyeRel = new float[3];
    private final float[] camV = new float[3];
    private final float[] eyeV = new float[3];

    private final float[] lightDir = new float[3];
    private final float[] lightColor = new float[3];
    private final float[] skyColor = new float[3];
    private final float[] ambient = new float[3];

    private Method renderHandMethod;
    private boolean handFailed;

    VoxelVolume volume() {
        return volume;
    }

    EmitterIndex emitters() {
        return emitters;
    }

    boolean isReady() {
        return traceSh != null && volume.isValid() && OpenGlHelper.isFramebufferEnabled();
    }

    void onWorldChange() {
        volume.invalidate();
        emitters.reset(null);
        lightGrid.invalidate();
        lod.invalidate();
        hasHistory = false;
    }

    void resetHistory() {
        hasHistory = false;
    }

    void reloadShaders() {
        deleteShaders();
        shaderFailed = false;
        hasHistory = false;
    }

    // ================================================================== world pass

    void render(float pt) {
        frameReady = false;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        Entity cam = mc.getRenderViewEntity();
        if (world == null || cam == null || !OpenGlHelper.isFramebufferEnabled()) return;
        if (!ensureShaders()) return;

        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * pt;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * pt;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * pt;

        volume.update(world, cx, cy, cz);
        if (!volume.isValid()) {
            hasHistory = false;
            return;
        }

        Framebuffer fb = mc.getFramebuffer();
        ensureTargets(fb.framebufferWidth, fb.framebufferHeight);

        readMatrix(GL11.GL_PROJECTION_MATRIX, proj);
        readMatrix(GL11.GL_MODELVIEW_MATRIX, view);
        if (!invert(proj, invProj) || !invert(view, invView)) return;
        Vector4f eye = Matrix4f.transform(tmpInv, new Vector4f(0f, 0f, 0f, 1f), null); // tmpInv = inverse(view)

        int ox = volume.originX(), oy = volume.originY(), oz = volume.originZ();
        camV[0] = (float) (cx - ox); camV[1] = (float) (cy - oy); camV[2] = (float) (cz - oz);
        eyeV[0] = camV[0] + eye.x; eyeV[1] = camV[1] + eye.y; eyeV[2] = camV[2] + eye.z;
        if (!hasHistory) {
            copy(proj, prevProj);
            copy(view, prevView);
            prevX = cx; prevY = cy; prevZ = cz;
            prevEyeRel[0] = eye.x; prevEyeRel[1] = eye.y; prevEyeRel[2] = eye.z;
        }
        float pcx = (float) (prevX - ox), pcy = (float) (prevY - oy), pcz = (float) (prevZ - oz);

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : MathHelper.clamp((now - lastNanos) / 1.0e9f, 0f, 0.25f);
        lastNanos = now;

        lights.collect(world, cam, pt, volume, cx, cy, cz, mc.gameSettings.thirdPersonView == 0);
        emitters.process(world, 96);
        lightGrid.update(emitters, cx, cy, cz, mc.gameSettings.renderDistanceChunks);
        if (PTConfig.lodShadows) lod.update(emitters, cx, cz, mc.gameSettings.renderDistanceChunks);
        computeLighting(world, cam, pt);

        // 1. grab colour + depth of the frame vanilla just drew
        fb.bindFramebuffer(false);
        bind2D(0, sceneTex);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        bind2D(0, depthTex);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        beginFullscreen();
        int prev = 1 - cur;

        // 1b. gbuffer: exact surface distance + normal
        gbuffer.bind();
        gbufferSh.use();
        setCommon(gbufferSh);
        bind2D(0, depthTex);
        gbufferSh.set("depthTex", 0);
        gbufferSh.setMatrix("invProj", invProj);
        gbufferSh.setMatrix("invView", invView);
        gbufferSh.set("depthTexel", 1f / width, 1f / height);
        drawQuad();

        // 2. eye probe
        probe[cur].bind();
        probeSh.use();
        setCommon(probeSh);
        bind2D(2, probe[prev].tex[0]);
        probeSh.set("prevProbe", 2);
        probeSh.set("eyePos", eyeV);
        probeSh.set("hasHistory", hasHistory ? 1f : 0f);
        drawQuad();

        // 3. trace
        accum[cur].bind();
        traceSh.use();
        setCommon(traceSh);
        bind2D(0, depthTex);
        bind2D(2, accum[prev].tex[0]);
        bind2D(3, accum[prev].tex[1]);
        traceSh.set("depthTex", 0);
        traceSh.set("histIndirect", 2);
        traceSh.set("histGeom", 3);
        bind2D(4, gbuffer.tex[0]);
        traceSh.set("gbufferTex", 4);
        traceSh.setMatrix("invProj", invProj);
        traceSh.setMatrix("invView", invView);
        traceSh.setMatrix("prevProj", prevProj);
        traceSh.setMatrix("prevView", prevView);
        traceSh.set("prevCamPos", pcx, pcy, pcz);
        traceSh.set("prevEye", pcx + prevEyeRel[0], pcy + prevEyeRel[1], pcz + prevEyeRel[2]);
        traceSh.set("hasHistory", hasHistory ? 1f : 0f);
        traceSh.set("maxAccumIndirect", (float) PTConfig.maxAccumulation);
        traceSh.set("bounces", PTConfig.bounces);
        traceSh.set("depthTexel", 1f / width, 1f / height);
        traceSh.set("traceRes", (float) traceW, (float) traceH);
        drawQuad();

        // 4. denoise the bounce light
        int indTex = accum[cur].tex[0], geomTex = accum[cur].tex[1];
        if (PTConfig.denoise) {
            atrousSh.use();
            atrousSh.set("inIndirect", 2);
            atrousSh.set("geomTex", 4);
            atrousSh.set("texel", 1f / traceW, 1f / traceH);
            bind2D(4, geomTex);
            for (int it = 0; it < 4; it++) {
                RenderTarget dst = filt[it & 1];
                dst.bind();
                bind2D(2, indTex);
                atrousSh.set("stepSize", (float) (1 << it));
                drawQuad();
                indTex = dst.tex[0];
            }
        }

        // 4b. direct light, full resolution, deterministic
        direct.bind();
        directSh.use();
        setCommon(directSh);
        bind2D(0, depthTex);
        directSh.set("depthTex", 0);
        bind2D(4, gbuffer.tex[0]);
        directSh.set("gbufferTex", 4);
        directSh.setMatrix("invProj", invProj);
        directSh.setMatrix("invView", invView);
        directSh.set("depthTexel", 1f / width, 1f / height);
        directSh.set("shadowedLights", PTConfig.shadowedLights);
        directSh.set("pixelShadows", PTConfig.pixelShadows ? 1f : 0f);
        drawQuad();
        int dirTex = direct.tex[0];

        // 4c. bloom from light-source pixels
        int bloomTex = 0;
        if (PTConfig.bloom) {
            bloomDown[0].bind();
            bloomPreSh.use();
            bind2D(0, sceneTex);
            bind2D(2, dirTex);
            bind2D(3, depthTex);
            bloomPreSh.set("sceneTex", 0);
            bloomPreSh.set("directTex", 2);
            bloomPreSh.set("depthTex", 3);
            bloomPreSh.set("srcTexel", 1f / width, 1f / height);
            drawQuad();
            bloomDownSh.use();
            bloomDownSh.set("srcTex", 2);
            for (int i = 1; i < BLOOM_LEVELS; i++) {
                bloomDown[i].bind();
                bind2D(2, bloomDown[i - 1].tex[0]);
                bloomDownSh.set("srcTexel", 1f / bloomDown[i - 1].width, 1f / bloomDown[i - 1].height);
                drawQuad();
            }
            bloomUpSh.use();
            bloomUpSh.set("srcTex", 2);
            bloomUpSh.set("addTex", 3);
            RenderTarget src = bloomDown[BLOOM_LEVELS - 1];
            for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
                bloomUp[i].bind();
                bind2D(2, src.tex[0]);
                bind2D(3, bloomDown[i].tex[0]);
                bloomUpSh.set("srcTexel", 1f / src.width, 1f / src.height);
                drawQuad();
                src = bloomUp[i];
            }
            bloomTex = bloomUp[0].tex[0];
        }

        // 5. auto exposure
        int expTex = 0;
        if (PTConfig.autoExposure) {
            lum.bind();
            lumSh.use();
            bind2D(0, sceneTex);
            bind2D(2, indTex);
            bind2D(3, dirTex);
            bind2D(4, depthTex);
            lumSh.set("sceneTex", 0);
            lumSh.set("indirectTex", 2);
            lumSh.set("directTex", 3);
            lumSh.set("depthTex", 4);
            drawQuad();
            bind2D(2, lum.tex[0]);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

            expo[cur].bind();
            exposureSh.use();
            bind2D(3, expo[prev].tex[0]);
            exposureSh.set("lumTex", 2);
            exposureSh.set("prevExposure", 3);
            exposureSh.set("adapt", 1f - (float) Math.exp(-dt * 1.5f));
            exposureSh.set("hasHistory", hasHistory ? 1f : 0f);
            drawQuad();
            expTex = expo[cur].tex[0];
        }

        // 6. composite into Minecraft's framebuffer
        fb.bindFramebuffer(true);
        compositeSh.use();
        bind2D(0, sceneTex);
        bind2D(2, indTex);
        bind2D(3, dirTex);
        bind2D(4, geomTex);
        bind2D(5, depthTex);
        bind2D(6, expTex);
        compositeSh.set("sceneTex", 0);
        compositeSh.set("indirectTex", 2);
        compositeSh.set("directTex", 3);
        compositeSh.set("geomTex", 4);
        compositeSh.set("depthTex", 5);
        compositeSh.set("exposureTex", 6);
        bind2D(7, gbuffer.tex[0]);
        compositeSh.set("gbufferTex", 7);
        compositeSh.setMatrix("invProj", invProj);
        compositeSh.set("lowRes", (float) traceW, (float) traceH);
        compositeSh.set("debugView", PTConfig.debugView);
        bindRaw(8, bloomTex);
        compositeSh.set("bloomTex", 8);
        compositeSh.set("bloomStrength", PTConfig.bloom ? (float) PTConfig.bloomStrength : 0f);
        setGrading(compositeSh);
        drawQuad();

        endFullscreen();

        lastProbeTex = probe[cur].tex[0];
        lastExposureTex = expTex;
        copy(proj, prevProj);
        copy(view, prevView);
        prevX = cx; prevY = cy; prevZ = cz;
        prevEyeRel[0] = eye.x; prevEyeRel[1] = eye.y; prevEyeRel[2] = eye.z;
        hasHistory = true;
        cur = prev;
        frame++;
        frameReady = true;
    }

    // ================================================================== hand pass

    /** Draws the first-person hand ourselves and relights it. Returns true if vanilla should skip its hand. */
    boolean renderHand(float pt, int pass) {
        if (!frameReady || handFailed || !PTConfig.litHand) return false;
        frameReady = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (renderHandMethod == null) {
            try {
                renderHandMethod = ReflectionHelper.findMethod(EntityRenderer.class, "renderHand", "func_78476_b",
                        float.class, int.class);
            } catch (Throwable t) {
                handFailed = true;
                PathTraceMod.LOG.warn("PathTrace: can't hook hand rendering, hand stays unlit", t);
                return false;
            }
        }

        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        try {
            renderHandMethod.invoke(mc.entityRenderer, pt, pass);
        } catch (Throwable t) {
            handFailed = true;
            PathTraceMod.LOG.warn("PathTrace: hand rendering failed, hand stays unlit", t);
            return true;
        }

        readMatrix(GL11.GL_PROJECTION_MATRIX, handProj);
        if (!invert(handProj, handInvProj)) return true;

        Framebuffer fb = mc.getFramebuffer();
        fb.bindFramebuffer(false);
        bind2D(0, sceneTex);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        bind2D(0, depthTex);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        beginFullscreen();
        fb.bindFramebuffer(true);
        handSh.use();
        setCommon(handSh);
        bind2D(0, sceneTex);
        bind2D(2, depthTex);
        bind2D(3, lastProbeTex);
        bind2D(4, lastExposureTex);
        handSh.set("handColor", 0);
        handSh.set("handDepth", 2);
        handSh.set("probeTex", 3);
        handSh.set("exposureTex", 4);
        handSh.setMatrix("handInvProj", handInvProj);
        handSh.setMatrix("invView", invView);
        handSh.set("eyePos", eyeV);
        handSh.set("texel", 1f / width, 1f / height);
        setGrading(handSh);
        drawQuad();
        endFullscreen();
        return true;
    }

    // ================================================================== uniforms

    private void setCommon(ShaderProgram s) {
        bind2D(5, volume.heightTexture());
        bind3D(6, volume.texture());
        bind3D(7, volume.coarseTexture());
        bindRaw3D(11, volume.shapeIdTexture());
        bindRaw3D(12, volume.shapes.texture());
        s.set("shapeIdTex", 11);
        s.set("shapeTex", 12);
        s.set("heightTex", 5);
        s.set("voxelTex", 6);
        s.set("coarseTex", 7);
        s.set("volumeSize", (float) VoxelVolume.N);
        s.set("originY", (float) volume.originY());
        s.set("camPos", camV);
        s.set("lightDir", lightDir);
        s.set("lightColor", lightColor);
        s.set("skyColor", skyColor);
        s.set("ambient", ambient);
        s.set("emissiveStrength", (float) PTConfig.emissiveStrength);
        s.set("frame", (float) (frame % 1024));
        s.setVec4Array("dynLightPos", lights.pos);
        s.setVec4Array("dynLightCol", lights.col);
        s.set("dynLightCount", lights.count);
        bindRaw(8, lightGrid.lightTexture());
        bindRaw(9, lightGrid.cellTexture());
        s.set("lightTex", 8);
        s.set("cellTex", 9);
        s.set("gridOrigin", (float) (lightGrid.ox - volume.originX()), (float) (lightGrid.oy - volume.originY()),
                (float) (lightGrid.oz - volume.originZ()));
        s.set("gridCells", (float) lightGrid.gx, (float) lightGrid.gy, (float) lightGrid.gz);
        s.set("lightTexSize", lightGrid.lightTexWidth(), lightGrid.lightTexHeight());
        s.set("cellTexSize", lightGrid.cellTexWidth(), (float) lightGrid.cellTexH);
        boolean lodOn = PTConfig.lodShadows && lod.isBuilt();
        if (lodOn) {
            bindRaw3D(10, lod.texture());
            bindRaw3D(13, lod.coarseTexture());
        }
        s.set("lodTex", 10);
        s.set("lodCoarseTex", 13);
        s.set("lodEnabled", lodOn ? 1f : 0f);
        s.set("lodSize", (float) lod.extent, (float) LodVolume.HEIGHT, (float) lod.extent);
        s.set("lodOrigin", (float) (lod.ox - volume.originX()), (float) (0 - volume.originY()),
                (float) (lod.oz - volume.originZ()));
    }

    private void setGrading(ShaderProgram s) {
        s.set("exposure", (float) PTConfig.exposure);
        s.set("autoExposure", PTConfig.autoExposure ? 1f : 0f);
        s.set("saturation", (float) PTConfig.saturation);
    }

    private void computeLighting(World world, Entity cam, float pt) {
        float sky = (float) PTConfig.skyStrength;
        if (!world.provider.isSurfaceWorld()) {
            set(lightDir, 0f, 1f, 0f);
            set(lightColor, 0f, 0f, 0f);
            if (world.provider.getDimensionType() == DimensionType.NETHER) {
                set(skyColor, 0.20f * sky, 0.09f * sky, 0.05f * sky);
                set(ambient, 0.10f, 0.05f, 0.03f);
            } else {
                set(skyColor, 0.10f * sky, 0.08f * sky, 0.14f * sky);
                set(ambient, 0.05f, 0.04f, 0.07f);
            }
            return;
        }

        float ang = world.getCelestialAngleRadians(pt);
        float x = -MathHelper.sin(ang), y = MathHelper.cos(ang), z = 0.3f;
        float len = MathHelper.sqrt(x * x + y * y + z * z);
        x /= len; y /= len; z /= len;

        float sun = (float) PTConfig.sunStrength;
        float r, g, b, k;
        if (y > -0.1f) {
            k = smooth(-0.1f, 0.15f, y) * sun;
            float warm = smooth(0.0f, 0.45f, y);
            r = 1.0f;
            g = lerp(0.55f, 0.94f, warm);
            b = lerp(0.30f, 0.85f, warm);
        } else {               // sun is down: use the moon, which is opposite
            x = -x; y = -y; z = -z;
            k = smooth(0.1f, 0.3f, y) * 0.08f * sun;
            r = 0.45f; g = 0.55f; b = 0.80f;
        }
        float weather = (1f - 0.8f * world.getRainStrength(pt)) * (1f - 0.5f * world.getThunderStrength(pt));
        k *= weather;
        set(lightDir, x, y, z);
        set(lightColor, r * k, g * k, b * k);

        // sky light: the sky's colour, but half desaturated (the vanilla sky colour is very blue)
        Vec3d s = world.getSkyColor(cam, pt);
        float sr = (float) Math.pow(Math.max(0, s.x), 2.2);
        float sg = (float) Math.pow(Math.max(0, s.y), 2.2);
        float sb = (float) Math.pow(Math.max(0, s.z), 2.2);
        float sl = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;
        set(skyColor,
                lerp(sl, sr, 0.5f) * sky + 0.004f,
                lerp(sl, sg, 0.5f) * sky + 0.005f,
                lerp(sl, sb, 0.5f) * sky + 0.008f);
        set(ambient, 0.002f, 0.002f, 0.003f);
    }

    private static void set(float[] a, float x, float y, float z) {
        a[0] = x; a[1] = y; a[2] = z;
    }

    private static float smooth(float e0, float e1, float v) {
        float t = MathHelper.clamp((v - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ================================================================== GL plumbing

    private boolean ensureShaders() {
        if (traceSh != null) return true;
        if (shaderFailed) return false;
        try {
            gbufferSh = ShaderProgram.load(VSH, "gbuffer.fsh");
            bloomPreSh = ShaderProgram.load(VSH, "bloom_pre.fsh");
            bloomDownSh = ShaderProgram.load(VSH, "bloom_down.fsh");
            bloomUpSh = ShaderProgram.load(VSH, "bloom_up.fsh");
            traceSh = ShaderProgram.load(VSH, "trace.fsh");
            atrousSh = ShaderProgram.load(VSH, "atrous.fsh");
            directSh = ShaderProgram.load(VSH, "direct.fsh");
            compositeSh = ShaderProgram.load(VSH, "composite.fsh");
            probeSh = ShaderProgram.load(VSH, "probe.fsh");
            handSh = ShaderProgram.load(VSH, "hand.fsh");
            lumSh = ShaderProgram.load(VSH, "lum.fsh");
            exposureSh = ShaderProgram.load(VSH, "exposure.fsh");
            return true;
        } catch (Exception e) {
            PathTraceMod.LOG.error("PathTrace shaders failed to load", e);
            deleteShaders();
            shaderFailed = true;
            ClientHandler.chat("§cPathTrace: shader error, see latest.log");
            return false;
        }
    }

    private void deleteShaders() {
        for (ShaderProgram s : new ShaderProgram[]{bloomPreSh, bloomDownSh, bloomUpSh, gbufferSh, traceSh, atrousSh, directSh, compositeSh, probeSh, handSh, lumSh, exposureSh}) {
            if (s != null) s.delete();
        }
        bloomPreSh = bloomDownSh = bloomUpSh = gbufferSh = traceSh = atrousSh = directSh = compositeSh = probeSh = handSh = lumSh = exposureSh = null;
    }

    private void ensureTargets(int w, int h) {
        double scale = MathHelper.clamp(PTConfig.renderScale, 0.25, 1.0);
        int tw = Math.max(1, (int) (w * scale));
        int th = Math.max(1, (int) (h * scale));
        if (w == width && h == height && tw == traceW && th == traceH && sceneTex != -1) return;
        deleteTargets();
        width = w; height = h; traceW = tw; traceH = th;

        sceneTex = makeTex(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w, h);
        depthTex = makeTex(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, w, h);
        for (int i = 0; i < 2; i++) {
            accum[i] = new RenderTarget(tw, th, 2, GL11.GL_NEAREST);   // indirect, geometry
            filt[i] = new RenderTarget(tw, th, 1, GL11.GL_NEAREST);    // filtered indirect
            probe[i] = new RenderTarget(8, 9, 1, GL11.GL_NEAREST);     // 64 directions + sun visibility row
            expo[i] = new RenderTarget(1, 1, 1, GL11.GL_NEAREST);
        }
        direct = new RenderTarget(w, h, 1, GL11.GL_NEAREST);
        gbuffer = new RenderTarget(w, h, 1, GL11.GL_NEAREST, GL30.GL_RGBA32F);   // 32-bit: distance must be exact
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            int bw = Math.max(1, w >> (i + 1)), bh = Math.max(1, h >> (i + 1));
            bloomDown[i] = new RenderTarget(bw, bh, 1, GL11.GL_LINEAR).linear();
            if (i < BLOOM_LEVELS - 1) bloomUp[i] = new RenderTarget(bw, bh, 1, GL11.GL_LINEAR).linear();
        }
        lum = new RenderTarget(64, 64, 1, GL11.GL_NEAREST_MIPMAP_NEAREST);
        bind2D(0, lum.tex[0]);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        hasHistory = false;
        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(false);
    }

    private void deleteTargets() {
        if (sceneTex != -1) GlStateManager.deleteTexture(sceneTex);
        if (depthTex != -1) GlStateManager.deleteTexture(depthTex);
        sceneTex = depthTex = -1;
        for (int i = 0; i < 2; i++) {
            if (accum[i] != null) accum[i].delete();
            if (filt[i] != null) filt[i].delete();
            if (probe[i] != null) probe[i].delete();
            if (expo[i] != null) expo[i].delete();
            accum[i] = filt[i] = probe[i] = expo[i] = null;
        }
        if (lum != null) lum.delete();
        if (direct != null) direct.delete();
        if (gbuffer != null) gbuffer.delete();
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            if (bloomDown[i] != null) bloomDown[i].delete();
            if (bloomUp[i] != null) bloomUp[i].delete();
            bloomDown[i] = bloomUp[i] = null;
        }
        lum = direct = gbuffer = null;
    }

    private static int makeTex(int internal, int format, int type, int w, int h) {
        int id = GlStateManager.generateTexture();
        GlStateManager.bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (ByteBuffer) null);
        return id;
    }

    private static void beginFullscreen() {
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableFog();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
    }

    private static void endFullscreen() {
        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
    }

    private static void bind2D(int unit, int tex) {
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager.bindTexture(tex);
    }

    /** Units 8+ are beyond GlStateManager's texture cache (8 units), so bind them directly. */
    private static void bindRaw(int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void bindRaw3D(int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void bind3D(int unit, int tex) {
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
    }

    private static void drawQuad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1f, -1f);
        GL11.glVertex2f(1f, -1f);
        GL11.glVertex2f(1f, 1f);
        GL11.glVertex2f(-1f, 1f);
        GL11.glEnd();
    }

    private static void readMatrix(int pname, FloatBuffer dst) {
        dst.clear();
        GL11.glGetFloat(pname, dst);
        dst.rewind();
    }

    /** Inverts src into dst; the inverse is also left in tmpInv. */
    private boolean invert(FloatBuffer src, FloatBuffer dst) {
        src.rewind();
        tmp.load(src);
        src.rewind();
        if (Matrix4f.invert(tmp, tmpInv) == null) return false;
        dst.clear();
        tmpInv.store(dst);
        dst.rewind();
        return true;
    }

    private static void copy(FloatBuffer src, FloatBuffer dst) {
        src.rewind();
        dst.clear();
        dst.put(src);
        dst.rewind();
        src.rewind();
    }
}
