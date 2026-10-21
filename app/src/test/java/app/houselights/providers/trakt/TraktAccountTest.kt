package app.houselights.providers.trakt

import app.houselights.core.TraktSession
import app.houselights.testing.Answer
import app.houselights.testing.FakeServer
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import java.io.IOException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Trakt account against a test server: the device code's every answer, renewing the tokens, signing out. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TraktAccountTest {
    private var session: TraktSession? = null
    private val now = 1_700_000_000L

    private val code = DeviceCode("dev-c0de", "ABCD1234", "https://trakt.tv/activate", expiresIn = 600, interval = 5)

    /** The account a test signs in with, its access token ending in six days. */
    private val lea = TraktSession("a1", "r1", expiresAt = now + 6 * DAY, username = "lea")

    /** Saves fail while above zero, each failure counted down. */
    private var failedSaves = 0

    private fun TestScope.account(server: FakeServer, keys: TraktKeys = KEYS) = TraktAccount(
        keys,
        session = { session },
        save = {
            if (failedSaves-- > 0) throw IOException("No space left on device")
            session = it
        },
        http = server.http,
        now = { now },
        clock = { testScheduler.currentTime },
    )

    /** A server answering each path with its answers in turn, the last one again and again. */
    private fun server(vararg answers: Pair<String, List<() -> Answer>>): FakeServer {
        val left = answers.toMap().mapValues { (_, list) -> list.toMutableList() }
        return FakeServer { request ->
            val queue = checkNotNull(left[request.url.encodedPath]) { "Unexpected ${request.url}" }
            synchronized(queue) { if (queue.size > 1) queue.removeAt(0) else queue.first() }()
        }
    }

    private fun answer(code: Int, body: String = ""): () -> Answer = { Answer(code, body) }

    private fun body(request: Request): JsonObject =
        Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    private fun FakeServer.at(path: String) = synchronized(requests) { requests.filter { it.url.encodedPath == path } }

    @Test
    fun aCodeIsAskedOfTraktsOwnHostWithTheClientIdAlone() = runTest {
        val server = server(DEVICE_CODE to listOf(answer(200, CODE_JSON)))
        val asked = account(server).deviceCode()
        assertEquals("ABCD1234", asked.userCode)
        assertEquals(600, asked.expiresIn)
        val request = server.requests.single()
        assertEquals("auth.trakt.tv", request.url.host)
        assertEquals(JsonObject(mapOf("client_id" to Json.parseToJsonElement("\"id\""))), body(request))
        assertEquals("2", request.header("trakt-api-version"))
        assertEquals("id", request.header("trakt-api-key"))
        assertEquals("application/json", request.header("Content-Type"))
        assertTrue(request.header("User-Agent").orEmpty(), request.header("User-Agent").orEmpty().startsWith("Houselights/"))
        // Signing in carries no token.
        assertNull(request.header("Authorization"))
    }

    @Test
    fun theCodeIsPolledAtItsIntervalSlowerWhenAskedUntilApprovedThenTheAccountIsSaved() = runTest {
        val polledAt = Collections.synchronizedList(mutableListOf<Long>())
        val polls = listOf(400, 429, 400, 200).map { status ->
            {
                polledAt += testScheduler.currentTime
                Answer(status, if (status == 200) TOKENS_JSON else "")
            }
        }
        val server = server(DEVICE_TOKEN to polls, SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        val account = account(server)
        assertEquals(DeviceCodeEnd.APPROVED, account.awaitApproval(code))
        // Every 5 s, then 5 s slower once Trakt asks it.
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), polledAt)
        server.at(DEVICE_TOKEN).forEach { poll ->
            val sent = body(poll)
            assertEquals("dev-c0de", sent.text("code"))
            assertEquals("id", sent.text("client_id"))
            assertFalse("A build without a secret sends none", "client_secret" in sent)
        }
        // Saved at once; the name is read after, so the sheet closes without waiting for it.
        assertEquals(TraktSession("a1", "r1", expiresAt = now + WEEK), session)
        assertEquals(listOf(DEVICE_TOKEN), server.paths.distinct())
        assertEquals("lea", account.readName())
        assertEquals("lea", session?.username)
        // The name is read with the new token, from the API's host.
        val settings = server.at(SETTINGS).single()
        assertEquals("api.trakt.tv", settings.url.host)
        assertEquals("Bearer a1", settings.header("Authorization"))
    }

    @Test
    fun eachWayACodeEndsIsSaidAndConnectsNothing() = runTest {
        val ends = mapOf(404 to DeviceCodeEnd.INVALID, 409 to DeviceCodeEnd.USED, 410 to DeviceCodeEnd.EXPIRED, 418 to DeviceCodeEnd.DENIED)
        ends.forEach { (status, end) ->
            val server = server(DEVICE_TOKEN to listOf(answer(400), answer(status)))
            assertEquals("$status", end, account(server).awaitApproval(code))
            assertEquals("$status", 2, server.requests.size)
            assertNull("$status", session)
        }
    }

    @Test
    fun aCodeNobodyEntersEndsWithItsTime() = runTest {
        val server = server(DEVICE_TOKEN to listOf(answer(400)))
        assertEquals(DeviceCodeEnd.EXPIRED, account(server).awaitApproval(code.copy(expiresIn = 30)))
        assertEquals(6, server.requests.size)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun aPollTheNetworkLosesIsTriedAgainAtTheNextInterval() = runTest {
        val server = server(
            DEVICE_TOKEN to listOf({ throw UnknownHostException("auth.trakt.tv") }, answer(503), answer(200, TOKENS_JSON)),
            SETTINGS to listOf(answer(200, SETTINGS_JSON)),
        )
        assertEquals(DeviceCodeEnd.APPROVED, account(server).awaitApproval(code))
        assertEquals(3, server.at(DEVICE_TOKEN).size)
    }

    @Test
    fun aNetworkDownUntilTheCodeEndsIsSaidAsSuch() = runTest {
        val server = server(DEVICE_TOKEN to listOf({ throw UnknownHostException("auth.trakt.tv") }))
        val thrown = runCatching { account(server).awaitApproval(code.copy(expiresIn = 30)) }.exceptionOrNull()
        assertTrue("$thrown", thrown is UnknownHostException)
        assertNull(session)
    }

    @Test
    fun anAccountWhoseNameCannotBeReadIsConnectedAllTheSame() = runTest {
        val server = server(DEVICE_TOKEN to listOf(answer(200, TOKENS_JSON)), SETTINGS to listOf({ throw IOException("reset") }))
        val account = account(server)
        assertEquals(DeviceCodeEnd.APPROVED, account.awaitApproval(code))
        assertNull(account.readName())
        assertEquals(TraktSession("a1", "r1", expiresAt = now + WEEK), session)
    }

    @Test
    fun anApprovalThatCannotBeSavedConnectsNothing() = runTest {
        failedSaves = 1
        val account = account(server(DEVICE_TOKEN to listOf(answer(200, TOKENS_JSON))))
        assertTrue(runCatching { account.awaitApproval(code) }.exceptionOrNull() is IOException)
        assertNull(session)
        assertTrue(runCatching { account.call { it.settings() } }.exceptionOrNull() is TraktSignedOutException)
    }

    @Test
    fun aStatusTheFlowDoesNotKnowStopsPollingAtOnce() = runTest {
        val server = server(DEVICE_TOKEN to listOf(answer(400), answer(403)))
        val thrown = runCatching { account(server).awaitApproval(code) }.exceptionOrNull()
        assertEquals(403, (thrown as? HttpException)?.code())
        assertEquals(2, server.requests.size)
        assertNull(session)
    }

    @Test
    fun cancellingTheWaitStopsPolling() = runTest {
        // The first poll is held until the wait is cancelled.
        val held = CountDownLatch(1)
        val server = server(
            DEVICE_TOKEN to listOf({
                held.await(5, TimeUnit.SECONDS)
                Answer(400)
            }),
        )
        val waiting = launch { account(server).awaitApproval(code) }
        waitFor("a first poll") { server.requests.isNotEmpty() }
        waiting.cancel()
        held.countDown()
        advanceTimeBy(60_000)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aSecretInTheBuildIsSentWithTheCodeTheRenewalAndTheRevocationNeverTheToken() = runTest {
        val server = server(
            DEVICE_TOKEN to listOf(answer(200, TOKENS_JSON)),
            SETTINGS to listOf(answer(200, SETTINGS_JSON)),
            TOKEN to listOf(answer(200, RENEWED_JSON)),
            REVOKE to listOf(answer(200)),
        )
        val keys = TraktKeys("id", "s3cret")
        account(server, keys).awaitApproval(code)
        // Another launch, the token in its last day.
        session = session?.copy(expiresAt = now + HOUR)
        val account = account(server, keys)
        account.call { it.settings() }
        account.signOut()
        listOf(DEVICE_TOKEN, TOKEN, REVOKE).forEach { path ->
            assertEquals(path, "s3cret", body(server.at(path).single()).text("client_secret"))
            // Trakt's own host gets no bearer, a session or not.
            assertNull(path, server.at(path).single().header("Authorization"))
        }
    }

    @Test
    fun anotherRedirectUriIsTheOneRenewalNames() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(TOKEN to listOf(answer(200, RENEWED_JSON)), SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        account(server, TraktKeys("id", redirectUri = "urn:ietf:wg:oauth:2.0:oob")).call { it.settings() }
        assertEquals("urn:ietf:wg:oauth:2.0:oob", body(server.at(TOKEN).single()).text("redirect_uri"))
    }

    @Test
    fun aTokenAboutToEndIsRenewedBeforeTheCall() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(TOKEN to listOf(answer(200, RENEWED_JSON)), SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        assertEquals("lea", account(server).call { it.settings() }.user.username)
        assertEquals(listOf(TOKEN, SETTINGS), server.paths)
        val sent = body(server.at(TOKEN).single())
        assertEquals("r1", sent.text("refresh_token"))
        assertEquals("id", sent.text("client_id"))
        assertEquals("refresh_token", sent.text("grant_type"))
        assertEquals(TraktClient.REDIRECT_URI, sent.text("redirect_uri"))
        assertFalse("client_secret" in sent)
        assertEquals("Bearer a2", server.at(SETTINGS).single().header("Authorization"))
        // The refresh token is single-use: the new one replaces it.
        assertEquals(lea.copy(accessToken = "a2", refreshToken = "r2", expiresAt = now + WEEK), session)
    }

    @Test
    fun aTokenTraktRefusesIsRenewedOnceAndTheCallMadeAgain() = runTest {
        session = lea
        val server = server(SETTINGS to listOf(answer(401), answer(200, SETTINGS_JSON)), TOKEN to listOf(answer(200, RENEWED_JSON)))
        account(server).call { it.settings() }
        assertEquals(listOf(SETTINGS, TOKEN, SETTINGS), server.paths)
        assertEquals(listOf("Bearer a1", "Bearer a2"), server.at(SETTINGS).map { it.header("Authorization") })
    }

    @Test
    fun aRefreshTokenTraktRefusesEndsTheConnectionItsTokensRevoked() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(
            TOKEN to listOf(answer(400, """{"error":"invalid_grant","error_description":"session not found"}""")),
            REVOKE to listOf(answer(200)),
        )
        val account = account(server)
        val thrown = runCatching { account.call { it.settings() } }.exceptionOrNull()
        assertTrue("$thrown", thrown is TraktSignedOutException)
        assertEquals("a1", body(server.at(REVOKE).single()).text("token"))
        // Only the fact is kept.
        assertEquals(lea.copy(accessToken = "", refreshToken = "", expiresAt = now + HOUR, ended = true), session)
        // Ended, it asks Trakt nothing more until connected again.
        assertTrue(runCatching { account.call { it.settings() } }.exceptionOrNull() is TraktSignedOutException)
        assertEquals(listOf(TOKEN, REVOKE), server.paths)
    }

    @Test
    fun aRenewalRefusedForAnotherReasonKeepsTheConnection() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(
            TOKEN to listOf(answer(400, """{"error":"invalid_request"}""")),
            SETTINGS to listOf(answer(200, SETTINGS_JSON)),
        )
        account(server).call { it.settings() }
        assertEquals("Bearer a1", server.at(SETTINGS).single().header("Authorization"))
        assertEquals(lea.copy(expiresAt = now + HOUR), session)
    }

    @Test
    fun aSecondRefusalOnceRenewedIsThrownWithoutRenewingAgain() = runTest {
        session = lea
        val server = server(SETTINGS to listOf(answer(401)), TOKEN to listOf(answer(200, RENEWED_JSON)))
        val thrown = runCatching { account(server).call { it.settings() } }.exceptionOrNull()
        assertEquals(401, (thrown as? HttpException)?.code())
        assertEquals(listOf(SETTINGS, TOKEN, SETTINGS), server.paths)
        assertEquals(lea.copy(accessToken = "a2", refreshToken = "r2", expiresAt = now + WEEK), session)
    }

    @Test
    fun renewedTokensThatCannotBeSavedAreKeptAndSavedAtTheNextCall() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        failedSaves = 1
        val server = server(TOKEN to listOf(answer(200, RENEWED_JSON)), SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        val account = account(server)
        account.call { it.settings() }
        // Trakt spent r1: r2 lives in memory until it is saved.
        assertEquals("r1", session?.refreshToken)
        account.call { it.settings() }
        assertEquals(lea.copy(accessToken = "a2", refreshToken = "r2", expiresAt = now + WEEK), session)
        assertEquals(listOf(TOKEN, SETTINGS, SETTINGS), server.paths)
        assertEquals(List(2) { "Bearer a2" }, server.at(SETTINGS).map { it.header("Authorization") })
    }

    @Test
    fun aNameReadDuringARenewalKeepsTheNewTokens() = runTest {
        session = lea.copy(expiresAt = now + HOUR, username = null)
        val server = server(TOKEN to listOf(answer(200, RENEWED_JSON)), SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        assertEquals("lea", account(server).readName())
        assertEquals(lea.copy(accessToken = "a2", refreshToken = "r2", expiresAt = now + WEEK), session)
    }

    @Test
    fun signingOutDuringARenewalRevokesTheNewTokenAndStaysSignedOut() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(
            TOKEN to listOf(answer(200, RENEWED_JSON)),
            SETTINGS to listOf(answer(200, SETTINGS_JSON)),
            REVOKE to listOf(answer(200)),
        )
        val account = account(server)
        // The call renews first, holding the session until its tokens are kept.
        val calling = async { runCatching { account.call { it.settings() } } }
        runCurrent()
        account.signOut()
        calling.await()
        assertEquals("a2", body(server.at(REVOKE).single()).text("token"))
        assertNull(session)
    }

    @Test
    fun aRenewalThatCannotReachTraktKeepsTheTokenWhileItLasts() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(TOKEN to listOf({ throw IOException("reset") }), SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        account(server).call { it.settings() }
        assertEquals("Bearer a1", server.at(SETTINGS).single().header("Authorization"))
        assertEquals(lea.copy(expiresAt = now + HOUR), session)
    }

    @Test
    fun callsMadeTogetherRenewOnce() = runTest {
        session = lea.copy(expiresAt = now + HOUR)
        val server = server(TOKEN to listOf(answer(200, RENEWED_JSON)), SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        val account = account(server)
        List(3) { async { account.call { it.settings() } } }.awaitAll()
        assertEquals(1, server.at(TOKEN).size)
        assertEquals(List(3) { "Bearer a2" }, server.at(SETTINGS).map { it.header("Authorization") })
    }

    @Test
    fun signingOutRevokesTheTokenThenForgetsTheAccount() = runTest {
        session = lea
        val server = server(REVOKE to listOf(answer(200)))
        account(server).signOut()
        val sent = body(server.requests.single())
        assertEquals("a1", sent.text("token"))
        assertEquals("id", sent.text("client_id"))
        assertNull(session)
    }

    @Test
    fun signingOutOfflineForgetsTheAccountAllTheSame() = runTest {
        session = lea
        account(server(REVOKE to listOf({ throw UnknownHostException("auth.trakt.tv") }))).signOut()
        assertNull(session)
    }

    @Test
    fun withoutAnAccountNothingIsAsked() = runTest {
        val server = server(SETTINGS to listOf(answer(200, SETTINGS_JSON)))
        assertTrue(runCatching { account(server).call { it.settings() } }.exceptionOrNull() is TraktSignedOutException)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theKeysNeverShowTheSecret() {
        assertFalse(TraktKeys("id", "s3cret").toString().contains("s3cret"))
        assertFalse(lea.toString().contains("a1") || lea.toString().contains("r1"))
        assertTrue(TraktKeys("id").available)
        assertFalse(TraktKeys("").available)
    }

    private companion object {
        val KEYS = TraktKeys("id")
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY

        const val DEVICE_CODE = "/oauth/device/code"
        const val DEVICE_TOKEN = "/oauth/device/token"
        const val TOKEN = "/oauth/token"
        const val REVOKE = "/oauth/revoke"
        const val SETTINGS = "/users/settings"

        const val CODE_JSON = """{"device_code":"dev-c0de","user_code":"ABCD1234","verification_url":"https://trakt.tv/activate",""" +
            """"expires_in":600,"interval":5}"""
        const val TOKENS_JSON = """{"access_token":"a1","token_type":"bearer","expires_in":604800,"refresh_token":"r1",""" +
            """"scope":"public","created_at":1699990000}"""
        const val RENEWED_JSON = """{"access_token":"a2","token_type":"bearer","expires_in":604800,"refresh_token":"r2",""" +
            """"scope":"public","created_at":1700000000}"""
        const val SETTINGS_JSON = """{"user":{"username":"lea","private":false,"name":"Léa","vip":false,"ids":{"slug":"lea"}},""" +
            """"account":{"timezone":"Europe/Paris"}}"""
    }
}
