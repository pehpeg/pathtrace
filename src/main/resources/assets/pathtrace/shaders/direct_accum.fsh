#version 120
// PathTrace - temporal smoothing of the direct light (sun and point-light shadows).
// The direct pass samples a slightly different point inside each pixel every frame; blending with
// the reprojected result of earlier frames turns that into smooth, anti-aliased shadow edges that
// don't crawl or flicker when the camera moves, and new shadows (chunks loading in) fade in over a
// few frames instead of popping. History is clamped to the current neighbourhood (no ghost trails)
// and dropped where the surface behind the pixel changed.
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;

uniform sampler2D curDirect;
uniform sampler2D histDirect;
uniform sampler2D histGeom;    // previous frame's surface (normal, distance) from the trace pass
uniform mat4 prevProj;
uniform mat4 prevView;
uniform vec3 prevCamPos;
uniform vec3 prevEye;
uniform float hasHistory;
uniform vec2 directRes;
uniform float blend;           // weight of the new frame

void main() {
    vec4 cur = texture2D(curDirect, uv);
    vec4 gb = texture2D(gbufferTex, uv);
    if (gb.w <= 0.0 || hasHistory < 0.5) {
        gl_FragColor = cur;
        return;
    }
    vec3 mn = cur.rgb, mx = cur.rgb;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++) {
            vec3 c = texture2D(curDirect, uv + vec2(float(i), float(j)) / directRes).rgb;
            mn = min(mn, c);
            mx = max(mx, c);
        }
    vec3 P = eyeRel() + viewRayDir(uv) * gb.w + camPos;
    vec4 pc = prevProj * (prevView * vec4(P - prevCamPos, 1.0));
    if (pc.w <= 0.0) { gl_FragColor = cur; return; }
    vec2 puv = pc.xy / pc.w * 0.5 + 0.5;
    if (puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0) { gl_FragColor = cur; return; }
    vec4 g = texture2D(histGeom, puv);
    float expected = length(P - prevEye);
    if (g.w <= 0.0 || abs(g.w - expected) > 0.05 + 0.03 * expected || dot(g.xyz, gb.xyz) < 0.8) {
        gl_FragColor = cur;
        return;
    }
    vec3 h = clamp(texture2D(histDirect, puv).rgb, mn, mx);
    gl_FragColor = vec4(mix(h, cur.rgb, blend), cur.a);
}
