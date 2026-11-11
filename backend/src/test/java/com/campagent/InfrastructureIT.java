package com.campagent;

import com.campagent.auth.Actor;
import com.campagent.chat.ChatService;
import com.campagent.common.*;
import com.campagent.knowledge.*;
import com.campagent.ticket.Tickets;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"camp.mode=cloud", "camp.api-key=protocol-only", "camp.chat-provider=dashscope", "camp.retrieval-mode=hybrid",
        "camp.jwt-secret=infrastructure-fixture-signing-key-at-least-32-bytes",
        "camp.demo-password=CampDemo2026!", "camp.storage=./target/it-documents", "camp.daily-limit=10000"})
class InfrastructureIT {
    static final ModelProtocolStub MODEL;
    static final String COLLECTION = "camp_agent_it_" + UUID.randomUUID().toString().replace("-", "");
    static final String QDRANT = required("CAMP_TEST_QDRANT_URL");
    static {
        try { MODEL = new ModelProtocolStub(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        String url = required("CAMP_TEST_MYSQL_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/camp_agent_it\\?.*"))
            throw new IllegalStateException("集成测试只允许本机 camp_agent_it 库");
        if (!URI.create(QDRANT).getHost().equals("127.0.0.1"))
            throw new IllegalStateException("集成测试只允许本机 Qdrant");
        p.add("spring.datasource.url", () -> url);
        p.add("spring.datasource.username", () -> required("CAMP_TEST_MYSQL_USER"));
        p.add("spring.datasource.password", () -> required("CAMP_TEST_MYSQL_PASSWORD"));
        p.add("camp.base-url", MODEL::url);
        p.add("camp.rerank-url", () -> MODEL.url() + "/rerank");
        p.add("camp.qdrant-url", () -> QDRANT);
        p.add("camp.collection", () -> COLLECTION);
    }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("请使用 scripts/test-infrastructure.ps1 设置测试环境");
        return value;
    }
    @Autowired Db db;
    @Autowired Documents documents;
    @Autowired DocumentWorker worker;
    @Autowired Retrieval retrieval;
    @Autowired ChatService chat;
    @Autowired Tickets tickets;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("documentExecutor")
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor documentExecutor;
    String camp;
    final Actor user = new Actor("user", "USER", "联调用户");
    final Actor staff = new Actor("staff", "STAFF", "联调客服");

    @BeforeEach void setup() {
        MODEL.behavior.set("valid");
        camp = Db.id();
        db.update("insert into campsite values(?,?,?,?,?,?,?,?)",
                camp, "联调专用虚构营地", "测试城市", "草地", "洗手间", "仅测试", "非真实业务", Db.now());
        MODEL.campsite.set(camp);
    }
    String version(String document, String text) {
        String id = Db.id();
        db.update("insert into knowledge_version values(?,?,?,?,?,?,?,?,?)",
                id, document, "fixture.md", "", "fixture-" + id, "", "PROCESSING", "", Db.now());
        documents.saveExtracted(id, text);
        return id;
    }
    String document(String visibility) {
        String id = Db.id();
        db.update("insert into knowledge_document values(?,?,?,?,?,NULL)",
                id, camp, "基础设施联调资料", "测试", visibility);
        return id;
    }
    List<String> chunks(String version) {
        return db.rows("select id from knowledge_chunk where version_id=?", version)
                .stream().map(r -> r.get("id").toString()).toList();
    }
    void awaitParsed(String version) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until) {
            var v = db.one("select * from knowledge_version where id=?", version);
            if (!v.get("status").equals("PROCESSING")) {
                assertEquals("REVIEW", v.get("status"), v.get("error_message").toString());
                return;
            }
            Thread.sleep(50);
        }
        fail("资料解析超时");
    }
    String upload(String name, byte[] bytes) throws Exception {
        var v = documents.upload(new MockMultipartFile("file", name, "application/octet-stream", bytes),
                camp, "联调提取资料", "测试", "PUBLIC", null);
        String id = v.get("id").toString();
        worker.parse(id);
        awaitParsed(id);
        return id;
    }

    @Test void actualMysqlAndLargeTextStorage() throws Exception {
        try (var connection = db.jdbc.getDataSource().getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        String text = "中文大文本容量测试。".repeat(6000);
        String version = version(document("PUBLIC"), text);
        assertEquals(text, db.one("select extracted_text from knowledge_version where id=?", version).get("extracted_text"));
    }
    @Test void realVectorIndexVersionSwitchAndUnpublish() {
        String doc = document("PUBLIC");
        String first = version(doc, "第一版公开规则 ALPHA：须先咨询。");
        documents.publish(doc, first);
        var initial = retrieval.search(user, "公开规则", camp);
        assertFalse(initial.isEmpty());
        assertTrue(initial.stream().allMatch(c -> c.get("version_id").equals(first)));
        String next = version(doc, "第二版公开规则 BETA：核对后提交咨询。");
        MODEL.behavior.set("embedding-failure");
        assertThrows(ApiException.class, () -> documents.publish(doc, next));
        assertEquals(first, db.one("select current_version from knowledge_document where id=?", doc).get("current_version"));
        assertEquals("REVIEW", db.one("select status from knowledge_version where id=?", next).get("status"));
        MODEL.behavior.set("valid");
        documents.publish(doc, next);
        var updated = retrieval.search(user, "公开规则", camp);
        assertFalse(updated.isEmpty());
        assertTrue(updated.stream().allMatch(c -> c.get("version_id").equals(next)));
        assertTrue((Boolean) retrieval.citation(user, chunks(first).getFirst()).get("historical"));
        documents.unpublish(doc);
        assertTrue(retrieval.search(user, "公开规则", camp).isEmpty());
    }
    @Test void privateEvidenceNeverEntersUserRerankOrCitations() {
        String publicDoc = document("PUBLIC"), internalDoc = document("STAFF");
        String pub = version(publicDoc, "公共营地规则 PUB-FIXTURE");
        String secret = version(internalDoc, "内部资料 SECRET-IT-4821");
        documents.publish(publicDoc, pub);
        documents.publish(internalDoc, secret);
        var found = retrieval.search(user, "SECRET-IT-4821", camp);
        assertTrue(found.stream().allMatch(c -> c.get("visibility").equals("PUBLIC")));
        assertTrue(MODEL.rerankInput.get().stream().noneMatch(s -> s.contains("SECRET-IT-4821")));
        assertThrows(ApiException.class, () -> retrieval.citation(user, chunks(secret).getFirst()));
        assertTrue(retrieval.search(staff, "内部资料", camp).stream().anyMatch(c -> c.get("visibility").equals("STAFF")));
    }
    @Test void agentRetrievesRealPublishedIndexAndReturnsValidCitation() {
        String doc = document("PUBLIC"), v = version(doc, "公开规则：请先核对日期并提交咨询。");
        documents.publish(doc, v);
        var result = chat.chat(user, "查询该营地公开规则", null);
        @SuppressWarnings("unchecked") var citations = (List<Map<String,Object>>) result.get("citations");
        assertFalse(citations.isEmpty());
        assertTrue(citations.stream().allMatch(c -> c.get("version_id").equals(v)));
        assertTrue(result.get("answer").toString().contains("[[" + citations.getFirst().get("id") + "]]"));
        assertTrue(MODEL.embeddings.get() > 0 && MODEL.reranks.get() > 0);
    }
    @Test void concurrentConfirmationIsOneMysqlTransaction() throws Exception {
        String conversation = Db.id();
        db.update("insert into conversation values(?,?,?,?)", conversation, user.id(), "并发确认测试", Db.now());
        String draft = tickets.prepare(user, conversation, camp, "咨询", "联调确认", "演示问题").get("id").toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                requests.add(() -> tickets.confirm(user, draft).get("id").toString());
            var results = executor.invokeAll(requests);
            Set<String> ids = new HashSet<>();
            for (var future : results) ids.add(future.get());
            assertEquals(1, ids.size());
        }
        assertEquals(1L, ((Number) db.one("select count(*) total from service_ticket where draft_id=?", draft).get("total")).longValue());
    }
    @Test void realDocxAndPdfExtractionPreservesSourceOrder() throws Exception {
        var wordBytes = new ByteArrayOutputStream();
        try (var word = new XWPFDocument()) {
            word.createParagraph().createRun().setText("第一段来源 BEFORE-TABLE");
            var table = word.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目"); table.getRow(0).getCell(1).setText("规则");
            table.getRow(1).getCell(0).setText("宠物"); table.getRow(1).getCell(1).setText("牵引后可进入");
            word.createParagraph().createRun().setText("末段来源 AFTER-TABLE");
            word.write(wordBytes);
        }
        String v = upload("rules.docx", wordBytes.toByteArray());
        String text = db.one("select extracted_text from knowledge_version where id=?", v).get("extracted_text").toString();
        assertTrue(text.indexOf("BEFORE-TABLE") < text.indexOf("牵引后可进入"));
        assertTrue(text.indexOf("牵引后可进入") < text.indexOf("AFTER-TABLE"));
        var pdfBytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Actual PDF fixture source."); content.endText();
            }
            pdf.save(pdfBytes);
        }
        String pv = upload("source.pdf", pdfBytes.toByteArray());
        String ptext = db.one("select extracted_text from knowledge_version where id=?", pv).get("extracted_text").toString();
        assertTrue(ptext.contains("第 1 页") && ptext.contains("Actual PDF fixture source."));
    }
    @Test void scanOcrProtocolAndMalformedFileHandling() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(bytes); }
        String scanned = upload("scan.pdf", bytes.toByteArray());
        assertTrue(db.one("select extracted_text from knowledge_version where id=?", scanned)
                .get("extracted_text").toString().contains("OCR 协议模拟"));
        var bad = documents.upload(new MockMultipartFile("file", "bad.pdf", "application/pdf",
                "not a PDF".getBytes(StandardCharsets.UTF_8)), camp, "损坏文件", "测试", "PUBLIC", null);
        worker.parse(bad.get("id").toString());
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && db.one("select status from knowledge_version where id=?", bad.get("id")).get("status").equals("PROCESSING"))
            Thread.sleep(50);
        assertEquals("FAILED", db.one("select status from knowledge_version where id=?", bad.get("id")).get("status"));
        assertTrue(chunks(bad.get("id").toString()).isEmpty());
    }
    @Test void requeuedParsingCannotMutatePublishedChunks() throws Exception {
        String v = upload("stable.txt", "已核对的公开规则，不应被重复解析改写。".getBytes(StandardCharsets.UTF_8));
        String doc = db.one("select document_id from knowledge_version where id=?", v).get("document_id").toString();
        documents.publish(doc, v);
        var original = chunks(v);
        worker.parse(v);
        documentExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertEquals("PUBLISHED", db.one("select status from knowledge_version where id=?", v).get("status"));
        assertEquals(original, chunks(v));
        assertThrows(ApiException.class, () -> documents.saveExtracted(v, "不能覆盖已发布版本"));
        assertEquals(original, chunks(v));
    }
    @AfterAll static void cleanupFixtureCollection() {
        MODEL.close();
        try {
            RestClient.create().delete().uri(QDRANT + "/collections/" + COLLECTION).retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ignored) {
            // No collection was created if a prerequisite failed.
        }
    }
}

