# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep everything - no obfuscation, no optimization
-dontoptimize
-dontobfuscate
-dontshrink
-dontpreverify

# Keep all classes and their members
-keep class ** { *; }

# Keep all annotations
-keepattributes *Annotation*

# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep all drawing app specific classes
-keep class com.example.newdrawingapp.** { *; }

# Keep all Kotlin metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep all reflection usage
-keepattributes Signature, Exception, *Annotation*, InnerClasses, EnclosingMethod