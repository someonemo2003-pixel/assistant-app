-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ONNX Runtime uses JNI and reflection.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# JavaMail (Gmail IMAP/SMTP)
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
-dontwarn myjava.awt.datatransfer.**


# Room (used by WorkManager) creates its databases by reflection with the no-argument constructor.
# The stricter R8 in AGP 9 removed it, so the app crashed on start: keep it.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
