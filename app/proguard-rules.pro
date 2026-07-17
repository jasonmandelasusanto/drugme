# R8 rules for release builds.
#
# Everything here guards against failures that CANNOT happen in debug: R8 only runs on
# release, so a missing rule surfaces for the first time in the APK you hand to users.
# For this app that specifically means crypto silently failing and locking someone out of
# their medication history.

# --- BouncyCastle (Argon2id) -------------------------------------------------
# Only Argon2 is used, and the rest of the provider is dead weight; but BC resolves
# algorithm implementations reflectively by name, so anything reachable that way must
# survive renaming or it fails at runtime with a confusing NoSuchAlgorithmException.
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }
-dontwarn org.bouncycastle.**
# BC ships JCE providers referencing javax classes absent on Android.
-dontwarn javax.naming.**

# --- Tink --------------------------------------------------------------------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
# Tink pulls in protobuf, which reflects over generated message classes.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# --- kotlinx.serialization ---------------------------------------------------
# Serializers are generated companions looked up by name. If R8 renames them, every
# @Serializable payload fails to encode — which for the sync layer means the user's data
# silently stops leaving the device.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.drugme.app.**$$serializer { *; }
-keepclassmembers class com.drugme.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.drugme.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room --------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Firebase / Play services ------------------------------------------------
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Credential Manager / Google ID
-keep class com.google.android.libraries.identity.googleid.** { *; }
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# --- App entities ------------------------------------------------------------
# Enum values are persisted and synced by NAME. R8 renaming an enum constant would
# reinterpret every stored row — a dose in "MG" would come back as something else.
-keepclassmembers enum com.drugme.app.domain.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Room entities and sync payloads are mapped by field name.
-keep class com.drugme.app.data.local.entity.** { *; }
-keep class com.drugme.app.data.sync.** { *; }
-keep class com.drugme.app.data.crypto.WrappedKey { *; }
-keep class com.drugme.app.data.crypto.KdfParams { *; }
-keep class com.drugme.app.data.crypto.VaultKeyDoc { *; }

# --- Hilt / WorkManager ------------------------------------------------------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# --- Misc --------------------------------------------------------------------
-dontwarn org.slf4j.**
-dontwarn java.lang.invoke.**
