package com.campagent.campsite;

import com.campagent.common.Db;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class MapQuotaMySqlIT extends MapQuotaTest {
    @Override @BeforeEach void setup() {
        String url=System.getenv("CAMP_TEST_MYSQL_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/camp_agent_it\\?.*"))
            throw new IllegalStateException("只允许本机独立 camp_agent_it 测试库");
        var source=new DriverManagerDataSource(url,System.getenv("CAMP_TEST_MYSQL_USER"),System.getenv("CAMP_TEST_MYSQL_PASSWORD"));
        manager=new DataSourceTransactionManager(source); db=new Db(new JdbcTemplate(source));
        db.update("create table if not exists map_quota(bucket varchar(180) primary key,used int not null)");
        db.update("delete from map_quota");
    }
}
