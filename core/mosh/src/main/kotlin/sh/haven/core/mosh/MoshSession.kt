package sh.haven.core.mosh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.transport.MoshTransport
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "MoshSession"

/**
 * Bridges a mosh transport session to the terminal emulator.
 *
 * Parallel to ReticulumSession: manages a transport instance and
 * shuttles terminal data between the mosh server and termlib.
 * No PTY or native code — the pure Kotlin MoshTransport handles
 * UDP, encryption, and protocol framing in-process.
 */
class MoshSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val serverIp: String,
    private val moshPort: Int,
    private val moshKey: String,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
    private val initialCols: Int = 80,
    private val initialRows: Int = 24,
    private val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private val startTime = System.currentTimeMillis()
    private val logger = object : MoshLogger {
        override fun d(tag: String, msg: String) {
            Log.d(tag, msg)
            verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$tag] $msg")
        }
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
            verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$tag] ERROR: $msg${throwable?.let { " (${it.message})" } ?: ""}")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: MoshTransport? = null

    /**
     * Start the mosh transport: opens UDP socket, begins send/receive loops.
     */
    fun start() {
        if (closed) return
        Log.d(TAG, "Starting mosh transport for $sessionId: $serverIp:$moshPort")

        val t = MoshTransport(
            serverIp = serverIp,
            port = moshPort,
            key = moshKey,
            onOutput = { data, offset, len ->
                if (!closed) {
                    onDataReceived(data, offset, len)
                }
            },
            onDisconnect = { cleanExit ->
                if (!closed) {
                    Log.d(TAG, "Transport disconnected for $sessionId (clean=$cleanExit)")
                    onDisconnected?.invoke(cleanExit)
                }
            },
            logger = logger,
            initialCols = initialCols,
            initialRows = initialRows,
        )
        transport = t
        t.start(scope)
    }

    /**
     * Send keyboard input to the mosh server.
     * Safe to call from any thread.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        transport?.sendInput(data)
    }

    /**
     * Notify the mosh server of a terminal resize.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        transport?.resize(cols, rows)
    }

    /**
     * Detach without closing the transport.
     * The mosh server keeps the session alive; we can reattach later.
     */
    fun detach() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }

    /** Drain captured transport logs. Returns null if verbose logging was not enabled. */
    fun drainTransportLog(): String? {
        val buf = verboseBuffer ?: return null
        if (buf.isEmpty()) return null
        val sb = StringBuilder()
        while (true) {
            val line = buf.poll() ?: break
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    override fun close() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }
}
