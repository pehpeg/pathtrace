#version 120
// PathTrace - bloom downsample (dual filter): centre + 4 diagonal taps of the larger level.

varying vec2 uv;

uniform sampler2D srcTex;
uniform vec2 srcTexel;

void main() {
    vec2 o = srcTexel;
    vec3 c = texture2D(srcTex, uv).rgb * 4.0
           + texture2D(srcTex, uv + vec2(-o.x, -o.y)).rgb
           + texture2D(srcTex, uv + vec2(o.x, -o.y)).rgb
           + texture2D(srcTex, uv + vec2(-o.x, o.y)).rgb
           + texture2D(srcTex, uv + vec2(o.x, o.y)).rgb;
    gl_FragColor = vec4(c / 8.0, 1.0);
}
