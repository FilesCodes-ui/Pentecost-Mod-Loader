#version 120
#include "/lib/settings.glsl"

const int shadowMapResolution = 2048;
const float shadowDistance = 128.0;
const float sunPathRotation = -30.0;

uniform sampler2D tex;
varying vec2 texcoord;
varying vec4 glcolor;

void main() {
    vec4 c = texture2D(tex, texcoord) * glcolor;
    if (c.a < 0.1) {
        discard;
    }
    gl_FragData[0] = c;
}