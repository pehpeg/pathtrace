#version 120
// PathTrace - direct light at full resolution, with no randomness at all:
//  - own glow of emissive blocks
//  - sun/moon: one fixed shadow ray
//  - point lights: the strongest few get a shadow ray each, the rest share their visibility
// With pixelShadows, rays start from the centre of the block-texture pixel, so every screen
// pixel inside one texture pixel gets the identical answer (idea from Vibrancy, MIT).
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;

uniform int shadowedLights;
uniform float pixelShadows;

// 1 if nothing blocks the segment from p to the light at lp
float lightVisible(vec3 p, vec3 lp) {
    vec3 L = lp - p;
    float d = length(L);
    if (d < 1e-3) return 1.0;
    vec3 hc; vec3 hn; float t;
    float r = traceRay(p, L / d, d - 0.05, hc, hn, t);
    if (r > 0.5 && r < 1.5 && any(notEqual(hc, floor(lp)))) return 0.0;
    return 1.0;
}

float sunVisibilityHard(vec3 p) {
    vec3 hc; vec3 hn; float t;
    float r = traceRay(p, lightDir, 1.0e6, hc, hn, t);
    if (r > 0.5) return 0.0;
    if (r < -0.5) return 1.0;
    return escapeVisibility(p + lightDir * t);
}

vec3 slotLight(int idx, vec3 p, vec3 n, vec3 origin, inout vec3 checkedSum) {
    if (idx < 0) return vec3(0.0);
    vec3 c;
    lightTerm(idx, p, n, c);
    checkedSum += c;
    return c * lightVisible(origin, ptLightPos[idx].xyz);
}

void main() {
    float d = texture2D(depthTex, uv).r;
    if (d >= 1.0) {
        gl_FragColor = vec4(0.0);
        return;
    }
    vec3 rel = relPosAt(uv);
    vec3 eye = (invView * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    vec3 viewDir = normalize(rel - eye);
    float dist = length(rel - eye);
    vec3 P = rel + camPos;
    vec3 N = surfaceNormal(uv, rel, viewDir);
    vec3 S = pixelShadows > 0.5 ? snapToTexel(P, N) : P;
    vec3 origin = S + N * (0.02 + 0.002 * dist);

    vec3 direct = vec3(0.0);

    // own glow
    vec3 own = floor(P - N * 0.25);
    if (insideVolume(own)) {
        vec4 ov = fetchVoxel(own);
        if (isSolid(ov)) direct += emitColor(ov) * emission(ov) * emissiveStrength * 0.35;
    }

    // sun / moon
    float ndl = dot(N, lightDir);
    if (ndl > 0.0) direct += lightColor * ndl * sunVisibilityHard(origin);

    // point lights: keep the 4 strongest (by unshadowed contribution)
    vec3 total = vec3(0.0);
    vec3 c;
    float w0 = 0.0, w1 = 0.0, w2 = 0.0, w3 = 0.0;
    int i0 = -1, i1 = -1, i2 = -1, i3 = -1;
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= ptLightCount) break;
        float w = lightTerm(i, P, N, c);
        if (w <= 0.0) continue;
        total += c;
        if (w > w0) {
            w3 = w2; i3 = i2; w2 = w1; i2 = i1; w1 = w0; i1 = i0; w0 = w; i0 = i;
        } else if (w > w1) {
            w3 = w2; i3 = i2; w2 = w1; i2 = i1; w1 = w; i1 = i;
        } else if (w > w2) {
            w3 = w2; i3 = i2; w2 = w; i2 = i;
        } else if (w > w3) {
            w3 = w; i3 = i;
        }
    }
    if (shadowedLights < 4) i3 = -1;
    if (shadowedLights < 3) i2 = -1;
    if (shadowedLights < 2) i1 = -1;
    if (shadowedLights < 1) i0 = -1;

    vec3 checkedSum = vec3(0.0);
    vec3 lit = slotLight(i0, P, N, origin, checkedSum)
             + slotLight(i1, P, N, origin, checkedSum)
             + slotLight(i2, P, N, origin, checkedSum)
             + slotLight(i3, P, N, origin, checkedSum);
    // weaker lights borrow the visibility ratio of the strong ones
    float ratio = luminance(checkedSum) > 1e-5 ? luminance(lit) / luminance(checkedSum) : 1.0;
    direct += lit + max(total - checkedSum, vec3(0.0)) * ratio;

    gl_FragColor = vec4(min(direct, vec3(32.0)), 1.0);
}
