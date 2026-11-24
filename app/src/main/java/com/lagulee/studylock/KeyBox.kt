package com.lagulee.studylock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API 키를 기기 안의 보안 저장소(Android Keystore) 열쇠로 암호화해 보관한다.
 * 키는 코드나 저장소(GitHub)에 들어가지 않고, 이 기기 밖으로 나가지 않는다(Claude에 요청할 때만 쓰임).
 */
object KeyBox {
    private const val ALIAS = "study_lock_api_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    const val CLAUDE = "claude"
    const val GEMINI = "gemini"

    fun save(store: Store, name: String, plain: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        store.setKeyCipher(name, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP))
    }

    fun load(store: Store, name: String): String? {
        val saved = store.keyCipher(name) ?: return null
        return try {
            val (iv, enc) = saved.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(enc), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(store: Store, name: String) = store.setKeyCipher(name, null)

    /** 사진 판단에 쓸 AI. 둘 다 있으면 사진을 학습에 쓰지 않는 Claude 를 먼저 쓴다. */
    fun provider(store: Store): String? = when {
        load(store, CLAUDE) != null -> CLAUDE
        load(store, GEMINI) != null -> GEMINI
        else -> null
    }

    /** 화면에 보여 줄 때는 끝 4자리만. */
    fun masked(key: String): String = if (key.length <= 8) "****" else key.take(7) + "…" + key.takeLast(4)
}
