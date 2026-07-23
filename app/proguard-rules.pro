# ProGuard/R8 rules.
#
# Code shrinking is currently DISABLED in build.gradle.kts (isMinifyEnabled =
# false) because R8 stripping crashed the released app on launch. These rules
# are kept so shrinking can be turned back on safely.

# Keep line numbers for readable crash reports, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ViewModels are instantiated reflectively by the ViewModel factory; keep their
# constructors (AndroidViewModel takes an Application).
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# The updater parses JSON with org.json (part of the Android platform), no rules
# needed. The equity data lives in assets/ and is not affected by shrinking.
