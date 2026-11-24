# Dependencies ship their own consumer rules (OkHttp, WorkManager, Compose,
# security-crypto/Tink, kotlinx-coroutines). Keep missing-class checks enabled.
# App-specific keeps below are added only against observed R8 output; the
# release-smoke CI job boots the minified APK on an emulator and exercises the
# credential store, share intake and worker scheduling to catch boundary breaks.

# R8 full mode repackages Kotlin metadata-driven lookups; keep the app's own
# reflectively-named entry points declared in the manifest safe by default
# (activities/receivers/providers are kept automatically by aapt rules).
