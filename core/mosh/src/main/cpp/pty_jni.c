#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>

/**
 * Forks a child process with a PTY.
 *
 * Argv and env are extracted from JNI BEFORE the fork, because JNI
 * functions are not async-signal-safe and must not be called in the
 * child process after fork.
 *
 * @param path   Executable path (mosh-client binary)
 * @param argv   Command-line arguments
 * @param env    Environment variables (KEY=VALUE)
 * @param rows   Initial terminal rows
 * @param cols   Initial terminal columns
 * @return int[2] = {masterFd, childPid}, or null on failure
 */
JNIEXPORT jintArray JNICALL
Java_sh_haven_core_mosh_PtyHelper_nativeForkPty(
    JNIEnv *env, jobject thiz,
    jstring path, jobjectArray argv, jobjectArray jenv,
    jint rows, jint cols)
{
    /* --- Extract all JNI data BEFORE fork --- */

    const char *c_path_tmp = (*env)->GetStringUTFChars(env, path, NULL);
    if (!c_path_tmp) return NULL;
    char *c_path = strdup(c_path_tmp);
    (*env)->ReleaseStringUTFChars(env, path, c_path_tmp);

    int argc = (*env)->GetArrayLength(env, argv);
    char **c_argv = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, argv, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        c_argv[i] = strdup(u);
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    c_argv[argc] = NULL;

    int envc = (*env)->GetArrayLength(env, jenv);
    char **c_env = calloc(envc + 1, sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jenv, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        c_env[i] = strdup(u);
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }

    /* --- Fork --- */

    int master;
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    pid_t pid = forkpty(&master, NULL, NULL, &ws);

    if (pid < 0) {
        /* Fork failed — clean up */
        free(c_path);
        for (int i = 0; i < argc; i++) free(c_argv[i]);
        free(c_argv);
        for (int i = 0; i < envc; i++) free(c_env[i]);
        free(c_env);
        return NULL;
    }

    if (pid == 0) {
        /* Child — NO JNI calls allowed here */

        /* Apply environment variables */
        for (int i = 0; i < envc; i++) {
            putenv(c_env[i]);
            /* Don't free — putenv takes ownership */
        }

        execv(c_path, c_argv);
        /* If execv returns, it failed */
        _exit(127);
    }

    /* Parent — clean up C strings (child has its own copies after fork) */
    free(c_path);
    for (int i = 0; i < argc; i++) free(c_argv[i]);
    free(c_argv);
    for (int i = 0; i < envc; i++) free(c_env[i]);
    free(c_env);

    /* Return [masterFd, childPid] */
    jintArray result = (*env)->NewIntArray(env, 2);
    jint buf[2] = { master, pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

/**
 * Send TIOCSWINSZ to resize the PTY, then send SIGWINCH to the child.
 *
 * TIOCSWINSZ on the master fd sets the terminal size and should send
 * SIGWINCH to the foreground process group. We also send SIGWINCH
 * explicitly to the child PID — mosh-client relies on SIGWINCH to
 * propagate resize to the mosh-server over UDP.
 */
JNIEXPORT void JNICALL
Java_sh_haven_core_mosh_PtyHelper_nativeResize(
    JNIEnv *env, jobject thiz,
    jint masterFd, jint childPid, jint rows, jint cols)
{
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    ioctl(masterFd, TIOCSWINSZ, &ws);
    if (childPid > 0) {
        kill((pid_t)childPid, SIGWINCH);
    }
}

/**
 * Non-blocking waitpid. Returns [pid, status] or null if not exited yet.
 */
JNIEXPORT jintArray JNICALL
Java_sh_haven_core_mosh_PtyHelper_nativeWaitPid(
    JNIEnv *env, jobject thiz,
    jint pid)
{
    int status = 0;
    pid_t result = waitpid((pid_t)pid, &status, WNOHANG);
    if (result <= 0) return NULL;

    jintArray arr = (*env)->NewIntArray(env, 2);
    jint buf[2] = { result, status };
    (*env)->SetIntArrayRegion(env, arr, 0, 2, buf);
    return arr;
}
