# Add project specific ProGuard rules here.

# Tink (transitively pulled in by androidx.security:security-crypto, and used
# directly for attachment Streaming AEAD as of Этап H.2) references
# com.google.errorprone annotations that are compile-time only - safe to
# silence rather than keep, standard fix for Tink + R8.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# libsignal's JNI layer calls back into Java by EXACT method/class signature
# (e.g. SessionCipher_DecryptPreKeySignalMessage -> getIdentityKeyPair() on the
# IdentityKeyStore implementation, passing IdentityKeyPair/SessionRecord/etc.
# across the JNI boundary). R8 obfuscation changes these descriptors, so every
# native callback fails with NoSuchMethodError in RELEASE builds only (the
# debug build is not minified, which is why emulator/dev tests never saw it).
# Keep the whole libsignal surface AND the app's key-store implementation
# unmangled so native lookups resolve.
-keep class org.signal.libsignal.** { *; }
-keep class com.messenger.app.data.signal.SignalKeyStore { *; }
