# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.fluxframe.app.**$$serializer { *; }
-keepclassmembers class com.fluxframe.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.fluxframe.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}

# OPPO 流体云 SDK 通过反射调用，保留可能被反射到的公开成员名
-keep class com.oppo.** { *; }
-keep class com.heytap.** { *; }
-keep class com.coloros.** { *; }
-dontwarn com.oppo.**
-dontwarn com.heytap.**
-dontwarn com.coloros.**

# Media3
-dontwarn androidx.media3.**
