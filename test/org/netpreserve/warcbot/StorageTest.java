package org.netpreserve.warcbot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StorageTest {
    @Test
    public void testBasicDeduplication(@TempDir Path tempDir) throws SQLException, IOException {
        try (var db = Database.newDatabaseInMemory();
             Storage storage = new Storage(tempDir, db.storage())) {

            var request = new HttpRequest(HttpMethod.GET, "http://example/" + UUID.randomUUID());
            var response = new HttpResponse();
            response.setStatus(200);
            response.setContent(Contents.utf8String("hello"));

            assertNotNull(storage.save(UUID.randomUUID(), request, response));
            assertNull(storage.save(UUID.randomUUID(), request, response));
        }
    }
}