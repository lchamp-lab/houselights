package app.houselights.testing

import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

/** An HTTP answer of [code], as Retrofit throws it. */
fun http(code: Int): HttpException = HttpException(Response.error<Unit>(code, "".toResponseBody()))

/** As smbj's Share.receive throws an answer other than success. */
fun smbStatus(status: NtStatus): SMBApiException =
    SMBApiException(status.value, SMB2MessageCommandCode.SMB2_READ, "Read failed for \\\\nas\\media\\film.mkv", null)

/** As nfs-client's Nfs3.lookupRootHandle throws a mount status other than MNT3_OK. */
fun mountFailure(status: MountStatus): MountException = MountException(
    status,
    "mount failure, server: 192.168.1.20, export: /volume1/media, nfs version: 3, returned state: ${status.value}",
)

/** As OkHttp throws it for a server certificate it cannot trust, here an expired one. */
fun certificateRefused(): SSLHandshakeException =
    SSLHandshakeException("Chain validation failed").apply { initCause(CertificateExpiredException("expired")) }
