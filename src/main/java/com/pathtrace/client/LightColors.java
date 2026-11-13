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
    static final int WARM = 0, FIRE = 1, RED = 2, COOL = 3, PURPLE = 4, GREEN = 5;

    static final float[][] RGB = {
            {1.00f, 0.72f, 0.42f}, // WARM   torches, glowstone, lamps
            {1.00f, 0.50f, 0.18f}, // FIRE   lava, fire, magma
            {1.00f, 0.20f, 0.12f}, // RED    redstone
            {0.75f, 0.88f, 1.00f}, // COOL   sea lantern, beacon, end rod
            {0.70f, 0.35f, 1.00f}, // PURPLE portals
            {0.50f, 1.00f, 0.45f}, // GREEN
    };

    private static final Map<Block, Integer> BLOCK_CACHE = new IdentityHashMap<>();

    private LightColors() {}

    static int forBlock(Block b) {
        Integer cached = BLOCK_CACHE.get(b);
        if (cached != null) return cached;
        ResourceLocation rl = b.getRegistryName();
        String p = rl == null ? "" : rl.toString();
        int c;
        if (p.contains("redstone")) c = RED;
        else if (p.contains("lava") || p.contains("magma") || p.contains("fire") || p.contains("furnace")) c = FIRE;
        else if (p.contains("sea_lantern") || p.contains("beacon") || p.contains("end_rod") || p.contains("end_gateway")) c = COOL;
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
        if (s == null || s.isEmpty()) return WARM;
        Item it = s.getItem();
        if (it instanceof ItemBlock) return forBlock(((ItemBlock) it).getBlock());
        if (it == Items.LAVA_BUCKET || it == Items.BLAZE_ROD || it == Items.BLAZE_POWDER
                || it == Items.FIRE_CHARGE || it == Items.MAGMA_CREAM) return FIRE;
        if (it == Items.NETHER_STAR) return COOL;
        return WARM;
    }
}
