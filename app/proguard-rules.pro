-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ONNX Runtime uses JNI and reflection.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
