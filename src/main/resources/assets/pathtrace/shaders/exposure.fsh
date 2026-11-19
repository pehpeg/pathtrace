#version 120
// PathTrace - eye adaptation. The exposure follows the light around the player (the eye probe:
// 64 directions all around, plus whether the sun reaches the eye), not what's on screen, so turning
// to look at a bright wall or into a shadow doesn't change it; walking into a cave or out into the
// sun does. A little of the on-screen average is mixed in for very dark or very bright views.

varying vec2 uv;

uniform sampler2D lumTex;
uniform sampler2D probeTex;
uniform sampler2D prevExposure;
uniform vec3 lightColor;
uniform float adapt;
uniform float hasHistory;

void main() {
    float sum = 0.0;
    for (int y = 0; y < 8; y++)
        for (int x = 0; x < 8; x++)
            sum += dot(texture2D(probeTex, (vec2(float(x), float(y)) + 0.5) / vec2(8.0, 9.0)).rgb, vec3(0.2126, 0.7152, 0.0722));
    float sunVis = texture2D(probeTex, vec2(0.5, 8.5) / vec2(8.0, 9.0)).r;
    float env = sum / 64.0 + sunVis * dot(lightColor, vec3(0.2126, 0.7152, 0.0722)) * 0.3;
    float screen = exp(texture2D(lumTex, vec2(0.5), 20.0).r);    // big bias = smallest mip = average
    float avg = exp(mix(log(max(env * 0.35, 1e-4)), log(max(screen, 1e-4)), 0.15));
    float target = clamp(pow(0.10 / max(avg, 1e-4), 0.6), 0.4, 2.0);
    float prev = texture2D(prevExposure, vec2(0.5)).r;
    float e = (hasHistory > 0.5 && prev > 0.0) ? mix(prev, target, adapt) : target;
    gl_FragColor = vec4(e, 0.0, 0.0, 1.0);
}
