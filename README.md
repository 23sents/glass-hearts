# Glass Hearts

A wordless, full-screen Android animation in an iOS "liquid glass" style.

* Pre-rendered glass heart sprites (glow, refraction rim, caustic, specular highlights)
* Frame-driven scene (Choreographer), time-based, pooled particles — no allocations per frame
* Heartbeat rhythm ("lub-dub"), spring physics for the entrance and touch bounce
* Touch: bursts of hearts, glass ripples, a trail while dragging; haptic tick
* Accelerometer parallax for the background and the heart

Structure: `HeartScene` (loop, layers, input) · `Background` · `Particles` · `GlassSprites` · `HeartShape` · `Motion` (Spring, Heartbeat).

Build: `gradle assembleDebug` (or push a `v*` tag to get `glass-hearts.apk` in Releases).
