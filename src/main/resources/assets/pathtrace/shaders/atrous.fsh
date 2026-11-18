#version 120
// PathTrace - one iteration of an edge-aware a-trous wavelet filter.
// Run with stepSize 1, 2, 4, 8 on the bounce light: a wide blur that stops at depth edges,
// normal edges and (while a pixel still has few samples, loosely) at brightness edges.

varying vec2 uv;

uniform sampler2D inIndirect;
uniform sampler2D geomTex;
uniform vec2 texel;
uniform float stepSize;

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float kernel(int i) {
    if (i == 0) return 0.375;
    if (i == 1 || i == -1) return 0.25;
    return 0.0625;
}

void main() {
    vec4 g = texture2D(geomTex, uv);
    vec4 ci = texture2D(inIndirect, uv);
    if (g.w <= 0.0) {
        gl_FragColor = ci;
        return;
    }
    float li = lum(ci.rgb);
    float sigI = clamp(6.0 / sqrt(max(ci.a, 1.0)), 0.5, 6.0);
    float depthTol = 0.01 * g.w * stepSize + 0.05;

    vec3 sumI = vec3(0.0);
    float wI = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 tc = uv + vec2(float(i), float(j)) * texel * stepSize;
            vec4 gq = texture2D(geomTex, tc);
            if (gq.w <= 0.0) continue;
            float wg = kernel(i) * kernel(j)
                     * exp(-abs(gq.w - g.w) / depthTol)
                     * pow(max(dot(gq.xyz, g.xyz), 0.0), 64.0);
            vec3 qi = texture2D(inIndirect, tc).rgb;
            if (!(abs(qi.r) < 1.0e6 && abs(qi.g) < 1.0e6 && abs(qi.b) < 1.0e6)) continue;
            float w1 = wg * exp(-abs(lum(qi) - li) / (sigI * (li + 0.05)));
            sumI += qi * w1;
            wI += w1;
        }
    }
    gl_FragColor = vec4(wI > 0.0 ? sumI / wI : ci.rgb, ci.a);
}
