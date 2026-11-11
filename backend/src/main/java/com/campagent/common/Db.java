package com.campagent.common;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class Db {
    public final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Map<String,Object>> rows(String sql,Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql,Object... args) { var r=rows(sql,args); if(r.isEmpty()) throw ApiException.missing(); return r.getFirst(); }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public static String id() { return UUID.randomUUID().toString(); }
    public static String now() { return java.time.Instant.now().toString(); }
}

