# Release builds are not minified by default (see app/build.gradle.kts).
# These rules are kept for anyone who turns minification on.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class cz.adaptech.tesseract4android.** { *; }
-dontwarn androidx.compose.**
