# Obfuscation and keep rules for Gemini Nano and Room
-keep class io.github.salex27.lumi.data.local.** { *; }
-keep class io.github.salex27.lumi.domain.model.** { *; }
-keepattributes *Annotation*
-dontwarn com.google.ai.client.generativeai.**
