#version 120
// PathTrace - per-pixel surface position and normal.
// Normals rebuilt from the depth buffer band and stripe wherever depth precision runs out,
// so the view ray is traced through the voxel grid instead: for full blocks that gives the
// exact face and distance. The depth buffer confirms the hit, and is the fallback for
// everything the grid doesn't contain (mobs, slabs, plants...).
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;

void main() {
    float d = texture2D(depthTex, uv).r;
    if (d >= 1.0) {
        gl_FragColor = vec4(0.0);
        return;
    }
    vec3 rel = relPosAt(uv);
    vec3 eye = eyeRel();
    vec3 dir = normalize(rel - eye);
    float depthDist = length(rel - eye);
    vec3 ro = camPos + eye;

    vec3 hc; vec3 hn; float t;
    float r = traceRay(ro, dir, depthDist + 2.0, hc, hn, t);
    // Only full blocks: a non-full block's 4x4x4 shape is coarser than its model, and trusting it
    // would put phantom surfaces next to thin things (fences, bars...) and on the ground under them.
    bool fullHit = false;
    if (r > 0.5 && r < 1.5) {
        vec3 uvw = (hc + 0.5) / volumeSize;
        fullHit = dot(texture3D(maskTexA, uvw) + texture3D(maskTexB, uvw), vec4(1.0)) <= 0.0;
    }
    if (fullHit && abs(t - depthDist) < 0.3 + 0.02 * depthDist) {
        gl_FragColor = vec4(hn, t);
    } else {
        gl_FragColor = vec4(surfaceNormal(uv, rel, dir), depthDist);
    }
}
