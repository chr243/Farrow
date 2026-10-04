# Phase 2: minification disabled. Keep kotlinx.serialization metadata if enabled later.
-keepattributes *Annotation*, InnerClasses

# Shizuku UserService is instantiated reflectively by Shizuku (app_process).
-keep class com.farrow.app.shizuku.ShellUserService { *; }
-keep class com.farrow.app.shizuku.IShellService** { *; }
