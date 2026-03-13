package sh.haven.core.mosh

/**
 * JNI bridge for PTY operations used by mosh-client.
 * Native implementation in pty_jni.c.
 */
object PtyHelper {
    init {
        System.loadLibrary("mosh-pty")
    }

    /**
     * Fork a child process with a new PTY.
     * @return int[2] = {masterFd, childPid}, or null on failure
     */
    external fun nativeForkPty(
        path: String,
        argv: Array<String>,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray?

    /**
     * Resize the PTY window and send SIGWINCH to the child process.
     */
    external fun nativeResize(masterFd: Int, childPid: Int, rows: Int, cols: Int)

    /**
     * Non-blocking waitpid to get child exit status.
     * @return int[2] = {pid, status}, or null if child hasn't exited.
     *         Decode status: if (status & 0x7f) == 0: exited with (status >> 8) & 0xff
     *                        else: killed by signal (status & 0x7f)
     */
    external fun nativeWaitPid(pid: Int): IntArray?
}
