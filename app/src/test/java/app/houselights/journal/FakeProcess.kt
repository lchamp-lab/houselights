package app.houselights.journal

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** A process that printed [stdout] and ended, as logcat -d does. */
internal class FakeProcess(private val stdout: InputStream) : Process() {
    constructor(output: String) : this(ByteArrayInputStream(output.toByteArray()))

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit
}
