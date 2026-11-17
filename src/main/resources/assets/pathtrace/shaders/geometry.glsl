// PathTrace: rebuild surface position and normal from Minecraft's depth buffer.
// Positions are relative to the camera entity (add camPos for volume space).

uniform sampler2D depthTex;
uniform mat4 invProj;
uniform mat4 invView;
uniform vec2 depthTexel;

vec3 relPosAt(vec2 tc) {
    float d = texture2D(depthTex, tc).r;
    vec4 v = invProj * vec4(tc * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    v /= v.w;
    return (invView * vec4(v.xyz, 1.0)).xyz;
}

vec3 surfaceNormal(vec2 tc, vec3 rel, vec3 viewDir) {
    vec3 px = relPosAt(tc + vec2(depthTexel.x, 0.0)) - rel;
    vec3 nx = rel - relPosAt(tc - vec2(depthTexel.x, 0.0));
    vec3 py = relPosAt(tc + vec2(0.0, depthTexel.y)) - rel;
    vec3 ny = rel - relPosAt(tc - vec2(0.0, depthTexel.y));
    vec3 dx = dot(px, px) < dot(nx, nx) ? px : nx;   // the side on the same surface
    vec3 dy = dot(py, py) < dot(ny, ny) ? py : ny;
    vec3 n = cross(dx, dy);
    float l = length(n);
    n = l > 1e-12 ? n / l : -viewDir;
    if (dot(n, viewDir) > 0.0) n = -n;
    // Minecraft is mostly axis-aligned: snap near-axis normals for clean results
    vec3 a = abs(n);
    if (a.x > 0.85) n = vec3(sign(n.x), 0.0, 0.0);
    else if (a.y > 0.85) n = vec3(0.0, sign(n.y), 0.0);
    else if (a.z > 0.85) n = vec3(0.0, 0.0, sign(n.z));
    return n;
}

// Snap a point on an axis-aligned face to the centre of its block-texture pixel (16 per block),
// so shading is computed once per texture pixel: stable, pixel-art shadow edges.
vec3 snapToTexel(vec3 p, vec3 n) {
    vec3 a = abs(n);
    if (max(a.x, max(a.y, a.z)) < 0.999) return p;   // entities, slopes: leave as is
    vec3 s = (floor(p * 16.0) + 0.5) / 16.0;
    return a.x > 0.5 ? vec3(p.x, s.y, s.z) : (a.y > 0.5 ? vec3(s.x, p.y, s.z) : vec3(s.x, s.y, p.z));
}
