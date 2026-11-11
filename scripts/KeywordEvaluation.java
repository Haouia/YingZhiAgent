import com.campagent.CampAgentApplication;
import com.campagent.auth.Actor;
import com.campagent.common.Db;
import com.campagent.common.Json;
import com.campagent.knowledge.Documents;
import com.campagent.knowledge.Retrieval;
import org.springframework.boot.SpringApplication;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

class KeywordEvaluation {
    private static final Actor USER = new Actor("user", "USER", "评测用户");
    private static final Actor STAFF = new Actor("staff", "STAFF", "评测客服");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private record Response(int status, long ms, Map<String, Object> body) {}

    public static void main(String[] args) throws Exception {
        var cases = Files.readAllLines(Path.of(args[0])).stream().filter(line -> !line.isBlank()).map(Json::map).toList();
        boolean live = Boolean.parseBoolean(args[2]);
        long turns = cases.stream().filter(c -> Boolean.TRUE.equals(c.get("live")))
                .mapToLong(c -> c.containsKey("prelude") ? 2 : 1).sum();
        if (turns > 40) throw new IllegalArgumentException("真实对话用例超出本轮 40 请求上限");
        var password = UUID.randomUUID().toString();
        try (var out = Files.newBufferedWriter(Path.of(args[1]));
             var app = SpringApplication.run(CampAgentApplication.class,
                     "--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=keyword-eval",
                     "--spring.datasource.url=jdbc:h2:mem:keyword_eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                     "--spring.datasource.username=sa", "--spring.datasource.password=",
                     "--camp.mode=cloud", "--camp.retrieval-mode=keyword", "--camp.chat-provider=deepseek",
                     "--camp.jwt-secret=" + UUID.randomUUID() + UUID.randomUUID(), "--camp.demo-password=" + password,
                     "--camp.deepseek-base-url=https://api.deepseek.com", "--camp.deepseek-model=" + args[3])) {
            String api = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api";
            var db = app.getBean(Db.class);
            var documents = app.getBean(Documents.class);
            var retrieval = app.getBean(Retrieval.class);
            var versions = documents.list();
            if (versions.size() != 61 || versions.stream().anyMatch(v -> !"REVIEW".equals(v.get("status"))))
                throw new IllegalStateException("评测只允许使用初始的 61 份待审核虚构种子资料");
            for (var version : versions) documents.publish(version.get("id").toString(), version.get("version_id").toString());
            var corpus = db.rows("select c.id,c.version_id,c.content,d.id document_id,d.campsite_id,d.visibility "
                    + "from knowledge_chunk c join knowledge_document d on d.current_version=c.version_id order by d.id,c.location");
            write(out, Map.of("type", "run", "startedAt", Instant.now().toString(), "live", live, "health",
                    request(api + "/health", "", null).body, "corpus", corpus));
            for (var c : cases) {
                String question = c.getOrDefault("retrievalQuestion", c.get("question")).toString();
                String camp = c.get("camp").toString();
                write(out, Map.of("type", "retrieval", "id", c.get("id"), "scoped", ranks(retrieval, question, camp),
                        "unscoped", ranks(retrieval, question, "")));
            }
            String token = request(api + "/auth/login", "", Map.of("username", USER.id(), "password", password))
                    .body.get("token").toString();
            var internal = retrieval.search(STAFF, "客服内部值班手册的处理标识", "c01").stream()
                    .filter(c -> "c01-staff".equals(c.get("document_id"))).findFirst().orElseThrow();
            boolean userFindsInternal = retrieval.search(USER, "客服内部值班手册的处理标识", "c01").stream()
                    .anyMatch(c -> "c01-staff".equals(c.get("document_id")));
            int internalHttp = request(api + "/knowledge/citations/" + internal.get("id"), token, null).status;
            write(out, Map.of("type", "access", "staffFindsInternal", true,
                    "userFindsInternal", userFindsInternal, "userCitationHttp", internalHttp));
            if (live) {
                for (var c : cases) {
                    if (!Boolean.TRUE.equals(c.get("live"))) continue;
                    var body = new LinkedHashMap<String, Object>();
                    body.put("message", c.get("question"));
                    if (c.containsKey("prelude")) {
                        var prelude = request(api + "/chat", token, Map.of("message", c.get("prelude")));
                        record(out, "prelude", c, prelude);
                        if (prelude.status != 200) continue;
                        body.put("conversationId", prelude.body.get("conversationId"));
                    }
                    record(out, "answer", c, request(api + "/chat", token, body));
                }
            }
            write(out, Map.of("type", "end", "finishedAt", Instant.now().toString(),
                    "tickets", db.rows("select id from service_ticket").size(),
                    "drafts", db.rows("select id from ticket_draft").size()));
        }
    }

    private static Map<String, Object> ranks(Retrieval retrieval, String question, String camp) {
        long start = System.nanoTime();
        var hits = retrieval.search(USER, question, camp);
        return Map.of("ms", (System.nanoTime() - start) / 1_000_000.0,
                "docs", hits.stream().map(c -> c.get("document_id")).toList());
    }

    private static Response request(String url, String token, Object body) throws InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(150));
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        long start = System.nanoTime();
        try {
            var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), (System.nanoTime() - start) / 1_000_000, Json.map(response.body()));
        } catch (IOException e) {
            return new Response(0, (System.nanoTime() - start) / 1_000_000, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private static void record(BufferedWriter out, String type, Map<String, Object> c, Response response) throws IOException {
        write(out, Map.of("type", type, "id", c.get("id"), "status", response.status,
                "ms", response.ms, "body", response.body));
        System.out.printf("%s %s HTTP %d %d ms%n", type, c.get("id"), response.status, response.ms);
    }

    private static void write(BufferedWriter out, Map<String, Object> row) throws IOException {
        out.write(Json.write(row));
        out.newLine();
        out.flush();
    }
}
