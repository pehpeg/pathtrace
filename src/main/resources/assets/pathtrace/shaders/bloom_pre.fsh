#version 120
// PathTrace - bloom source: only pixels that are light sources (torch flames, glowstone, lava...),
// weighted towards their brightest texels so a torch's flame glows more than its stick.

varying vec2 uv;

uniform sampler2D sceneTex;
uniform sampler2D directTex;   // alpha = how much the pixel is a light source
uniform sampler2D depthTex;
uniform vec2 srcTexel;         // one full-resolution pixel

vec3 tap(vec2 tc) {
    if (texture2D(depthTex, tc).r >= 1.0) return vec3(0.0);
    float glow = texture2D(directTex, tc).a;
    if (glow <= 0.0) return vec3(0.0);
    vec3 albedo = pow(texture2D(sceneTex, tc).rgb, vec3(2.2));
    float l = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
    return albedo * glow * smoothstep(0.05, 0.5, l);
}

void main() {
    vec2 o = srcTexel * 0.5;
    vec3 c = tap(uv + vec2(-o.x, -o.y)) + tap(uv + vec2(o.x, -o.y)) + tap(uv + vec2(-o.x, o.y)) + tap(uv + vec2(o.x, o.y));
    gl_FragColor = vec4(c * 0.25, 1.0);
}
