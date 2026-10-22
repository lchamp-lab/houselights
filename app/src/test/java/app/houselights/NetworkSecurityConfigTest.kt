package app.houselights

import app.houselights.providers.trakt.TraktClient
import app.houselights.testing.children
import app.houselights.testing.xmlRoot
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/** The network security config per host, as Android resolves it: user CAs for home servers, not for TMDB or Trakt. */
class NetworkSecurityConfigTest {
    private val file = File("src/main/res/xml/network_security_config.xml")

    /** What a host is allowed: cleartext, and the certificate sources it trusts. */
    private data class Policy(val cleartext: Boolean, val anchors: Set<String>)

    @Test
    fun tmdbTrustsTheSystemOnly() {
        listOf("api.themoviedb.org", "image.tmdb.org", "themoviedb.org", "www.themoviedb.org").forEach { host ->
            assertEquals(host, Policy(cleartext = false, anchors = setOf("system")), policy(host))
        }
    }

    @Test
    fun traktTrustsTheSystemOnly() {
        val called = listOf(TraktClient.API_URL, TraktClient.AUTH_URL).map { it.toHttpUrl().host }
        (called + "trakt.tv").forEach { host ->
            assertEquals(host, Policy(cleartext = false, anchors = setOf("system")), policy(host))
        }
    }

    @Test
    fun homeServersKeepCleartextAndTheUsersCertificates() {
        listOf("192.168.1.20", "jellyfin.local", "nas").forEach { host ->
            assertEquals(host, Policy(cleartext = true, anchors = setOf("system", "user")), policy(host))
        }
    }

    private fun policy(host: String): Policy {
        val root = xmlRoot(file)
        val base = root.children("base-config").singleOrNull()
        // Android's defaults for an app targeting API 28 or later: no cleartext, system authorities.
        val defaults = Policy(cleartext = false, anchors = setOf("system"))
        val start = base?.let { applied(it, defaults) } ?: defaults
        return root.children("domain-config").mapNotNull { match(it, host, start) }.maxByOrNull { it.first }?.second ?: start
    }

    /** The most specific config under [config] (or itself) naming [host]: matched name length and policy. */
    private fun match(config: Element, host: String, inherited: Policy): Pair<Int, Policy>? {
        val own = config.children("domain").mapNotNull { domain ->
            val name = domain.textContent.trim().lowercase()
            val subdomains = domain.getAttribute("includeSubdomains") == "true"
            name.takeIf { host == it || (subdomains && host.endsWith(".$it")) }?.length
        }.maxOrNull()
        val policy = applied(config, inherited)
        val nested = config.children("domain-config").mapNotNull { match(it, host, policy) }
        return (listOfNotNull(own?.let { it to policy }) + nested).maxByOrNull { it.first }
    }

    /** [inherited], overridden by what [config] sets. */
    private fun applied(config: Element, inherited: Policy): Policy {
        val cleartext = config.getAttribute("cleartextTrafficPermitted").takeIf { it.isNotEmpty() }?.toBooleanStrict()
        val anchors = config.children("trust-anchors").singleOrNull()
            ?.children("certificates")?.map { it.getAttribute("src") }?.toSet()
        return Policy(cleartext ?: inherited.cleartext, anchors ?: inherited.anchors)
    }
}
