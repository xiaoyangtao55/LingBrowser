# 「翎」浏览器 Release 混淆规则

# ---- WebView 相关 ----
# 我们在 WebView 里通过 addJavascriptInterface 暴露对象，只要不依赖反射调用
# 就可以正常混淆；但为稳妥起见保留 @JavascriptInterface 注解方法名。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# ---- Room 生成的实现类 ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- Kotlin 元数据，Compose 运行时需要 ----
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,InnerClasses,Signature
-dontwarn kotlin.**

# ---- Compose ----
-dontwarn androidx.compose.**

# 保留行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
