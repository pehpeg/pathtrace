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
uniform mat4 invProj;
uniform vec2 lowRes;
uniform float exposure;
uniform float autoExposure;
uniform float saturation;
uniform int debugView;

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 scene = texture2D(sceneTex, uv).rgb;
    float d = texture2D(depthTex, uv).r;
    if (d >= 1.0) {
        gl_FragColor = vec4(scene, 1.0);
        return;
    }
    vec4 v = invProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    float dist = length(v.xyz / v.w);

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
    vec3 dir = texture2D(directTex, uv).rgb;

    float e = exposure * (autoExposure > 0.5 ? texture2D(exposureTex, vec2(0.5)).r : 1.0);
    if (debugView == 1 || debugView == 2) {
        vec3 l = aces((debugView == 1 ? dir : ind) * e * 0.5);
        gl_FragColor = vec4(pow(l, vec3(1.0 / 2.2)), 1.0);
        return;
    }
    if (debugView == 3) {
        gl_FragColor = vec4(texture2D(geomTex, uv).xyz * 0.5 + 0.5, 1.0);
        return;
    }
    vec3 albedo = pow(scene, vec3(2.2));
    vec3 c = aces(albedo * (ind + dir) * e);
    c = pow(c, vec3(1.0 / 2.2));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, saturation);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
