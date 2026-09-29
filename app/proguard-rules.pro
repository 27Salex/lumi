# Reglas de ofuscación y preservación para Gemini Nano y Room DB
-keep class com.antigravity.gemininanotaskmanager.data.local.** { *; }
-keep class com.antigravity.gemininanotaskmanager.domain.model.** { *; }
-keepattributes *Annotation*
-dontwarn com.google.ai.client.generativeai.**
