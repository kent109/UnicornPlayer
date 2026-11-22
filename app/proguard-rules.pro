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
# 项目自身类：所有子包 + 根包 Application 入口
# com.unicorn.player.<任意子包> 全通配，覆盖：
#   model / database / service / viewmodel / util(+子包) / equalizer /
#   activity / ui / ui.player / adapter / playback / repository / manager /
#   widget / diagnostics / UnicornPlayerApplication
# ============================================================
-keep class com.unicorn.player.** { *; }

# 必须 keep UnicornPlayerApplication 本身（根包唯一类）
-keep class com.unicorn.player.UnicornPlayerApplication { *; }

# Room Entity / DAO 注解保留
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
# Kyrie (矢量动画库，反射访问私有字段)
# ============================================================
-keep class com.github.alexjlockwood.kyrie.** { *; }
-dontwarn com.github.alexjlockwood.kyrie.**

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
# openccjava（简繁转换）
# ============================================================
-keep class openccjava.** { *; }
-dontwarn openccjava.**

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

# ============================================================
# Kotlinx Serialization
# ============================================================
-dontwarn kotlinx.serialization.**

# ============================================================
# media3-decoder-ape（本地 AAR：files() 不携带 consumer 规则）
# native 层通过 FindClass + RegisterNatives 严格按名绑定，
# read(ByteBuffer) 回调由 native GetMethodID 按名查找
# ============================================================
-keep class io.github.eugenedibtsev.media3.ape.ApeDecoderJni {
    native <methods>;
    int read(java.nio.ByteBuffer);
}
-keep class io.github.eugenedibtsev.media3.ape.ApeLibrary {
    native <methods>;
}
-keep class io.github.eugenedibtsev.media3.ape.ApeSupport {
    public <methods>;
}
-keep class io.github.eugenedibtsev.media3.ape.ApeExtractorsFactory {
    public <methods>;
}
-keep class io.github.eugenedibtsev.media3.ape.ApeExtractor {
    public <fields>;
    public <methods>;
}
-keep class io.github.eugenedibtsev.media3.ape.ApeStreamMetadata {
    public <fields>;
    public <methods>;
}
-keep class io.github.eugenedibtsev.media3.ape.ApeDecoderException {
    public <fields>;
    public <methods>;
}