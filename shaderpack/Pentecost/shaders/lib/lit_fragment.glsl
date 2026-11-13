// Fragment stage of every lit surface. The program file picks what it is with a define:
// WATER keeps the see-through color, ENTITY adds the red flash when a mob is hurt.
#include "/lib/settings.glsl"
#include "/lib/light.glsl"

uniform sampler2D texture;
uniform vec4 entityColor;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normalView;
varying vec3 feet;

void main() {
    vec4 base = texture2D(texture, texcoord) * glcolor;
#ifndef WATER
    if (base.a < 0.1) {
        discard;
    }
#endif
    vec3 albedo = pow(base.rgb, vec3(2.2));
    vec3 color = shade(albedo, lmcoord, normalize(normalView), feet);
    color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
#ifdef ENTITY
    color = mix(color, entityColor.rgb, entityColor.a);
#endif
    color = fogged(color, feet);
    gl_FragData[0] = vec4(color, base.a);
}
