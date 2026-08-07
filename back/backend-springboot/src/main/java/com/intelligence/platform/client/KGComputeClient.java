package com.intelligence.platform.client;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class KGComputeClient {
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
        
    @org.springframework.beans.factory.annotation.Value("${kg.compute.url:http://localhost:8101}")
    private String baseUrl;

    public String computeCommunities(String json) throws Exception {
        return post("/compute/communities", json);
    }

    public String computeCentrality(String json) throws Exception {
        return post("/compute/centrality", json);
    }

    public String computePagerank(String json) throws Exception {
        return post("/compute/pagerank", json);
    }

    private String post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("KG compute request failed: " + resp.statusCode());
        }
        return resp.body();
    }
}
