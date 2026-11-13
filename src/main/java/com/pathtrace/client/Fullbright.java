package com.pathtrace.client;

import com.pathtrace.PathTraceMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Forces the vanilla lightmap to plain white while path tracing is active, so the
 * rasterised frame contains (approximately) unlit albedo instead of vanilla lighting.
 */
final class Fullbright {
    private static Field updateNeeded, colors, texture;
    private static boolean resolved, broken, active;

    private Fullbright() {}

    private static void resolve() {
        if (resolved) return;
        updateNeeded = ReflectionHelper.findField(EntityRenderer.class, "lightmapUpdateNeeded", "field_78536_aa");
        colors = ReflectionHelper.findField(EntityRenderer.class, "lightmapColors", "field_78504_Q");
        texture = ReflectionHelper.findField(EntityRenderer.class, "lightmapTexture", "field_78513_d");
        resolved = true;
    }

    static void setActive(boolean on) {
        if (broken) return;
        EntityRenderer er = Minecraft.getMinecraft().entityRenderer;
        if (er == null) return;
        try {
            resolve();
            if (on) {
                updateNeeded.setBoolean(er, false); // stop vanilla rewriting it this frame
                int[] c = (int[]) colors.get(er);
                if (!active || c[0] != -1 || c[c.length - 1] != -1) {
                    Arrays.fill(c, 0xFFFFFFFF);
                    ((DynamicTexture) texture.get(er)).updateDynamicTexture();
                }
                active = true;
            } else if (active) {
                updateNeeded.setBoolean(er, true); // let vanilla restore its lightmap
                active = false;
            }
        } catch (Throwable t) {
            broken = true;
            PathTraceMod.LOG.warn("Could not override the lightmap; lighting will be doubled up with vanilla's.", t);
        }
    }
}
