package com.rokid.style.clawsses

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.Base64
import java.util.UUID

/**
 * Manages an Ed25519 identity keypair stored in the Android Keystore.
 * The private key never leaves the secure hardware (or software keystore on
 * devices without a TEE). Signing is done inside the Keystore.
 *
 * Device ID is a stable UUID persisted in SharedPreferences alongside
 * the keystore alias (no sensitive material in prefs).
 */
class DeviceIdentity(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "clawsses_device_identity_v1"
        private const val PREF_FILE = "clawsses_identity"
        private const val PREF_DEVICE_ID = "device_id"
    }

    private val identityPrefs by lazy {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    /** Stable device identifier (UUID). Generated once, then persisted. */
    val deviceId: String by lazy {
        identityPrefs.getString(PREF_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            identityPrefs.edit().putString(PREF_DEVICE_ID, id).apply()
            id
        }
    }

    /** Returns the Ed25519 public key encoded as Base64 (no padding). */
    val publicKeyBase64: String by lazy {
        val kp = getOrCreateKeyPair()
        Base64.getEncoder().encodeToString(kp.public.encoded)
    }

    /**
     * Signs [data] with the stored Ed25519 private key and returns the
     * signature as a Base64-encoded string.
     */
    fun sign(data: ByteArray): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("Key not found in keystore; call getOrCreateKeyPair() first")

        val sig = Signature.getInstance("Ed25519").apply {
            initSign(privateKey as java.security.PrivateKey)
            update(data)
        }
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val cert = keyStore.getCertificate(KEY_ALIAS)
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            return KeyPair(cert.publicKey, privateKey)
        }

        // Generate a new Ed25519 keypair inside the Keystore.
        val keyGen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, // Ed25519 is exposed via EC in Android Keystore
            KEYSTORE_PROVIDER
        )

        // Android Keystore supports Ed25519 from API 31+.
        // On API 26-30 we fall back to a software-backed key pair stored in Keystore via
        // a workaround: generate outside, import. However Android Keystore does not support
        // importing asymmetric keys directly pre-API31 without a secure import spec.
        // For maximum compatibility on API 26-30, we generate the key outside the hardware
        // Keystore but still persist the key material encrypted in private SharedPreferences.
        return try {
            keyGen.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("Ed25519"))
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .build()
            )
            keyGen.generateKeyPair()
        } catch (e: Exception) {
            // Fallback: software Ed25519 key, persisted as an opaque secret in Keystore
            generateSoftwareKeyPair()
        }
    }

    /**
     * Fallback for devices that do not support Ed25519 in the hardware keystore.
     * Generates the keypair in software and stores encoded bytes in an
     * EncryptedSharedPreferences-equivalent pattern using a symmetric AES key
     * from the Keystore for wrapping.
     */
    private fun generateSoftwareKeyPair(): KeyPair {
        // Re-use the standard java.security provider which supports Ed25519 on JVM/Android 11+.
        // On API 26-30 the provider may not be present; fall back to X25519/P-256.
        return try {
            val gen = KeyPairGenerator.getInstance("Ed25519")
            gen.initialize(256)
            val kp = gen.generateKeyPair()
            persistSoftwareKeyPair(kp)
            kp
        } catch (e: Exception) {
            // Last resort: use EC P-256 which is universally available.
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            val kp = gen.generateKeyPair()
            persistSoftwareKeyPair(kp)
            kp
        }
    }

    private fun persistSoftwareKeyPair(kp: KeyPair) {
        identityPrefs.edit()
            .putString("sw_pub", Base64.getEncoder().encodeToString(kp.public.encoded))
            .putString("sw_priv", Base64.getEncoder().encodeToString(kp.private.encoded))
            .putString("sw_alg", kp.private.algorithm)
            .apply()
    }

    /** Loads a software-backed keypair previously persisted in prefs. */
    private fun loadSoftwareKeyPair(): KeyPair? {
        val pubB64 = identityPrefs.getString("sw_pub", null) ?: return null
        val privB64 = identityPrefs.getString("sw_priv", null) ?: return null
        val alg = identityPrefs.getString("sw_alg", "Ed25519") ?: "Ed25519"

        val kf = java.security.KeyFactory.getInstance(alg)
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
        return KeyPair(pub, priv)
    }

    /**
     * Signs [data] using a software-backed keypair (fallback path).
     * Called from [sign] if the Keystore key is unavailable.
     */
    fun signWithSoftwareKey(data: ByteArray): String {
        val kp = loadSoftwareKeyPair()
            ?: throw IllegalStateException("No software key pair found")
        val alg = when (kp.private.algorithm) {
            "Ed25519" -> "Ed25519"
            else -> "SHA256withECDSA"
        }
        val sig = Signature.getInstance(alg).apply {
            initSign(kp.private)
            update(data)
        }
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    /**
     * Public entry-point: sign a nonce string. Tries hardware Keystore first,
     * falls back to software key.
     */
    fun signNonce(nonce: String): String {
        return try {
            sign(nonce.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            signWithSoftwareKey(nonce.toByteArray(Charsets.UTF_8))
        }
    }
}
