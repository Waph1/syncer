# youtubedl-android: maps yt-dlp's JSON with Jackson (reflection) and loads its classes by name.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-keep class com.fasterxml.jackson.annotation.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn java.beans.**
