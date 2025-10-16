# 文件: app/proguard-rules.pro
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.inputmethodservice.InputMethodService
-keep class com.remoteinput.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }