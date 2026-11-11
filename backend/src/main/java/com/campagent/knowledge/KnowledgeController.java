package com.campagent.knowledge;
import com.campagent.common.*;
import com.campagent.auth.Actor;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api")
public class KnowledgeController {
    private final Documents docs;private final DocumentWorker worker;private final Retrieval retrieval;private final Db db;
    public KnowledgeController(Documents docs,DocumentWorker worker,Retrieval retrieval,Db db){this.docs=docs;this.worker=worker;this.retrieval=retrieval;this.db=db;}
    @PreAuthorize("hasRole('OPS')") @GetMapping("/ops/documents") public List<?> list(){return docs.list();}
    @PreAuthorize("hasRole('OPS')") @GetMapping("/ops/documents/{id}") public Map<?,?> detail(@PathVariable String id){return docs.detail(id);}
    @PreAuthorize("hasRole('OPS')") @PostMapping("/ops/documents") public Map<?,?> upload(@RequestParam MultipartFile file,@RequestParam String campsiteId,@RequestParam String title,@RequestParam String category,@RequestParam(defaultValue="PUBLIC")String visibility,@RequestParam(required=false)String documentId) throws Exception {
        var v=docs.upload(file,campsiteId,title,category,visibility,documentId);if(v.get("status").equals("PROCESSING")){try{worker.parse(v.get("id").toString());}catch(org.springframework.core.task.TaskRejectedException e){db.update("update knowledge_version set status='FAILED',error_message='导入队列已满，请稍后重试' where id=?",v.get("id"));throw ApiException.bad("导入队列已满，请稍后重试");}}return Map.of("id",v.get("document_id"),"versionId",v.get("id"));
    }
    @PreAuthorize("hasRole('OPS')") @PostMapping("/ops/documents/{id}/publish") public Map<?,?> publish(@PathVariable String id,@RequestBody Map<String,String> r){return docs.publish(id,r.get("versionId"));}
    @PreAuthorize("hasRole('OPS')") @PostMapping("/ops/documents/{id}/unpublish") public Map<?,?> unpublish(@PathVariable String id){docs.unpublish(id);return Map.of("ok",true);}
    @GetMapping("/knowledge/citations/{id}") public Map<?,?> citation(@PathVariable String id){return retrieval.citation(Actor.current(),id);}
    @PreAuthorize("hasRole('OPS')") @GetMapping("/ops/metrics") public Map<?,?> metrics(){
        return Map.of("requests",db.rows("select mode,status,count(*) total,avg(elapsed_ms) avg_ms from agent_run group by mode,status"),"tickets",db.rows("select status,count(*) total from service_ticket group by status"),"documents",db.rows("select status,count(*) total from knowledge_version group by status"));
    }
}

