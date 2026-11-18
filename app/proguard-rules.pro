# FreeTV release shrinker rules.
# Room, Hilt, Media3, OkHttp, Coil and kotlinx.serialization ship their own
# consumer rules; only app-specific keeps go here.

# kotlinx.serialization – keep generated serializers of @Serializable classes
# (navigation routes, database backup JSON).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class com.example.freetv.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.example.freetv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
