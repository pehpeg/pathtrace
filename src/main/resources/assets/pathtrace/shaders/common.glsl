// PathTrace shared code: voxel ray marching, sky, sun and point light sampling.
// Everything is in "volume space": world coordinates minus the voxel volume's origin.

uniform sampler3D voxelTex;   // RGB albedo, A = 128 solid | colour << 4 | light
uniform sampler3D coarseTex;  // 1 texel per 4x4x4 cell, R > 0 if anything solid inside
uniform sampler2D heightTex;  // world height per column
uniform sampler3D shapeIdTex; // per block: number of its 16^3 shape (R + 256 G), 0 = full block
uniform sampler3D shapeTex;   // ShapeAtlas: 32 x 32 bricks of 16x16x16 cells
uniform float volumeSize;
uniform float originY;
uniform vec3 camPos;

uniform vec3 lightDir;        // sun or moon
uniform vec3 lightColor;
uniform vec3 skyColor;
uniform vec3 ambient;
uniform float emissiveStrength;
uniform float frame;

// Block lights: clustered grid (see LightGrid.java). Dynamic lights: small uniform list.
uniform sampler2D lightTex;   // 2 texels per light: (pos relative to grid origin, radius), (rgb intensity, offset code)
uniform sampler2D cellTex;    // 4 texels per 8x8x8 cell: 16 light indices, strongest first, -1 = none
uniform vec3 gridOrigin;      // volume space
uniform vec3 gridCells;
uniform vec2 lightTexSize;
uniform vec2 cellTexSize;
#define GRID_CELL 8.0
#define MAX_DYN 16
uniform vec4 dynLightPos[MAX_DYN];   // xyz position, w radius
uniform vec4 dynLightCol[MAX_DYN];   // rgb intensity, w = offset code (13)
#define LIGHT_PUSH 0.8               // LightGrid.PUSH
uniform int dynLightCount;
float lightMinDist = 0.0;            // falloff never gets brighter than at this distance

#define MAX_STEPS 160

// Far shadow grid (LodVolume.java): exact 1-block solid grid over the render distance, y from world 0.
// lodTex packs 8 blocks along X per texel (bits); lodCoarseTex has one texel per 8x8x8 blocks.
uniform sampler3D lodTex;
uniform sampler3D lodCoarseTex;
uniform vec3 lodOrigin;       // volume space
uniform vec3 lodSize;         // blocks: (extent, 256, extent)
uniform float lodEnabled;
#define LOD_STEPS 256

// ------------------------------------------------------------------ random numbers
// Animated interleaved gradient noise: spreads error evenly in screen space and over
// time, which the temporal filter and denoiser clean up much better than white noise.
vec2 rngPix;
float rngCounter = 0.0;

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float rand() {
    rngCounter += 1.0;
    return ign(rngPix + vec2(47.0, 17.0) * rngCounter + 5.588238 * mod(frame, 64.0));
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// ------------------------------------------------------------------ voxels
vec4 fetchVoxel(vec3 cell) {
    return texture3D(voxelTex, (cell + 0.5) / volumeSize);
}

bool isSolid(vec4 v) {
    return v.a > 0.499;
}

// Stained glass: not solid, but light passing through takes its colour.
bool isTint(vec4 v) {
    return v.a > 0.2 && v.a < 0.499;
}

vec3 glassTint(vec4 v) {
    vec3 c = pow(v.rgb, vec3(2.2));
    c /= max(max(c.r, c.g), max(c.b, 1e-3));
    return mix(vec3(1.0), c, 0.85);
}

// Colour picked up from stained glass by the last traceRay call (reset by the caller).
vec3 g_trans = vec3(1.0);

float voxelAlpha(vec4 v) {
    return floor(v.a * 255.0 + 0.5);
}

float emission(vec4 v) {
    return mod(voxelAlpha(v), 16.0) / 15.0;
}

vec3 emitColorIndex(float idx) {
    if (idx < 0.5) return vec3(1.00, 0.72, 0.42);
    if (idx < 1.5) return vec3(1.00, 0.50, 0.18);
    if (idx < 2.5) return vec3(1.00, 0.20, 0.12);
    if (idx < 3.5) return vec3(0.75, 0.88, 1.00);
    if (idx < 4.5) return vec3(0.70, 0.35, 1.00);
    return vec3(0.50, 1.00, 0.45);
}

vec3 emitColor(vec4 v) {
    return emitColorIndex(floor(mod(voxelAlpha(v), 128.0) / 16.0));
}

bool insideVolume(vec3 c) {
    return c.x >= 0.0 && c.y >= 0.0 && c.z >= 0.0 &&
           c.x < volumeSize && c.y < volumeSize && c.z < volumeSize;
}

bool coarseEmpty(vec3 cell) {
    return texture3D(coarseTex, (floor(cell * 0.25) + 0.5) / (volumeSize * 0.25)).r < 0.5;
}

// ---- sub-block shapes: a non-full block is a 16x16x16 grid, one cell per texture pixel
#define SHAPE_RES 16.0
#define SHAPE_BRICKS 32.0

float shapeIdAt(vec3 cell) {
    vec4 v = texture3D(shapeIdTex, (cell + 0.5) / volumeSize);
    return floor(v.r * 255.0 + 0.5) + 256.0 * floor(v.g * 255.0 + 0.5);
}

bool shapeSolid(float id, vec3 m) {
    float b = id - 1.0;
    vec3 t = vec3(vec2(mod(b, SHAPE_BRICKS), floor(b / SHAPE_BRICKS)) * SHAPE_RES + m.xy, m.z);
    return texture3D(shapeTex, (t + 0.5) / vec3(SHAPE_BRICKS * SHAPE_RES, SHAPE_BRICKS * SHAPE_RES, SHAPE_RES)).r > 0.5;
}

// Walk the 16^3 cells of `cell`, which the ray enters at distance tEnter through face nEnter.
// skipFirst: the ray starts inside this block (on its surface). Ignore solid cells until the ray
// reaches an open one, so a surface doesn't shadow itself.
bool traceMicro(vec3 ro, vec3 rd, vec3 invd, vec3 stp, vec3 cell, float tEnter, vec3 nEnter,
                float id, bool skipFirst, out float tHit, out vec3 nHit) {
    float cs = 1.0 / SHAPE_RES;
    vec3 p = (ro + rd * (tEnter + 1e-4) - cell) * SHAPE_RES;
    vec3 m = clamp(floor(p), vec3(0.0), vec3(SHAPE_RES - 1.0));
    vec3 tMax = abs(cell + (m + step(0.0, rd)) * cs - ro) * invd;
    float t = tEnter;
    vec3 n = nEnter;
    bool inSelf = skipFirst;
    for (int i = 0; i < 48; i++) {
        bool solid = shapeSolid(id, m);
        if (inSelf && !solid) inSelf = false;
        if (!inSelf && solid) {
            tHit = t;
            nHit = n;
            return true;
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            m.x += stp.x; t = tMax.x; tMax.x += cs * invd.x; n = vec3(-stp.x, 0.0, 0.0);
        } else if (tMax.y < tMax.z) {
            m.y += stp.y; t = tMax.y; tMax.y += cs * invd.y; n = vec3(0.0, -stp.y, 0.0);
        } else {
            m.z += stp.z; t = tMax.z; tMax.z += cs * invd.z; n = vec3(0.0, 0.0, -stp.z);
        }
        if (any(lessThan(m, vec3(0.0))) || any(greaterThan(m, vec3(SHAPE_RES - 1.0)))) return false;
    }
    return false;
}

// Grid traversal (Amanatides & Woo) that jumps over empty 4x4x4 cells.
// The starting cell is skipped on purpose.
// Returns 1 = hit a block, 0 = left the volume, 2 = reached maxT, -1 = ran out of steps.
float traceRay(vec3 ro, vec3 rd, float maxT, out vec3 hitCell, out vec3 hitNormal, out float hitT) {
    vec3 cell = floor(ro);
    vec3 stp = sign(rd);
    vec3 invd = 1.0 / max(abs(rd), vec3(1e-6));
    vec3 sgn = step(0.0, rd);
    vec3 tMax = abs(cell + sgn - ro) * invd;
    hitCell = cell;
    hitNormal = vec3(0.0);
    hitT = 0.0;
    if (!insideVolume(cell)) return 0.0;

    // The starting block is normally skipped (it's the air in front of the surface we start on).
    // But if it's a non-full block (fence, bars, slab...), the rest of its shape can still block the ray.
    vec4 sv = fetchVoxel(cell);
    if (isSolid(sv) && emission(sv) <= 0.0) {
        float sid = shapeIdAt(cell);
        float tm; vec3 nm;
        if (sid > 0.5 && traceMicro(ro, rd, invd, stp, cell, 0.0, vec3(0.0), sid, true, tm, nm)) {
            if (tm > maxT) return 2.0;
            hitT = tm; hitNormal = nm;
            return 1.0;
        }
    }

    for (int i = 0; i < MAX_STEPS; i++) {
        if (coarseEmpty(cell)) {
            // leap to the far side of this empty 4x4x4 cell
            vec3 cb = floor(cell * 0.25) * 4.0;
            vec3 tb = abs(cb + sgn * 4.0 - ro) * invd;
            float te;
            if (tb.x < tb.y && tb.x < tb.z) {
                te = tb.x;
                hitNormal = vec3(-stp.x, 0.0, 0.0);
                cell = floor(ro + rd * te);
                cell.x = stp.x > 0.0 ? cb.x + 4.0 : cb.x - 1.0;
            } else if (tb.y < tb.z) {
                te = tb.y;
                hitNormal = vec3(0.0, -stp.y, 0.0);
                cell = floor(ro + rd * te);
                cell.y = stp.y > 0.0 ? cb.y + 4.0 : cb.y - 1.0;
            } else {
                te = tb.z;
                hitNormal = vec3(0.0, 0.0, -stp.z);
                cell = floor(ro + rd * te);
                cell.z = stp.z > 0.0 ? cb.z + 4.0 : cb.z - 1.0;
            }
            hitT = max(te, hitT);
            tMax = abs(cell + sgn - ro) * invd;
        } else {
            if (tMax.x < tMax.y && tMax.x < tMax.z) {
                cell.x += stp.x; hitT = tMax.x; tMax.x += invd.x; hitNormal = vec3(-stp.x, 0.0, 0.0);
            } else if (tMax.y < tMax.z) {
                cell.y += stp.y; hitT = tMax.y; tMax.y += invd.y; hitNormal = vec3(0.0, -stp.y, 0.0);
            } else {
                cell.z += stp.z; hitT = tMax.z; tMax.z += invd.z; hitNormal = vec3(0.0, 0.0, -stp.z);
            }
        }
        if (!insideVolume(cell)) { hitCell = cell; return 0.0; }
        if (hitT > maxT) return 2.0;
        vec4 vox = fetchVoxel(cell);
        if (isTint(vox)) g_trans *= glassTint(vox);
        if (isSolid(vox)) {
            float id = shapeIdAt(cell);
            if (id < 0.5) { hitCell = cell; return 1.0; }   // full block
            if (emission(vox) > 0.0) continue;   // small light sources (torches...) don't cast shadows
            float tm; vec3 nm;
            if (traceMicro(ro, rd, invd, stp, cell, hitT, hitNormal, id, false, tm, nm)) {
                if (tm > maxT) return 2.0;
                hitCell = cell; hitT = tm; hitNormal = nm;
                return 1.0;
            }
        }
    }
    return -1.0;
}

bool lodInside(vec3 c) {
    return all(greaterThanEqual(c, vec3(0.0))) && all(lessThan(c, lodSize));
}

bool lodSolid(vec3 c) {
    float bx = floor(c.x / 8.0);
    float v = floor(texture3D(lodTex, (vec3(bx, c.y, c.z) + 0.5) / vec3(lodSize.x / 8.0, lodSize.y, lodSize.z)).r * 255.0 + 0.5);
    return mod(floor(v / exp2(c.x - bx * 8.0)), 2.0) > 0.5;
}

bool lodCoarseEmpty(vec3 c) {
    return texture3D(lodCoarseTex, (floor(c / 8.0) + 0.5) / (lodSize / 8.0)).r < 0.5;
}

// True if the far grid blocks the segment p -> p + dir * maxT. The start block is checked only if
// checkStart (when continuing a ray from the detailed grid); hits on targetCell (a light's own block)
// don't count. Jumps over empty 8x8x8 regions.
bool lodBlocked(vec3 p, vec3 dir, float maxT, bool checkStart, vec3 targetCell) {
    if (lodEnabled < 0.5 || maxT <= 0.0) return false;
    vec3 ro = p - lodOrigin;
    vec3 tc = targetCell - lodOrigin;
    vec3 cell = floor(ro);
    if (!lodInside(cell)) return false;
    if (checkStart && lodSolid(cell) && any(notEqual(cell, tc))) return true;
    vec3 stp = sign(dir);
    vec3 invd = 1.0 / max(abs(dir), vec3(1e-6));
    vec3 sgn = step(0.0, dir);
    vec3 tMax = abs(cell + sgn - ro) * invd;
    float t = 0.0;
    for (int i = 0; i < LOD_STEPS; i++) {
        if (lodCoarseEmpty(cell)) {
            vec3 cb = floor(cell / 8.0) * 8.0;
            vec3 tb = abs(cb + sgn * 8.0 - ro) * invd;
            if (tb.x < tb.y && tb.x < tb.z) {
                t = tb.x; cell = floor(ro + dir * t); cell.x = stp.x > 0.0 ? cb.x + 8.0 : cb.x - 1.0;
            } else if (tb.y < tb.z) {
                t = tb.y; cell = floor(ro + dir * t); cell.y = stp.y > 0.0 ? cb.y + 8.0 : cb.y - 1.0;
            } else {
                t = tb.z; cell = floor(ro + dir * t); cell.z = stp.z > 0.0 ? cb.z + 8.0 : cb.z - 1.0;
            }
            tMax = abs(cell + sgn - ro) * invd;
        } else {
            if (tMax.x < tMax.y && tMax.x < tMax.z) {
                cell.x += stp.x; t = tMax.x; tMax.x += invd.x;
            } else if (tMax.y < tMax.z) {
                cell.y += stp.y; t = tMax.y; tMax.y += invd.y;
            } else {
                cell.z += stp.z; t = tMax.z; tMax.z += invd.z;
            }
        }
        if (t > maxT) return false;
        if (!lodInside(cell)) return false;
        if (lodSolid(cell) && any(notEqual(cell, tc))) return true;
    }
    return false;
}

// Shadow ray: the detailed grid near the camera, then the far grid.
// Returns true if something blocks p -> p + dir * maxT. A hit on `targetCell` (the light's own
// block) doesn't count.
bool shadowBlocked(vec3 p, vec3 dir, float maxT, vec3 targetCell) {
    vec3 hc; vec3 hn; float t;
    float r = traceRay(p, dir, maxT, hc, hn, t);
    if (r > 0.5 && r < 1.5) return any(notEqual(hc, targetCell));
    if (r < -0.5 || r > 1.5) return false;
    // left the detailed grid at distance t (t == 0: started outside it, so skip the start block)
    return lodBlocked(p + dir * t, dir, maxT - t, t > 0.0, targetCell);
}

// ------------------------------------------------------------------ sky + sun
// A ray that leaves the grid sees sky only if it is above the terrain height there.
float escapeVisibility(vec3 p) {
    if (p.y <= 0.0) return 0.0;
    vec2 c = clamp(floor(p.xz), vec2(0.0), vec2(volumeSize - 1.0));
    float h = texture2D(heightTex, (c + 0.5) / volumeSize).r * 255.0 - originY;
    return p.y >= h - 0.5 ? 1.0 : 0.0;
}

vec3 skyRadiance(vec3 d) {
    return skyColor * mix(0.35, 1.0, clamp(d.y * 0.5 + 0.5, 0.0, 1.0));
}

vec3 sunVisibility(vec3 p) {
    vec3 jitter = (vec3(rand(), rand(), rand()) - 0.5) * 0.04;   // soft shadow edges
    vec3 d = normalize(lightDir + jitter);
    vec3 hc; vec3 hn; float t;
    g_trans = vec3(1.0);
    float r = traceRay(p, d, 1.0e6, hc, hn, t);
    if (r > 0.5 && r < 1.5) return vec3(0.0);
    if (r < -0.5) return g_trans;
    if (lodEnabled > 0.5) return lodBlocked(p + d * t, d, 1.0e6, t > 0.0, vec3(-1.0e9)) ? vec3(0.0) : g_trans;
    return g_trans * escapeVisibility(p + d * t);
}

vec3 cosineDirection(vec3 n) {
    float r1 = rand();
    float r2 = rand();
    float phi = 6.2831853 * r1;
    float r = sqrt(r2);
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    return normalize(t * (cos(phi) * r) + b * (sin(phi) * r) + n * sqrt(max(0.0, 1.0 - r2)));
}

// NaN / infinity guard: one bad pixel would otherwise spread through the denoiser and history
vec3 sane(vec3 c) {
    bool ok = abs(c.r) < 1.0e6 && abs(c.g) < 1.0e6 && abs(c.b) < 1.0e6;
    return ok ? c : vec3(0.0);
}

// ------------------------------------------------------------------ point lights
// visitLights(p, n) walks the dynamic lights and the block lights of p's grid cell, and leaves:
//   g_sum    total unshadowed light
//   g_total  its luminance; g_pick = one light chosen in proportion to its contribution
//   g_top*   the 4 strongest lights (for exact shadows in direct.fsh)
//   g_glow   glow of the emitting block p sits on (from the light list, works outside the voxel grid)
vec3 g_sum;
float g_total;
vec4 g_pick;
vec3 g_pickCell;
vec4 g_topP0, g_topP1, g_topP2, g_topP3;
vec3 g_topB0, g_topB1, g_topB2, g_topB3;   // the light's own block (its hits don't shadow it)
vec3 g_topC0, g_topC1, g_topC2, g_topC3;
float g_topW0, g_topW1, g_topW2, g_topW3;
vec3 g_ownCell;
vec3 g_glow;
bool g_wantTop = false;
// with g_exclude, lights at the g_ex* positions are skipped (they already got an exact shadow ray)
bool g_exclude = false;
vec3 g_ex0, g_ex1, g_ex2, g_ex3;

float lightTerm(vec4 lp, vec3 lc, vec3 p, vec3 n, out vec3 contrib) {
    vec3 L = lp.xyz - p;
    float d2 = dot(L, L);
    contrib = vec3(0.0);
    if (d2 >= lp.w * lp.w) return 0.0;
    float d = sqrt(max(d2, 1e-4));
    float ndl = dot(n, L / d);
    if (ndl <= -0.25) return 0.0;
    float fd = max(d, lightMinDist);
    float x = max(1.0 - fd / lp.w, 0.0);
    float wrap = (ndl + 0.25) / 1.25;      // block light spreads a little past grazing angles, like vanilla
    contrib = lc * (wrap * x * x / (1.0 + 0.3 * fd * fd));
    return luminance(contrib);
}

// The block a light belongs to: lights of full glowing blocks are pushed out towards their open faces.
vec3 lightBlock(vec3 lp, float code) {
    vec3 o = vec3(mod(code, 3.0), mod(floor(code / 3.0), 3.0), floor(code / 9.0)) - 1.0;
    float l = length(o);
    return floor(l > 0.5 ? lp - o / l * LIGHT_PUSH : lp);
}

void visitLight(vec4 lp, vec4 lcw, vec3 p, vec3 n) {
    vec3 lc = lcw.rgb;
    vec3 blk = lightBlock(lp.xyz, lcw.w);
    if (all(equal(blk, g_ownCell))) {
        g_glow = max(g_glow, lc / max(max(lc.r, lc.g), max(lc.b, 1e-4)) * ((lp.w - 0.5) / 15.0));
    }
    if (g_exclude) {
        if (all(lessThan(abs(lp.xyz - g_ex0), vec3(1e-3))) || all(lessThan(abs(lp.xyz - g_ex1), vec3(1e-3)))
         || all(lessThan(abs(lp.xyz - g_ex2), vec3(1e-3))) || all(lessThan(abs(lp.xyz - g_ex3), vec3(1e-3)))) return;
    }
    vec3 c;
    float w = lightTerm(lp, lc, p, n, c);
    if (w <= 0.0) return;
    g_sum += c;
    g_total += w;
    if (rand() * g_total < w) { g_pick = lp; g_pickCell = blk; }
    if (!g_wantTop) return;
    if (w > g_topW0) {
        g_topW3 = g_topW2; g_topP3 = g_topP2; g_topC3 = g_topC2; g_topB3 = g_topB2;
        g_topW2 = g_topW1; g_topP2 = g_topP1; g_topC2 = g_topC1; g_topB2 = g_topB1;
        g_topW1 = g_topW0; g_topP1 = g_topP0; g_topC1 = g_topC0; g_topB1 = g_topB0;
        g_topW0 = w; g_topP0 = lp; g_topC0 = c; g_topB0 = blk;
    } else if (w > g_topW1) {
        g_topW3 = g_topW2; g_topP3 = g_topP2; g_topC3 = g_topC2; g_topB3 = g_topB2;
        g_topW2 = g_topW1; g_topP2 = g_topP1; g_topC2 = g_topC1; g_topB2 = g_topB1;
        g_topW1 = w; g_topP1 = lp; g_topC1 = c; g_topB1 = blk;
    } else if (w > g_topW2) {
        g_topW3 = g_topW2; g_topP3 = g_topP2; g_topC3 = g_topC2; g_topB3 = g_topB2;
        g_topW2 = w; g_topP2 = lp; g_topC2 = c; g_topB2 = blk;
    } else if (w > g_topW3) {
        g_topW3 = w; g_topP3 = lp; g_topC3 = c; g_topB3 = blk;
    }
}

bool visitIndex(float idx, vec3 p, vec3 n) {
    if (idx < 0.0) return false;
    vec2 base = vec2(mod(idx, 128.0) * 2.0, floor(idx / 128.0));
    vec4 lp = texture2D(lightTex, (base + 0.5) / lightTexSize);
    vec4 lcw = texture2D(lightTex, (base + vec2(1.5, 0.5)) / lightTexSize);
    lp.xyz += gridOrigin;
    visitLight(lp, lcw, p, n);
    return true;
}

void visitLights(vec3 p, vec3 n) {
    g_sum = vec3(0.0); g_total = 0.0; g_pick = vec4(0.0); g_pickCell = vec3(-1.0e9); g_glow = vec3(0.0);
    g_topB0 = vec3(-1.0e9); g_topB1 = vec3(-1.0e9); g_topB2 = vec3(-1.0e9); g_topB3 = vec3(-1.0e9);
    g_topW0 = 0.0; g_topW1 = 0.0; g_topW2 = 0.0; g_topW3 = 0.0;
    g_topP0 = vec4(0.0); g_topP1 = vec4(0.0); g_topP2 = vec4(0.0); g_topP3 = vec4(0.0);
    g_topC0 = vec3(0.0); g_topC1 = vec3(0.0); g_topC2 = vec3(0.0); g_topC3 = vec3(0.0);
    for (int i = 0; i < MAX_DYN; i++) {
        if (i >= dynLightCount) break;
        visitLight(dynLightPos[i], dynLightCol[i], p, n);
    }
    vec3 c = floor((p - gridOrigin) / GRID_CELL);
    if (any(lessThan(c, vec3(0.0))) || any(greaterThanEqual(c, gridCells))) return;
    float cell = (c.z * gridCells.y + c.y) * gridCells.x + c.x;
    for (int j = 0; j < 4; j++) {
        float t = cell * 4.0 + float(j);
        vec4 ids = texture2D(cellTex, (vec2(mod(t, cellTexSize.x), floor(t / cellTexSize.x)) + 0.5) / cellTexSize);
        if (!visitIndex(ids.x, p, n)) return;
        if (!visitIndex(ids.y, p, n)) return;
        if (!visitIndex(ids.z, p, n)) return;
        if (!visitIndex(ids.w, p, n)) return;
    }
}

// Many lights close together: vanilla takes the brightest, a plain sum blows out. The sum is
// kept as is up to about two nearby lights' worth, then rolls off smoothly (towards 2x that).
const float LIGHT_KNEE = 3.0;
float lightCrowding(float total) {
    if (total <= LIGHT_KNEE) return 1.0;
    float over = total - LIGHT_KNEE;
    return (LIGHT_KNEE + over / (1.0 + over / LIGHT_KNEE)) / total;
}
float g_lightScale = 1.0;

// Lights of full glowing blocks sit just outside the face they shine from. Treat them as a small
// square on that face: a point on it at (u, v) in [-1, 1]. Other lights stay points.
vec3 lightOnFace(vec3 lp, vec3 blk, vec2 uv) {
    if (all(greaterThanEqual(lp, blk)) && all(lessThan(lp, blk + 1.0))) return lp;
    vec3 o = normalize(lp - (blk + 0.5));
    vec3 t = normalize(abs(o.y) < 0.9 ? cross(o, vec3(0.0, 1.0, 0.0)) : cross(o, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(o, t);
    return lp + (t * uv.x + b * uv.y) * 0.4;
}

// Find the strongest lights at p (the ones direct.fsh shadows exactly), keeping at most `count`.
void findTopLights(vec3 p, vec3 n, vec3 ownCell, int count) {
    g_wantTop = true;
    g_exclude = false;
    g_ownCell = ownCell;
    visitLights(p, n);
    g_wantTop = false;
    g_lightScale = lightCrowding(g_total);
    if (count < 4) g_topW3 = 0.0;
    if (count < 3) g_topW2 = 0.0;
    if (count < 2) g_topW1 = 0.0;
    if (count < 1) g_topW0 = 0.0;
}

// 1 if nothing blocks the segment from p to the light at lp (hits on its own block don't count)
vec3 lightVisible(vec3 p, vec3 lp, vec3 lightBlockCell) {
    vec3 L = lp - p;
    float d = length(L);
    if (d < 1e-3) return vec3(1.0);
    g_trans = vec3(1.0);
    return shadowBlocked(p, L / d, d - 0.05, lightBlockCell) ? vec3(0.0) : g_trans;
}

// All point lights at p. With shadows, the light picked in proportion to its contribution
// gets one shadow ray that stands in for all of them (unbiased one-sample estimator).
vec3 pointLights(vec3 p, vec3 n, bool shadows) {
    g_ownCell = vec3(-1.0e9);
    visitLights(p, n);
    float k = lightCrowding(g_total);
    if (g_total <= 1e-5 || !shadows) return g_sum * k;
    vec3 target = lightOnFace(g_pick.xyz, g_pickCell, vec2(rand(), rand()) * 2.0 - 1.0);
    return g_sum * k * lightVisible(p, target, g_pickCell);
}

// 64 fixed directions on a sphere (Fibonacci), shared by the eye probe and the hand pass.
vec3 probeDir(float i) {
    float y = 1.0 - (i + 0.5) / 32.0;
    float r = sqrt(max(0.0, 1.0 - y * y));
    float phi = i * 2.3999632;
    return vec3(cos(phi) * r, y, sin(phi) * r);
}

vec3 acesTonemap(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}
