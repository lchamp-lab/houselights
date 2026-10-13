package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class ScanErrorsTest {
    private fun kind(e: Throwable) = ScanErrors.classify(e).kind

    @Test
    fun unreachable() {
        assertEquals(ScanErrorKind.UNREACHABLE, kind(IOException("rpc error, server: 192.168.1.20, RPC error: waiting for connection to be established, but failed /192.168.1.20:111")))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(IOException("wrapped", ConnectException("Connection refused"))))
    }

    @Test
    fun smbLogin() {
        assertEquals(ScanErrorKind.LOGIN_FAILED, kind(RuntimeException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed for 'zidoo'")))
    }

    @Test
    fun accessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(IOException("NFS3ERR_ACCES")))
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(RuntimeException("STATUS_ACCESS_DENIED (0xc0000022)")))
    }

    @Test
    fun notFound() {
        assertEquals(ScanErrorKind.NOT_FOUND, kind(RuntimeException("STATUS_BAD_NETWORK_NAME (0xc00000cc)")))
        assertEquals(ScanErrorKind.NOT_FOUND, kind(IOException("NFS3ERR_NOENT")))
    }

    @Test
    fun other() {
        assertEquals(ScanErrorKind.OTHER, kind(IllegalStateException("something else")))
    }
}
