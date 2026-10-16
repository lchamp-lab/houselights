-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
# kotlinx.serialization brings its own rules (META-INF/com.android.tools in its jar): they keep the
# Companion and serializer() that its lookups by type find, of @Serializable classes only.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**

# smbj: Kerberos (GSS) and EL are optional desktop features, absent on Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# smbj registers event handlers through reflection (mbassador), and finds its Kerberos (SPNEGO)
# authenticator by name off Android. On Android it signs in and signs with BouncyCastle's lightweight
# classes (BCSecurityProvider: MD4, MD5, HMAC, AES-CMAC, AES-CCM and GCM, RC4, DES, KDF), which it
# creates directly: R8 follows them. Nothing installs BouncyCastle's JCA provider, whose algorithms
# are only found by their names, so none of it is kept.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn org.bouncycastle.**

# nfs-client (NFSv3) runs on Netty 3, which references optional logging and codec libraries.
-keep class com.emc.ecs.nfsclient.** { *; }
-keep class org.jboss.netty.** { *; }
-dontwarn org.jboss.netty.**
-dontwarn org.apache.commons.lang3.**

# Release builds keep warnings and errors in logcat, not the step-by-step log (titles, paths).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Navigation finds the enums of typed routes by their name.
-keepnames class app.houselights.core.LibraryKind
-keepnames class app.houselights.core.ArtworkKind
-keepnames class app.houselights.ui.onboarding.FolderField
