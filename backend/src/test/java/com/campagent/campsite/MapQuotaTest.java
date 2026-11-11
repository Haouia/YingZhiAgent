package com.campagent.campsite;

import com.campagent.common.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class MapQuotaTest {
    Db db;
    DataSourceTransactionManager manager;
    final Instant time = Instant.parse("2026-11-10T15:59:58Z");
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        manager = new DataSourceTransactionManager(source); db = new Db(new JdbcTemplate(source));
        db.update("create table map_quota(bucket varchar(180) primary key,used int not null)");
    }
    @Test void minuteDayAccountAndRestartBoundaries() {
        var quota = new MapQuota(db, manager, 1, 2, 20);
        quota.reserve("alice", time);
        var failure = assertThrows(ApiException.class, () -> quota.reserve("alice", time));
        assertEquals(429, failure.status.value()); assertEquals(2, failure.retryAfter);
        quota.reserve("bob", time);
        var restarted = new MapQuota(db, manager, 1, 2, 20);
        assertThrows(ApiException.class, () -> restarted.reserve("alice", time));
        restarted.reserve("alice", time.minusSeconds(60));
        assertThrows(ApiException.class, () -> restarted.reserve("alice", time.minusSeconds(120)));
        restarted.reserve("alice", time.plusSeconds(2)); // Beijing midnight, not UTC midnight.
    }
    @Test void concurrentUsersCannotExceedGlobalBudgetAndRejectedReservationsRollBack() throws Exception {
        var quota = new MapQuota(db, manager, 30, 200, 7);
        var accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<Future<?>>();
            for(int i=0;i<20;i++) { String user="u"+i; work.add(pool.submit(() -> {
                try { quota.reserve(user,time); accepted.incrementAndGet(); }
                catch(ApiException limited) { assertEquals(429,limited.status.value()); }
            })); }
            for(var result:work) result.get();
        }
        assertEquals(7,accepted.get());
        assertEquals(7,db.jdbc.queryForObject("select used from map_quota where bucket=?",Integer.class,"global:2026-11-10"));
    }
    @Test void upstreamAttemptsSurviveOuterTransactionFailure() {
        var quota = new MapQuota(db,manager,1,200,1000);
        new TransactionTemplate(manager).executeWithoutResult(status -> { quota.reserve("alice",time); status.setRollbackOnly(); });
        assertThrows(ApiException.class, () -> quota.reserve("alice",time));
        assertThrows(ApiException.class, () -> quota.reserve("",time));
    }
}
