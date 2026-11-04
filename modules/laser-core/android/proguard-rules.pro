# Keep the JNI bridge and Expo-instantiated modules/views intact under R8.
-keep class dev.vakharwalad23.lasercore.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
