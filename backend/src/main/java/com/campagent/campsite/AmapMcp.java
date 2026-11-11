package com.campagent.campsite;

import com.campagent.common.ApiException;
import com.campagent.common.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AmapMcp {
    private static final Set<String> TOOLS = Set.of("maps_text_search", "maps_search_detail");
    private final boolean enabled;
    private final String key;
    private final String origin;
    private final Semaphore active = new Semaphore(1);
    private McpSyncClient client;
    @Autowired private MapQuota quota;

    @Autowired
    public AmapMcp(@Value("${camp.amap-enabled:false}") boolean enabled, @Value("${camp.amap-key:}") String key) {
        this(enabled, key, "https://mcp.amap.com");
    }

    AmapMcp(boolean enabled, String key, String origin) {
        this.enabled = enabled;
        this.key = key.strip();
        this.origin = origin;
    }

    public boolean available() {
        return enabled && key.matches("[A-Za-z0-9_-]{8,128}");
    }

    Map<?, ?> call(String user, String tool, Map<String, Object> arguments) {
        if (!TOOLS.contains(tool)) throw new IllegalArgumentException("Unsupported map tool");
        if (!available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "真实营地查询未启用或未配置高德 Key");
        if (!active.tryAcquire()) throw limited();
        try {
            quota.reserve(user, java.time.Instant.now());
            if (client == null) connect();
            var response = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
            if (Boolean.TRUE.equals(response.isError())) {
                if (isLimited(response.content().toString())) throw limited();
                throw unavailable();
            }
            Map<?, ?> data;
            if (response.structuredContent() instanceof Map<?, ?> structured) {
                data = structured;
            } else if (!response.content().isEmpty() && response.content().getFirst() instanceof McpSchema.TextContent text) {
                data = Json.map(text.text());
            } else {
                throw unavailable();
            }
            String code = String.valueOf(data.get("infocode"));
            if (isLimited(String.valueOf(data.get("info"))) || Set.of("10014", "10019", "10020", "10021").contains(code)) throw limited();
            if ("0".equals(String.valueOf(data.get("status"))) || data.containsKey("error")) throw unavailable();
            return data;
        } catch (ApiException known) {
            org.slf4j.LoggerFactory.getLogger(AmapMcp.class).warn("mapFailure requestId={} status={}",org.slf4j.MDC.get("requestId"),known.status.value());
            throw known;
        } catch (RuntimeException failure) {
            close();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String message = String.valueOf(cause.getMessage());
                if (isLimited(message) || message.contains("HTTP 429")) throw limited();
                if (cause instanceof TimeoutException || message.toLowerCase(Locale.ROOT).contains("timed out")) {
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "地图服务响应超时，请稍后再试");
                }
            }
            throw unavailable();
        } finally {
            active.release();
        }
    }

    private void connect() {
        var transport = HttpClientStreamableHttpTransport.builder(origin)
                .endpoint("/mcp?key=" + key)
                .connectTimeout(Duration.ofSeconds(3))
                .openConnectionOnStartup(false)
                .build();
        client = McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
        client.initialize();
        var names = client.listTools().tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
        if (!names.containsAll(TOOLS)) {
            close();
            throw unavailable();
        }
    }

    private static boolean isLimited(String message) {
        return message.contains("QPS_HAS_EXCEEDED_THE_LIMIT");
    }

    private static ApiException limited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "地图查询暂时限流，请稍后再试");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "地图服务暂不可用，请稍后再试");
    }

    @PreDestroy
    public void close() {
        if (client == null) return;
        var old = client;
        client = null;
        try { old.close(); } catch (RuntimeException ignored) { /* Avoid leaking the credential in a transport error. */ }
    }
}
