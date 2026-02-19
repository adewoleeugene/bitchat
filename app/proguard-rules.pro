# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.bitchat.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.nostr.** { *; }
-keep class com.bitchat.android.identity.** { *; }

# Arti (Tor) ProGuard rules
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.jni.** { *; }
-keepnames class org.torproject.jni.**
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.jni.**

# Room database entities
-keep class com.bitchat.android.data.local.entities.** { *; }
-keep class com.bitchat.android.data.local.** { *; }

# Solana module
-keep class com.bitchat.android.solana.** { *; }

# EdDSA crypto
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# BIP39 mnemonic (cash.z.ecc)
-keep class cash.z.ecc.android.bip39.** { *; }
-dontwarn cash.z.ecc.android.bip39.**

# QR Code
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
