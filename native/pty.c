// Buddy's pseudo-terminal: opens a pty, starts a program on it, resizes it, waits for it.
// Called from app.buddy.assistant.Pty. Build: native/build.sh → app/src/main/jniLibs/arm64-v8a/libbuddypty.so
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char **strings(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void freeStrings(char **s) {
    if (s == NULL) return;
    for (char **p = s; *p; p++) free(*p);
    free(s);
}

// Returns {masterFd, pid}, or throws IOException.
JNIEXPORT jintArray JNICALL Java_app_buddy_assistant_Pty_start(JNIEnv *env, jclass cls, jstring jpath,
        jobjectArray jargs, jobjectArray jenv, jstring jcwd, jint rows, jint cols) {
    int master = posix_openpt(O_RDWR | O_CLOEXEC | O_NOCTTY);
    if (master < 0 || grantpt(master) || unlockpt(master)) goto fail;
    char slaveName[64];
    if (ptsname_r(master, slaveName, sizeof slaveName)) goto fail;

    // UTF-8, and erase is DEL like most terminals
    struct termios tios;
    tcgetattr(master, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(master, TCSANOW, &tios);
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(master, TIOCSWINSZ, &ws);

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    char **argv = strings(env, jargs);
    char **envp = strings(env, jenv);

    pid_t pid = fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        freeStrings(argv);
        freeStrings(envp);
        goto fail;
    }
    if (pid == 0) {
        // child: new session with the pty as its controlling terminal
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);
        close(master);
        setsid();
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        // close everything else the app had open
        for (int fd = 3; fd < 1024; fd++) close(fd);
        if (cwd) chdir(cwd);
        execve(path, argv, envp ? envp : (char *[]) {NULL});
        _exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    freeStrings(argv);
    freeStrings(envp);

    jintArray result = (*env)->NewIntArray(env, 2);
    jint vals[2] = {master, pid};
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;

fail:;
    int err = errno;
    if (master >= 0) close(master);
    jclass ioe = (*env)->FindClass(env, "java/io/IOException");
    (*env)->ThrowNew(env, ioe, strerror(err));
    return NULL;
}

JNIEXPORT void JNICALL Java_app_buddy_assistant_Pty_resize(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(fd, TIOCSWINSZ, &ws);
}

// Blocks until the program exits; returns its exit code (or 128 + signal).
JNIEXPORT jint JNICALL Java_app_buddy_assistant_Pty_waitFor(JNIEnv *env, jclass cls, jint pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL Java_app_buddy_assistant_Pty_hangup(JNIEnv *env, jclass cls, jint pid) {
    kill(-pid, SIGHUP);
    kill(pid, SIGHUP);
}

JNIEXPORT void JNICALL Java_app_buddy_assistant_Pty_close(JNIEnv *env, jclass cls, jint fd) {
    close(fd);
}
