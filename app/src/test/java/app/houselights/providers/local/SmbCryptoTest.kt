package app.houselights.providers.local

import com.hierynomus.security.bc.BCSecurityProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** smbj signs in with BouncyCastle primitives: they must work with the BouncyCastle version the build forces. */
class SmbCryptoTest {
    private val provider = BCSecurityProvider()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun md4ForNtlmHashes() {
        val md4 = provider.getDigest("MD4")
        md4.update("abc".toByteArray())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", md4.digest().hex())
    }

    @Test
    fun hmacMd5ForNtlmV2() {
        val mac = provider.getMac("HmacMD5")
        mac.init("key".toByteArray())
        mac.update("The quick brown fox jumps over the lazy dog".toByteArray())
        assertEquals("80070713463e7749b90c2dc24911e275", mac.doFinal().hex())
    }
}
