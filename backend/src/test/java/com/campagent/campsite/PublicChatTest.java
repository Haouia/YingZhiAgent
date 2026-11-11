package com.campagent.campsite;

import com.campagent.ModelProtocolStub;
import com.campagent.common.Json;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:publicchat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "camp.map-minute-limit=1000", "camp.mode=cloud", "camp.chat-provider=deepseek", "camp.deepseek-api-key=fixture-deepseek",
        "camp.jwt-secret=public-chat-fixture-signing-key-at-least-32-bytes", "camp.storage=./target/public-chat-files",
        "camp.retrieval-mode=keyword", "camp.demo-password=CampDemo2026!"})
class PublicChatTest {
    static final DiscoveryTest.MapEndpoint MAP = new DiscoveryTest.MapEndpoint();
    static final ModelProtocolStub MODEL;
    static {
        try { MODEL = new ModelProtocolStub(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @TestConfiguration
    static class Fixture {
        @Bean @Primary AmapMcp localMap() { return new AmapMcp(true, "fixture-map-key", MAP.origin()); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("camp.deepseek-base-url", MODEL::url);
    }
    @AfterAll static void stop() { MODEL.close(); MAP.close(); }
    @BeforeEach void reset() { MAP.behavior = "valid"; MODEL.publicCall.set(Map.of()); }
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    record Reply(int status, Map<String, Object> body) {}

    @Test void lookupDetailsAndRuleFollowUpUseOnlyTheCurrentConversation() throws Exception {
        String user = login("user");
        call("searchPublicCampsites", Map.of("city", "杭州", "keyword", "露营地"));
        var found = chat(user, "找杭州的真实露营地", null);
        assertEquals(200, found.status());
        var search = Json.map(Json.write(found.body().get("publicSearch")));
        var places = Json.rows(Json.write(search.get("places")));
        assertEquals(List.of("A1", "A2"), places.stream().map(p -> p.get("id")).toList());
        String conversation = found.body().get("conversationId").toString();

        call("getPublicCampsite", Map.of("position", 1));
        var detail = chat(user, "查看第二个营地的地址", conversation);
        assertEquals(200, detail.status());
        assertEquals("A2", Json.map(Json.write(detail.body().get("publicDetail"))).get("id"));
        assertEquals(404, chat(user, "第二个营地的地址", null).status());
        assertEquals(404, chat(login("user2"), "第二个能带宠物吗", conversation).status());

        assertEquals(200, request("/discovery/campsites?city=%E6%9D%AD%E5%B7%9E", user, null).status());
        MODEL.publicCall.set(Map.of());
        var rules = chat(user, "它能带宠物吗", conversation);
        assertEquals(200, rules.status());
        assertEquals("A2", Json.map(Json.write(rules.body().get("publicAnswer"))).get("placeId"));
        assertTrue(rules.body().get("answer").toString().contains("暂无已审核发布"));
        assertEquals(List.of(), rules.body().get("citations"));
        assertNull(rules.body().get("pendingTicketDraft"));
        assertFalse(Json.write(MODEL.lastBody.get()).contains("青谷房车营地"));

        var history = http.send(HttpRequest.newBuilder(URI.create(url("/conversations/" + conversation + "/messages")))
                .header("Authorization", "Bearer " + user).build(), HttpResponse.BodyHandlers.ofString()).body();
        assertFalse(history.contains("青谷房车营地"));
        assertFalse(history.contains("示例路"));
        assertTrue(history.contains("publicLookup"));
        assertEquals(400, chat(user, "第六个能带宠物吗", conversation).status());
        MODEL.campsite.set("c01");
        var local = chat(user, "这个青岚竹溪营地能带宠物吗", conversation);
        assertEquals(200, local.status());
        assertFalse(Boolean.TRUE.equals(local.body().get("publicLookup")));
        assertTrue(Json.write(local.body().get("toolSummary")).contains("searchKnowledge"));
        assertFalse(Json.write(MODEL.lastBody.get().get("tools")).contains("askPublicCampsiteRules"));
    }

    @Test void explicitlyRequestedMapSearchCanShareANameWithALocalCamp() throws Exception {
        call("searchPublicCampsites", Map.of("city", "杭州", "keyword", "青岚竹溪营地"));
        var result = chat(login("user"), "查高德地图杭州的青岚竹溪营地", null);
        assertEquals(200, result.status());
        assertNotNull(result.body().get("publicSearch"));
        assertTrue(Json.write(MODEL.lastBody.get().get("tools")).contains("searchPublicCampsites"));
    }

    @Test void mapFailureAndEmptyResultsRemainDistinctThroughChat() throws Exception {
        String user = login("user");
        call("searchPublicCampsites", Map.of("city", "", "keyword", "露营地"));
        assertEquals(400, chat(user, "找真实营地", null).status());
        call("searchPublicCampsites", Map.of("city", "杭州", "keyword", "露营地"));
        MAP.behavior = "empty";
        var empty = chat(user, "找杭州露营地", null);
        assertEquals(200, empty.status());
        assertEquals(List.of(), Json.map(Json.write(empty.body().get("publicSearch"))).get("places"));
        MAP.behavior = "limited";
        var limited = chat(user, "找杭州露营地", null);
        assertEquals(429, limited.status());
        assertFalse(Json.write(limited.body()).contains("fixture-map-key"));
    }

    void call(String name, Map<String, Object> arguments) {
        MODEL.publicCall.set(Map.of("name", name, "arguments", Json.write(arguments)));
    }
    Reply chat(String token, String message, String conversation) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("message", message);
        if (conversation != null) body.put("conversationId", conversation);
        return request("/chat", token, body);
    }
    String login(String username) throws Exception {
        var r = request("/auth/login", "", Map.of("username", username, "password", "CampDemo2026!"));
        assertEquals(200, r.status());
        return r.body().get("token").toString();
    }
    String url(String path) { return "http://127.0.0.1:" + port + "/api" + path; }
    Reply request(String path, String token, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url(path)));
        if (!token.isBlank()) req.header("Authorization", "Bearer " + token);
        if (body != null) req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        var response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), Json.map(response.body()));
    }
}
