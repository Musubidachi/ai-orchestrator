package dev.fmangoni.forge.security.osv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.osv.model.OsvQuery;
import dev.fmangoni.forge.security.osv.model.OsvVuln;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OsvHttpClient implements OsvClient {

    private static final URI BATCH = URI.create("https://api.osv.dev/v1/querybatch");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    @Override
    public List<List<OsvVuln>> lookup(List<OsvQuery> queries) throws IOException {
        List<List<OsvVuln>> out = new ArrayList<>();
        // OSV caps a batch at 1,000 queries.
        for (int from = 0; from < queries.size(); from += 500) {
            List<OsvQuery> batch = queries.subList(from, Math.min(from + 500, queries.size()));
            JsonNode results = post(batch).path("results");
            for (int i = 0; i < batch.size(); i++) {
                List<OsvVuln> vulns = new ArrayList<>();
                for (JsonNode ref : results.path(i).path("vulns")) {
                    vulns.add(details(ref.path("id").asText(), batch.get(i).name()));
                }
                out.add(vulns);
            }
        }
        return out;
    }

    private JsonNode post(List<OsvQuery> batch) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode list = body.putArray("queries");
        for (OsvQuery query : batch) {
            ObjectNode item = list.addObject();
            item.putObject("package").put("name", query.name()).put("ecosystem", query.ecosystem());
            item.put("version", query.version());
        }
        return send(HttpRequest.newBuilder(BATCH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private OsvVuln details(String id, String packageName) throws IOException {
        JsonNode vuln = send(HttpRequest.newBuilder(
                URI.create("https://api.osv.dev/v1/vulns/" + id)).GET());
        return new OsvVuln(id, vuln.path("summary").asText(""), severityOf(vuln),
                fixedIn(vuln, packageName));
    }

    private JsonNode send(HttpRequest.Builder request) throws IOException {
        try {
            HttpResponse<String> response = client.send(
                    request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OSV answered " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted asking OSV", e);
        }
    }

    /** GitHub's advisory database grades every entry; that grading is the one used. */
    public static String severityOf(JsonNode vuln) {
        String graded = vuln.path("database_specific").path("severity").asText("")
                .toLowerCase(Locale.ROOT);
        return switch (graded) {
            case "critical" -> SecurityFinding.CRITICAL;
            case "high" -> SecurityFinding.HIGH;
            case "low" -> SecurityFinding.LOW;
            default -> SecurityFinding.MEDIUM;
        };
    }

    public static String fixedIn(JsonNode vuln, String packageName) {
        for (JsonNode affected : vuln.path("affected")) {
            if (!packageName.equals(affected.path("package").path("name").asText())) {
                continue;
            }
            for (JsonNode range : affected.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    if (event.has("fixed")) {
                        return event.path("fixed").asText();
                    }
                }
            }
        }
        return "";
    }
}
