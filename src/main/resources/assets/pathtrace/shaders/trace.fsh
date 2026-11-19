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
uniform float checkerboard;

uniform sampler2D directTopTex;   // from direct.fsh: the lights it shadowed exactly at this pixel
uniform sampler2D directAuxTex;   // r = crowding factor

// Every point light except the ones direct.fsh shadowed exactly: two of them, picked at random,
// get a shadow ray and stand in for all of them (accumulated over frames and denoised), plus the
// smooth residual of lights that didn't fit in the light list.
vec3 remainingLights(vec3 P, vec3 N, vec3 origin) {
    vec4 top = texture2D(directTopTex, uv);
    float scale = texture2D(directAuxTex, uv).r;
    if (scale <= 0.0) return vec3(0.0);
    vec3 resid;
    vec3 s = sampleLights(P, N, origin, top, 2, resid);
    return (s + resid) * scale;
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

    // Checkerboard: each frame only half the pixels trace new samples; the others keep their
    // reprojected history (when it is valid), so the tracer costs about half.
    if (checkerboard > 0.5 && wsum > 0.05 && mod(floor(gl_FragCoord.x) + floor(gl_FragCoord.y) + frame, 2.0) > 0.5) {
        vec4 h = histI / wsum;
        gl_FragData[0] = vec4(sane(h.rgb), h.a);
        gl_FragData[1] = vec4(N, dist);
        return;
    }

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
        vec3 hres;
        vec3 hl = sampleLights(hp, hn, hp, vec4(-9.0), 1, hres) + hres;
        float nd = dot(hn, lightDir);
        if (nd > 0.0 && dot(lightColor, vec3(0.33)) > 0.03) hl += lightColor * nd * sunVisibility(hp);   // (skip faint moonlight)
        indirect += throughput * hl;
        pos = hp;
        nrm = hn;
    }
    indirect = min(indirect, vec3(8.0));   // tame fireflies
    indirect += min(sane(remainingLights(P, N, origin)), vec3(24.0));
    indirect = sane(indirect);

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
