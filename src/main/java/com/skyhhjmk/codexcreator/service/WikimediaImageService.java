package com.skyhhjmk.codexcreator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A deliberately narrow Commons-only image source. External URLs are never accepted from the agent. */
@ApplicationScoped
public class WikimediaImageService {
    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "codex.creator.wikimedia.enabled", defaultValue = "true")
    boolean enabled;

    public List<Candidate> search(String query, int limit) {
        requireEnabled();
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) throw new IllegalArgumentException("query is required");
        int safeLimit = Math.max(1, Math.min(limit, 8));
        JsonNode pages = api("action=query&generator=search&gsrnamespace=6&gsrlimit=" + safeLimit
                + "&gsrsearch=" + encode(safeQuery) + "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1200");
        List<Candidate> result = new ArrayList<>();
        pages.path("query").path("pages").elements().forEachRemaining(page -> candidate(page).ifPresent(result::add));
        return result;
    }

    public Candidate inspect(String title) { return resolve(title); }

    public ImageBytes download(String title) {
        Candidate candidate = resolve(title);
        if (!candidate.downloadUrl().startsWith("https://upload.wikimedia.org/")) {
            throw new IllegalArgumentException("Commons returned an untrusted download host");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(candidate.downloadUrl()))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "WindBlog-CodexCreator/1.0").GET().build();
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var body = response.body()) {
                bytes = body.readNBytes(MAX_BYTES + 1);
            }
            String mime = response.headers().firstValue("content-type").orElse("").split(";", 2)[0].trim().toLowerCase();
            if (response.statusCode() != 200 || bytes.length == 0 || bytes.length > MAX_BYTES
                    || !(mime.equals("image/png") || mime.equals("image/jpeg") || mime.equals("image/webp") || mime.equals("image/gif"))) {
                throw new IllegalArgumentException("Commons image is unavailable, too large, or has an unsupported type");
            }
            return new ImageBytes(candidate, bytes, mime);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not download Commons image", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not download Commons image", exception);
        }
    }

    private Candidate resolve(String title) {
        requireEnabled();
        if (title == null || !title.startsWith("File:") || title.length() > 300) throw new IllegalArgumentException("a Commons File: title is required");
        JsonNode pages = api("action=query&titles=" + encode(title) + "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1200");
        JsonNode page = pages.path("query").path("pages").elements().hasNext() ? pages.path("query").path("pages").elements().next() : null;
        return candidate(page).orElseThrow(() -> new IllegalArgumentException("Commons file not found or lacks reusable license metadata"));
    }

    private java.util.Optional<Candidate> candidate(JsonNode page) {
        if (page == null || page.path("missing").asBoolean()) return java.util.Optional.empty();
        JsonNode info = page.path("imageinfo").path(0);
        String url = info.path("thumburl").asText(info.path("url").asText(""));
        JsonNode metadata = info.path("extmetadata");
        String license = metadata.path("LicenseShortName").path("value").asText("");
        String licenseUrl = metadata.path("LicenseUrl").path("value").asText("");
        String artist = stripHtml(metadata.path("Artist").path("value").asText(""));
        if (url.isBlank() || license.isBlank() || licenseUrl.isBlank()) return java.util.Optional.empty();
        String title = page.path("title").asText("");
        return java.util.Optional.of(new Candidate(title, "https://commons.wikimedia.org/wiki/" + encode(title),
                url, license, licenseUrl, artist));
    }

    private JsonNode api(String query) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + "?format=json&origin=*&" + query))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", "WindBlog-CodexCreator/1.0").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("Commons search failed");
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not query Wikimedia Commons", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not query Wikimedia Commons", exception);
        }
    }

    private void requireEnabled() { if (!enabled) throw new IllegalStateException("Wikimedia Commons images are disabled"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String stripHtml(String value) { return value.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim(); }

    public record Candidate(String title, String sourcePage, String downloadUrl, String license, String licenseUrl, String artist) { }
    public record ImageBytes(Candidate candidate, byte[] bytes, String mimeType) { }
}
