# Keep kotlinx.serialization generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.legal.automation.** {
    *** Companion;
}
-keepclasseswithmembers class com.legal.automation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.legal.automation.**$$serializer { *; }

# Shizuku AIDL + user service run in a separate process; keep them intact.
-keep class com.legal.automation.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
