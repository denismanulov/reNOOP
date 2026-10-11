package com.noop.friends

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The phone's key: what it signs verifies with what it publishes, it is kept per server, and the
 * contract's vector (`friends-server/README.md`) verifies here as it does on the server and in the Swift
 * app. The key is a software one: the Android Keystore does not exist on the JVM these tests run on, so
 * [AndroidFriendsKeyStore] itself is not exercised here.
 */
class FriendsKeyTest {
    private val message = (
        "renoop-friends-v2\nPUT\n/v2/me/days/2026-10-10\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\n" +
            "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445"
        ).toByteArray(Charsets.UTF_8)

    @Test
    fun theContractsSignatureVerifies() {
        val spki = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ/iGnmqlOeIlaOq9cf3gYSDRWcoyKOiHtx1m7TC7Wnr6CDeXUMf1QOVArjNKQgNPyKXVXw0/9N1Iyj8xadf1YA==",
        )
        val signature = Base64.getDecoder().decode(
            "MEYCIQCbv6DGgAsKyGcPSjPiv6/b8i3IJZkUCmkxZt2/mg+nBgIhAPIfodgQQUa5TD0KWsCpCIKzIgtAwstLtonCzIHZLJMQ",
        )
        assertEquals("b9310888608f332f9d712a19e65fd9a088948406f04240f82a4e50843774784a", FriendsKeys.keyId(spki))
        assertTrue(friendsSignatureVerifies(spki, message, signature))
        assertFalse(friendsSignatureVerifies(spki, message + " ".toByteArray(), signature))
    }

    /** What the contract signs is what this client builds for the same request. */
    @Test
    fun theContractsSigningStringIsWhatTheClientBuilds() {
        val body = "{\"recovery\":81}".toByteArray(Charsets.UTF_8)
        assertEquals("a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445", FriendsKeys.sha256Hex(body))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FriendsKeys.sha256Hex(ByteArray(0)))
        val text = FriendsWire.signingString("PUT", "/v2/me/days/2026-10-10", 1_791_540_000L, "AAAAAAAAAAAAAAAAAAAAAA", FriendsKeys.sha256Hex(body))
        assertArrayEquals(message, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun whatAKeySignsVerifiesWithWhatItPublishes() {
        val key = SoftwareFriendsSigner()
        assertEquals("an uncompressed P-256 point in SubjectPublicKeyInfo", 91, key.publicKeySpki.size)
        // The fixed head of that encoding: the server takes no other form of the key.
        assertEquals(
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004",
            FriendsKeys.hex(key.publicKeySpki.copyOfRange(0, 27)),
        )
        assertEquals(FriendsKeys.sha256Hex(key.publicKeySpki), key.keyId)
        assertEquals(64, key.keyId.length)
        val signature = key.sign(message)
        assertEquals("a DER sequence", 0x30, signature[0].toInt())
        assertTrue(friendsSignatureVerifies(key.publicKeySpki, message, signature))
        assertFalse(friendsSignatureVerifies(key.publicKeySpki, message + 1, signature))
        assertFalse(friendsSignatureVerifies(SoftwareFriendsSigner().publicKeySpki, message, signature))
    }

    @Test
    fun aKeyIsKeptPerServerAndComesBackTheSame() {
        val store = MemoryFriendsKeyStore()
        val key = store.create("https://a.example")
        assertEquals(key.keyId, (store.find("https://a.example") as FriendsKeyStore.Kept.Key).signer.keyId)
        assertTrue("another server has no key", store.find("https://b.example") is FriendsKeyStore.Kept.Absent)
        val other = store.create("https://b.example")
        assertNotEquals(key.keyId, other.keyId)
        assertNotEquals(FriendsKeys.alias("https://a.example"), FriendsKeys.alias("https://b.example"))
        store.delete("https://a.example")
        assertTrue(store.find("https://a.example") is FriendsKeyStore.Kept.Absent)
        assertTrue(store.find("https://b.example") is FriendsKeyStore.Kept.Key)
    }

    /** A key's name says nothing of the server it is for, and is the same on every launch. */
    @Test
    fun aKeysNameIsAHashOfTheServerAddress() {
        val alias = FriendsKeys.alias("https://renoop.duckdns.org")
        assertEquals(alias, FriendsKeys.alias("https://renoop.duckdns.org"))
        assertTrue(alias.startsWith("renoop.friends.key."))
        assertEquals(32, alias.removePrefix("renoop.friends.key.").length)
        assertFalse(alias.contains("duckdns"))
        assertEquals(
            FriendsKeys.sha256Hex("https://renoop.duckdns.org".toByteArray()).take(32),
            alias.removePrefix("renoop.friends.key."),
        )
    }

    @Test
    fun aKeyThatCannotBeKeptIsNotHandedOut() {
        val store = MemoryFriendsKeyStore()
        store.refusesWrites = true
        try {
            store.create("https://a.example")
            fail("a key that was not kept was handed out")
        } catch (refused: FriendsKeyException) {
            assertEquals(FriendsKeyProblem.NOT_KEPT, refused.problem)
        }
        assertNull(store.kept("https://a.example"))
    }

    /**
     * The store tells "nothing is kept" from "what is kept could not be read": only the first may ever
     * be answered by making a new key.
     */
    @Test
    fun theStoreTellsNoKeyFromAKeyItCouldNotRead() {
        val store = MemoryFriendsKeyStore()
        val server = "https://a.example"
        assertTrue(store.find(server) is FriendsKeyStore.Kept.Absent)
        val key = store.create(server)
        store.failsReads = true
        assertTrue("a failed read is not an answer", store.find(server) is FriendsKeyStore.Kept.Unreadable)
        store.failsReads = false
        assertEquals(key.keyId, (store.find(server) as FriendsKeyStore.Kept.Key).signer.keyId)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun hexIsLowerCaseWithEveryByteInTwoDigits() {
        assertEquals("00017f80ff", FriendsKeys.hex(byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())))
        assertEquals("", FriendsKeys.hex(ByteArray(0)))
    }
}
