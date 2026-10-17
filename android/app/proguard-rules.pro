-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class app.hermesconduit.android.**$$serializer { *; }
-keepclassmembers class app.hermesconduit.android.** { *** Companion; }
# Tink (used by androidx.security-crypto) references compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# WebRTC (GPT-Live) is called from native code through JNI.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
