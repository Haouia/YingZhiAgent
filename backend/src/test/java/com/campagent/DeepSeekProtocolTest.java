package com.campagent;

import com.campagent.common.ApiException;
import com.campagent.knowledge.Cloud;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekProtocolTest {
    ModelProtocolStub stub;
    Cloud cloud;
    @BeforeEach void setup() throws Exception {
        stub = new ModelProtocolStub();
        cloud = new Cloud("dashscope-fixture", stub.url(), "qwen-plus", "text-embedding-v4",
                "qwen3-rerank", stub.url() + "/rerank", "qwen-vl-ocr", "", "", "unused");
        cloud.configureChatProvider("deepseek", "deepseek-fixture", "deepseek-flash", stub.url());
    }
    @AfterEach void close() { stub.close(); }
    void chat() { cloud.chat(List.of(Map.of("role", "user", "content", "营地规则")), List.of()); }

    @Test void correctEndpointThinkingAndSeparateCredentials() {
        chat();
        assertEquals("/chat/completions", stub.lastPath.get());
        assertEquals("deepseek-flash", stub.lastBody.get().get("model"));
        assertEquals(Map.of("type", "disabled"), stub.lastBody.get().get("thinking"));
        assertFalse(stub.lastBody.get().containsKey("enable_thinking"));
        assertEquals("Bearer deepseek-fixture", stub.lastAuthorization.get());
        cloud.embed("规则");
        assertEquals("/v1/embeddings", stub.lastPath.get());
        assertEquals("Bearer dashscope-fixture", stub.lastAuthorization.get());
    }
    @Test void missingDeepSeekKeyCannotUseDashScopeKey() {
        cloud.configureChatProvider("deepseek", "", "deepseek-flash", stub.url());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, this::chat).status);
        assertEquals("", stub.lastPath.get());
    }
    @Test void deepSeekKeyCannotBeUsedForEmbeddings() {
        cloud = new Cloud("", stub.url(), "", "", "", "", "", "", "", "");
        cloud.configureChatProvider("deepseek", "deepseek-fixture", "deepseek-flash", stub.url());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> cloud.embed("规则")).status);
        assertEquals(0, stub.embeddings.get());
    }
    @Test void visionExtractionUsesDeepSeekAndRejectsTruncation() {
        assertTrue(cloud.ocr(new byte[]{1,2,3}, "image/png").contains("模拟"));
        assertEquals("deepseek-flash", stub.lastBody.get().get("model"));
        assertEquals(4096, ((Number)stub.lastBody.get().get("max_tokens")).intValue());
        assertEquals("Bearer deepseek-fixture", stub.lastAuthorization.get());
        stub.behavior.set("ocr-truncated");
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ApiException.class, () -> cloud.ocr(new byte[]{1}, "image/png")).status);
    }
    @Test void unsupportedModelCannotPretendToExtractImages() {
        cloud.configureChatProvider("deepseek", "deepseek-fixture", "deepseek-v4-pro", stub.url());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> cloud.ocr(new byte[]{1}, "image/png")).status);
    }
    @Test void rejectsInvalidProviderAndRemoteCredentialDestination() {
        assertThrows(IllegalArgumentException.class, () -> cloud.configureChatProvider("unknown", "", "", stub.url()));
        assertThrows(IllegalArgumentException.class, () -> cloud.configureChatProvider("deepseek", "fixture", "", "https://example.com"));
        assertThrows(IllegalArgumentException.class, () -> cloud.configureChatProvider("deepseek", "fixture", "", "relative"));
    }
}
