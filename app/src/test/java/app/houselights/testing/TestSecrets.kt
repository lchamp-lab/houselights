package app.houselights.testing

import app.houselights.core.Secrets
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A new AES key, in place of one from the device's Keystore. */
fun testKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

/** Secrets sealed with a key of the test's own ([testKey]). */
fun testSecrets(): Secrets {
    val key = testKey()
    return Secrets { key }
}
