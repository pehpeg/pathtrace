// PathTrace shared code: voxel ray marching, sky, sun and point light sampling.
// Everything is in "volume space": world coordinates minus the voxel volume's origin.

uniform sampler3D voxelTex;   // RGB albedo, A = 128 solid | colour << 4 | light
uniform sampler3D coarseTex;  // 1 texel per 4x4x4 cell, R > 0 if anything solid inside
uniform sampler2D heightTex;  // world height per column
uniform float volumeSize;
uniform float originY;
uniform vec3 camPos;

uniform vec3 lightDir;        // sun or moon
uniform vec3 lightColor;
uniform vec3 skyColor;
uniform vec3 ambient;
uniform float emissiveStrength;
uniform float frame;

#define MAX_LIGHTS 48
uniform vec4 ptLightPos[MAX_LIGHTS];   // xyz position, w radius (blocks)
uniform vec4 ptLightCol[MAX_LIGHTS];   // rgb intensity
uniform int ptLightCount;
uniform float neeRadius;               // emitting blocks closer than this are in the light list

#define MAX_STEPS 160

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
        if (isSolid(fetchVoxel(cell))) { hitCell = cell; return 1.0; }
    }
    return -1.0;
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

float sunVisibility(vec3 p) {
    vec3 jitter = (vec3(rand(), rand(), rand()) - 0.5) * 0.04;   // soft shadow edges
    vec3 d = normalize(lightDir + jitter);
    vec3 hc; vec3 hn; float t;
    float r = traceRay(p, d, 1.0e6, hc, hn, t);
    if (r > 0.5) return 0.0;
    if (r < -0.5) return 1.0;
    return escapeVisibility(p + d * t);
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

// ------------------------------------------------------------------ point lights
float lightTerm(int i, vec3 p, vec3 n, out vec3 contrib) {
    vec4 lp = ptLightPos[i];
    vec3 L = lp.xyz - p;
    float d2 = dot(L, L);
    contrib = vec3(0.0);
    if (d2 >= lp.w * lp.w) return 0.0;
    float d = sqrt(max(d2, 1e-4));
    float ndl = dot(n, L / d);
    if (ndl <= 0.0) return 0.0;
    float x = 1.0 - d / lp.w;
    contrib = ptLightCol[i].rgb * (ndl * x * x / (1.0 + 0.3 * d2));
    return luminance(contrib);
}

// Sum of all point lights at p. With shadows, one light is picked in proportion to its
// contribution and a single shadow ray decides visibility (unbiased "one sample" estimator).
vec3 pointLights(vec3 p, vec3 n, bool shadows) {
    vec3 sum = vec3(0.0);
    vec3 c;
    float total = 0.0;
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= ptLightCount) break;
        total += lightTerm(i, p, n, c);
        sum += c;
    }
    if (total <= 1e-5 || !shadows) return sum;

    float target = rand() * total;
    float acc = 0.0;
    vec3 lpos = vec3(0.0);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= ptLightCount) break;
        acc += lightTerm(i, p, n, c);
        lpos = ptLightPos[i].xyz;
        if (acc >= target) break;
    }
    vec3 L = lpos - p;
    float d = length(L);
    vec3 hc; vec3 hn; float t;
    float r = traceRay(p, L / max(d, 1e-4), d - 0.05, hc, hn, t);
    if (r > 0.5 && r < 1.5 && any(notEqual(hc, floor(lpos)))) return vec3(0.0);
    return sum;
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
