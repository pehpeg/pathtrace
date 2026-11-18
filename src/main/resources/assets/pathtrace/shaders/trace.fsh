#version 120
// PathTrace - per-pixel lighting. Primary visibility comes from Minecraft's depth buffer;
// everything after that is ray-marched through the voxel volume.
// Direct light is done separately (direct.fsh); this pass only does bounce/sky light.
// Outputs: 0 = indirect light (rgb, sample count),
//          1 = geometry (world normal, distance from eye) for reprojection and denoising.
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;

uniform sampler2D histIndirect;
uniform sampler2D histGeom;

uniform mat4 prevProj;
uniform mat4 prevView;
uniform vec3 prevCamPos;
uniform vec3 prevEye;

uniform float hasHistory;
uniform float maxAccumIndirect;
uniform int bounces;
uniform vec2 traceRes;
uniform int shadowedLights;

// Every point light except the strongest few (those get exact shadows in direct.fsh):
// one of them is picked in proportion to its contribution and gets a shadow ray that
// stands in for all of them. Noisy per frame, but it is accumulated and denoised.
vec3 remainingLights(vec3 P, vec3 N, vec3 origin) {
    vec3 own = floor(P - N * 0.25);
    g_wantRem = true;
    findTopLights(P, N, insideVolume(own) ? vec3(-1.0e9) : own, 4);
    g_wantRem = false;
    // lights beyond shadowedLights are top-4 members direct.fsh doesn't shadow: they're ours too
    vec3 sum = g_remSum;
    float scale = g_lightScale;
    vec3 extra = vec3(0.0);
    if (shadowedLights < 4 && g_topW3 > 0.0) extra += g_topC3 * lightVisible(origin, lightTarget(origin, g_topP3.xyz, g_topB3, vec3(0.0)), g_topB3);
    if (shadowedLights < 3 && g_topW2 > 0.0) extra += g_topC2 * lightVisible(origin, lightTarget(origin, g_topP2.xyz, g_topB2, vec3(0.0)), g_topB2);
    if (shadowedLights < 2 && g_topW1 > 0.0) extra += g_topC1 * lightVisible(origin, lightTarget(origin, g_topP1.xyz, g_topB1, vec3(0.0)), g_topB1);
    if (shadowedLights < 1 && g_topW0 > 0.0) extra += g_topC0 * lightVisible(origin, lightTarget(origin, g_topP0.xyz, g_topB0, vec3(0.0)), g_topB0);
    vec3 rem = vec3(0.0);
    if (g_remTotal > 1e-5) {
        vec3 target = lightTarget(origin, g_remPick.xyz, g_remCell, (vec3(rand(), rand(), rand()) - 0.5) * 0.6);
        rem = sum * lightVisible(origin, target, g_remCell);
    }
    return (rem + extra) * scale;
}

void main() {
    rngPix = gl_FragCoord.xy;
    if (texture2D(gbufferTex, uv).w <= 0.0) {
        gl_FragData[0] = vec4(0.0);
        gl_FragData[1] = vec4(0.0);
        return;
    }

    vec4 gb = texture2D(gbufferTex, uv);
    vec3 eye = eyeRel();
    vec3 viewDir = viewRayDir(uv);
    float dist = gb.w;
    vec3 rel = eye + viewDir * dist;
    vec3 P = rel + camPos;
    vec3 N = gb.xyz;
    vec3 origin = P + N * (0.02 + 0.002 * dist);

    // ---------------- indirect: cosine-weighted diffuse bounces
    vec3 indirect = ambient;
    vec3 throughput = vec3(1.0);
    vec3 pos = origin;
    vec3 nrm = N;
    for (int b = 0; b < 3; b++) {
        if (b >= bounces) break;
        vec3 dir = cosineDirection(nrm);
        vec3 hc; vec3 hn; float t;
        float r = traceRay(pos, dir, 1.0e6, hc, hn, t);
        if (r < 0.5 || r > 1.5) {
            float vis = r < -0.5 ? 0.5 : escapeVisibility(pos + dir * t);
            indirect += throughput * skyRadiance(dir) * vis;
            break;
        }
        vec4 v = fetchVoxel(hc);
        // (no emission here: every block light is already sampled directly as a point light)
        throughput *= pow(v.rgb, vec3(2.2)) * 0.85;
        vec3 hp = pos + dir * t + hn * 0.01;
        vec3 hl = pointLights(hp, hn, true);
        float nd = dot(hn, lightDir);
        if (nd > 0.0) hl += lightColor * nd * sunVisibility(hp);
        indirect += throughput * hl;
        pos = hp;
        nrm = hn;
    }
    indirect = min(indirect, vec3(8.0));   // tame fireflies
    indirect += min(sane(remainingLights(P, N, origin)), vec3(24.0));
    indirect = sane(indirect);

    // ---------------- temporal accumulation (bilinear, geometry-validated reprojection)
    vec4 histI = vec4(0.0);
    float wsum = 0.0;
    if (hasHistory > 0.5) {
        vec4 pc = prevProj * (prevView * vec4(P - prevCamPos, 1.0));
        if (pc.w > 0.0) {
            vec2 puv = pc.xy / pc.w * 0.5 + 0.5;
            vec2 pp = puv * traceRes - 0.5;
            vec2 base = floor(pp);
            vec2 f = pp - base;
            float expected = length(P - prevEye);
            for (int k = 0; k < 4; k++) {
                vec2 o = vec2(mod(float(k), 2.0), floor(float(k) * 0.5));
                vec2 tc = (base + o + 0.5) / traceRes;
                if (tc.x < 0.0 || tc.y < 0.0 || tc.x > 1.0 || tc.y > 1.0) continue;
                vec4 g = texture2D(histGeom, tc);
                if (g.w <= 0.0) continue;
                if (abs(g.w - expected) > 0.05 + 0.02 * expected) continue;
                if (dot(g.xyz, N) < 0.8) continue;
                float bw = (o.x > 0.5 ? f.x : 1.0 - f.x) * (o.y > 0.5 ? f.y : 1.0 - f.y);
                histI += texture2D(histIndirect, tc) * bw;
                wsum += bw;
            }
        }
    }

    vec3 outI = indirect;
    float cI = 1.0;
    if (wsum > 0.05) {
        histI /= wsum;
        histI.rgb = sane(histI.rgb);
        cI = min(histI.a + 1.0, maxAccumIndirect);
        outI = mix(histI.rgb, indirect, 1.0 / cI);
    }
    gl_FragData[0] = vec4(outI, cI);
    gl_FragData[1] = vec4(N, dist);
}
