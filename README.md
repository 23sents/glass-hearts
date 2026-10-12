# Glass Hearts

A wordless, full-screen Android animation in an iOS "liquid glass" style.

* Pre-rendered glass heart sprites (glow, refraction rim, caustic, specular highlights)
* Frame-driven scene (Choreographer), time-based, pooled particles — no allocations per frame
* Heartbeat rhythm ("lub-dub"), spring physics for the entrance and touch bounce
* Touch: bursts of hearts, glass ripples, a trail while dragging; haptic tick
* Accelerometer parallax for the background and the heart
* Shake the phone: every heart trembles, and on a vigorous shake they all fly apart sideways (new ones are thrown out of the big heart, which bounces)

* Microphone: blow on the phone and the hearts tremble and fly apart; a clap launches fireworks (nothing is recorded or sent anywhere)
* Draw a heart with a finger: a recogniser (chamfer distance + notch / tip checks) triggers a heart-shaped firework
* Sounds are synthesised in code (no audio files): heartbeat "lub-dub", glass chimes, rustle, fireworks
* Live wallpaper (`HeartWallpaperService`): the same engine, silent, 30 fps; opened from the small glass button in the app

Structure: `HeartEngine` (scene, input, layers) · `HeartScene` (app view) · `HeartWallpaperService` · `MicListener` · `SoundFx` · `HeartRecognizer` · `Fireworks` · `Background` · `Particles` · `GlassSprites` · `HeartShape` · `Motion` (Spring, Heartbeat).

Build: `gradle assembleDebug` (or push a `v*` tag to get `glass-hearts.apk` in Releases).
