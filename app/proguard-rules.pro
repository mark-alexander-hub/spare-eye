# NanoHTTPD reads its own classes reflectively for the temp-file manager.
-keep class fi.iki.elonen.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
