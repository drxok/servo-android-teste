-dontwarn **
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }

-dontwarn org.mozilla.**
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
