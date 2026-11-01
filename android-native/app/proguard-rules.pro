# Retain Compose runtime and model classes
-keepattributes *Annotation*
-dontwarn androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
