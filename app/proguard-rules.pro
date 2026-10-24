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
# smbj finds by name only its Kerberos (SPNEGO) authenticator, off Android, and its flags through their
# enums' values(), which the default rules keep. It signs in and signs with BouncyCastle's lightweight
# classes (BCSecurityProvider: MD4, MD5, HMAC, AES-CMAC, AES-CCM and GCM, RC4, DES, KDF), which it
# creates directly: R8 follows them. Nothing installs BouncyCastle's JCA provider, whose algorithms are
# only found by their names, so none of it is kept.
-dontwarn org.bouncycastle.**

# mbassador (smbj's events) finds a listener's @Handler methods (a connection closed, a session logged
# off, a share disconnected) by their annotation, reads the annotations' values, and creates the
# invocation @Handler names, ReflectiveHandlerInvocation, through its constructor.
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keep @interface net.engio.mbassy.listener.** { *; }
-keep class * extends net.engio.mbassy.dispatch.HandlerInvocation {
    <init>(net.engio.mbassy.subscription.SubscriptionContext);
}

# nfs-client (NFSv3) runs on Netty 3, which references optional logging and codec libraries. Neither
# finds its own classes by name: Netty's field updaters name their fields in constants R8 follows.
-dontwarn org.jboss.netty.**
-dontwarn org.apache.commons.lang3.**

# Failures reads a NAS failure by its exception's simple name and message: those names stay, as in logcat.
-keepnames class com.hierynomus.** extends java.lang.Throwable
-keepnames class com.emc.ecs.nfsclient.** extends java.lang.Throwable
-keepnames class org.jboss.netty.** extends java.lang.Throwable

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
