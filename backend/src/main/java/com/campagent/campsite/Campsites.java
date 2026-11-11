package com.campagent.campsite;
import com.campagent.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class Campsites {
    private final Db db;
    public Campsites(Db db){this.db=db;}
    public List<Map<String,Object>> search(String city,String facility,String keyword) {
        var sql=new StringBuilder("select * from campsite where 1=1"); var args=new ArrayList<Object>();
        for(var filter:List.of(new String[]{"city",city==null?"":city},new String[]{"facilities",facility==null?"":facility},new String[]{"name",keyword==null?"":keyword})) {
            if(!filter[1].isBlank()){if(filter[1].length()>100)throw ApiException.bad("筛选条件过长");sql.append(" and ").append(filter[0]).append(" like ?");args.add("%"+filter[1].replace("%","\\%").replace("_","\\_")+"%");}
        }
        return db.rows(sql+" order by id limit 30",args.toArray());
    }
    public Map<String,Object> get(String id){return db.one("select * from campsite where id=?",id);}
}

