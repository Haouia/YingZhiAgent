package com.campagent;

import com.campagent.common.ApiException;
import com.campagent.knowledge.Cloud;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CloudProtocolTest {
    private ModelProtocolStub stub;
    private Cloud cloud;
    @BeforeEach void setup() throws Exception {
        stub = new ModelProtocolStub();
        cloud = new Cloud("protocol-only", stub.url(), "qwen-plus", "text-embedding-v4",
                "qwen3-rerank", stub.url() + "/rerank", "qwen-vl-ocr", "", "", "unused");
    }
    @AfterEach void close() { stub.close(); }

    @Test void validEmbeddingAndRerankContract() {
        assertEquals(1024, cloud.embed("协议测试").size());
        var chunk = Map.<String,Object>of("id", UUID.randomUUID().toString(), "content", "演示规则");
        assertEquals(List.of(chunk), cloud.rerank("规则", List.of(chunk)));
        assertEquals(1, stub.embeddings.get());
        assertEquals(1, stub.reranks.get());
    }
    @Test void invalidEmbeddingDimensionsAreAnUpstreamFailure() {
        stub.behavior.set("embedding-short");
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ApiException.class, () -> cloud.embed("问题")).status);
    }
    @Test void nonNumericEmbeddingIsRejectedCleanly() {
        stub.behavior.set("embedding-nonnumeric");
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ApiException.class, () -> cloud.embed("问题")).status);
    }
    @Test void fractionalRerankIndexIsRejected() {
        stub.behavior.set("rerank-fractional");
        assertThrows(ApiException.class, () -> cloud.rerank("问题", List.of(Map.of("content", "规则"))));
    }
    @Test void duplicateRerankIndexIsRejected() {
        stub.behavior.set("rerank-duplicate");
        assertThrows(ApiException.class, () -> cloud.rerank("问题", List.of(Map.of("content", "规则"))));
    }
    @Test void emptyRerankResultIsRejected() {
        stub.behavior.set("rerank-empty");
        assertThrows(ApiException.class, () -> cloud.rerank("问题", List.of(Map.of("content", "规则"))));
    }
    @Test void missingKeyIsNotASilentDemoFallback() {
        var missing = new Cloud("", stub.url(), "qwen-plus", "", "", "", "", "", "", "");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class,
                () -> missing.chat(List.of(Map.of("role", "user", "content", "你好")), List.of())).status);
    }
    @Test void truncatedOcrIsRejected() {
        stub.behavior.set("ocr-truncated");
        assertThrows(ApiException.class, () -> cloud.ocr(new byte[]{1,2,3}, "image/png"));
    }
}

