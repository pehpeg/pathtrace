#version 120
// PathTrace - light probe at the eye: 64 directions (8x8 texels) of incoming light,
// plus sun visibility in the last row. Used to light the first-person hand.
#include "common.glsl"

uniform sampler2D prevProbe;
uniform vec3 eyePos;
uniform float hasHistory;

void main() {
    rngPix = gl_FragCoord.xy * 11.0 + vec2(3.0, 7.0);
    vec2 cellc = floor(gl_FragCoord.xy);
    vec4 prev = texture2D(prevProbe, gl_FragCoord.xy / vec2(8.0, 9.0));
    vec3 result = vec3(0.0);

    if (cellc.y > 7.5) {
        if (cellc.x < 0.5) {
            float vis = 0.0;
            for (int k = 0; k < 4; k++) vis += sunVisibility(eyePos);
            result = vec3(vis * 0.25);
        }
    } else {
        float idx = cellc.y * 8.0 + cellc.x;
        vec3 dir = normalize(probeDir(idx) + (vec3(rand(), rand(), rand()) - 0.5) * 0.3);
        vec3 hc; vec3 hn; float t;
        float r = traceRay(eyePos, dir, 1.0e6, hc, hn, t);
        if (r < 0.5 || r > 1.5) {
            float vis = r < -0.5 ? 0.5 : escapeVisibility(eyePos + dir * t);
            result = skyRadiance(dir) * vis;
        } else {
            vec4 v = fetchVoxel(hc);
            vec3 hp = eyePos + dir * t + hn * 0.01;
            vec3 l = ambient + skyColor * 0.15 + pointLights(hp, hn, true);
            float nd = dot(hn, lightDir);
            if (nd > 0.0) l += lightColor * nd * sunVisibility(hp);
            result = pow(v.rgb, vec3(2.2)) * 0.85 * l;
            if (length(hc + 0.5 - camPos) > neeRadius) result += emitColor(v) * emission(v) * emissiveStrength;
        }
    }
    if (hasHistory > 0.5) result = mix(prev.rgb, result, 0.12);
    gl_FragColor = vec4(result, 1.0);
}
