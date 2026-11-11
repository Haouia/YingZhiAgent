package com.campagent.ticket;
import com.campagent.auth.Actor;
import com.campagent.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
@Service
public class Tickets {
    private final Db db;
    public Tickets(Db db){this.db=db;}
    public Map<String,Object> preview(Actor actor,String conversation,String camp,String kind,String title,String description) {
        if(kind==null||!Set.of("咨询","投诉","建议").contains(kind)||title==null||title.isBlank()||title.length()>120||description==null||description.isBlank()||description.length()>3000) throw ApiException.bad("请填写有效的工单类别、标题和描述");
        db.one("select id from campsite where id=?",camp);
        db.one("select id from conversation where id=? and user_id=?",conversation,actor.id());
        return Map.of("id",Db.id(),"campsite_id",camp,"kind",kind,"title",title,"description",description,"status","PENDING");
    }
    @Transactional public Map<String,Object> prepare(Actor actor,String conversation,String camp,String kind,String title,String description) {
        return savePrepared(actor,conversation,preview(actor,conversation,camp,kind,title,description));
    }
    @Transactional public Map<String,Object> savePrepared(Actor actor,String conversation,Map<String,Object> draft) {
        db.one("select id from conversation where id=? and user_id=? for update",conversation,actor.id());
        db.update("update ticket_draft set status='INVALID' where conversation_id=? and user_id=? and status='PENDING'",conversation,actor.id());
        String id=draft.get("id").toString();db.update("insert into ticket_draft values(?,?,?,?,?,?,?,?,?)",id,actor.id(),conversation,draft.get("campsite_id"),draft.get("kind"),draft.get("title"),draft.get("description"),"PENDING",Db.now());
        return db.one("select * from ticket_draft where id=?",id);
    }
    @Transactional public Map<String,Object> confirm(Actor a,String id) {
        var draft=db.one("select * from ticket_draft where id=? and user_id=? for update",id,a.id());
        if(draft.get("status").equals("CONFIRMED")) return db.one("select * from service_ticket where draft_id=?",id);
        if(!draft.get("status").equals("PENDING")) throw new ApiException(HttpStatus.CONFLICT,"草稿已失效，请确认最新卡片");
        String ticket=Db.id();String now=Db.now();
        db.update("insert into service_ticket values(?,?,?,?,?,?,?,?,?,?,?)",ticket,id,a.id(),draft.get("campsite_id"),draft.get("kind"),draft.get("title"),draft.get("description"),"待处理",0,now,now);
        db.update("update ticket_draft set status='CONFIRMED' where id=?",id);
        return own(a,ticket);
    }
    public List<Map<String,Object>> mine(Actor a) { return db.rows("select t.*,c.name campsite_name from service_ticket t join campsite c on c.id=t.campsite_id where user_id=? order by t.created_at desc limit 100",a.id()); }
    public Map<String,Object> own(Actor a,String id){var t=db.one("select * from service_ticket where id=? and user_id=?",id,a.id());t.put("events",events(id));return t;}
    public List<Map<String,Object>> all(){return db.rows("select t.*,c.name campsite_name from service_ticket t join campsite c on c.id=t.campsite_id order by t.created_at desc limit 100");}
    public Map<String,Object> detail(String id){var t=db.one("select * from service_ticket where id=?",id);t.put("events",events(id));return t;}
    private List<Map<String,Object>> events(String id){return db.rows("select status,reply,created_at from ticket_event where ticket_id=? order by created_at",id);}
    @Transactional public Map<String,Object> handle(Actor a,String id,String status,String reply,int revision) {
        var t=db.one("select * from service_ticket where id=? for update",id);
        String old=t.get("status").toString();
        boolean allowed=old.equals("待处理")&&status.equals("处理中")||old.equals("处理中")&&Set.of("已解决","已关闭").contains(status);
        if(!allowed||((Number)t.get("revision")).intValue()!=revision)throw new ApiException(HttpStatus.CONFLICT,"工单状态已变化，请刷新");
        if(reply==null||reply.isBlank()||reply.length()>3000)throw ApiException.bad("请填写处理说明");
        String now=Db.now();db.update("update service_ticket set status=?,revision=revision+1,updated_at=? where id=?",status,now,id);
        db.update("insert into ticket_event values(?,?,?,?,?,?)",Db.id(),id,a.id(),status,reply,now);return detail(id);
    }
}

