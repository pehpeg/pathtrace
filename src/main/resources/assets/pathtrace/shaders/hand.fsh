#version 120
// PathTrace - relight the first-person hand / held item (drawn fullbright) using the eye
// probe for bounce + sky light, sun visibility at the eye, and the point lights.
#include "common.glsl"

varying vec2 uv;

uniform sampler2D handColor;
uniform sampler2D handDepth;
uniform sampler2D probeTex;
uniform sampler2D exposureTex;
uniform mat4 handInvProj;
uniform mat4 invView;
uniform vec3 eyePos;
uniform vec2 texel;
uniform float exposure;
uniform float autoExposure;
uniform float saturation;

vec3 viewPos(vec2 tc) {
    float d = texture2D(handDepth, tc).r;
    vec4 v = handInvProj * vec4(tc * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

vec3 pickSide(vec3 c, vec2 a, vec2 b) {
    float da = texture2D(handDepth, a).r;
    float db = texture2D(handDepth, b).r;
    vec3 pa = da < 1.0 ? viewPos(a) - c : vec3(1e3);
    vec3 pb = db < 1.0 ? c - viewPos(b) : vec3(1e3);
    return dot(pa, pa) < dot(pb, pb) ? pa : pb;
}

void main() {
    float d = texture2D(handDepth, uv).r;
    if (d >= 1.0) discard;
    rngPix = gl_FragCoord.xy;
    lightMinDist = 1.5;   // a held torch is right next to the hand: don't let it blow out

    vec3 vp = viewPos(uv);
    vec3 dx = pickSide(vp, uv + vec2(texel.x, 0.0), uv - vec2(texel.x, 0.0));
    vec3 dy = pickSide(vp, uv + vec2(0.0, texel.y), uv - vec2(0.0, texel.y));
    vec3 nV = cross(dx, dy);
    nV = length(nV) > 1e-9 ? normalize(nV) : -normalize(vp);
    if (dot(nV, vp) > 0.0) nV = -nV;

    mat3 R = mat3(invView);
    vec3 n = normalize(R * nV);
    vec3 P = eyePos + R * vp;

    // diffuse irradiance from the probe's 64 directions
    vec3 E = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        float fi = float(i);
        vec2 tc = (vec2(mod(fi, 8.0), floor(fi / 8.0)) + 0.5) / vec2(8.0, 9.0);
        E += texture2D(probeTex, tc).rgb * max(dot(n, probeDir(fi)), 0.0);
    }
    E *= 4.0 / 64.0;

    float sunVis = texture2D(probeTex, vec2(0.5 / 8.0, 8.5 / 9.0)).r;
    vec3 light = E + ambient + lightColor * max(dot(n, lightDir), 0.0) * sunVis + pointLights(P, n, false);
    light = min(light, vec3(3.0));

    float e = exposure * (autoExposure > 0.5 ? texture2D(exposureTex, vec2(0.5)).r : 1.0);
    vec3 albedo = pow(texture2D(handColor, uv).rgb, vec3(2.2));
    vec3 c = pow(acesTonemap(albedo * light * e), vec3(1.0 / 2.2));
    c = mix(vec3(luminance(c)), c, saturation);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
