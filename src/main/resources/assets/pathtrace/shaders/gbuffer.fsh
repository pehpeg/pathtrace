#version 120
// PathTrace - per-pixel surface position and normal.
// Normals rebuilt from the depth buffer band and stripe wherever depth precision runs out,
// so the view ray is traced through the voxel grid instead: for full blocks that gives the
// exact face and distance. The depth buffer confirms the hit, and is the fallback for
// everything the grid doesn't contain (mobs, slabs, plants...).
#include "common.glsl"
#include "geometry.glsl"

varying vec2 uv;
uniform float cloudY;   // volume space, -1e9 when clouds are off

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

    // Everything between the eye and the depth-buffer surface is known to be empty, so start the
    // ray just before that surface instead of marching all the way from the eye.
    float t0 = max(depthDist - 1.5, 0.0);
    vec3 hc; vec3 hn; float t;
    float r = traceRay(ro + dir * t0, dir, 3.5, hc, hn, t);
    t += t0;
    // Only full blocks: a non-full block's shape is only approximately its model, and trusting it
    // would put phantom surfaces next to thin things (fences, bars...) and on the ground under them.
    bool fullHit = false;
    if (r > 0.5 && r < 1.5) {
        fullHit = shapeIdAt(hc) < 0.5;
    }
    if (fullHit && abs(t - depthDist) < 0.3 + 0.02 * depthDist) {
        gl_FragColor = vec4(hn, t);
    } else {
        // clouds aren't in the voxel grid and must not be lit as terrain (they'd turn black): w = 0
        vec3 P = ro + dir * depthDist;
        float tol = 1.0 + 0.02 * depthDist;   // far away the depth buffer is coarse
        if (P.y > cloudY - tol && P.y < cloudY + 4.0 + tol) {
            vec3 cc = floor(P);
            if (!insideVolume(cc) || !isSolid(fetchVoxel(cc))) {
                gl_FragColor = vec4(0.0);
                return;
            }
        }
        gl_FragColor = vec4(surfaceNormal(uv, rel, dir), depthDist);
    }
}
