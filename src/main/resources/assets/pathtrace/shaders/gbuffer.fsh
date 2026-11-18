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
uniform float projNear, projFar;   // of the terrain projection; clouds are drawn with far * 2*sqrt(2)

// Clouds are drawn with their own projection (4x render distance instead of sqrt(2)x), so their depth
// means a different distance. Distance along this pixel's ray if the depth value belongs to a cloud:
float cloudDistance(float d, float depthDist) {
    float n = projNear, f = projFar, fc = projFar * 2.828427;
    float ndc = d * 2.0 - 1.0;
    float zt = 2.0 * f * n / ((f + n) - ndc * (f - n));
    float zc = 2.0 * fc * n / ((fc + n) - ndc * (fc - n));
    return depthDist * zc / zt;
}

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
        // clouds aren't in the voxel grid and must not be lit as terrain (they'd turn black): w = 0.
        // A pixel is a cloud if, read with the cloud projection, it sits in the cloud layer, and read
        // as terrain there is no block behind it.
        float dc = cloudDistance(d, depthDist);
        vec3 Pc = ro + dir * dc;
        float tol = 0.5 + 0.01 * dc;
        if (Pc.y > cloudY - tol && Pc.y < cloudY + 4.0 + tol) {
            vec3 cc = floor(ro + dir * (depthDist + 0.05));
            bool solid = insideVolume(cc) ? isSolid(fetchVoxel(cc))
                       : (lodEnabled > 0.5 && lodInside(cc - lodOrigin) && lodSolid(cc - lodOrigin));
            if (!solid) {
                gl_FragColor = vec4(0.0);
                return;
            }
        }
        gl_FragColor = vec4(surfaceNormal(uv, rel, dir), depthDist);
    }
}
