package com.nexuslink.protocol.db;

import java.util.Locale;

/**
 * Generates the {@code mvn} command a user should run in their own terminal to fetch a JDBC driver
 * jar, per shell.
 *
 * <h2>Why hand the user a command instead of just downloading it</h2>
 * In a locked-down organisation the machine's Maven is already configured correctly — the internal
 * Artifactory mirror, the credentials, the proxy, the corporate CA — in {@code ~/.m2/settings.xml}
 * and the developer's shell environment. Reproducing all of that inside the app is guesswork that
 * fails in exactly the environments that need it most. {@link ExternalDriverLoader} still tries the
 * direct download because it works for many users, but when it can't, running one {@code mvn}
 * command in a shell that is already known to work is the reliable path: Maven resolves the jar
 * through the org's own mirror, and the user then attaches the resulting file.
 *
 * <p>{@code dependency:copy} is used rather than plain {@code dependency:get} because it also
 * places the jar in a known output directory, so the user has a concrete file to point at.
 */
public final class MavenCommandHelp {

    /** The shells we generate commands for. Quoting and home-directory syntax differ across them. */
    public enum Shell {
        BASH("Linux / macOS (bash, zsh)"),
        POWERSHELL("Windows PowerShell"),
        CMD("Windows Command Prompt (cmd.exe)");

        private final String label;

        Shell(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private MavenCommandHelp() {}

    /** The shell most likely in front of this user, used to pick the default tab. */
    public static Shell detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? Shell.POWERSHELL : Shell.BASH;
    }

    /** The directory NexusLink scans for driver jars, written in {@code shell}'s own syntax. */
    public static String driverDirectory(Shell shell) {
        return switch (shell) {
            case BASH -> "$HOME/.nexuslink/drivers";
            case POWERSHELL -> "$env:USERPROFILE\\.nexuslink\\drivers";
            case CMD -> "%USERPROFILE%\\.nexuslink\\drivers";
        };
    }

    /**
     * The command to download {@code group:artifact:version} into {@link ExternalDriverLoader#DRIVER_DIR},
     * ready to paste into {@code shell}.
     */
    public static String downloadCommand(String mavenCoords, Shell shell) {
        String dir = driverDirectory(shell);
        // PowerShell parses a bare -Dkey=value inconsistently across versions; quoting the whole
        // argument is the form that works everywhere.
        return switch (shell) {
            case POWERSHELL -> "mvn dependency:copy \"-Dartifact=" + mavenCoords + "\" "
                    + "\"-DoutputDirectory=" + dir + "\"";
            case BASH, CMD -> "mvn dependency:copy -Dartifact=" + mavenCoords + " "
                    + "-DoutputDirectory=" + dir;
        };
    }

    /** Creates the driver directory first, for users who have never downloaded a driver before. */
    public static String makeDirectoryCommand(Shell shell) {
        return switch (shell) {
            case BASH -> "mkdir -p " + driverDirectory(Shell.BASH);
            case POWERSHELL -> "New-Item -ItemType Directory -Force -Path " + driverDirectory(Shell.POWERSHELL);
            case CMD -> "if not exist \"" + driverDirectory(Shell.CMD) + "\" mkdir \""
                    + driverDirectory(Shell.CMD) + "\"";
        };
    }

    /** The full copy-paste block: create the directory, then download the jar. */
    public static String script(String mavenCoords, Shell shell) {
        return makeDirectoryCommand(shell) + System.lineSeparator() + downloadCommand(mavenCoords, shell);
    }

    /** Step-by-step instructions shown next to the command in the driver manager. */
    public static String instructions(String driverName, String mavenCoords, Shell shell) {
        String jarName = jarFileName(mavenCoords);
        return """
                Getting the %s driver using your own Maven setup

                1. Open %s.
                2. Run the commands below. Maven uses the settings your organisation already gave you
                   in ~/.m2/settings.xml, so the jar comes from your internal Artifactory/Nexus mirror
                   with your credentials and proxy — no direct internet access needed.
                3. The jar lands in %s as %s.
                4. Come back here, click "Add from JAR…", and select that file.

                The driver is registered immediately — there is no need to restart NexusLink.

                If Maven reports that it cannot find the artifact, your mirror may not carry this
                version. Ask which version is approved internally and substitute it in the command;
                then use "Add from JAR…" exactly the same way.
                """.formatted(driverName, shell.label(), driverDirectory(shell), jarName);
    }

    /** The file name Maven will produce for {@code group:artifact:version}. */
    public static String jarFileName(String mavenCoords) {
        String[] parts = mavenCoords == null ? new String[0] : mavenCoords.split(":");
        return parts.length == 3 ? parts[1] + "-" + parts[2] + ".jar" : "the driver jar";
    }
}
