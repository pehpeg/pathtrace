# PathTrace

Real-time path traced lighting for **Minecraft 1.12.2** (Forge 14.23.5.x, client-side only).

- **Sun and moon shadows** following time of day and weather
- **Sky light** blocked by terrain, so caves and interiors go dark naturally
- **Bounce light** that picks up the colour of nearby blocks
- **Block lights** (torches, glowstone, lava, sea lanterns…) sampled directly for clean, coloured light
- **Dynamic lights**: held torches and other light sources, dropped items, burning mobs, blazes, fireballs
- **Lit first-person hand and held item**
- **Coloured light through stained glass**
- **Noise-free, pixel-snapped shadows** from the sun and the strongest nearby lights
- Wavelet denoiser for bounce light, temporal accumulation and auto exposure

## Download

Download the jar from the [**Latest build** release](https://github.com/pehpeg/pathtrace/releases/tag/latest).
It is rebuilt on every push to `main`. Put the jar in `.minecraft/mods`. No other mods are needed.

## Build it yourself

You need JDK 17 or 21 to run Gradle. JDK 8 for compiling is downloaded automatically.

```
gradlew build        (Windows)
./gradlew build      (macOS / Linux)
```

The jar is written to `build/libs/pathtrace-<version>.jar`. Ignore the `-dev` and `-sources` jars.

## Commands

| Command | Action |
|---------|--------|
| `/pathtrace on` / `off` / `toggle` | Turn path tracing on or off (`/pt` works too) |
| `/pathtrace status` | Show whether it's on |
| `/pathtrace set <setting> <value>` | Change any setting below (saved to the config); tab completes |
| `/pathtrace get <setting>` | Show a setting's value |
| `/pathtrace reload` | Reload shaders |
| `/pathtrace preset potato\|fast\|balanced\|quality\|ultra` | Set the performance-related settings in one go (`balanced` is the default) |
| `/pathtrace perf` | Where the frame time goes: GPU milliseconds per pass, CPU milliseconds per step |

The same on/off and reload actions can be bound to keys under Controls → PathTrace (unbound by default).

## Settings

The **PathTrace...** button on the pause menu, Mods → PathTrace → Config, or `config/pathtrace.cfg`.

| Setting | Default | Notes |
|---------|---------|-------|
| `renderScale` | 0.5 | Resolution of the tracer. The result is upscaled edge-aware. **Lower it first if FPS is low** |
| `bounces` | 1 | 0 = direct light only, 2–3 = brighter interiors, slower |
| `maxAccumulation` | 32 | Frames averaged for bounce/sky light |
| `pixelShadows` | true | Shadows computed once per block-texture pixel: crisp, pixel-art edges with no flicker |
| `directScale` | 0.5 | Resolution of the direct light and shadows, upscaled edge-aware. 1.0 = sharpest, 0.5 ≈ 4× cheaper |
| `checkerboard` | true | Trace bounce light for half the pixels per frame, alternating (about halves the tracer cost) |
| `shadowedLights` | 3 | Strongest nearby lights that get an exact shadow per pixel (the rest are sampled and denoised) |
| `lightSaturation` | 1.6 | How coloured block light is (1 = subtle tints) |
| `denoise` | true | Edge-aware wavelet denoiser |
| `autoExposure` | true | Eye adaptation |
| `exposure`, `saturation` | 1.0, 1.0 | Grading |
| `sunStrength`, `skyStrength` | 1.3, 0.7 | Light balance |
| `pointLightStrength`, `emissiveStrength` | 2.5, 2.5 | Block light and glow brightness |
| `dynamicLights` | true | Lights from entities and items |
| `litHand` | true | Path traced lighting on the first-person hand |
| `bloom`, `bloomStrength` | true, 1.6 | Soft glow around light sources (flames, glowstone, lava…) |
| `lodShadows` | true | Shadows beyond the detailed 128-block grid, from a 1-block grid out to the render distance |
| `debugView` | 0 | 1 = direct light only, 2 = bounce light only, 3 = surface normals, 4 = ray diagnostics, 5 = light-list fill |

## How it works

1. Minecraft draws the world as usual, but with a white lightmap, which leaves plain surface colours and depth.
2. A 128³ block grid around the camera is kept on the GPU as a 3D texture, plus a coarse 32³ occupancy grid
   for skipping empty space. A second, coarser 128³ grid (2–4 blocks per cell) covers the whole render distance
   so shadow rays can continue past the detailed grid. Light sources are indexed per chunk and sorted into
   8×8×8-block cells, so every pixel only checks the lights near it, out to the render distance. Block changes and chunk loads are pushed in immediately.
3. `gbuffer.fsh` traces each pixel's view ray through the grid to get the exact surface position and
   direction, checked against the depth buffer (which is used directly for mobs, slabs, plants…).
4. `direct.fsh` (full resolution, no randomness) gives each block-texture pixel one fixed sun shadow ray and
   one shadow ray for each of the 4 strongest nearby lights. Weaker lights share those lights' visibility.
   Because nothing is random, direct light has no noise and needs no averaging over frames.
   Pixel-snapped shadows are an idea from [Vibrancy](https://github.com/TheTypholorian/vibrancy) (MIT).
5. `trace.fsh` (reduced resolution) follows diffuse bounces and sky light through the grid and blends them with
   reprojected history. `atrous.fsh` denoises that in 4 passes.
6. `composite.fsh` upsamples the bounce light, adds direct light, and applies albedo, exposure and an ACES tonemap.
7. The hand is drawn by the mod and relit (`hand.fsh`) from a 64-direction light probe traced at the eye.

## Screenshots

CI starts the game on a virtual display (software OpenGL), builds a test scene and takes screenshots
of every build. They are on the [`screenshots` branch](https://github.com/pehpeg/pathtrace/tree/screenshots).
Run the same thing locally with `gradlew runClient -Pautoshot`.

## Limitations

- Non-full blocks (slabs, stairs, fences, bars, torches, plants) cast shadows from their actual model at
  texture-pixel resolution (16×16×16 per block), so light passes through the gaps in bars, fences, leaves
  of plants etc. Glass and liquids let light through, small light sources (torches) don't cast shadows,
  and mobs don't cast shadows yet. Up to 1024 distinct non-full block shapes are kept.
- Beyond about 64 blocks, shadows come from a 1-block grid of full blocks, so non-full blocks (fences, slabs,
  plants) and stained glass don't cast shadows or colour light there.
- Not compatible with OptiFine shaders.
