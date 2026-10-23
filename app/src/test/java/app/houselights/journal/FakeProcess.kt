package app.houselights.journal

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** A process that printed [output] and ended, as logcat -d does. */
internal class FakeProcess(output: String) : Process() {
    private val stdout = ByteArrayInputStream(output.toByteArray())

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit
}
