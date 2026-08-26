package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The two body types the Body tab could not express: multipart form-data and a raw binary file. */
class RestBodyTypesTest {

    @TempDir Path tempDir;

    @Test
    void aFormPartIsOnlyCompleteWhenItHasWhatItNeeds() {
        RestRequest.FormPart text = new RestRequest.FormPart("field", "value");
        assertTrue(text.isComplete());

        RestRequest.FormPart unnamed = new RestRequest.FormPart("", "value");
        assertFalse(unnamed.isComplete());

        RestRequest.FormPart file = RestRequest.FormPart.ofFile("upload", "");
        assertFalse(file.isComplete(), "a file part with no path cannot be sent");
        file.setFilePath("/tmp/x.txt");
        assertTrue(file.isComplete());

        text.setEnabled(false);
        assertFalse(text.isComplete(), "a disabled row is skipped");
    }

    @Test
    void multipartContentTypeIsLeftToTheEncoderBecauseItCarriesTheBoundary() {
        RestRequest request = new RestRequest();
        request.setBodyType(RestRequest.BodyType.FORM_DATA);
        assertNull(request.contentType());
    }

    @Test
    void aBinaryBodyDeclaresOctetStreamUnlessTold() {
        RestRequest request = new RestRequest();
        request.setBodyType(RestRequest.BodyType.BINARY);
        assertEquals("application/octet-stream", request.contentType());
        request.setBinaryContentType("image/png");
        assertEquals("image/png", request.contentType());
    }

    @Test
    void theOlderBodyTypesKeepTheirContentTypes() {
        RestRequest request = new RestRequest();
        request.setBodyType(RestRequest.BodyType.JSON);
        assertEquals("application/json", request.contentType());
        request.setBodyType(RestRequest.BodyType.FORM_URLENCODED);
        assertEquals("application/x-www-form-urlencoded", request.contentType());
        request.setBodyType(RestRequest.BodyType.NONE);
        assertNull(request.contentType());
    }

    @Test
    void aMultipartBodyEncodesTextAndFilePartsTogether() throws Exception {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "file content here");

        MultipartFormData form = new MultipartFormData("TESTBOUNDARY");
        form.addField("title", "a report");
        form.addFile("attachment", "note.txt", Files.readAllBytes(file));
        String encoded = new String(form.build(), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(encoded.contains("--TESTBOUNDARY"), encoded);
        assertTrue(encoded.contains("name=\"title\""), encoded);
        assertTrue(encoded.contains("a report"), encoded);
        assertTrue(encoded.contains("filename=\"note.txt\""), encoded);
        assertTrue(encoded.contains("file content here"), encoded);
        assertTrue(encoded.endsWith("--TESTBOUNDARY--\r\n"), "the closing boundary must be last");
        assertTrue(form.getContentType().contains("boundary=TESTBOUNDARY"), form.getContentType());
    }

    @Test
    void formPartsSurviveOnTheRequest() {
        RestRequest request = new RestRequest();
        request.setBodyType(RestRequest.BodyType.FORM_DATA);
        request.getFormParts().add(new RestRequest.FormPart("a", "1"));
        request.getFormParts().add(RestRequest.FormPart.ofFile("f", "/tmp/x"));
        assertEquals(2, request.getFormParts().size());
        assertTrue(request.getFormParts().get(1).isFile());
    }
}
