package io.github.salex27.lumi.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import io.github.salex27.lumi.domain.ai.BrainChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Lumi's brain (#2): which engine leads and the cloud providers' settings. Keys never leave this phone. */
data class BrainConfig(
    val choice: BrainChoice = BrainChoice.AUTO,
    val anthropicKey: String = "",
    val anthropicInterpretModel: String = DEFAULT_ANTHROPIC_INTERPRET,
    val anthropicReplyModel: String = DEFAULT_ANTHROPIC_REPLY,
    val openAiKey: String = "",
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val compatibleBaseUrl: String = "",
    val compatibleKey: String = "",
    val compatibleModel: String = ""
) {
    companion object {
        /** Interpreting: fast and cheap (the issue suggests Haiku). */
        const val DEFAULT_ANTHROPIC_INTERPRET = "claude-haiku-4-5"
        /** Replies and questions: the current Opus. */
        const val DEFAULT_ANTHROPIC_REPLY = "claude-opus-5-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    }
}

/**
 * Brain settings in the "brain" prefs file, which is NOT in the backup. API keys are encrypted with an AES key that
 * lives in the Android Keystore (never exportable), so a copy of the prefs file alone doesn't reveal them.
 */
class BrainSettings(context: Context) {
    private val prefs = context.getSharedPreferences("brain", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<BrainConfig> = _config.asStateFlow()
    val current: BrainConfig get() = _config.value

    private fun load() = BrainConfig(
        choice = runCatching { BrainChoice.valueOf(prefs.getString("choice", null) ?: "") }.getOrDefault(BrainChoice.AUTO),
        anthropicKey = secret("anthropic_key"),
        anthropicInterpretModel = prefs.getString("anthropic_interpret_model", null) ?: BrainConfig.DEFAULT_ANTHROPIC_INTERPRET,
        anthropicReplyModel = prefs.getString("anthropic_reply_model", null) ?: BrainConfig.DEFAULT_ANTHROPIC_REPLY,
        openAiKey = secret("openai_key"),
        openAiModel = prefs.getString("openai_model", null) ?: BrainConfig.DEFAULT_OPENAI_MODEL,
        compatibleBaseUrl = prefs.getString("compatible_url", "").orEmpty(),
        compatibleKey = secret("compatible_key"),
        compatibleModel = prefs.getString("compatible_model", "").orEmpty()
    )

    fun save(c: BrainConfig) {
        val clean = c.copy(
            anthropicKey = c.anthropicKey.trim(), openAiKey = c.openAiKey.trim(), compatibleKey = c.compatibleKey.trim(),
            anthropicInterpretModel = c.anthropicInterpretModel.trim().ifBlank { BrainConfig.DEFAULT_ANTHROPIC_INTERPRET },
            anthropicReplyModel = c.anthropicReplyModel.trim().ifBlank { BrainConfig.DEFAULT_ANTHROPIC_REPLY },
            openAiModel = c.openAiModel.trim().ifBlank { BrainConfig.DEFAULT_OPENAI_MODEL },
            compatibleBaseUrl = c.compatibleBaseUrl.trim(), compatibleModel = c.compatibleModel.trim()
        )
        prefs.edit()
            .putString("choice", clean.choice.name)
            .putString("anthropic_key", seal(clean.anthropicKey))
            .putString("anthropic_interpret_model", clean.anthropicInterpretModel)
            .putString("anthropic_reply_model", clean.anthropicReplyModel)
            .putString("openai_key", seal(clean.openAiKey))
            .putString("openai_model", clean.openAiModel)
            .putString("compatible_url", clean.compatibleBaseUrl)
            .putString("compatible_key", seal(clean.compatibleKey))
            .putString("compatible_model", clean.compatibleModel)
            .apply()
        _config.value = clean
    }

    // ── Keystore-backed secrets ──────────────────────────────────────────────────────────────────────────────

    private fun secret(name: String): String = prefs.getString(name, null)?.let(::open).orEmpty()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    /** "" stays ""; otherwise base64(iv) + ":" + base64(ciphertext). */
    private fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun open(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            val (iv, data) = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            // A restored phone has a different Keystore: the key must be typed again
            Log.w("LumiBrain", "could not open a stored key: ${it.javaClass.simpleName}")
            ""
        }
    }

    private companion object {
        const val ALIAS = "lumi_brain_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
