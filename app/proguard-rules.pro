-keepattributes *Annotation*
-keep class libXray.** { *; }

# AppRoutingDialog is opened from both the Home app picker and Rules routing.
# R8 8.x can produce invalid monitor bytecode for its nested async lambdas on
# some Android runtimes, resulting in VerifyError before the dialog is built.
-keep class com.h2ray.app.AppRoutingDialog** { *; }
