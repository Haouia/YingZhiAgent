package com.campagent.config;
import com.campagent.common.*;
import com.campagent.knowledge.Documents;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
@Component
public class Seed implements ApplicationRunner {
    private final Db db;private final Documents documents;private final BCryptPasswordEncoder passwords;private final String password,mode,bindAddress;
    public Seed(Db db,Documents documents,BCryptPasswordEncoder passwords,@Value("${camp.demo-password}")String password,@Value("${camp.mode}")String mode,
                @Value("${server.address}")String bindAddress){this.db=db;this.documents=documents;this.passwords=passwords;this.password=password;this.mode=mode;this.bindAddress=bindAddress;}
    @SuppressWarnings("unchecked") public void run(ApplicationArguments args) throws Exception{
        boolean localDemoPassword = "123456".equals(password) && "demo".equals(mode)
                && Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(bindAddress);
        if(password.length()<12 && !localDemoPassword)throw new IllegalStateException("DEMO_PASSWORD must be at least 12 characters");
        db.update("update knowledge_version set status='FAILED',error_message='处理因服务重启中断，请重新上传' where status='PROCESSING'");
        for(var u:List.of(new String[]{"user","露营体验者","USER"},new String[]{"user2","另一位用户","USER"},new String[]{"staff","客服小营","STAFF"},new String[]{"ops","知识运营","OPS"})){
            if(db.rows("select id from app_user where username=?",u[0]).isEmpty())db.update("insert into app_user values(?,?,?,?,?)",u[0],u[0],passwords.encode(password),u[1],u[2]);
        }
        String text;try(var in=getClass().getResourceAsStream("/demo.json")){text=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        var data=Json.map(text);
        for(var c:(List<Map<String,Object>>)data.get("camps"))if(db.rows("select id from campsite where id=?",c.get("id")).isEmpty())db.update("insert into campsite values(?,?,?,?,?,?,?,?)",c.get("id"),c.get("name"),c.get("city"),c.get("terrain"),c.get("facilities"),c.get("fee_desc"),c.get("description"),Db.now());
        for(var d:(List<Map<String,Object>>)data.get("documents"))if(db.rows("select id from knowledge_document where id=?",d.get("id")).isEmpty()){
            String v=Db.id();db.update("insert into knowledge_document values(?,?,?,?,?,NULL)",d.get("id"),d.get("campsiteId"),d.get("title"),d.get("category"),d.get("visibility"));
            db.update("insert into knowledge_version values(?,?,?,?,?,?,?,?,?)",v,d.get("id"),d.get("id")+".md","","seed-"+d.get("id"),"","PROCESSING","",Db.now());
            documents.saveExtracted(v,d.get("content").toString());
            if(mode.equals("demo")){db.update("update knowledge_version set status='PUBLISHED' where id=?",v);db.update("update knowledge_document set current_version=? where id=?",v,d.get("id"));}
        }
    }
}


