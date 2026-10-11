package com.noop.friends

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

// MARK: - Friends: the phone's key for the friends service
//
// There is no password. A phone is known to the server by an ECDSA P-256 key it makes the first time
// Friends is turned on, and every request is signed with it. The private key is made in the Android
// Keystore and never leaves it: nothing of it is in a preference, a file or a `.noopbak` backup. There is
// one key per server address, so a server the wearer names themselves never sees a signature the fork's
// own server would accept. Swift twin: `FriendsKey` (the Secure Enclave where there is one).
//
// The Keystore does not exist on the JVM the unit tests run on, so everything above it (the signing of
// a request, the store's state machine) works against the two interfaces below and is tested with a
// software key. The Keystore implementation itself is exercised only on a device.

/** What signs a request: a Keystore key in the app, a software key in tests. */
interface FriendsSigner {
    /** Lower-case hex SHA-256 of [publicKeySpki]: the phone's id on the server. */
    val keyId: String

    /** The public key as SubjectPublicKeyInfo DER. */
    val publicKeySpki: ByteArray

    /** A DER ECDSA-SHA256 signature over [message]. Throws when the key cannot be used. */
    fun sign(message: ByteArray): ByteArray
}

/** Why a key is not in hand. */
enum class FriendsKeyProblem {
    /** The key was made but could not be kept, so it would not survive a relaunch. */
    NOT_KEPT,

    /** A key may be kept but could not be read just now, so none may be made in its place. */
    NOT_READ,
}

class FriendsKeyException(val problem: FriendsKeyProblem) : Exception(problem.name)

/** Where a phone's keys are kept between launches, one for each server address. */
interface FriendsKeyStore {
    /**
     * What is kept for a server. "Nothing is kept" and "what is kept could not be read" are told apart,
     * because only the first may be answered by making a new key.
     */
    sealed class Kept {
        class Key(val signer: FriendsSigner) : Kept()

        /** Nothing is kept, or what is kept is not a key this phone can use. */
        object Absent : Kept()

        /** A key may be kept, but the store could not be read just now. */
        object Unreadable : Kept()
    }

    /** What is kept for [server] (a normalised server address). */
    fun find(server: String): Kept

    /** Makes and keeps a new key for [server], replacing any kept before. Throws [FriendsKeyException]. */
    fun create(server: String): FriendsSigner

    fun delete(server: String)

    /**
     * Removes the session token API version 1 kept. No phone key is involved: that token is of no use
     * to any server since version 2.
     */
    fun removeObsoleteToken()
}

/** What every key store derives the same way. */
object FriendsKeys {
    /** The name a key for [server] is kept under. */
    fun alias(server: String): String = "renoop.friends.key." + sha256Hex(server.toByteArray(Charsets.UTF_8)).take(32)

    /** A phone's id on the server: lower-case hex SHA-256 of its public key's SubjectPublicKeyInfo. */
    fun keyId(publicKeySpki: ByteArray): String = sha256Hex(publicKeySpki)

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** A DER ECDSA-SHA256 signature over [message] by [key]. */
    fun sign(key: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
}

/**
 * The Android Keystore: one P-256 signing key per server, hardware-backed where the phone has the
 * hardware, usable without the wearer present so an upload can follow a background strap sync.
 */
class AndroidFriendsKeyStore(context: Context) : FriendsKeyStore {
    private val app = context.applicationContext

    private class KeystoreSigner(private val key: PrivateKey, override val publicKeySpki: ByteArray) : FriendsSigner {
        override val keyId: String = FriendsKeys.keyId(publicKeySpki)
        override fun sign(message: ByteArray): ByteArray = FriendsKeys.sign(key, message)
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun find(server: String): FriendsKeyStore.Kept = try {
        val store = keystore()
        val alias = FriendsKeys.alias(server)
        if (!store.containsAlias(alias)) {
            FriendsKeyStore.Kept.Absent
        } else {
            val key = store.getKey(alias, null) as? PrivateKey
            val spki = store.getCertificate(alias)?.publicKey?.encoded
            if (key == null || spki == null || key.algorithm != KeyProperties.KEY_ALGORITHM_EC) {
                // An entry under the name that is not a signing key: nothing this phone can use.
                FriendsKeyStore.Kept.Absent
            } else {
                FriendsKeyStore.Kept.Key(KeystoreSigner(key, spki))
            }
        }
    } catch (_: Exception) {
        // Every failure of the Keystore leaves the entry in doubt: it is never read as "no key".
        FriendsKeyStore.Kept.Unreadable
    }

    override fun create(server: String): FriendsSigner {
        try {
            val spec = KeyGenParameterSpec.Builder(FriendsKeys.alias(server), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        } catch (_: Exception) {
            throw FriendsKeyException(FriendsKeyProblem.NOT_KEPT)
        }
        // Handed out only as the store reads it back: that is the key a relaunch will find.
        return (find(server) as? FriendsKeyStore.Kept.Key)?.signer ?: throw FriendsKeyException(FriendsKeyProblem.NOT_KEPT)
    }

    override fun delete(server: String) {
        runCatching { keystore().deleteEntry(FriendsKeys.alias(server)) }
    }

    /** Version 1's token was the one value of an encrypted preferences file of its own. */
    override fun removeObsoleteToken() {
        runCatching { app.deleteSharedPreferences(OBSOLETE_TOKEN_FILE) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val OBSOLETE_TOKEN_FILE = "noop_friends_secure"
    }
}
