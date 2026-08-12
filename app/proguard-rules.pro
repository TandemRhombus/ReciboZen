# ReciboZen release hardening. R8's optimized default rules perform shrinking,
# optimization and identifier obfuscation; keep rules stay intentionally narrow.

# Kotlin metadata is required by a few AndroidX components, but source file names
# are rewritten so a decompiled APK does not expose the original project layout.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Room generates direct implementations. Keep only the database class name used
# by Room's generated lookup; entities and business/security logic remain obfuscated.
-keepnames class com.recibozen.app.data.local.ReciboZenDatabase

# Preserve enum valueOf()/values() contracts used by persisted non-sensitive state.
-keepclassmembers,allowoptimization,allowobfuscation enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
