#ifndef PENTECOST_LIGHT
#define PENTECOST_LIGHT

// Lighting for every lit surface. Fragment shaders only.

uniform sampler2DShadow shadowtex1;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform vec3 shadowLightPosition;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform float rainStrength;
uniform vec3 fogColor;

// Pulls the middle of the shadow map closer, so shadows near the player are sharp.
vec2 shadowWarp(vec2 xy) {
    return xy / (length(xy) * 0.9 + 0.1);
}

// 1.0 in the light, 0.0 in shadow.
float shadowAt(vec3 feet, vec3 normalWorld, float facing) {
#ifdef SHADOWS
    vec3 p = feet + normalWorld * (0.06 + 0.10 * (1.0 - facing));
    vec4 sp = shadowProjection * (shadowModelView * vec4(p, 1.0));
    sp.xyz /= sp.w;
    sp.xy = shadowWarp(sp.xy);
    sp.xyz = sp.xyz * 0.5 + 0.5;
    sp.z -= 0.0007;
    if (sp.x < 0.0 || sp.x > 1.0 || sp.y < 0.0 || sp.y > 1.0 || sp.z > 1.0) {
        return 1.0;
    }
    float lit;
#ifdef SOFT_SHADOWS
    float o = 1.3 / 2048.0;
    lit = shadow2D(shadowtex1, sp.xyz).x * 0.4;
    lit += shadow2D(shadowtex1, sp.xyz + vec3( o, 0.0, 0.0)).x * 0.15;
    lit += shadow2D(shadowtex1, sp.xyz + vec3(-o, 0.0, 0.0)).x * 0.15;
    lit += shadow2D(shadowtex1, sp.xyz + vec3(0.0,  o, 0.0)).x * 0.15;
    lit += shadow2D(shadowtex1, sp.xyz + vec3(0.0, -o, 0.0)).x * 0.15;
#else
    lit = shadow2D(shadowtex1, sp.xyz).x;
#endif
    // Far away the map is coarse, so fade back to plain light.
    float faded = smoothstep(0.7, 1.0, length(feet.xz) / SHADOW_RANGE);
    return mix(lit, 1.0, faded);
#else
    return 1.0;
#endif
}

// Colors and strength for the time of day. daytime: 0 at night, 1 at noon.
void skyLight(out vec3 direct, out vec3 ambient, out float daytime) {
    float height = dot(normalize(sunPosition), normalize(upPosition));
    daytime = smoothstep(-0.12, 0.20, height);
    vec3 sun = mix(vec3(1.00, 0.50, 0.22), vec3(1.00, 0.93, 0.82), smoothstep(0.02, 0.45, height));
    vec3 moon = vec3(0.16, 0.21, 0.34);
    direct = mix(moon, sun * 1.25, daytime);
    vec3 dayAmbient = mix(vec3(0.46, 0.40, 0.44), vec3(0.40, 0.50, 0.68), smoothstep(0.0, 0.5, height));
    ambient = mix(vec3(0.040, 0.055, 0.105), dayAmbient, daytime);
    direct *= 1.0 - 0.85 * rainStrength;
    ambient = mix(ambient, vec3(dot(ambient, vec3(0.33))) * 0.9, 0.6 * rainStrength);
}

// Full lighting of one surface. albedo is linear. lm is the game's light levels: x block, y sky.
vec3 shade(vec3 albedo, vec2 lm, vec3 normalView, vec3 feet) {
    float skyRaw = clamp((lm.y - 0.03) / 0.94, 0.0, 1.0);
    float blockRaw = clamp((lm.x - 0.03) / 0.94, 0.0, 1.0);
    vec3 direct;
    vec3 ambient;
    float daytime;
    skyLight(direct, ambient, daytime);

    vec3 toLight = normalize(shadowLightPosition);
    float facing = max(dot(normalView, toLight), 0.0);
    vec3 normalWorld = mat3(gbufferModelViewInverse) * normalView;
    float lit = shadowAt(feet, normalWorld, facing);
    float exposed = smoothstep(0.45, 0.95, skyRaw);

    vec3 sunPart = direct * facing * lit * exposed;
    float hemi = 0.70 + 0.30 * normalWorld.y;
    vec3 skyPart = ambient * (skyRaw * skyRaw) * hemi;
    vec3 blockPart = vec3(1.00, 0.62, 0.30) * BLOCK_LIGHT * pow(blockRaw, 2.6) * 1.5;
    return albedo * (sunPart + skyPart + blockPart + vec3(0.025));
}

vec3 fogged(vec3 color, vec3 feet) {
    float f = clamp((length(feet) - gl_Fog.start) / (gl_Fog.end - gl_Fog.start), 0.0, 1.0);
    return mix(color, fogColor, f);
}

#endif
