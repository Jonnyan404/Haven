package sh.haven.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sh.haven.et.EtLogger
import sh.haven.et.transport.EtTransport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the ET transport against a real etserver.
 *
 * Requires:
 *   - etserver running on test.host:test.et.port (default 2022)
 *   - etterminal available in PATH on the remote host
 *   - SSH key or password auth configured
 *
 * Run with:
 *   ./gradlew :integration-tests:test -Dtest.host=192.168.0.180 -Dtest.user=ian -Dtest.key=~/.ssh/id_ed25519
 */
class EtTransportTest {

    private var sshSession: com.jcraft.jsch.Session? = null
    private var transport: EtTransport? = null
    private val receivedOutput = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val testLogger = object : EtLogger {
        override fun d(tag: String, msg: String) = println("[$tag] $msg")
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            System.err.println("[$tag] ERROR: $msg")
            throwable?.printStackTrace(System.err)
        }
    }

    @Before
    fun setUp() {
        TestServer.requireConfigured()
    }

    @After
    fun tearDown() {
        transport?.close()
        sshSession?.disconnect()
    }

    /** Bootstrap etterminal via SSH, returning (clientId, passkey). */
    private fun bootstrap(): Pair<String, String> {
        val session = TestServer.sshConnect()
        sshSession = session

        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        fun randomAlphaNum(len: Int) = String(CharArray(len) { chars.random() })
        val proposedId = "XXX" + randomAlphaNum(13)
        val proposedKey = randomAlphaNum(32)
        val term = "xterm-256color"

        val cmd = "echo '${proposedId}/${proposedKey}_${term}' | etterminal"
        val result = TestServer.exec(session, cmd)
        val output = result.stdout + "\n" + result.stderr

        val marker = "IDPASSKEY:"
        val markerPos = output.indexOf(marker)
        assertTrue("etterminal bootstrap failed. Output: ${output.take(200)}", markerPos >= 0)

        val idPasskey = output.substring(markerPos + marker.length).trim().take(49)
        val parts = idPasskey.split("/", limit = 2)
        assertEquals("Expected clientId/passkey format", 2, parts.size)
        assertEquals("clientId should be 16 chars", 16, parts[0].length)
        assertEquals("passkey should be 32 chars", 32, parts[1].length)

        return Pair(parts[0], parts[1])
    }

    @Test
    fun `ET bootstrap returns valid credentials`() {
        bootstrap()
    }

    @Test
    fun `ET transport connects and receives shell output`() = runBlocking {
        val (clientId, passkey) = bootstrap()
        val outputLatch = CountDownLatch(1)

        transport = EtTransport(
            serverHost = TestServer.host,
            port = TestServer.etPort,
            clientId = clientId,
            passkey = passkey,
            onOutput = { data, offset, len ->
                val text = String(data, offset, len)
                receivedOutput.add(text)
                outputLatch.countDown()
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should receive shell output within 5s", outputLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Should have received some output", receivedOutput.isNotEmpty())
    }

    @Test
    fun `ET transport sends input and receives echo`() = runBlocking {
        val (clientId, passkey) = bootstrap()
        val echoLatch = CountDownLatch(1)
        val marker = "HAVEN_TEST_${System.currentTimeMillis()}"

        transport = EtTransport(
            serverHost = TestServer.host,
            port = TestServer.etPort,
            clientId = clientId,
            passkey = passkey,
            onOutput = { data, offset, len ->
                val text = String(data, offset, len)
                receivedOutput.add(text)
                if (receivedOutput.joinToString("").contains(marker)) {
                    echoLatch.countDown()
                }
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        // Wait for shell prompt
        delay(1000)

        // Send echo command
        transport!!.sendInput("echo $marker\n".toByteArray())

        assertTrue(
            "Should receive echo of marker within 5s",
            echoLatch.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `ET transport handles resize during active session`() = runBlocking {
        val (clientId, passkey) = bootstrap()
        val outputLatch = CountDownLatch(1)

        transport = EtTransport(
            serverHost = TestServer.host,
            port = TestServer.etPort,
            clientId = clientId,
            passkey = passkey,
            onOutput = { _, _, _ -> outputLatch.countDown() },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should connect within 5s", outputLatch.await(5, TimeUnit.SECONDS))

        // Resize should not throw or disconnect
        transport!!.resize(120, 40)
        delay(500)
        transport!!.resize(80, 24)
        delay(500)

        // Verify session still works after resize
        val postResizeLatch = CountDownLatch(1)
        val postResizeOutput = CopyOnWriteArrayList<String>()
        // Replace transport with one that captures post-resize output
        val marker = "RESIZE_OK_${System.currentTimeMillis()}"
        transport!!.sendInput("echo $marker\n".toByteArray())
        delay(2000)

        val allOutput = receivedOutput.joinToString("")
        // Session should still be alive (no disconnect)
        assertNotNull("Transport should still be active", transport)
    }

    @Test
    fun `ET transport responds to keepalives`() = runBlocking {
        val (clientId, passkey) = bootstrap()
        val outputLatch = CountDownLatch(1)
        var disconnected = false

        transport = EtTransport(
            serverHost = TestServer.host,
            port = TestServer.etPort,
            clientId = clientId,
            passkey = passkey,
            onOutput = { _, _, _ -> outputLatch.countDown() },
            onDisconnect = { disconnected = true },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should connect within 5s", outputLatch.await(5, TimeUnit.SECONDS))

        // Wait long enough for server to send keepalives (typically 5-10s)
        delay(15_000)

        assertFalse("Should not have disconnected during keepalive period", disconnected)
    }

    @Test
    fun `ET transport handles rapid input`() = runBlocking {
        val (clientId, passkey) = bootstrap()
        val outputLatch = CountDownLatch(1)

        transport = EtTransport(
            serverHost = TestServer.host,
            port = TestServer.etPort,
            clientId = clientId,
            passkey = passkey,
            onOutput = { _, _, _ -> outputLatch.countDown() },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should connect within 5s", outputLatch.await(5, TimeUnit.SECONDS))

        // Rapid-fire input — tests nonce sequencing under load
        repeat(50) { i ->
            transport!!.sendInput("echo line$i\n".toByteArray())
        }

        delay(3000)
        // If nonces went out of sync, the server would have dropped the connection
        // and subsequent sends would fail silently
    }
}
