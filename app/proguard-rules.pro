-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep,includedescriptorclasses class app.houselights.**$$serializer { *; }
-keepclassmembers class app.houselights.** { *** Companion; }
-keepclasseswithmembers class app.houselights.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**

# smbj: Kerberos (GSS) and EL are optional desktop features, absent on Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# smbj registers event handlers and security providers through reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
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
