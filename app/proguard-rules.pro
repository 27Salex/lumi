# Reglas de ofuscación y preservación para Gemini Nano y Room DB
-keep class io.github.salex27.lumi.data.local.** { *; }
-keep class io.github.salex27.lumi.domain.model.** { *; }
-keepattributes *Annotation*
-dontwarn com.google.ai.client.generativeai.**
