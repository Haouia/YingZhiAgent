package com.campagent;

import com.campagent.common.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class ModelProtocolStub implements AutoCloseable {
    private final HttpServer server;
    public final AtomicReference<String> behavior = new AtomicReference<>("valid");
    public final AtomicReference<String> campsite = new AtomicReference<>("");
    public final AtomicReference<Map<String, Object>> publicCall = new AtomicReference<>(Map.of());
    public final AtomicReference<List<String>> rerankInput = new AtomicReference<>(List.of());
    public final AtomicInteger embeddings = new AtomicInteger();
    public final AtomicInteger reranks = new AtomicInteger();
    public final AtomicReference<Map<String,Object>> lastBody = new AtomicReference<>(Map.of());
    public final AtomicReference<String> lastAuthorization = new AtomicReference<>("");
    public final AtomicReference<String> lastPath = new AtomicReference<>("");

    public ModelProtocolStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = 200;
            Object response;
            try {
                var body = Json.map(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String path = exchange.getRequestURI().getPath();
                lastBody.set(body);
                lastPath.set(path);
                lastAuthorization.set(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
                if (path.endsWith("/embeddings")) {
                    embeddings.incrementAndGet();
                    if (behavior.get().equals("embedding-failure")) {
                        status = 503;
                        response = Map.of("error", Map.of("message", "simulated outage"));
                    } else {
                        int dimensions = behavior.get().equals("embedding-short") ? 3 : 1024;
                        List<Object> vector = new ArrayList<>(Collections.nCopies(dimensions, 0.0));
                        vector.set(0, 1.0);
                        vector.set(1, behavior.get().equals("embedding-nonnumeric") ? "invalid" : 0.2);
                        response = Map.of("data", List.of(Map.of("index", 0, "embedding", vector)));
                    }
                } else if (path.endsWith("/rerank")) {
                    reranks.incrementAndGet();
                    @SuppressWarnings("unchecked") var input = (Map<String,Object>) body.get("input");
                    @SuppressWarnings("unchecked") var documents = (List<String>) input.get("documents");
                    rerankInput.set(List.copyOf(documents));
                    List<Map<String,Object>> results = new ArrayList<>();
                    if (behavior.get().equals("rerank-fractional")) {
                        results.add(Map.of("index", 0.5, "relevance_score", 0.9));
                    } else if (behavior.get().equals("rerank-duplicate")) {
                        results.add(Map.of("index", 0, "relevance_score", 0.9));
                        results.add(Map.of("index", 0, "relevance_score", 0.8));
                    } else if (!behavior.get().equals("rerank-empty")) {
                        for (int i = 0; i < Math.min(5, documents.size()); i++)
                            results.add(Map.of("index", i, "relevance_score", 0.9 - i * 0.1));
                    }
                    response = Map.of("output", Map.of("results", results));
                } else if (body.get("model").equals("qwen-vl-ocr")
                        || Json.rows(Json.write(body.get("messages"))).getLast().get("content") instanceof List<?>) {
                    response = choice(behavior.get().equals("ocr-truncated") ? "length" : "stop",
                            Map.of("role", "assistant", "content", "OCR 协议模拟提取文字，不能代表真实识别效果。"));
                } else {
                    @SuppressWarnings("unchecked") var messages = (List<Map<String,Object>>) body.get("messages");
                    var last = messages.getLast();
                    if (behavior.get().equals("direct-answer-without-tools")) {
                        response = choice("stop", Map.of("role", "assistant", "content", "入住前1小时可免费取消。"));
                    } else if (Set.of("ticket-without-evidence", "ticket-invalid-answer").contains(behavior.get())) {
                        if (last.get("role").equals("tool") && last.get("content").toString().startsWith("{")) {
                            response = choice("stop", Map.of("role", "assistant", "content", behavior.get().equals("ticket-invalid-answer") ? "已整理草稿。[[invalid-evidence-id]]" : "已整理草稿，请确认。"));
                        } else {
                            var fn = last.get("role").equals("tool")
                                    ? Map.of("name", "prepareTicket", "arguments", Json.write(Map.of("campsiteId", campsite.get(), "kind", "投诉", "title", "停车标识问题", "description", "入口指示不清导致绕行")))
                                    : Map.of("name", "searchKnowledge", "arguments", Json.write(Map.of("question", last.get("content"), "campsiteId", campsite.get())));
                            response = choice("tool_calls", Map.of("role", "assistant", "tool_calls",
                                    List.of(Map.of("id", "ticket-fixture-call", "type", "function", "function", fn))));
                        }
                    } else if (!publicCall.get().isEmpty()) {
                        response = choice("tool_calls", Map.of("role", "assistant", "tool_calls",
                                List.of(Map.of("id", "map-call", "type", "function", "function", publicCall.get()))));
                    } else if (Set.of("repairable-reference", "invented-reference").contains(behavior.get()) && last.get("role").equals("system")) {
                        var evidence = Json.rows(messages.reversed().stream().filter(m -> m.get("role").equals("tool"))
                                .findFirst().orElseThrow().get("content").toString());
                        String answer = behavior.get().equals("invented-reference") ? "可以免费退款。[[not-an-evidence-id]]"
                                : "宠物必须牵绳。[[" + evidence.getFirst().get("id") + "]]";
                        response = choice("stop", Map.of("role", "assistant", "content", answer));
                    } else if (last.get("role").equals("tool")) {
                        var evidence = Json.rows(last.get("content").toString());
                        String answer = evidence.isEmpty() ? "资料中没有明确说明。"
                                : "根据已发布的演示资料，提供规则原文依据。[[" + evidence.getFirst().get("id") + "]]";
                        if (behavior.get().equals("uncited-answer")) answer = "入住前一律可以免费退款。";
                        if (Set.of("invented-reference", "repairable-reference").contains(behavior.get())) answer = "可以免费退款。[[not-an-evidence-id]]";
                        response = choice("stop", Map.of("role", "assistant", "content", answer));
                    } else {
                        var fn = Map.of("name", "searchKnowledge", "arguments", Json.write(
                                Map.of("question", last.get("content"), "campsiteId", campsite.get())));
                        response = choice("tool_calls", Map.of("role", "assistant", "tool_calls",
                                List.of(Map.of("id", "fixture-call", "type", "function", "function", fn))));
                    }
                }
            } catch (Exception e) {
                status = 500;
                response = Map.of("error", "Protocol fixture rejected request");
            }
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
    private static Object choice(String finish, Map<String,Object> message) {
        return Map.of("choices", List.of(Map.of("finish_reason", finish, "message", message)));
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }
}

