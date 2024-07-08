package org.netpreserve.warcbot;

import com.github.mizosoft.methanol.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

public class CrawlClient implements Crawl, AutoCloseable {
    private final HttpClient client;

    public CrawlClient(String baseUrl) {
        this.client = Methanol.newBuilder()
                .baseUri(baseUrl)
                .build();
    }

    public Config getConfig() {
        return GET("config", Config.class);
    }

    public void setConfig(Config config) {
        PUT("config", config);
    }

    @Override
    public void pause() {
        POST("pause");
    }

    @Override
    public void unpause() {
        POST("unpause");
    }

    @Override
    public List<Candidate> listQueue(String queue) {
        return GET("/queues/" + queue, List.class);
    }

    private <T> T GET(String path, Class<T> clazz) {
        try {
            return client.send(MutableRequest.GET(path), MoreBodyHandlers.ofObject(clazz)).body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void POST(String path) {
        try {
            client.send(MutableRequest.POST(path, BodyPublishers.noBody()), BodyHandlers.discarding());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void PUT(String path, Object body) {
        try {
            client.send(MutableRequest.create(path).PUT(MoreBodyPublishers.ofObject(body, MediaType.APPLICATION_JSON)),
                    BodyHandlers.discarding());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
