package com.pathtrace;

import net.minecraftforge.common.config.Config;

/**
 * Editable in-game via Mods > PathTrace > Config, or in config/pathtrace.cfg.
 */
@Config(modid = PathTraceMod.MODID, name = PathTraceMod.MODID)
public class PTConfig {

    @Config.Comment("Indirect light bounces per sample. 0 = direct light only, 1 = good default, 2-3 = brighter interiors, slower.")
    @Config.RangeInt(min = 0, max = 3)
    public static int bounces = 1;

    @Config.Comment("Fraction of screen resolution the path tracer runs at; the result is upscaled edge-aware. 0.5 is the sweet spot.")
    @Config.RangeDouble(min = 0.25, max = 1.0)
    public static double renderScale = 0.5;

    @Config.Comment("Fraction of screen resolution the direct light and shadows are computed at, upscaled edge-aware. 1.0 = sharpest, 0.5 = about 4x cheaper.")
    @Config.RangeDouble(min = 0.25, max = 1.0)
    public static double directScale = 0.5;

    @Config.Comment("Frames averaged for bounce/sky light. Higher = smoother, slower to react to changes.")
    @Config.RangeInt(min = 1, max = 256)
    public static int maxAccumulation = 32;

    @Config.Comment("Compute shadows once per block-texture pixel: crisp pixel-art shadow edges that never flicker.")
    public static boolean pixelShadows = true;

    @Config.Comment("How many of the strongest nearby block/dynamic lights get an exact shadow per pixel (weaker ones share theirs).")
    @Config.RangeInt(min = 0, max = 4)
    public static int shadowedLights = 3;

    @Config.Comment("Edge-aware wavelet denoiser.")
    public static boolean denoise = true;

    @Config.Comment("Brightness multiplier (applied on top of auto exposure).")
    @Config.RangeDouble(min = 0.1, max = 8.0)
    public static double exposure = 1.0;

    @Config.Comment("Adapt brightness like an eye: brighter in caves, calmer in daylight.")
    public static boolean autoExposure = true;

    @Config.Comment("Colour saturation of the final image.")
    @Config.RangeDouble(min = 0.0, max = 2.0)
    public static double saturation = 1.0;

    @Config.Comment("Sunlight strength.")
    @Config.RangeDouble(min = 0.0, max = 10.0)
    public static double sunStrength = 1.3;

    @Config.Comment("Sky light strength.")
    @Config.RangeDouble(min = 0.0, max = 10.0)
    public static double skyStrength = 0.7;

    @Config.Comment("Glow of light-emitting blocks seen directly or via bounces.")
    @Config.RangeDouble(min = 0.0, max = 50.0)
    public static double emissiveStrength = 2.5;

    @Config.Comment("Strength of light cast by torches, glowstone, lava, held/dropped items etc.")
    @Config.RangeDouble(min = 0.0, max = 50.0)
    public static double pointLightStrength = 2.5;

    @Config.Comment("How coloured block light is: 1 = natural tints, 1.6 = clearly orange torches, red redstone, blue sea lanterns...")
    @Config.RangeDouble(min = 0.0, max = 3.0)
    public static double lightSaturation = 1.6;

    @Config.Comment("Light from held items, dropped items, burning mobs, blazes and fireballs.")
    public static boolean dynamicLights = true;

    @Config.Comment("Light the first-person hand and held item with the path traced lighting.")
    public static boolean litHand = true;

    @Config.Comment("Soft glow around light sources (torch flames, glowstone, lava...).")
    public static boolean bloom = true;

    @Config.Comment("Bloom strength.")
    @Config.RangeDouble(min = 0.0, max = 5.0)
    public static double bloomStrength = 1.6;

    @Config.Comment("Shadows beyond the detailed 128-block grid, from a 1-block grid out to the render distance.")
    public static boolean lodShadows = true;

    @Config.Comment("Debug view: 0 = normal, 1 = direct light only, 2 = bounce/sky light only, 3 = surface normals, 4 = ray diagnostics, 5 = light-list fill.")
    @Config.RangeInt(min = 0, max = 7)
    public static int debugView = 0;

    @Config.Comment("Voxel slices re-scanned every 8 frames as a background safety net (block and chunk changes are picked up instantly anyway). 0 = off.")
    @Config.RangeInt(min = 0, max = 32)
    public static int refreshSlicesPerFrame = 1;

    @Config.Comment("Voxel slices scanned per frame when the volume has to be rebuilt (after moving far or changing worlds).")
    @Config.RangeInt(min = 1, max = 128)
    public static int rebuildSlicesPerFrame = 24;
}
