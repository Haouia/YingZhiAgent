package com.campagent.campsite;

import com.campagent.common.ApiException;
import com.campagent.common.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:discovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "camp.map-minute-limit=1000", "camp.mode=demo", "camp.demo-password=CampDemo2026!"})
class DiscoveryTest {
    private static final String SEARCH = "/discovery/campsites?city=" + URLEncoder.encode("杭州", StandardCharsets.UTF_8);
    private static final MapEndpoint MAP = new MapEndpoint();
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;

    @TestConfiguration
    static class Fixture {
        @Bean @Primary AmapMcp localMap() {
            return new AmapMcp(true, "fixture-map-key", MAP.origin());
        }
    }

    @BeforeEach void reset() { MAP.behavior = "valid"; }
    @AfterAll static void stop() { MAP.close(); }

    @Test void searchDetailAndOrdinalFollowUpRespectAuthenticationAndSelection() throws Exception {
        assertEquals(401, request(SEARCH, "", null).status());
        String user = login("user");
        var search = request(SEARCH, user, null);
        assertEquals(200, search.status());
        assertEquals("高德地图", search.body().get("source"));
        assertNotNull(search.body().get("queriedAt"));
        var places = Json.rows(Json.write(search.body().get("places")));
        assertEquals(List.of("A1", "A2"), places.stream().map(place -> place.get("id")).toList());
        String context = search.body().get("searchId").toString();

        String detailUrl = "/discovery/campsites/A2?searchId=" + context;
        assertEquals(404, request(detailUrl, login("user2"), null).status());
        assertEquals(404, request("/discovery/campsites/OTHER?searchId=" + context, user, null).status());
        var detail = request(detailUrl, user, null);
        assertEquals(200, detail.status());
        assertEquals("杭州市", detail.body().get("city"));
        assertEquals("A2", detail.body().get("id"));

        var followUp = request("/discovery/follow-up", user,
                Map.of("searchId", context, "question", "第二个可以带宠物吗？"));
        assertEquals(200, followUp.status());
        assertEquals("A2", followUp.body().get("placeId"));
        assertTrue(followUp.body().get("answer").toString().contains("暂无已审核发布"));
        assertEquals(400, request("/discovery/follow-up", user,
                Map.of("searchId", context, "question", "第三个怎么样？")).status());
        assertEquals(400, request("/discovery/follow-up", user,
                Map.of("searchId", context, "placeId", "A1", "question", "第六个怎么样？")).status());
    }

    @Test void emptyResultsRateLimitAndWrongCityHaveDistinctOutcomes() throws Exception {
        String user = login("user");
        assertEquals(400, request("/discovery/campsites", user, null).status());
        MAP.behavior = "empty";
        var empty = request(SEARCH, user, null);
        assertEquals(200, empty.status());
        assertEquals(List.of(), empty.body().get("places"));

        MAP.behavior = "limited";
        var limited = request(SEARCH, user, null);
        assertEquals(429, limited.status());
        assertTrue(limited.body().get("message").toString().contains("限流"));
        assertFalse(Json.write(limited.body()).contains("fixture-map-key"));

        MAP.behavior = "wrong-city";
        var search = request(SEARCH, user, null);
        assertEquals(200, search.status());
        assertEquals(404, request("/discovery/campsites/A1?searchId=" + search.body().get("searchId"), user, null).status());
    }

    @Test void sameNamePlacesKeepTheirDistinctIdsAndOrder() throws Exception {
        MAP.behavior = "same-name";
        String user = login("user");
        var search = request(SEARCH, user, null);
        assertEquals(200, search.status());
        var places = Json.rows(Json.write(search.body().get("places")));
        assertEquals(List.of("同名露营地", "同名露营地"), places.stream().map(place -> place.get("name")).toList());
        String context = search.body().get("searchId").toString();
        var detail = request("/discovery/campsites/A2?searchId=" + context, user, null);
        assertEquals(200, detail.status());
        assertEquals("A2", detail.body().get("id"));
        var answer = request("/discovery/follow-up", user, Map.of("searchId", context, "question", "第二个能带宠物吗"));
        assertEquals(200, answer.status());
        assertEquals("A2", answer.body().get("placeId"));
    }

    @Test void disabledAndMissingKeyAvoidTheUpstream() {
        int calls = MAP.calls.get();
        for (var client : List.of(new AmapMcp(false, "fixture-map-key", MAP.origin()), new AmapMcp(true, "", MAP.origin()))) {
            var failure = assertThrows(ApiException.class, () -> client.call("user", "maps_text_search", Map.of("keywords", "露营地")));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status);
        }
        assertEquals(calls, MAP.calls.get());
    }

    @Test void anUpstreamTimeoutIsNotAnEmptySearch() throws Exception {
        MAP.behavior = "timeout";
        var result = request(SEARCH, login("user"), null);
        assertEquals(504, result.status());
        assertTrue(result.body().get("message").toString().contains("超时"));
    }

    @org.springframework.beans.factory.annotation.Autowired com.campagent.common.Db db;
    @org.springframework.beans.factory.annotation.Autowired PublicCampsites camps;
    @Test void directAndConversationCallsShareDurableQuotaAndValidateBeforeCharging() throws Exception {
        int before=used(); String user=login("user");
        var first=request(SEARCH,user,null);
        assertEquals(before+1,used());
        assertEquals(404,request("/discovery/campsites/UNKNOWN?searchId="+first.body().get("searchId"),user,null).status());
        assertEquals(before+1,used());
        camps.search(new com.campagent.auth.Actor("user","USER","test"),"another-conversation","杭州","露营地");
        assertEquals(before+2,used());
        MAP.behavior="limited";
        var limited=request(SEARCH,user,null);
        assertEquals(429,limited.status());
        assertEquals(before+3,used());
    }
    private int used() {
        var rows=db.rows("select sum(used) used from map_quota where bucket like 'day:user:%'");
        Object value=rows.getFirst().get("used"); return value==null ? 0 : ((Number)value).intValue();
    }

    private String login(String username) throws Exception {
        var response = request("/auth/login", "", Map.of("username", username, "password", "CampDemo2026!"));
        assertEquals(200, response.status());
        return response.body().get("token").toString();
    }

    private record Reply(int status, Map<String, Object> body) {}

    private Reply request(String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path));
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), Json.map(response.body()));
    }

    static class MapEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger calls = new AtomicInteger();
        volatile String behavior = "valid";

        MapEndpoint() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/mcp", exchange -> {
                    try (exchange) {
                        if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                        calls.incrementAndGet();
                        if (!"key=fixture-map-key".equals(exchange.getRequestURI().getRawQuery())) { exchange.sendResponseHeaders(401, -1); return; }
                        var rpc = Json.map(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        if (!rpc.containsKey("id")) { exchange.sendResponseHeaders(202, -1); return; }
                        Object result = switch (rpc.get("method").toString()) {
                            case "initialize" -> Map.of("protocolVersion", "2025-03-26", "serverInfo",
                                    Map.of("name", "fixture", "version", "1"), "capabilities", Map.of("tools", Map.of()));
                            case "tools/list" -> Map.of("tools", List.of(tool("maps_text_search"), tool("maps_search_detail")));
                            case "tools/call" -> toolResult((Map<?, ?>) rpc.get("params"));
                            default -> Map.of();
                        };
                        byte[] output = Json.write(Map.of("jsonrpc", "2.0", "id", rpc.get("id"), "result", result)).getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, output.length);
                        exchange.getResponseBody().write(output);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                    }
                });
                server.start();
            } catch (Exception failure) {
                throw new IllegalStateException("Could not start MCP fixture", failure);
            }
        }

        private Object toolResult(Map<?, ?> call) throws InterruptedException {
            if ("timeout".equals(behavior)) Thread.sleep(6000);
            if ("limited".equals(behavior)) return Map.of("isError", true, "content",
                    List.of(Map.of("type", "text", "text", "CUQPS_HAS_EXCEEDED_THE_LIMIT")));
            var args = (Map<?, ?>) call.get("arguments");
            Object data;
            if ("maps_text_search".equals(call.get("name"))) {
                if (!Boolean.TRUE.equals(args.get("citylimit")) || !"杭州".equals(args.get("city"))) throw new IllegalArgumentException("search scope");
                var first = Map.of("id", "A1", "name", "same-name".equals(behavior) ? "同名露营地" : "云溪露营地", "city", "杭州市", "address", "示例路1号", "typecode", "080300");
                data = Map.of("pois", "empty".equals(behavior) ? List.of() : List.of(first, first,
                        Map.of("id", "SHOP", "name", "露营地装备专卖店", "typecode", "060000"),
                        Map.of("id", "OTHER", "name", "跨市露营地", "city", "成都市"),
                        Map.of("id", "A2", "name", "same-name".equals(behavior) ? "同名露营地" : "青谷房车营地", "city", "杭州市", "address", "示例路2号", "typecode", "080300")));
            } else {
                String name = "A2".equals(args.get("id")) ? "青谷房车营地" : "云溪露营地";
                if ("same-name".equals(behavior)) name = "同名露营地";
                data = Map.of("id", args.get("id"), "name", name,
                        "city", "wrong-city".equals(behavior) ? "成都市" : "杭州市",
                        "address", "示例路", "type", "体育休闲服务;露营地", "location", "120.1,30.2");
            }
            return Map.of("isError", false, "content", List.of(Map.of("type", "text", "text", Json.write(data))));
        }

        private static Map<String, Object> tool(String name) {
            return Map.of("name", name, "inputSchema", Map.of("type", "object", "properties", Map.of()));
        }

        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); executor.close(); }
    }
}
