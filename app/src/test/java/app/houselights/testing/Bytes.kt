package app.houselights.testing

/** The bytes [parts] spell in hexadecimal, two digits a byte, spaces ignored: a packet written as a capture shows it. */
fun hex(vararg parts: String): ByteArray {
    val digits = parts.joinToString("").filterNot { it.isWhitespace() }
    require(digits.length % 2 == 0) { "An odd number of hexadecimal digits" }
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
