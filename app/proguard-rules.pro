# Pupurin Loom Android — 混淆规则
# 空实现：当前 release 不开启 minify（isMinifyEnabled=false）
# 若开启，需保留 @JavascriptInterface 桥接类与 Gson 反射模型
-keepclassmembers class com.pupurin.loom.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.pupurin.loom.bridge.** { *; }
-keepattributes Signature, *Annotation*