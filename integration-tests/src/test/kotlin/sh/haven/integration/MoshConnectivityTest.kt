package sh.haven.integration

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sh.haven.mosh.crypto.MoshCrypto
import sh.haven.mosh.network.MoshConnection
import sh.haven.mosh.proto.Transportinstruction.Instruction as TransportInstruction

/**
 * Low-level mosh connectivity test — bypasses the transport to test
 * the connection + crypto layer directly.
 */
class MoshConnectivityTest {

    @Before
    fun setUp() {
        TestServer.requireConfigured()
    }

    @Test
    fun `mosh key parses to valid AES-128 key`() {
        val session = TestServer.sshConnect()
        try {
            val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
            val output = result.stdout + "\n" + result.stderr
            val connectLine = output.lines().first { it.startsWith("MOSH CONNECT") }
            val key = connectLine.split(" ")[3]

            println("Key: $key (${key.length} chars)")
            val crypto = MoshCrypto(key)
            assertNotNull("Crypto should initialise", crypto)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `mosh connection sends and receives first packet`() {
        val session = TestServer.sshConnect()
        try {
            val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
            val output = result.stdout + "\n" + result.stderr
            val connectLine = output.lines().first { it.startsWith("MOSH CONNECT") }
            val parts = connectLine.split(" ")
            val moshPort = parts[2].toInt()
            val moshKey = parts[3]

            println("Connecting to ${TestServer.host}:$moshPort")

            val crypto = MoshCrypto(moshKey)
            val conn = MoshConnection(TestServer.host, moshPort, crypto)

            // Send an empty keepalive instruction (what the transport sends initially)
            val keepalive = TransportInstruction.newBuilder().apply {
                oldNum = 0
                newNum = 0
                ackNum = 0
            }.build()
            conn.sendInstruction(keepalive)
            println("Sent initial keepalive")

            // Try to receive server's response
            val response = conn.receiveInstruction(5000)
            assertNotNull("Should receive response from mosh-server within 5s", response)
            println("Received instruction: oldNum=${response!!.oldNum} newNum=${response.newNum} ackNum=${response.ackNum}")
            assertTrue("Server should have newNum > 0", response.newNum > 0)

            conn.close()
        } finally {
            session.disconnect()
        }
    }
}
