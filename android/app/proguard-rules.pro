# kotlinx.serialization: mantém serializadores das classes @Serializable
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.smartplug.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.smartplug.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.smartplug.app.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
