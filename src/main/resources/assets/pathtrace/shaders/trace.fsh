#version 120
// PathTrace - per-pixel lighting. Primary visibility comes from Minecraft's depth buffer;
// everything after that is ray-marched through the voxel volume.
// Outputs: 0 = indirect light (rgb, sample count), 1 = direct light (rgb, sample count),
//          2 = geometry (world normal, distance from eye) for reprojection and denoising.
#include "common.glsl"

varying vec2 uv;

uniform sampler2D depthTex;
uniform sampler2D histIndirect;
uniform sampler2D histDirect;
uniform sampler2D histGeom;

uniform mat4 invProj;
uniform mat4 invView;
uniform mat4 prevProj;
uniform mat4 prevView;
uniform vec3 prevCamPos;
uniform vec3 prevEye;

uniform float hasHistory;
uniform float maxAccumIndirect;
uniform float maxAccumDirect;
uniform int bounces;
uniform vec2 depthTexel;
uniform vec2 traceRes;

vec3 relPosAt(vec2 tc) {
    float d = texture2D(depthTex, tc).r;
    vec4 v = invProj * vec4(tc * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    v /= v.w;
    return (invView * vec4(v.xyz, 1.0)).xyz;
}

vec3 surfaceNormal(vec2 tc, vec3 rel, vec3 viewDir) {
    vec3 px = relPosAt(tc + vec2(depthTexel.x, 0.0)) - rel;
    vec3 nx = rel - relPosAt(tc - vec2(depthTexel.x, 0.0));
    vec3 py = relPosAt(tc + vec2(0.0, depthTexel.y)) - rel;
    vec3 ny = rel - relPosAt(tc - vec2(0.0, depthTexel.y));
    vec3 dx = dot(px, px) < dot(nx, nx) ? px : nx;   // the side on the same surface
    vec3 dy = dot(py, py) < dot(ny, ny) ? py : ny;
    vec3 n = cross(dx, dy);
    float l = length(n);
    n = l > 1e-12 ? n / l : -viewDir;
    if (dot(n, viewDir) > 0.0) n = -n;
    // Minecraft is mostly axis-aligned: snap near-axis normals for clean results
    vec3 a = abs(n);
    if (a.x > 0.85) n = vec3(sign(n.x), 0.0, 0.0);
    else if (a.y > 0.85) n = vec3(0.0, sign(n.y), 0.0);
    else if (a.z > 0.85) n = vec3(0.0, 0.0, sign(n.z));
    return n;
}

void main() {
    rngPix = gl_FragCoord.xy;
    float d = texture2D(depthTex, uv).r;
    if (d >= 1.0) {
        gl_FragData[0] = vec4(0.0);
        gl_FragData[1] = vec4(0.0);
        gl_FragData[2] = vec4(0.0);
        return;
    }

    vec3 rel = relPosAt(uv);
    vec3 eye = (invView * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    vec3 viewDir = normalize(rel - eye);
    float dist = length(rel - eye);
    vec3 P = rel + camPos;
    vec3 N = surfaceNormal(uv, rel, viewDir);
    vec3 origin = P + N * (0.02 + 0.002 * dist);

    // ---------------- direct: own glow, sun/moon, point lights
    vec3 direct = vec3(0.0);
    vec3 own = floor(P - N * 0.25);
    if (insideVolume(own)) {
        vec4 ov = fetchVoxel(own);
        if (isSolid(ov)) direct += emitColor(ov) * emission(ov) * emissiveStrength * 0.35;
    }
    float ndl = dot(N, lightDir);
    if (ndl > 0.0) direct += lightColor * ndl * sunVisibility(origin);
    direct += pointLights(origin, N, true);

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
        // emitters near the camera are already sampled as point lights
        if (length(hc + 0.5 - camPos) > neeRadius) {
            indirect += throughput * emitColor(v) * emission(v) * emissiveStrength;
        }
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
    direct = min(direct, vec3(32.0));

    // ---------------- temporal accumulation (bilinear, geometry-validated reprojection)
    vec4 histI = vec4(0.0);
    vec4 histD = vec4(0.0);
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
                histD += texture2D(histDirect, tc) * bw;
                wsum += bw;
            }
        }
    }

    vec3 outI = indirect;
    vec3 outD = direct;
    float cI = 1.0;
    float cD = 1.0;
    if (wsum > 0.05) {
        histI /= wsum;
        histD /= wsum;
        cI = min(histI.a + 1.0, maxAccumIndirect);
        cD = min(histD.a + 1.0, maxAccumDirect);
        outI = mix(histI.rgb, indirect, 1.0 / cI);
        outD = mix(histD.rgb, direct, 1.0 / cD);
    }
    gl_FragData[0] = vec4(outI, cI);
    gl_FragData[1] = vec4(outD, cD);
    gl_FragData[2] = vec4(N, dist);
}
