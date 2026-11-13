# PathTrace

Real-time path traced lighting for **Minecraft 1.12.2** (Forge 14.23.5.x, client-side only).

- **Sun and moon shadows** with soft edges, following time of day and weather
- **Sky light** blocked by terrain, so caves and interiors go dark naturally
- **Bounce light** that picks up the colour of nearby blocks
- **Block lights** (torches, glowstone, lava, sea lanterns…) sampled directly for clean, coloured light
- **Dynamic lights**: held torches and other light sources, dropped items, burning mobs, blazes, fireballs
- **Lit first-person hand and held item**
- Wavelet denoiser, temporal accumulation and auto exposure

## Download

Every push is built by GitHub Actions. Open the **Actions** tab, pick the latest green run and download the
`pathtrace-jar` artifact. Put the jar in `.minecraft/mods`. No other mods are needed.

## Build it yourself

You need JDK 17 or 21 to run Gradle. JDK 8 for compiling is downloaded automatically.

```
gradlew build        (Windows)
./gradlew build      (macOS / Linux)
```

The jar is written to `build/libs/pathtrace-<version>.jar`. Ignore the `-dev` and `-sources` jars.

## Controls

| Key | Action |
|-----|--------|
| **P** | Toggle path tracing |
| **O** | Reload shaders |

## Settings

Mods → PathTrace → Config, or `config/pathtrace.cfg`.

| Setting | Default | Notes |
|---------|---------|-------|
| `renderScale` | 0.5 | Resolution of the tracer. The result is upscaled edge-aware. **Lower it first if FPS is low** |
| `bounces` | 1 | 0 = direct light only, 2–3 = brighter interiors, slower |
| `maxAccumulation` | 32 | Frames averaged for bounce/sky light |
| `maxAccumulationDirect` | 8 | Frames averaged for sun and point lights. Keeps moving lights responsive |
| `denoise` | true | Edge-aware wavelet denoiser |
| `autoExposure` | true | Eye adaptation |
| `exposure`, `saturation` | 1.0, 1.1 | Grading |
| `sunStrength`, `skyStrength` | 2.6, 1.4 | Light balance |
| `pointLightStrength`, `emissiveStrength` | 6.0, 4.0 | Block light and glow brightness |
| `dynamicLights` | true | Lights from entities and items |
| `litHand` | true | Path traced lighting on the first-person hand |

## How it works

1. Minecraft draws the world as usual, but with a white lightmap, which leaves plain surface colours and depth.
2. A 128³ block grid around the camera is kept on the GPU as a 3D texture, plus a coarse 32³ occupancy grid
   for skipping empty space. Block changes and chunk loads are pushed in immediately.
3. `trace.fsh` rebuilds each pixel's position from depth. It traces the sun shadow, samples one point light
   (block lights and dynamic lights, chosen by importance), and follows diffuse bounces through the grid.
   Direct and indirect light go into separate buffers, each blended with reprojected history.
4. `atrous.fsh` denoises in 4 passes. `composite.fsh` upsamples, applies albedo, exposure and an ACES tonemap.
5. The hand is drawn by the mod and relit (`hand.fsh`) from a 64-direction light probe traced at the eye.

## Limitations

- In the voxel grid, only full opaque blocks, leaves and light sources block light. Slabs, stairs, glass,
  fences and mobs don't cast shadows, but they do receive light.
- Beyond about 64 blocks, surfaces get sky and sun light without traced shadows.
- Not compatible with OptiFine shaders.
