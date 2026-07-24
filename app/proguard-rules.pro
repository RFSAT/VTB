# VTB R8 / ProGuard rules
#
# R8 is enabled for release builds (v1.20.32) on Play's recommendation: it
# strips unreachable library code, which shrinks the app and also removes
# Material components this app never uses — the bottom-sheet and side-sheet
# classes that were triggering Play's "deprecated edge-to-edge API" notice
# for setStatusBarColor / setNavigationBarColor.
#
# ---------------------------------------------------------------------------
# THE ONE THING THAT MUST NOT BREAK
# ---------------------------------------------------------------------------
# Everything this app remembers — rifle, bullet and scope profiles, profile
# sets, the stored analysis payload and history, and the whole-app backup
# document — is written and read by Gson using REFLECTION over Kotlin data
# classes. Gson uses each FIELD'S NAME as the JSON key. R8 renames fields by
# default, so obfuscating these classes would silently change every stored
# key: saved profiles, calibrations and backup files written by one build
# would be unreadable by the next, with no crash and no build error to warn
# anyone. The failure would only appear as "my profiles are gone".
#
# This build therefore keeps ALL of the app's own classes and members intact
# and lets R8 optimise only the libraries. That is deliberately conservative:
# it forfeits some optimisation of app code in exchange for a guarantee that
# no persisted format can shift. Narrow it later — one package at a time,
# each verified with a save / reload / backup / restore round trip on a real
# device — rather than in one step.
-keep class com.rfsat.vtb.** { *; }
-keepclassmembers class com.rfsat.vtb.** { *; }

# ---------------------------------------------------------------------------
# Gson itself
# ---------------------------------------------------------------------------
# Generic signatures must survive or TypeToken cannot reconstruct element
# types (e.g. List<BulletProfile>, Map<String, Entry>) at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }

# ---------------------------------------------------------------------------
# Line numbers for readable crash reports
# ---------------------------------------------------------------------------
# The mapping file is packaged into the App Bundle automatically and Play
# picks it up, so stack traces stay decodable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
