#version 120
// PathTrace - one iteration of an edge-aware a-trous wavelet filter.
// Run with stepSize 1, 2, 4, 8: a wide blur that stops at depth edges, normal edges
// and (while a pixel still has few samples, loosely) at brightness edges.

varying vec2 uv;

uniform sampler2D inIndirect;
uniform sampler2D inDirect;
uniform sampler2D geomTex;
uniform vec2 texel;
uniform float stepSize;
uniform float filterDirect;

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
    vec4 cd = texture2D(inDirect, uv);
    if (g.w <= 0.0) {
        gl_FragData[0] = ci;
        gl_FragData[1] = cd;
        return;
    }
    float li = lum(ci.rgb);
    float ld = lum(cd.rgb);
    float sigI = clamp(6.0 / sqrt(max(ci.a, 1.0)), 0.5, 6.0);
    float sigD = clamp(2.0 / sqrt(max(cd.a, 1.0)), 0.3, 2.0);
    float depthTol = 0.01 * g.w * stepSize + 0.05;

    vec3 sumI = vec3(0.0);
    vec3 sumD = vec3(0.0);
    float wI = 0.0;
    float wD = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 tc = uv + vec2(float(i), float(j)) * texel * stepSize;
            vec4 gq = texture2D(geomTex, tc);
            if (gq.w <= 0.0) continue;
            float wg = kernel(i) * kernel(j)
                     * exp(-abs(gq.w - g.w) / depthTol)
                     * pow(max(dot(gq.xyz, g.xyz), 0.0), 64.0);
            vec3 qi = texture2D(inIndirect, tc).rgb;
            float w1 = wg * exp(-abs(lum(qi) - li) / (sigI * (li + 0.05)));
            sumI += qi * w1;
            wI += w1;
            if (filterDirect > 0.5) {
                vec3 qd = texture2D(inDirect, tc).rgb;
                float w2 = wg * exp(-abs(lum(qd) - ld) / (sigD * (ld + 0.05)));
                sumD += qd * w2;
                wD += w2;
            }
        }
    }
    gl_FragData[0] = vec4(wI > 0.0 ? sumI / wI : ci.rgb, ci.a);
    gl_FragData[1] = (filterDirect > 0.5 && wD > 0.0) ? vec4(sumD / wD, cd.a) : cd;
}
