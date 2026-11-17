#version 120
// PathTrace - eye adaptation: exposure from the average log luminance, eased over time.

varying vec2 uv;

uniform sampler2D lumTex;
uniform sampler2D prevExposure;
uniform float adapt;
uniform float hasHistory;

void main() {
    float avgLog = texture2D(lumTex, vec2(0.5), 20.0).r;   // big bias = smallest mip = average
    float avg = exp(avgLog);
    float target = clamp(pow(0.18 / max(avg, 1e-4), 0.6), 0.5, 2.5);
    float prev = texture2D(prevExposure, vec2(0.5)).r;
    float e = (hasHistory > 0.5 && prev > 0.0) ? mix(prev, target, adapt) : target;
    gl_FragColor = vec4(e, 0.0, 0.0, 1.0);
}
