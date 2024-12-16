-keepclasseswithmembernames class io.grovs.Grovs { *; }
-keep class io.grovs.Grovs { *; }
-keep class io.grovs.Grovs$** { *; }
-keepclasseswithmembernames class io.grovs.Grovs {
    public <methods>;
}
-keep class io.grovs.Grovs { *; }

-keepclassmembers class io.grovs.Grovs {
    public static ** Companion;
}

-keep class io.grovs.model.** { *; }

-keep interface io.grovs.GrovsDeeplinkListener {
   <methods>;
}
-keep interface io.grovs.GrovsLinkGenerationListener {
   <methods>;
}
-keep interface io.grovs.GrovsNotificationsListener {
   <methods>;
}