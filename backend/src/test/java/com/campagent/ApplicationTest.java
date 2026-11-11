package com.campagent;
import com.campagent.common.*;
import com.campagent.knowledge.*;
import com.campagent.chat.ChatService;
import com.campagent.auth.Actor;
import com.campagent.campsite.Campsites;
import com.campagent.campsite.PublicCampsites;
import com.campagent.ticket.Tickets;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","camp.mode=demo","camp.daily-limit=1000","camp.storage=./target/test-files"})
class ApplicationTest {
    @LocalServerPort int port;
    @Autowired Db db;
    @Autowired Documents documents;
    @Autowired Retrieval retrieval;
    @Autowired Campsites camps;
    @Autowired PublicCampsites publicCamps;
    @Autowired Tickets tickets;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final HttpClient client=HttpClient.newHttpClient();
    record Result(int status,Map<String,Object> data,String text){}
    Result request(String method,String path,String token,Object body) throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path));
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(body!=null)req.header("Content-Type","application/json");
        req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.write(body)));
        var r=client.send(req.build(),HttpResponse.BodyHandlers.ofString());
        Map<String,Object> m=r.body().startsWith("{")?Json.map(r.body()):Map.of();return new Result(r.statusCode(),m,r.body());
    }
    String login(String user) throws Exception {var r=request("POST","/auth/login",null,Map.of("username",user,"password","123456"));assertEquals(200,r.status,r.text);return r.data.get("token").toString();}
    Map<String,Object> draft(String token) throws Exception {
        var r=request("POST","/chat",token,Map.of("message","投诉青岚竹溪营地：停车标识不清晰"));assertEquals(200,r.status,r.text);
        @SuppressWarnings("unchecked")var d=(Map<String,Object>)r.data.get("pendingTicketDraft");assertNotNull(d);return r.data;
    }
    @Test void healthAndLogin() throws Exception{
        assertEquals(200,request("GET","/health",null,null).status);
        assertEquals(401,request("GET","/campsites",null,null).status);
        assertEquals(401,request("POST","/auth/login",null,Map.of("username","user","password","bad")).status);
        for(var account:Map.of("user","USER","user2","USER","staff","STAFF","ops","OPS").entrySet()){
            assertEquals(account.getValue(),request("GET","/auth/me",login(account.getKey()),null).data.get("role"));
        }
    }
    @Test void rolesAreEnforced() throws Exception {
        String user=login("user");assertEquals(403,request("GET","/ops/documents",user,null).status);
        assertEquals(403,request("GET","/staff/tickets",user,null).status);
        assertEquals(403,request("PUT","/ops/campsites/c01",user,Map.of()).status);
        assertEquals(200,request("GET","/ops/documents",login("ops"),null).status);
    }
    @Test void validationAndCampSearch() throws Exception {
        var t=login("user");assertEquals(200,request("GET","/campsites?city="+URLEncoder.encode("杭州",StandardCharsets.UTF_8),t,null).status);
        assertEquals(400,request("POST","/chat",t,Map.of("message"," ")).status);
        assertEquals(400,request("POST","/chat",t,Map.of("message","a".repeat(2001))).status);
        assertEquals(404,request("GET","/campsites/no-such",t,null).status);
    }
    @Test void ticketConfirmationIsolationAndWorkflow() throws Exception {
        var user=login("user");var user2=login("user2");var staff=login("staff");
        var response=draft(user);@SuppressWarnings("unchecked")var d=(Map<String,Object>)response.get("pendingTicketDraft");String id=d.get("id").toString();
        assertTrue(db.rows("select id from service_ticket where draft_id=?",id).isEmpty(),"草稿不能自动创建正式工单");
        assertEquals(404,request("POST","/ticket-drafts/"+id+"/confirm",user2,null).status);
        assertEquals(404,request("GET","/conversations/"+response.get("conversationId")+"/messages",user2,null).status);
        var confirm=request("POST","/ticket-drafts/"+id+"/confirm",user,null);assertEquals(200,confirm.status,confirm.text);
        var repeat=request("POST","/ticket-drafts/"+id+"/confirm",user,null);assertEquals(confirm.data.get("id"),repeat.data.get("id"));
        String tid=confirm.data.get("id").toString();assertEquals(404,request("GET","/tickets/"+tid,user2,null).status);
        assertEquals(200,request("PATCH","/staff/tickets/"+tid,staff,Map.of("status","处理中","reply","正在核查","revision",0)).status);
        assertEquals(409,request("PATCH","/staff/tickets/"+tid,staff,Map.of("status","已解决","reply","已处理","revision",0)).status);
        assertEquals(200,request("PATCH","/staff/tickets/"+tid,staff,Map.of("status","已解决","reply","已补充标识","revision",1)).status);
        var own=request("GET","/tickets/"+tid,user,null);assertEquals("已解决",own.data.get("status"));assertTrue(own.text.contains("已补充标识"));
    }
    @Test void changedDraftInvalidatesOldCard() throws Exception {
        var user=login("user");var first=draft(user);@SuppressWarnings("unchecked")var a=(Map<String,Object>)first.get("pendingTicketDraft");
        var next=request("POST","/chat",user,Map.of("message","投诉青岚竹溪营地：修改为公共区域标识问题","conversationId",first.get("conversationId")));assertEquals(200,next.status,next.text);
        assertEquals(409,request("POST","/ticket-drafts/"+a.get("id")+"/confirm",user,null).status);
    }
    @Test void citationsAndPrivateKnowledge() throws Exception {
        var userActor=new Actor("user","USER","测试");
        var staffActor=new Actor("staff","STAFF","客服");
        var normal=retrieval.search(userActor,"宠物","c01");assertFalse(normal.isEmpty());
        assertTrue(normal.stream().allMatch(c->c.get("visibility").equals("PUBLIC")));
        var internal=retrieval.search(staffActor,"STAFF-ONLY-7391","c01").stream().filter(c->c.get("visibility").equals("STAFF")).findFirst().orElseThrow();
        assertThrows(ApiException.class,()->retrieval.citation(userActor,internal.get("id").toString()));
        var result=request("POST","/chat",login("user"),Map.of("message","青岚竹溪营地宠物规定"));assertEquals(200,result.status,result.text);assertTrue(result.text.contains("未调用大模型"));assertFalse(result.text.contains("STAFF-ONLY-7391"));
    }
    @Test void publicationAndHistoricalCitation() {
        String doc="test-"+Db.id(),v=Db.id(),now=Db.now();
        db.update("insert into knowledge_document values(?,?,?,?,?,NULL)",doc,"c01","版本测试","测试","PUBLIC");
        db.update("insert into knowledge_version values(?,?,?,?,?,?,?,?,?)",v,doc,"test.md","","hash","","PROCESSING","",now);
        documents.saveExtracted(v,"独特版本校验词：松果广播。");
        assertTrue(retrieval.search(new Actor("user","USER","测"),"松果广播","c01").stream().noneMatch(c->c.get("document_id").equals(doc)));
        documents.publish(doc,v);
        var chunk=db.one("select * from knowledge_chunk where version_id=?",v);
        documents.unpublish(doc);
        assertTrue((Boolean)retrieval.citation(new Actor("user","USER","测"),chunk.get("id").toString()).get("historical"));
        assertTrue(retrieval.search(new Actor("user","USER","测"),"松果广播","c01").stream().noneMatch(c->c.get("document_id").equals(doc)));
    }
    @Test void longTextChunkingRetainsContent(){
        String text="甲".repeat(1800);var chunks=Documents.split(text);assertTrue(chunks.size()>=3);assertTrue(chunks.stream().allMatch(c->c.length()<=800));
        assertEquals(List.of(),Documents.split(" \n\n"));
    }
    @Test void springAiToolLoopWithLocalProtocolServer() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions",exchange->{
            var body=Json.map(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            Map<String,Object> response;
            if(calls.incrementAndGet()==1){
                assertTrue(body.containsKey("tools"));
                var function=Map.of("name","searchCampsites","arguments",Json.write(Map.of("city","杭州","facility","洗手间","keyword","")));
                var tool=Map.of("id","call-1","type","function","function",function);
                var message=Map.of("role","assistant","tool_calls",List.of(tool));
                response=Map.of("choices",List.of(Map.of("finish_reason","tool_calls","message",message)));
            }
            else{assertTrue(body.toString().contains("青岚竹溪营地"));response=Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("role","assistant","content","查询工具已返回杭州的青岚竹溪营地和云汀湖畔营地。"))));}
            byte[] bytes=Json.write(response).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try{
            var cloud=new Cloud("test-key","http://127.0.0.1:"+server.getAddress().getPort(),"qwen-plus","text-embedding-v4","qwen3-rerank","","qwen-vl-ocr","","","test");
            var chat=new ChatService(db,camps,retrieval,tickets,cloud,publicCamps,transactionManager,"cloud",1000);
            var result=chat.chat(new Actor("user","USER","测"),"杭州有洗手间的营地",null);assertEquals(2,calls.get());assertTrue(result.get("answer").toString().contains("青岚"));assertFalse(((List<?>)result.get("toolSummary")).isEmpty());
        }finally{server.stop(0);}
    }
}

