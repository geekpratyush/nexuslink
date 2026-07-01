package com.nexuslink.protocol.azure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline unit tests for {@link AzureConnectionString}. No network or SDK involved: every case is a
 * pure string-parse assertion over the recognised keys, derived endpoints, and redaction.
 */
class AzureConnectionStringTest {

    @Test
    void parsesFullSharedKeyString() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "DefaultEndpointsProtocol=https;AccountName=acc;AccountKey=abc123==;"
                        + "EndpointSuffix=core.windows.net");

        assertFalse(cs.isDevelopment());
        assertEquals("https", cs.protocol());
        assertEquals("acc", cs.accountName());
        assertEquals("abc123==", cs.accountKey());
        assertEquals("core.windows.net", cs.endpointSuffix());
        assertNull(cs.sharedAccessSignature());
        assertEquals("https://acc.blob.core.windows.net", cs.blobEndpoint());
        assertEquals("https://acc.queue.core.windows.net", cs.queueEndpoint());
        assertEquals("https://acc.table.core.windows.net", cs.tableEndpoint());
        assertEquals("https://acc.file.core.windows.net", cs.fileEndpoint());
    }

    @Test
    void defaultsProtocolAndSuffixWhenOmitted() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "AccountName=acc;AccountKey=k==");

        assertEquals("https", cs.protocol());
        assertEquals("core.windows.net", cs.endpointSuffix());
        assertEquals("https://acc.blob.core.windows.net", cs.blobEndpoint());
    }

    @Test
    void honoursHttpProtocolAndCustomSuffix() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "DefaultEndpointsProtocol=http;AccountName=acc;AccountKey=k==;"
                        + "EndpointSuffix=core.chinacloudapi.cn");

        assertEquals("http", cs.protocol());
        assertEquals("http://acc.blob.core.chinacloudapi.cn", cs.blobEndpoint());
    }

    @Test
    void developmentStorageShortcut() {
        AzureConnectionString cs = AzureConnectionString.parse("UseDevelopmentStorage=true");

        assertTrue(cs.isDevelopment());
        assertEquals("devstoreaccount1", cs.accountName());
        assertEquals(AzureConnectionString.DEV_ACCOUNT_KEY, cs.accountKey());
        assertEquals("http", cs.protocol());
        assertEquals("http://127.0.0.1:10000/devstoreaccount1", cs.blobEndpoint());
        assertEquals("http://127.0.0.1:10001/devstoreaccount1", cs.queueEndpoint());
        assertEquals("http://127.0.0.1:10002/devstoreaccount1", cs.tableEndpoint());
    }

    @Test
    void developmentStorageKeyIsCaseInsensitive() {
        AzureConnectionString cs = AzureConnectionString.parse("usedevelopmentstorage=TRUE");
        assertTrue(cs.isDevelopment());
        assertEquals("devstoreaccount1", cs.accountName());
    }

    @Test
    void explicitBlobEndpointOverridesDerived() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "DefaultEndpointsProtocol=https;AccountName=acc;AccountKey=k==;"
                        + "BlobEndpoint=https://custom.example.com/acc");

        assertEquals("https://custom.example.com/acc", cs.blobEndpoint());
        // Non-overridden services still derive from the account.
        assertEquals("https://acc.queue.core.windows.net", cs.queueEndpoint());
    }

    @Test
    void caseInsensitiveKeys() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "defaultendpointsprotocol=HTTPS;ACCOUNTNAME=acc;accountKEY=k==");

        assertEquals("https", cs.protocol());
        assertEquals("acc", cs.accountName());
        assertEquals("k==", cs.accountKey());
    }

    @Test
    void toleratesWhitespaceAndTrailingSemicolons() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "  AccountName = acc ; AccountKey = k== ;;");

        assertEquals("acc", cs.accountName());
        assertEquals("k==", cs.accountKey());
    }

    @Test
    void sasOnlyHasNullAccountKey() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "AccountName=acc;SharedAccessSignature=sv=2021-08-06&sig=abc%3D");

        assertNull(cs.accountKey());
        assertEquals("sv=2021-08-06&sig=abc%3D", cs.sharedAccessSignature());
        assertEquals("https://acc.blob.core.windows.net", cs.blobEndpoint());
    }

    @Test
    void sasWithoutAccountNameParses() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "BlobEndpoint=https://acc.blob.core.windows.net;"
                        + "SharedAccessSignature=sv=2021-08-06&sig=xyz");

        assertNull(cs.accountName());
        assertNull(cs.accountKey());
        assertEquals("https://acc.blob.core.windows.net", cs.blobEndpoint());
        // With no account, non-explicit services cannot be derived.
        assertNull(cs.queueEndpoint());
    }

    @Test
    void redactionMasksKeyAndSas() {
        AzureConnectionString cs = AzureConnectionString.parse(
                "AccountName=acc;AccountKey=SUPERSECRETKEY==;"
                        + "SharedAccessSignature=sig=SECRETSIG");

        String redacted = cs.redacted();
        assertFalse(redacted.contains("SUPERSECRETKEY=="));
        assertFalse(redacted.contains("SECRETSIG"));
        assertTrue(redacted.contains("acc"));
        // toString delegates to the redacted form.
        assertFalse(cs.toString().contains("SUPERSECRETKEY=="));
        assertFalse(cs.toString().contains("SECRETSIG"));
    }

    @Test
    void emptyInputThrows() {
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse(""));
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse("   "));
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse(null));
    }

    @Test
    void noAccountAndNoDevOrSasThrows() {
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse("DefaultEndpointsProtocol=https"));
    }

    @Test
    void badProtocolThrows() {
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse(
                        "DefaultEndpointsProtocol=ftp;AccountName=acc;AccountKey=k=="));
    }

    @Test
    void malformedPairMissingEqualsThrows() {
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse("AccountName=acc;BogusSegment;AccountKey=k=="));
    }

    @Test
    void emptyKeyThrows() {
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse("=value;AccountName=acc"));
    }

    @Test
    void useDevelopmentStorageFalseThrows() {
        assertThrows(AzureConnectionString.MalformedConnectionStringException.class,
                () -> AzureConnectionString.parse("UseDevelopmentStorage=false"));
    }

    @Test
    void equalsAndHashCodeMatchForSameInput() {
        AzureConnectionString a = AzureConnectionString.parse("AccountName=acc;AccountKey=k==");
        AzureConnectionString b = AzureConnectionString.parse("AccountName=acc;AccountKey=k==");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
