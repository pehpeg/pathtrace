#version 120
// PathTrace - log luminance of the lit image at low resolution (mipmapped to an average).

varying vec2 uv;

uniform sampler2D sceneTex;
uniform sampler2D indirectTex;
uniform sampler2D directTex;
uniform sampler2D depthTex;

void main() {
    // Exposure follows how much LIGHT there is, not how bright the surfaces are: a black sheep or a
    // white wall filling the screen must not change it. Surfaces count as a typical 30% grey.
    vec3 c = pow(texture2D(sceneTex, uv).rgb, vec3(2.2));   // sky: as drawn
    if (texture2D(depthTex, uv).r < 1.0) {
        c = 0.3 * (texture2D(indirectTex, uv).rgb + texture2D(directTex, uv).rgb);
    }
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    gl_FragColor = vec4(log(l + 1e-3), 0.0, 0.0, 1.0);
}
