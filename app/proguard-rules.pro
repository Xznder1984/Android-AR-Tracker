-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn javax.annotation.**
-dontwarn org.jspecify.annotations.**

# CameraX uses reflection in a few places.
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
