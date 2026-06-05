# Add project specific ProGuard rules here.
# https://developer.android.com/studio/build/shrink-code

# Keep Room generated classes
-keep class androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Hilt
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
