#version 120
#include "/lib/settings.glsl"

const int shadowMapResolution = 2048;
const float shadowDistance = 128.0;
const float sunPathRotation = -30.0;

uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
    float grey = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(grey), c, SATURATION);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.18);
    gl_FragData[0] = vec4(c, 1.0);
}