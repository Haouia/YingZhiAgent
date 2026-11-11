package com.campagent.knowledge;
import com.campagent.common.*;
import com.campagent.auth.Actor;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class Retrieval {
    private String retrievalMode="hybrid";
    @org.springframework.beans.factory.annotation.Autowired
    public void configureMode(@Value("${camp.retrieval-mode:hybrid}")String mode){
        if(!Set.of("keyword","hybrid").contains(mode))throw new IllegalArgumentException("不支持的检索模式");
        retrievalMode=mode;
    }
    public boolean usesVectorIndex(){return !demo&&retrievalMode.equals("hybrid");}
    public String mode(){return demo?"keyword":retrievalMode;}
    private final Db db;private final Cloud cloud;private final boolean demo;
    public Retrieval(Db db,Cloud cloud,@Value("${camp.mode}")String mode){this.db=db;this.cloud=cloud;this.demo=mode.equals("demo");}
    public List<Map<String,Object>> search(Actor actor,String question,String campsite) {
        if(question==null||question.isBlank()||question.length()>2000)throw ApiException.bad("检索问题不能为空或过长");
        String sql="select c.*,d.id document_id,d.title,d.visibility,d.campsite_id from knowledge_chunk c join knowledge_version v on c.version_id=v.id join knowledge_document d on d.current_version=v.id where v.status='PUBLISHED'";
        var args=new ArrayList<Object>();
        if(!actor.staff())sql+=" and d.visibility='PUBLIC'";
        if(campsite!=null&&!campsite.isBlank()){sql+=" and d.campsite_id=?";args.add(campsite);}
        var chunks=db.rows(sql,args.toArray());
        // shortcut: BM25 uses the same SQL corpus in process (<=5000 chunks); move to a dedicated text index when the corpus grows.
        if(chunks.size()>5000)throw ApiException.bad("资料规模超出本版检索上限");
        var lexical=bm25(question,chunks);
        if(!usesVectorIndex())return lexical.stream().limit(5).toList();
        var dense=cloud.dense(question,chunks.stream().map(c->c.get("id").toString()).toList());
        var scores=new HashMap<String,Double>();
        for(int i=0;i<Math.min(20,lexical.size());i++)scores.merge(lexical.get(i).get("id").toString(),1.0/(60+i+1),Double::sum);
        for(int i=0;i<dense.size();i++)scores.merge(dense.get(i),1.0/(60+i+1),Double::sum);
        var fused=chunks.stream().filter(c->scores.containsKey(c.get("id").toString())).sorted(Comparator.comparingDouble((Map<String,Object> c)->scores.get(c.get("id").toString())).reversed()).limit(40).toList();
        var ranked=cloud.rerank(question,fused);
        return ranked.stream().filter(c->!db.rows("select c.id from knowledge_chunk c join knowledge_document d on d.current_version=c.version_id where c.id=?"+(actor.staff()?"":" and d.visibility='PUBLIC'"),c.get("id")).isEmpty()).toList();
    }
    public Map<String,Object> citation(Actor actor,String id) {
        var c=db.one("select c.*,d.title,d.visibility,d.current_version,d.campsite_id from knowledge_chunk c join knowledge_version v on v.id=c.version_id join knowledge_document d on d.id=v.document_id where c.id=?",id);
        if(!actor.staff()&&!c.get("visibility").equals("PUBLIC"))throw ApiException.missing();
        c.put("historical",!Objects.equals(c.get("current_version"),c.get("version_id")));return c;
    }
    static List<String> tokens(String text) {
        var out=new ArrayList<String>();var normalized=text.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+").matcher(normalized);
        while(m.find()){String s=m.group();if(s.matches("[a-z0-9]+"))out.add(s);else{for(int i=0;i<s.length();i++){out.add(s.substring(i,i+1));if(i+1<s.length())out.add(s.substring(i,i+2));}}}return out;
    }
    private static String expand(String question) {
        var expanded=new StringBuilder(question);
        for(var group:List.of(
                List.of("退掉","退订","取消","撤销","去不了","取消 退订 改期"),
                List.of("撤营","离场","退场","走人","收帐篷","入住 离开 时间"),
                List.of("带狗","带猫","小狗","毛孩子","宠物 牵引"),
                List.of("花多少钱","啥价","价钱","一宿","过一晚","收费 费用 营位"),
                List.of("点火","生火","升篝火","明火 篝火 木炭"),
                List.of("搁哪","帐篷旁","驶入","停好车","停车 车辆 营位"))) {
            if(group.subList(0,group.size()-1).stream().anyMatch(question::contains)) expanded.append(' ').append(group.getLast());
        }
        return expanded.toString();
    }
    public static List<Map<String,Object>> bm25(String question,List<Map<String,Object>> chunks) {
        var query=new HashSet<>(tokens(expand(question)));if(query.isEmpty()||chunks.isEmpty())return List.of();
        var frequencies=new ArrayList<Map<String,Integer>>();var df=new HashMap<String,Integer>();
        int[] lengths=new int[chunks.size()];long totalLength=0;
        for(int i=0;i<chunks.size();i++){
            var words=tokens(chunks.get(i).get("content").toString());lengths[i]=words.size();totalLength+=words.size();
            var frequency=new HashMap<String,Integer>();
            for(String word:words)if(query.contains(word))frequency.merge(word,1,Integer::sum);
            for(String word:frequency.keySet())df.merge(word,1,Integer::sum);
            frequencies.add(frequency);
        }
        double avg=(double)totalLength/chunks.size();
        var scores=new HashMap<String,Double>();
        for(int i=0;i<chunks.size();i++){
            double score=0;
            for(String term:query){int tf=frequencies.get(i).getOrDefault(term,0);if(tf==0)continue;double idf=Math.log(1+(chunks.size()-df.get(term)+0.5)/(df.get(term)+0.5));score+=idf*tf*2.2/(tf+1.2*(0.25+0.75*lengths[i]/Math.max(avg,1)));}
            if(score>0)scores.put(chunks.get(i).get("id").toString(),score);
        }
        return chunks.stream().filter(c->scores.containsKey(c.get("id").toString())).sorted(Comparator.comparingDouble((Map<String,Object> c)->scores.get(c.get("id").toString())).reversed().thenComparing(c->c.get("id").toString())).toList();
    }
}

