package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    /** This fork's version: the upstream version it's based on, then -sw.N, N counting this fork's builds. */
    static final String VERSION = "2.2.8-sw.7";
    static final int VERSION_CODE = 182;
    /** The upstream Buddy release this build is based on (see Updates and UPSTREAM.md). */
    static final String UPSTREAM = "2.2.8";

    private BuildInfo() {
    }
}
