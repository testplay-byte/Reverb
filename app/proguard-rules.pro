# Reverb — ProGuard rules

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Jsoup
-keep class org.jsoup.** { *; }

# QuickJS (dokar3/quickjs-kt)
-keep class com.dokar.quickjs.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keepclassmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Compose
-keep class androidx.compose.** { *; }

# Reverb — keep the Site contract + DTOs (reflection by Hilt + Room)
-keep class app.reverb.source.api.** { *; }
-keep class app.reverb.source.universal.UniversalSite { *; }
-keep class app.reverb.adblock.** { *; }
