#ifndef PENTECOST_SETTINGS
#define PENTECOST_SETTINGS

// Options. Oculus shows these in the pack's settings.
#define SHADOWS // Sun and moon shadows.
#define SOFT_SHADOWS // Softer shadow edges. Costs a little speed.
#define SATURATION 1.10 // [0.70 0.85 1.00 1.10 1.20 1.35]
#define BLOCK_LIGHT 1.0 // [0.6 0.8 1.0 1.2 1.5]

// Not options. Shadow code and the shadow pass must agree on this.
#define SHADOW_RANGE 128.0

#endif
