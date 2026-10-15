package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.1.7";
    static final int VERSION_CODE = 157;

    private BuildInfo() {
    }
}
