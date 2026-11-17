#version 120
// PathTrace - direct light at full resolution, with no randomness at all:
//  - own glow of emissive blocks
//  - sun/moon: one fixed shadow ray
//  - point lights: the strongest few get a shadow ray each, the rest share their visibility
// Alpha = how strongly this pixel is itself a light source (feeds the bloom).
// With pixelShadows, rays start from the centre of the block-texture pixel, so every screen
// pixel inside one texture pixel gets the identical answer (idea from Vibrancy, MIT).
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;

uniform int shadowedLights;
uniform float pixelShadows;

float sunVisibilityHard(vec3 p) {
    vec3 hc; vec3 hn; float t;
    float r = traceRay(p, lightDir, 1.0e6, hc, hn, t);
    if (r > 0.5) return 0.0;
    if (r < -0.5) return 1.0;
    // left the detailed grid (or started outside it): continue in the far grid
    if (lodEnabled > 0.5) return lodBlocked(p + lightDir * t, lightDir, 1.0e6, t <= 0.0 ? lodCell * 1.5 : 0.0) ? 0.0 : 1.0;
    return escapeVisibility(p + lightDir * t);
}

vec3 slot(float w, vec4 lp, vec3 c, vec3 blk, vec3 origin, inout vec3 checkedSum) {
    if (w <= 0.0) return vec3(0.0);
    checkedSum += c;
    return c * lightVisible(origin, lp.xyz, blk);
}

void main() {
    if (texture2D(gbufferTex, uv).w <= 0.0) {
        gl_FragColor = vec4(0.0);
        return;
    }
    vec4 gb = texture2D(gbufferTex, uv);
    vec3 eye = eyeRel();
    vec3 viewDir = viewRayDir(uv);
    float dist = gb.w;
    vec3 rel = eye + viewDir * dist;
    vec3 P = rel + camPos;
    vec3 N = gb.xyz;
    vec3 S = pixelShadows > 0.5 ? snapToTexel(P, N) : P;
    vec3 origin = S + N * (0.02 + 0.002 * dist);

    vec3 direct = vec3(0.0);
    float glow = 0.0;   // how much this pixel is a light source (alpha output, feeds bloom)

    // own glow: from the voxel grid nearby, from the light list beyond it
    vec3 own = floor(P - N * 0.25);
    bool ownInGrid = insideVolume(own);
    if (ownInGrid) {
        vec4 ov = fetchVoxel(own);
        if (isSolid(ov)) {
            direct += emitColor(ov) * emission(ov) * emissiveStrength * 0.35;
            glow = emission(ov);
        }
    }

    // sun / moon
    float ndl = dot(N, lightDir);
    if (ndl > 0.0) direct += lightColor * ndl * sunVisibilityHard(origin);

    // point lights: exact shadows for the 4 strongest, the rest share their visibility
    g_wantTop = true;
    g_ownCell = ownInGrid ? vec3(-1.0e9) : own;
    visitLights(P, N);
    if (!ownInGrid) {
        direct += g_glow * emissiveStrength * 0.35;
        glow = max(g_glow.r, max(g_glow.g, g_glow.b));
    }
    if (shadowedLights < 4) g_topW3 = 0.0;
    if (shadowedLights < 3) g_topW2 = 0.0;
    if (shadowedLights < 2) g_topW1 = 0.0;
    if (shadowedLights < 1) g_topW0 = 0.0;
    vec3 checkedSum = vec3(0.0);
    vec3 lit = slot(g_topW0, g_topP0, g_topC0, g_topB0, origin, checkedSum)
             + slot(g_topW1, g_topP1, g_topC1, g_topB1, origin, checkedSum)
             + slot(g_topW2, g_topP2, g_topC2, g_topB2, origin, checkedSum)
             + slot(g_topW3, g_topP3, g_topC3, g_topB3, origin, checkedSum);
    float ratio = luminance(checkedSum) > 1e-5 ? luminance(lit) / luminance(checkedSum) : 1.0;
    direct += lit + max(g_sum - checkedSum, vec3(0.0)) * ratio;

    gl_FragColor = vec4(min(direct, vec3(32.0)), glow);
}
