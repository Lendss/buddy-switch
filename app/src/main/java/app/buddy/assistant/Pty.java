package app.buddy.assistant;

/** The terminal's pseudo-terminal, implemented in native/pty.c (libbuddypty.so). */
final class Pty {
    static {
        System.loadLibrary("buddypty");
    }

    private Pty() {
    }

    /** Starts {@code path} on a new pty; returns {masterFd, pid}. */
    static native int[] start(String path, String[] args, String[] env, String cwd, int rows, int cols)
            throws java.io.IOException;

    static native void resize(int fd, int rows, int cols);

    /** Blocks until the program exits; returns its exit code. */
    static native int waitFor(int pid);

    static native void hangup(int pid);

    static native void close(int fd);
}
