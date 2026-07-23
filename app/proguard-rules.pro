# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留堆栈跟踪的行号信息，便于定位崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留注解和泛型信息
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod

# 保留 native 方法不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 Parcelable 实现类
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留 Serializable 类
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
# 项目自身类（Room / Service / ViewModel / Model）
# ============================================================
-keep class com.unicorn.player.model.** { *; }
-keep class com.unicorn.player.database.** { *; }
-keep class com.unicorn.player.service.** { *; }
-keep class com.unicorn.player.viewmodel.** { *; }

-keepclassmembers class ** {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# ============================================================
# OkHttp / Okio
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Gson
# ============================================================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ============================================================
# Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ============================================================
# LrcView (JitPack: com.github.bifan-wei:LrcView)
# ============================================================
-keep class com.github.bifan_wei.lrcview.** { *; }
-dontwarn com.github.bifan_wei.lrcview.**

# ============================================================
# SmartRefreshLayout
# ============================================================
-keep class com.scwang.smart.refresh.** { *; }
-dontwarn com.scwang.smart.refresh.**

# ============================================================
# DocumentFile (SAF)
# ============================================================
-keep class androidx.documentfile.provider.** { *; }
-dontwarn androidx.documentfile.provider.**

# ============================================================
# opencc4j（简繁转换）
# ============================================================
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn com.github.houbb.opencc4j.**

# ============================================================
# pinyin4j（拼音转换）
# ============================================================
-keep class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

# ============================================================
# WaveSideBar
# ============================================================
-keep class com.github.nanchen2251.** { *; }
-dontwarn com.github.nanchen2251.**

# ============================================================
# SwitchButton
# ============================================================
-keep class com.github.zcweng.switchbutton.** { *; }
-dontwarn com.github.zcweng.switchbutton.**

# ============================================================
# Kotlin 协程
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**