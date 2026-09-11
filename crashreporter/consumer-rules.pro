# ZBD Crash Reporter — consumer ProGuard/R8 rules.
#
# These ship INSIDE crashreporter-release.aar (as proguard.txt) and are applied
# automatically by any host app that minifies with R8/ProGuard. Without them R8
# strips/renames com.crashreporter.**, which breaks the reporter (and, critically,
# the JNI native-method bindings that native crash capture depends on).

# Keep the whole crash-reporter library (classes + members).
-keep class com.crashreporter.** { *; }

# JNI: native methods are resolved by name at runtime, so they (and the classes
# that declare them) must never be renamed or removed.
-keepclasseswithmembernames class com.crashreporter.** {
    native <methods>;
}
