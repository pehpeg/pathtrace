package com.pathtrace.client;

import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.IdentityHashMap;
import java.util.Map;

/** Light levels and colours for blocks and items. Colour indices must match emitColorIndex() in common.glsl. */
final class LightColors {
    static final int WARM = 0, FIRE = 1, RED = 2, COOL = 3, PURPLE = 4, GREEN = 5, TORCH = 6, CYAN = 7;

    /** Base colours; must match emitColorIndex() in common.glsl. lightSaturation pushes them further. */
    static final float[][] BASE = {
            {1.00f, 0.78f, 0.45f}, // WARM   glowstone, redstone lamps
            {1.00f, 0.45f, 0.12f}, // FIRE   lava, fire, magma, furnaces
            {1.00f, 0.12f, 0.06f}, // RED    redstone torches/dust/ore
            {0.60f, 0.82f, 1.00f}, // COOL   sea lantern, end rod
            {0.62f, 0.25f, 1.00f}, // PURPLE portals, ender chests
            {0.45f, 1.00f, 0.40f}, // GREEN
            {1.00f, 0.60f, 0.28f}, // TORCH  torches, jack o'lanterns
            {0.40f, 0.95f, 1.00f}, // CYAN   beacons
    };
    static final float[][] RGB = new float[BASE.length][3];
    private static double builtSaturation = -1;

    /** Recompute RGB from BASE with the configured saturation. Returns true if it changed. */
    static boolean update() {
        double sat = com.pathtrace.PTConfig.lightSaturation;
        if (sat == builtSaturation) return false;
        builtSaturation = sat;
        for (int i = 0; i < BASE.length; i++) saturate(BASE[i], (float) sat, RGB[i]);
        return true;
    }

    static void saturate(float[] c, float sat, float[] out) {
        float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        float m = 0f;
        for (int k = 0; k < 3; k++) { out[k] = Math.max(0f, l + (c[k] - l) * sat); m = Math.max(m, out[k]); }
        if (m > 0f) for (int k = 0; k < 3; k++) out[k] /= m;   // brightest channel stays at 1
    }

    static { update(); }

    private static final Map<Block, Integer> BLOCK_CACHE = new IdentityHashMap<>();

    private LightColors() {}

    static int forBlock(Block b) {
        Integer cached = BLOCK_CACHE.get(b);
        if (cached != null) return cached;
        ResourceLocation rl = b.getRegistryName();
        String p = rl == null ? "" : rl.toString();
        int c;
        if (p.contains("redstone_lamp")) c = WARM;
        else if (p.contains("redstone")) c = RED;
        else if (p.contains("lava") || p.contains("magma") || p.contains("fire") || p.contains("furnace")) c = FIRE;
        else if (p.contains("beacon")) c = CYAN;
        else if (p.contains("sea_lantern") || p.contains("end_rod") || p.contains("end_gateway")) c = COOL;
        else if (p.contains("torch") || p.contains("lit_pumpkin")) c = TORCH;
        else if (p.contains("portal") || p.contains("ender_chest")) c = PURPLE;
        else c = WARM;
        BLOCK_CACHE.put(b, c);
        return c;
    }

    @SuppressWarnings("deprecation")
    static int itemLight(ItemStack s) {
        if (s == null || s.isEmpty()) return 0;
        Item it = s.getItem();
        if (it instanceof ItemBlock) {
            try {
                return ((ItemBlock) it).getBlock().getDefaultState().getLightValue();
            } catch (Throwable t) {
                return 0;
            }
        }
        if (it == Items.LAVA_BUCKET) return 15;
        if (it == Items.NETHER_STAR) return 12;
        if (it == Items.BLAZE_ROD || it == Items.FIRE_CHARGE) return 10;
        if (it == Items.GLOWSTONE_DUST || it == Items.BLAZE_POWDER) return 8;
        if (it == Items.MAGMA_CREAM || it == Items.SPECTRAL_ARROW) return 6;
        return 0;
    }

    static int itemColor(ItemStack s) {
        if (s == null || s.isEmpty()) return TORCH;
        Item it = s.getItem();
        if (it instanceof ItemBlock) return forBlock(((ItemBlock) it).getBlock());
        if (it == Items.LAVA_BUCKET || it == Items.BLAZE_ROD || it == Items.BLAZE_POWDER
                || it == Items.FIRE_CHARGE || it == Items.MAGMA_CREAM) return FIRE;
        if (it == Items.NETHER_STAR) return COOL;
        return TORCH;
    }
}
