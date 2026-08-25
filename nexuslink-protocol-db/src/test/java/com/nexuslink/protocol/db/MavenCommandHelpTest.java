package com.nexuslink.protocol.db;

import com.nexuslink.protocol.db.MavenCommandHelp.Shell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCommandHelpTest {

    private static final String COORDS = "com.oracle.database.jdbc:ojdbc11:23.4.0.24.05";

    @Test
    void bashCommandUsesUnixHomeAndPathSeparators() {
        String cmd = MavenCommandHelp.downloadCommand(COORDS, Shell.BASH);
        assertEquals("mvn dependency:copy -Dartifact=" + COORDS
                + " -DoutputDirectory=$HOME/.nexuslink/drivers", cmd);
    }

    @Test
    void powershellQuotesEachDefineSoItParsesConsistently() {
        String cmd = MavenCommandHelp.downloadCommand(COORDS, Shell.POWERSHELL);
        assertTrue(cmd.contains("\"-Dartifact=" + COORDS + "\""), cmd);
        assertTrue(cmd.contains("$env:USERPROFILE\\.nexuslink\\drivers"), cmd);
    }

    @Test
    void cmdUsesPercentStyleEnvironmentVariables() {
        String cmd = MavenCommandHelp.downloadCommand(COORDS, Shell.CMD);
        assertTrue(cmd.contains("%USERPROFILE%\\.nexuslink\\drivers"), cmd);
        assertTrue(cmd.startsWith("mvn dependency:copy -Dartifact="), cmd);
    }

    @Test
    void everyShellGetsAMatchingMakeDirectoryCommand() {
        assertTrue(MavenCommandHelp.makeDirectoryCommand(Shell.BASH).startsWith("mkdir -p "));
        assertTrue(MavenCommandHelp.makeDirectoryCommand(Shell.POWERSHELL).startsWith("New-Item "));
        assertTrue(MavenCommandHelp.makeDirectoryCommand(Shell.CMD).startsWith("if not exist "));
    }

    @Test
    void scriptCombinesDirectoryCreationAndDownload() {
        String script = MavenCommandHelp.script(COORDS, Shell.BASH);
        assertTrue(script.contains("mkdir -p"), script);
        assertTrue(script.contains("dependency:copy"), script);
    }

    @Test
    void jarFileNameMatchesWhatMavenWillProduce() {
        assertEquals("ojdbc11-23.4.0.24.05.jar", MavenCommandHelp.jarFileName(COORDS));
        assertEquals("the driver jar", MavenCommandHelp.jarFileName(null));
        assertEquals("the driver jar", MavenCommandHelp.jarFileName("not-coordinates"));
    }

    @Test
    void instructionsNameTheDriverTheShellAndTheResultingFile() {
        String text = MavenCommandHelp.instructions("Oracle", COORDS, Shell.POWERSHELL);
        assertTrue(text.contains("Oracle"), text);
        assertTrue(text.contains("Windows PowerShell"), text);
        assertTrue(text.contains("ojdbc11-23.4.0.24.05.jar"), text);
        assertTrue(text.contains("settings.xml"), "must explain that the org's own Maven config is used");
        assertTrue(text.contains("no need to restart"), "dynamic load is the point");
    }

    @Test
    void detectPicksAShellForThisPlatform() {
        Shell detected = MavenCommandHelp.detect();
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        assertEquals(windows ? Shell.POWERSHELL : Shell.BASH, detected);
    }
}
