package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenRepositoryConfigTest {

    @Test
    void artifactPathFollowsMavenLayout() {
        assertEquals("com/oracle/database/jdbc/ojdbc11/23.4.0.24.05/ojdbc11-23.4.0.24.05.jar",
                MavenRepositoryConfig.artifactPath("com.oracle.database.jdbc", "ojdbc11", "23.4.0.24.05"));
    }

    @Test
    void artifactUrlIsJoinedToTheBaseUrl() {
        var repo = new MavenRepositoryConfig("https://artifactory.corp/artifactory/maven-remote", null, null, null);
        assertEquals("https://artifactory.corp/artifactory/maven-remote/org/x/x/1.0/x-1.0.jar",
                repo.artifactUrl("org.x", "x", "1.0"));
    }

    @Test
    void basicAuthIsUsedWhenAUsernameIsConfigured() {
        var repo = new MavenRepositoryConfig(MavenRepositoryConfig.CENTRAL, "alice", "s3cret", null);
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, repo.authorizationHeader().orElseThrow());
    }

    @Test
    void tokenTakesPrecedenceOverBasicAuth() {
        var repo = new MavenRepositoryConfig(MavenRepositoryConfig.CENTRAL, "alice", "s3cret", "abc123");
        assertEquals("Bearer abc123", repo.authorizationHeader().orElseThrow());
    }

    @Test
    void anonymousRepositorySendsNoAuthorization() {
        var repo = new MavenRepositoryConfig(MavenRepositoryConfig.CENTRAL, null, "", null);
        assertTrue(repo.authorizationHeader().isEmpty());
    }

    @Test
    void displayNameDistinguishesCentralFromAMirror() {
        var central = new MavenRepositoryConfig(MavenRepositoryConfig.CENTRAL, null, null, null);
        assertFalse(central.isCustomRepository());
        assertEquals("Maven Central", central.displayName());

        var mirror = new MavenRepositoryConfig("https://artifactory.corp/artifactory/maven", null, null, null);
        assertTrue(mirror.isCustomRepository());
        assertEquals("artifactory.corp", mirror.displayName());
    }

    @Test
    void systemPropertiesWinOverEveryOtherSource() {
        System.setProperty("nexuslink.maven.repoUrl", "https://artifactory.corp/artifactory/maven/");
        System.setProperty("nexuslink.maven.token", "tok");
        try {
            var repo = MavenRepositoryConfig.resolve();
            assertEquals("https://artifactory.corp/artifactory/maven", repo.baseUrl(), "trailing slash trimmed");
            assertEquals("Bearer tok", repo.authorizationHeader().orElseThrow());
        } finally {
            System.clearProperty("nexuslink.maven.repoUrl");
            System.clearProperty("nexuslink.maven.token");
        }
    }

    @Test
    void settingsXmlMirrorAndMatchingServerAreParsed() {
        String xml = """
                <settings>
                  <!-- <mirror><id>ignored</id><url>http://commented.out</url></mirror> -->
                  <mirrors>
                    <mirror>
                      <id>corp</id>
                      <mirrorOf>*</mirrorOf>
                      <url>https://artifactory.corp/artifactory/maven-virtual/</url>
                    </mirror>
                  </mirrors>
                  <servers>
                    <server><id>other</id><username>nope</username><password>nope</password></server>
                    <server><id>corp</id><username>alice</username><password>s3cret</password></server>
                  </servers>
                </settings>
                """;
        var settings = MavenRepositoryConfig.MavenSettings.parse(xml);
        assertEquals("https://artifactory.corp/artifactory/maven-virtual", settings.mirrorUrl());
        assertEquals("alice", settings.username());
        assertEquals("s3cret", settings.password());
    }

    @Test
    void encryptedSettingsXmlPasswordIsIgnored() {
        String xml = """
                <settings>
                  <mirrors><mirror><id>corp</id><url>https://artifactory.corp/repo</url></mirror></mirrors>
                  <servers><server><id>corp</id><username>alice</username><password>{aBcD/eF=}</password></server></servers>
                </settings>
                """;
        var settings = MavenRepositoryConfig.MavenSettings.parse(xml);
        assertEquals("alice", settings.username());
        assertEquals(null, settings.password());
    }

    @Test
    void settingsXmlWithoutAMirrorYieldsNothing() {
        var settings = MavenRepositoryConfig.MavenSettings.parse("<settings><servers/></settings>");
        assertEquals(null, settings.mirrorUrl());
    }
}
