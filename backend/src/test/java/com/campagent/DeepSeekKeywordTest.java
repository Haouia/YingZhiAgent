package com.campagent;

import com.campagent.auth.Actor;
import com.campagent.chat.ChatService;
import com.campagent.common.*;
import com.campagent.knowledge.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:keyword;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "camp.mode=cloud", "camp.chat-provider=deepseek", "camp.deepseek-api-key=deepseek-fixture", "camp.api-key=",
        "camp.retrieval-mode=keyword", "camp.jwt-secret=keyword-fixture-signing-key-at-least-32-bytes",
        "camp.demo-password=CampDemo2026!", "camp.storage=./target/keyword-documents"})
class DeepSeekKeywordTest {
    static final ModelProtocolStub MODEL;
    static {
        try { MODEL = new ModelProtocolStub(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("camp.deepseek-base-url", MODEL::url);
        p.add("camp.deepseek-model", () -> "deepseek-flash");
    }
    @AfterAll static void close() { MODEL.close(); }
    @Autowired Db db;
    @Autowired Documents documents;
    @Autowired Retrieval retrieval;
    @Autowired ChatService chat;
    @Autowired com.campagent.ticket.Tickets tickets;
    final Actor user = new Actor("user", "USER", "测试用户");
    final Actor staff = new Actor("staff", "STAFF", "测试客服");
    String camp;
    @BeforeEach void setup() {
        MODEL.behavior.set("valid");
        camp = Db.id();
        db.update("insert into campsite values(?,?,?,?,?,?,?,?)", camp, "关键词测试营地", "测试城市", "草地", "洗手间", "仅测试", "虚构资料", Db.now());
        MODEL.campsite.set(camp);
    }
    String document(String visibility, String text) {
        String doc = Db.id(), version = Db.id();
        db.update("insert into knowledge_document values(?,?,?,?,?,NULL)", doc, camp, "关键词资料", "规则", visibility);
        db.update("insert into knowledge_version values(?,?,?,?,?,?,?,?,?)", version, doc, "fixture.md", "", "hash-" + version, "", "PROCESSING", "", Db.now());
        documents.saveExtracted(version, text);
        documents.publish(doc, version);
        return doc;
    }
    @Test void cloudPublishingAndAgentCitationsWorkWithoutEmbeddingService() {
        document("PUBLIC", "宠物规则：演示营地要求宠物全程牵绳。");
        assertFalse(retrieval.usesVectorIndex());
        var result = chat.chat(user, "宠物规则是什么？", null);
        assertEquals("cloud", result.get("mode"));
        assertEquals("deepseek", result.get("chatProvider"));
        assertEquals("keyword", result.get("retrievalMode"));
        var evidence = Json.rows(Json.write(result.get("citations")));
        assertFalse(evidence.isEmpty());
        assertTrue(result.get("answer").toString().contains("[[" + evidence.getFirst().get("id") + "]]"));
        assertEquals(0, MODEL.embeddings.get());
        assertEquals(0, MODEL.reranks.get());
    }
    @Test void keywordRetrievalHonorsVisibilityAndUnpublish() {
        String published = document("PUBLIC", "宠物规则：必须牵绳。");
        document("STAFF", "宠物内部规则：仅客服可见。");
        assertEquals(1, retrieval.search(user, "宠物规则", camp).size());
        assertEquals(2, retrieval.search(staff, "宠物规则", camp).size());
        documents.unpublish(published);
        assertTrue(retrieval.search(user, "宠物规则", camp).isEmpty());
        assertEquals(1, retrieval.search(staff, "宠物规则", camp).size());
    }
    @Test void unknownCampAndMissingCitationReturnAnEvidenceGap() {
        document("PUBLIC", "虚构营地取消规则：入住前48小时免费取消。");
        MODEL.campsite.set("");
        MODEL.behavior.set("uncited-answer");
        var result = chat.chat(user, "蓝港环岛营地免费取消的时限是几小时？", null);
        assertTrue(result.get("answer").toString().contains("未找到可核实"));
        assertFalse(result.get("answer").toString().contains("免费退款"));
        assertEquals(List.of(), result.get("citations"));
    }

    @Test void fabricatedCitationIsStillRejected() {
        document("PUBLIC", "宠物规则：必须牵绳。");
        MODEL.behavior.set("invented-reference");
        int messagesBefore = db.rows("select id from chat_message where role='assistant'").size();
        var error = assertThrows(ApiException.class, () -> chat.chat(user, "宠物规则是什么？", null));
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, error.status);
        assertFalse(MODEL.lastBody.get().containsKey("tools"));
        assertEquals(messagesBefore, db.rows("select id from chat_message where role='assistant'").size());
    }

    @Test void complaintDraftIsAcknowledgedWhenRuleSearchHasNoEvidence() {
        MODEL.behavior.set("ticket-without-evidence");
        var result = chat.chat(user, "投诉：停车入口指示不清，请整理草稿待我确认。", null);
        var draft = Json.map(Json.write(result.get("pendingTicketDraft")));
        assertEquals("PENDING", draft.get("status"));
        assertTrue(result.get("answer").toString().contains("草稿"));
        assertTrue(result.get("answer").toString().contains("确认"));
        assertFalse(result.get("answer").toString().contains("未找到可核实"));
        assertEquals(List.of(), result.get("citations"));
        assertTrue(db.rows("select id from service_ticket where draft_id=?", draft.get("id")).isEmpty());
    }

    @Test void citationRepairUsesCurrentEvidenceWithoutCallingMoreTools() {
        document("PUBLIC", "宠物规则：必须牵绳。");
        MODEL.behavior.set("repairable-reference");
        var result = chat.chat(user, "宠物规则是什么？", null);
        var evidence = Json.rows(Json.write(result.get("citations")));
        assertEquals(1, evidence.size());
        assertTrue(result.get("answer").toString().contains("[[" + evidence.getFirst().get("id") + "]]"));
        assertFalse(result.get("answer").toString().contains("免费退款"));
        assertFalse(MODEL.lastBody.get().containsKey("tools"));
        assertEquals(1, Json.rows(Json.write(result.get("toolSummary"))).size());
    }

    @Test void modelCannotSupplyRulesWithoutUsingEvidence() {
        MODEL.behavior.set("direct-answer-without-tools");
        var result = chat.chat(user, "免费取消需要提前几小时？", null);
        assertFalse(result.get("answer").toString().contains("1小时"));
        assertEquals(List.of(), result.get("citations"));
        assertTrue(result.get("answer").toString().contains("未找到可核实"));
    }

    @Test void namedCampRulesAreRetrievedEvenWhenModelSkipsTools() {
        String name = "依据测试营地" + camp;
        db.update("update campsite set name=? where id=?", name, camp);
        document("PUBLIC", "虚构演示：入住前48小时可免费取消。");
        MODEL.behavior.set("direct-answer-without-tools");
        var result = chat.chat(user, name + "免费取消需要提前几小时？", null);
        assertFalse(result.get("answer").toString().contains("1小时"));
        assertTrue(result.get("answer").toString().contains("48小时"));
        assertFalse(Json.rows(Json.write(result.get("citations"))).isEmpty());
    }

    @Test void failedAnswerPreservesThePreviousDraft() {
        String conversation = Db.id();
        db.update("insert into conversation values(?,?,?,?)", conversation, user.id(), "草稿失败回归", Db.now());
        var old = tickets.prepare(user, conversation, camp, "投诉", "原草稿", "原问题");
        MODEL.behavior.set("ticket-invalid-answer");
        var error = assertThrows(ApiException.class, () -> chat.chat(user, "修改投诉草稿为停车标识问题", conversation));
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, error.status);
        assertEquals("PENDING", db.one("select status from ticket_draft where id=?", old.get("id")).get("status"));
        assertEquals(1, db.rows("select id from ticket_draft where conversation_id=?", conversation).size());
        assertTrue(db.rows("select id from chat_message where conversation_id=?", conversation).isEmpty());
    }

    @Test void messageWriteFailureRollsBackDraftReplacement() {
        String conversation = Db.id();
        db.update("insert into conversation values(?,?,?,?)", conversation, user.id(), "事务失败回归", Db.now());
        var old = tickets.prepare(user, conversation, camp, "投诉", "原草稿", "原问题");
        MODEL.behavior.set("ticket-without-evidence");
        db.jdbc.execute("alter table chat_message add constraint reject_draft_reply check (role <> 'assistant' or content <> '已整理工单草稿。请核对下方卡片并点击确认，之后才会生成正式工单。')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> chat.chat(user, "修改投诉草稿为停车标识问题", conversation));
            assertEquals("PENDING", db.one("select status from ticket_draft where id=?", old.get("id")).get("status"));
            assertEquals(1, db.rows("select id from ticket_draft where conversation_id=?", conversation).size());
            assertTrue(db.rows("select id from chat_message where conversation_id=?", conversation).isEmpty());
        } finally {
            db.jdbc.execute("alter table chat_message drop constraint reject_draft_reply");
        }
    }
}
