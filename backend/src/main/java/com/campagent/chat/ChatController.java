package com.campagent.chat;
import com.campagent.common.*;
import com.campagent.auth.Actor;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatService chat;private final Db db;
    public ChatController(ChatService chat,Db db){this.chat=chat;this.db=db;}
    public record Message(@NotBlank @Size(max=2000)String message,@Size(max=36)String conversationId){}
    @PostMapping("/chat") public Map<?,?> message(@Valid @RequestBody Message r){return chat.chat(Actor.current(),r.message(),r.conversationId());}
    @GetMapping("/conversations") public List<?> list(){return db.rows("select * from conversation where user_id=? order by created_at desc limit 100",Actor.current().id());}
    @GetMapping("/conversations/{id}/messages") public List<?> messages(@PathVariable String id){
        db.one("select id from conversation where id=? and user_id=?",id,Actor.current().id());
        var rows=db.rows("select * from chat_message where conversation_id=? order by created_at",id);for(var r:rows)r.put("extra",Json.map(r.get("extra").toString()));return rows;
    }
}

