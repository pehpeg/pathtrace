#version 120
// PathTrace - upsample the bounce light edge-aware, add the full-res direct light, apply both to the
// (fullbright) frame Minecraft drew, expose and tonemap.

varying vec2 uv;

uniform sampler2D sceneTex;
uniform sampler2D depthTex;
uniform sampler2D indirectTex;
uniform sampler2D directTex;
uniform sampler2D geomTex;
uniform sampler2D exposureTex;
uniform sampler2D gbufferTex;
uniform mat4 invProj;
uniform vec2 lowRes;
uniform float exposure;
uniform float autoExposure;
uniform float saturation;
uniform int debugView;
uniform sampler2D bloomTex;
uniform float bloomStrength;
uniform sampler2D waterTex;    // r = blocks of water between the surface and what's lit (0 = none)
uniform vec3 skyColor;
uniform vec3 lightColor;
uniform vec2 directRes;        // resolution the direct light was computed at
uniform vec2 fullRes;

// Direct light computed below full resolution: joint bilateral upsample (bilinear weights, but only
// from texels on the same surface: same distance and facing).
vec3 upsampleDirect(vec2 uv, float dist, vec3 n) {
    if (directRes.x >= fullRes.x - 0.5) return texture2D(directTex, uv).rgb;
    vec2 pp = uv * directRes - 0.5;
    vec2 base = floor(pp);
    vec2 f = pp - base;
    vec3 sum = vec3(0.0);
    float ws = 0.0;
    vec3 nearest = vec3(0.0);
    float best = 1.0e9;
    for (int k = 0; k < 4; k++) {
        vec2 o = vec2(mod(float(k), 2.0), floor(float(k) * 0.5));
        vec2 tc = clamp((base + o + 0.5) / directRes, vec2(0.0), vec2(1.0));
        vec4 g = texture2D(gbufferTex, tc);       // what the direct pass saw at that texel
        vec3 c = texture2D(directTex, tc).rgb;
        if (g.w <= 0.0) continue;
        float dd = abs(g.w - dist) / (0.01 * dist + 0.02);
        float bw = (o.x > 0.5 ? f.x : 1.0 - f.x) * (o.y > 0.5 ? f.y : 1.0 - f.y);
        float w = bw * exp(-dd) * pow(max(dot(g.xyz, n), 0.0), 16.0);
        sum += c * w;
        ws += w;
        float score = dd + (1.0 - dot(g.xyz, n)) * 8.0;
        if (score < best) { best = score; nearest = c; }
    }
    return ws > 1e-4 ? sum / ws : nearest;
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 scene = texture2D(sceneTex, uv).rgb;
    float d = texture2D(depthTex, uv).r;
    if (debugView == 6) {   // red: lit as a surface, green: depth but passed through (clouds), blue: sky
        float gw = texture2D(gbufferTex, uv).w;
        gl_FragColor = vec4(d < 1.0 && gw > 0.0 ? 1.0 : 0.0, d < 1.0 && gw <= 0.0 ? 1.0 : 0.0, d >= 1.0 ? 1.0 : 0.0, 1.0);
        return;
    }
    if (debugView == 7) { gl_FragColor = vec4(scene, 1.0); return; }
    if (d >= 1.0) {
        vec3 b = texture2D(bloomTex, uv).rgb * bloomStrength;
        float eb = exposure * (autoExposure > 0.5 ? texture2D(exposureTex, vec2(0.5)).r : 1.0);
        gl_FragColor = vec4(scene + pow(aces(b * eb), vec3(1.0 / 2.2)), 1.0);
        return;
    }
    vec4 gbHere = texture2D(gbufferTex, uv);
    float dist = gbHere.w;
    if (dist <= 0.0) {   // not a lit surface (clouds): pass through like the sky
        vec3 b = texture2D(bloomTex, uv).rgb * bloomStrength;
        float eb = exposure * (autoExposure > 0.5 ? texture2D(exposureTex, vec2(0.5)).r : 1.0);
        gl_FragColor = vec4(scene + pow(aces(b * eb), vec3(1.0 / 2.2)), 1.0);
        return;
    }

    // joint bilateral upsample: bilinear weights, rejecting low-res texels at another depth
    vec2 pp = uv * lowRes - 0.5;
    vec2 base = floor(pp);
    vec2 f = pp - base;
    vec3 ind = vec3(0.0);
    float ws = 0.0;
    for (int k = 0; k < 4; k++) {
        vec2 o = vec2(mod(float(k), 2.0), floor(float(k) * 0.5));
        vec2 tc = clamp((base + o + 0.5) / lowRes, vec2(0.0), vec2(1.0));
        vec4 g = texture2D(geomTex, tc);
        if (g.w <= 0.0) continue;
        float bw = (o.x > 0.5 ? f.x : 1.0 - f.x) * (o.y > 0.5 ? f.y : 1.0 - f.y);
        float w = bw * exp(-abs(g.w - dist) / (0.02 * dist + 0.03)) + 1e-5;
        ind += texture2D(indirectTex, tc).rgb * w;
        ws += w;
    }
    if (ws > 0.0) {
        ind /= ws;
    } else {
        ind = texture2D(indirectTex, uv).rgb;
    }
    vec3 dir = upsampleDirect(uv, dist, gbHere.xyz);

    float e = exposure * (autoExposure > 0.5 ? texture2D(exposureTex, vec2(0.5)).r : 1.0);
    if (debugView == 1 || debugView == 2) {
        vec3 l = aces((debugView == 1 ? dir : ind) * e * 0.5);
        gl_FragColor = vec4(pow(l, vec3(1.0 / 2.2)), 1.0);
        return;
    }
    if (debugView == 4 || debugView == 5) {
        gl_FragColor = vec4(dir, 1.0);
        return;
    }
    if (debugView == 3) {
        gl_FragColor = vec4(texture2D(geomTex, uv).xyz * 0.5 + 0.5, 1.0);
        return;
    }
    vec3 albedo = pow(scene, vec3(2.2));
    // seen through water: the bottom's light fades with the water in between, replaced by light
    // scattered in the water itself (so deep water is a dim, even blue rather than a lit sea bed)
    float wpath = texture2D(waterTex, uv).r;
    if (wpath > 0.0) {
        vec3 T = pow(vec3(0.80, 0.90, 0.96), vec3(wpath));
        vec3 scatter = (skyColor * 0.9 + lightColor * 0.25) * vec3(0.35, 0.6, 0.85);
        ind = ind * T + scatter * (1.0 - T);
        dir = dir * T;
    }
    vec3 c = aces((albedo * (ind + dir) + texture2D(bloomTex, uv).rgb * bloomStrength) * e);
    c = pow(c, vec3(1.0 / 2.2));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, saturation);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
