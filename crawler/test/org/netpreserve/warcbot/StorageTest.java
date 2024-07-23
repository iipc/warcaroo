package org.netpreserve.warcbot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

class StorageTest {
    @Test
    public void testBasicDeduplication(@TempDir Path tempDir) throws SQLException, IOException {
        try (var db = Database.newDatabaseInMemory();
             Storage storage = new Storage(tempDir, db.storage())) {

//            var request = new HttpRequest(HttpMethod.GET, "http://example/" + UUID.randomUUID());
//            var response = new HttpResponse();
//            response.setStatus(200);
//            response.setContent(Contents.utf8String("hello"));
//
//            assertNotNull(storage.save(new Resource.Metadata(UUID.randomUUID(), 0, ipAddress), request, response));
//            assertNull(storage.save(new Resource.Metadata(UUID.randomUUID(), 0, ipAddress), request, response));
        }
    }
}