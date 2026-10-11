package com.noop.friends

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** A software P-256 key: what stands for the Keystore key on the JVM, where there is no Keystore. */
class SoftwareFriendsSigner(private val pair: KeyPair = newPair()) : FriendsSigner {
    override val publicKeySpki: ByteArray = pair.public.encoded
    override val keyId: String = FriendsKeys.keyId(publicKeySpki)
    override fun sign(message: ByteArray): ByteArray = FriendsKeys.sign(pair.private, message)

    companion object {
        fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }
}

/** Whether [signature] (DER) is the ECDSA-SHA256 signature of [message] under the key [spki] publishes. */
fun friendsSignatureVerifies(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
    return Signature.getInstance("SHA256withECDSA").run {
        initVerify(key)
        update(message)
        runCatching { verify(signature) }.getOrDefault(false)
    }
}

/** Keys held in memory, with the ways a real store can fail. */
class MemoryFriendsKeyStore : FriendsKeyStore {
    private val keys = HashMap<String, FriendsSigner>()

    /** Set to make every read fail, as a store that cannot be reached does. */
    @Volatile var failsReads = false

    /** Set to make every write fail, as a store that refuses does. */
    @Volatile var refusesWrites = false

    /** How many keys were asked to be made, refused ones included. */
    @Volatile var writeCount = 0
        private set

    /** How many times the removal of version 1's token was asked for. */
    @Volatile var obsoleteTokenRemovals = 0
        private set

    @Synchronized
    override fun find(server: String): FriendsKeyStore.Kept = when {
        failsReads -> FriendsKeyStore.Kept.Unreadable
        else -> keys[FriendsKeys.alias(server)]?.let { FriendsKeyStore.Kept.Key(it) } ?: FriendsKeyStore.Kept.Absent
    }

    @Synchronized
    override fun create(server: String): FriendsSigner {
        writeCount++
        if (refusesWrites) throw FriendsKeyException(FriendsKeyProblem.NOT_KEPT)
        return SoftwareFriendsSigner().also { keys[FriendsKeys.alias(server)] = it }
    }

    @Synchronized
    override fun delete(server: String) {
        keys.remove(FriendsKeys.alias(server))
    }

    @Synchronized
    override fun removeObsoleteToken() {
        obsoleteTokenRemovals++
    }

    /** The key kept for [server], or null: what a test asks without going through [find]'s failures. */
    @Synchronized
    fun kept(server: String): FriendsSigner? = keys[FriendsKeys.alias(server)]
}
