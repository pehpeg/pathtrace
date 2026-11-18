#version 120
// PathTrace - direct light at full resolution, with no randomness at all:
//  - own glow of emissive blocks
//  - sun/moon: one fixed shadow ray
//  - point lights: the strongest few get an exact shadow ray each; all the others are
//    sampled stochastically (with their own shadow ray) in trace.fsh, where they get
//    accumulated over frames and denoised
// Alpha = how strongly this pixel is itself a light source (feeds the bloom).
// With pixelShadows, rays start from the centre of the block-texture pixel, so every screen
// pixel inside one texture pixel gets the identical answer (idea from Vibrancy, MIT).
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;

uniform int shadowedLights;
uniform float pixelShadows;

vec3 sunVisibilityHard(vec3 p) {
    vec3 hc; vec3 hn; float t;
    g_trans = vec3(1.0);
    float r = traceRay(p, lightDir, 1.0e6, hc, hn, t);
    if (r > 0.5 && r < 1.5) return vec3(0.0);
    if (r < -0.5) return g_trans;
    // left the detailed grid (or started outside it): continue in the far grid
    if (lodEnabled > 0.5) return lodBlocked(p + lightDir * t, lightDir, 1.0e6, t > 0.0, vec3(-1.0e9)) ? vec3(0.0) : g_trans;
    return g_trans * escapeVisibility(p + lightDir * t);
}

vec3 slot(float w, vec4 lp, vec3 c, vec3 blk, vec3 origin) {
    if (w <= 0.0) return vec3(0.0);
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

    // point lights: exact shadows for the strongest few (the rest: trace.fsh)
    findTopLights(P, N, ownInGrid ? vec3(-1.0e9) : own, shadowedLights);
    if (!ownInGrid) {
        direct += g_glow * emissiveStrength * 0.35;
        glow = max(g_glow.r, max(g_glow.g, g_glow.b));
    }
    direct += g_lightScale * (slot(g_topW0, g_topP0, g_topC0, g_topB0, origin)
                            + slot(g_topW1, g_topP1, g_topC1, g_topB1, origin)
                            + slot(g_topW2, g_topP2, g_topC2, g_topB2, origin)
                            + slot(g_topW3, g_topP3, g_topC3, g_topB3, origin));

    gl_FragColor = vec4(min(sane(direct), vec3(32.0)), glow);
}
