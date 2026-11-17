#version 120
// PathTrace - bloom upsample (dual filter): 8-tap tent from the smaller level, plus this level.

varying vec2 uv;

uniform sampler2D srcTex;     // smaller, already upsampled level
uniform sampler2D addTex;     // this level's downsampled image
uniform vec2 srcTexel;

void main() {
    vec2 o = srcTexel;
    vec3 c = texture2D(srcTex, uv + vec2(-2.0 * o.x, 0.0)).rgb
           + texture2D(srcTex, uv + vec2(2.0 * o.x, 0.0)).rgb
           + texture2D(srcTex, uv + vec2(0.0, -2.0 * o.y)).rgb
           + texture2D(srcTex, uv + vec2(0.0, 2.0 * o.y)).rgb
           + (texture2D(srcTex, uv + vec2(-o.x, -o.y)).rgb
            + texture2D(srcTex, uv + vec2(o.x, -o.y)).rgb
            + texture2D(srcTex, uv + vec2(-o.x, o.y)).rgb
            + texture2D(srcTex, uv + vec2(o.x, o.y)).rgb) * 2.0;
    gl_FragColor = vec4(c / 12.0 + texture2D(addTex, uv).rgb, 1.0);
}
