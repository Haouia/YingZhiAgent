package com.campagent.knowledge;
import com.campagent.common.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
@Service
public class Documents {
    private final Db db;private final Path storage;private final Cloud cloud;private final Retrieval retrieval;
    public Documents(Db db,Cloud cloud,Retrieval retrieval,@Value("${camp.storage}")String storage){this.db=db;this.cloud=cloud;this.retrieval=retrieval;this.storage=Path.of(storage).toAbsolutePath().normalize();}
    @Transactional public Map<String,Object> upload(MultipartFile file,String campsite,String title,String category,String visibility,String documentId) throws Exception {
        db.one("select id from campsite where id=?",campsite);
        if(title==null||title.isBlank()||title.length()>200||category==null||category.isBlank()||category.length()>80||!Set.of("PUBLIC","STAFF").contains(visibility))throw ApiException.bad("请填写标题、类别和访问范围");
        String name=Objects.toString(file.getOriginalFilename(),"");String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!Set.of("md","txt","pdf","docx","png","jpg","jpeg").contains(ext)||file.isEmpty()||file.getSize()>10*1024*1024||name.length()>250)throw ApiException.bad("支持 10MB 内的 MD/TXT/PDF/DOCX/PNG/JPG");
        String doc=documentId==null||documentId.isBlank()?Db.id():documentId;
        if(documentId!=null&&!documentId.isBlank()){
            var existing=db.one("select * from knowledge_document where id=? for update",doc);
            if(!Objects.equals(existing.get("campsite_id"),campsite)||!Objects.equals(existing.get("visibility"),visibility))throw ApiException.bad("新版本必须保持营地和访问范围一致");
        }else db.update("insert into knowledge_document values(?,?,?,?,?,NULL)",doc,campsite,title,category,visibility);
        byte[] bytes=file.getBytes();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var prior=db.rows("select * from knowledge_version where document_id=? and content_hash=? and status<>'FAILED'",doc,hash);
        if(!prior.isEmpty())return prior.getFirst();
        String id=Db.id();Files.createDirectories(storage);Path path=storage.resolve(id+"."+ext);
        Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
        db.update("insert into knowledge_version values(?,?,?,?,?,?,?,?,?)",id,doc,name,path.toString(),hash,"","PROCESSING","",Db.now());
        return db.one("select * from knowledge_version where id=?",id);
    }
    public List<Map<String,Object>> list(){return db.rows("select d.*,v.id version_id,v.filename,v.status,v.error_message,v.created_at from knowledge_document d join knowledge_version v on d.id=v.document_id order by v.created_at desc limit 200");}
    public Map<String,Object> detail(String id){
        var d=db.one("select * from knowledge_document where id=?",id);d.put("versions",db.rows("select id,filename,extracted_text,status,error_message,created_at from knowledge_version where document_id=? order by created_at desc",id));return d;
    }
    @Transactional public Map<String,Object> publish(String doc,String version) {
        db.one("select * from knowledge_document where id=? for update",doc);
        var v=db.one("select * from knowledge_version where id=? and document_id=?",version,doc);
        if(!Set.of("REVIEW","PUBLISHED").contains(v.get("status").toString()))throw ApiException.bad("资料尚未解析完成，请核对提取内容后发布");
        var chunks=db.rows("select * from knowledge_chunk where version_id=? order by location",version);
        if(chunks.isEmpty())throw ApiException.bad("没有可发布的知识片段");
        if(retrieval.usesVectorIndex())cloud.index(chunks);
        db.update("update knowledge_version set status='PUBLISHED' where id=?",version);db.update("update knowledge_document set current_version=? where id=?",version,doc);return detail(doc);
    }
    @Transactional public void unpublish(String doc){db.one("select id from knowledge_document where id=? for update",doc);db.update("update knowledge_document set current_version=NULL where id=?",doc);}
    public static List<String> split(String text){
        var result=new ArrayList<String>();StringBuilder current=new StringBuilder();
        for(String p:text.replace("\r","").split("\n\\s*\n")){
            p=p.strip();if(p.isEmpty())continue;
            if(current.length()+p.length()>800&&!current.isEmpty()){result.add(current.toString());current.setLength(0);}
            while(p.length()>800){result.add(p.substring(0,800));p=p.substring(720);}
            current.append(p).append("\n\n");
        }
        if(!current.isEmpty())result.add(current.toString().strip());return result;
    }
    @Transactional public void saveExtracted(String id,String text){
        if(text.isBlank()||text.length()>150000)throw ApiException.bad("提取内容为空或超过 15 万字符");
        var version=db.one("select status from knowledge_version where id=? for update",id);
        if(!"PROCESSING".equals(version.get("status")))throw ApiException.bad("资料已经完成处理，不允许改写现有版本");
        db.update("delete from knowledge_chunk where version_id=?",id);
        var parts=split(text);int i=0;
        for(String part:parts)db.update("insert into knowledge_chunk values(?,?,?,?)",Db.id(),id,"片段 "+(++i),part);
        db.update("update knowledge_version set extracted_text=?,status='REVIEW',error_message='' where id=?",text,id);
    }
    public Path file(String version){return Path.of(db.one("select file_path from knowledge_version where id=?",version).get("file_path").toString());}
}


