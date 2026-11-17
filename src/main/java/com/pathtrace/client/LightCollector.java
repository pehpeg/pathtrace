package com.pathtrace.client;

import com.pathtrace.PTConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/**
 * Per-frame dynamic lights from entities: held items, dropped items, burning mobs, blazes,
 * fireballs. Block lights are handled by {@link LightGrid}.
 */
final class LightCollector {
    static final int MAX = 16;          // must match MAX_DYN in common.glsl
    private static final int MAX_DYNAMIC = MAX;
    private static final double DYNAMIC_RANGE = 40.0;

    final FloatBuffer pos = BufferUtils.createFloatBuffer(MAX * 4);
    final FloatBuffer col = BufferUtils.createFloatBuffer(MAX * 4);
    int count;

    private final double[] dynD2 = new double[MAX_DYNAMIC];
    private final float[][] dyn = new float[MAX_DYNAMIC][5]; // x y z light color (volume space)
    private int dynCount;

    void collect(World world, Entity cam, float pt, VoxelVolume vol, double cx, double cy, double cz, boolean firstPerson) {
        pos.clear();
        col.clear();
        count = 0;
        float strength = (float) PTConfig.pointLightStrength;
        int ox = vol.originX(), oy = vol.originY(), oz = vol.originZ();

        dynCount = 0;
        if (PTConfig.dynamicLights) collectDynamic(world, cam, pt, cx, cy, cz, firstPerson);
        for (int i = 0; i < dynCount; i++) {
            float[] d = dyn[i];
            put((float) (d[0] - ox), (float) (d[1] - oy), (float) (d[2] - oz), (int) d[3], (int) d[4], strength);
        }

        while (pos.position() < pos.capacity()) {
            pos.put(0f);
            col.put(0f);
        }
        pos.rewind();
        col.rewind();
    }

    private void put(float x, float y, float z, int light, int color, float strength) {
        if (count >= MAX || light <= 0) return;
        float[] rgb = LightColors.RGB[Math.min(color, LightColors.RGB.length - 1)];
        float k = light / 15f * strength;
        pos.put(x).put(y).put(z).put(light + 0.5f);
        col.put(rgb[0] * k).put(rgb[1] * k).put(rgb[2] * k).put(13f);   // 13 = not offset
        count++;
    }

    private void collectDynamic(World world, Entity cam, float pt, double cx, double cy, double cz, boolean firstPerson) {
        for (Entity e : world.loadedEntityList) {
            int light = 0;
            int color = LightColors.WARM;
            double yOff = e.height * 0.5;
            if (e instanceof EntityItem) {
                ItemStack s = ((EntityItem) e).getItem();
                light = LightColors.itemLight(s);
                color = LightColors.itemColor(s);
                yOff = 0.3;
            } else if (e instanceof EntityLivingBase) {
                EntityLivingBase l = (EntityLivingBase) e;
                ItemStack a = l.getHeldItemMainhand();
                ItemStack b = l.getHeldItemOffhand();
                int la = LightColors.itemLight(a), lb = LightColors.itemLight(b);
                if (la >= lb) {
                    light = la;
                    color = LightColors.itemColor(a);
                } else {
                    light = lb;
                    color = LightColors.itemColor(b);
                }
                yOff = e.height * 0.6;
            }
            if ((e instanceof EntityBlaze || e instanceof EntityMagmaCube) && light < 10) {
                light = 10;
                color = LightColors.FIRE;
            }
            if (e instanceof EntityFireball) {
                light = Math.max(light, 12);
                color = LightColors.FIRE;
            }
            if (e.isBurning()) {
                light = 15;
                color = LightColors.FIRE;
            }
            if (light <= 0) continue;

            double x = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
            double y = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + yOff;
            double z = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
            if (e == cam && firstPerson) {
                // just in front of and below the eyes, where the held item is
                float yaw = (e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * pt) * 0.017453292f;
                x += -MathHelper.sin(yaw) * 0.4;
                z += MathHelper.cos(yaw) * 0.4;
                y = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + e.getEyeHeight() - 0.35;
            }
            double d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
            if (d2 > DYNAMIC_RANGE * DYNAMIC_RANGE) continue;
            insertDynamic(d2, x, y, z, light, color);
        }
    }

    private void insertDynamic(double d2, double x, double y, double z, int light, int color) {
        int i = dynCount;
        if (i == MAX_DYNAMIC) {
            if (d2 >= dynD2[MAX_DYNAMIC - 1]) return;
            i = MAX_DYNAMIC - 1;
        } else {
            dynCount++;
        }
        while (i > 0 && dynD2[i - 1] > d2) {
            dynD2[i] = dynD2[i - 1];
            float[] t = dyn[i];
            dyn[i] = dyn[i - 1];
            dyn[i - 1] = t;
            i--;
        }
        dynD2[i] = d2;
        float[] d = dyn[i];
        d[0] = (float) x; d[1] = (float) y; d[2] = (float) z; d[3] = light; d[4] = color;
    }
}
