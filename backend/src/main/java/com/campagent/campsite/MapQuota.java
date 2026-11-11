package com.campagent.campsite;

import com.campagent.common.ApiException;
import com.campagent.common.Db;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MapQuota {
    private final Db db;
    private final TransactionTemplate transaction;
    private final int minuteLimit, dayLimit, globalLimit;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public MapQuota(Db db, PlatformTransactionManager manager,
                    @Value("${camp.map-minute-limit:30}") int minuteLimit,
                    @Value("${camp.map-day-limit:200}") int dayLimit,
                    @Value("${camp.map-global-day-limit:1000}") int globalLimit) {
        if (minuteLimit < 1 || dayLimit < 1 || globalLimit < 1) throw new IllegalArgumentException("地图额度必须为正数");
        this.db = db; this.minuteLimit = minuteLimit; this.dayLimit = dayLimit; this.globalLimit = globalLimit;
        transaction = new TransactionTemplate(manager);
        // Quota survives rollback of a chat transaction after an attempted upstream call.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void reserve(String user, Instant now) {
        if (user == null || !user.matches("[A-Za-z0-9_-]{1,80}")) throw ApiException.bad("地图调用身份无效");
        var local = now.atZone(ZONE);
        String day = local.toLocalDate().toString();
        long minute = now.getEpochSecond() / 60;
        int dayWait = (int) Duration.between(now, local.toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant()).toSeconds() + 1;
        transaction.executeWithoutResult(status -> {
            // Fixed lock order prevents concurrent users from overspending the global budget.
            consume("global:" + day, globalLimit, dayWait);
            consume("day:" + user + ":" + day, dayLimit, dayWait);
            consume("minute:" + user + ":" + minute, minuteLimit, 60 - (int)(now.getEpochSecond() % 60));
        });
    }

    private void consume(String bucket, int limit, int wait) {
        db.update("insert into map_quota(bucket,used) values(?,0) on duplicate key update bucket=bucket", bucket);
        if (db.update("update map_quota set used=used+1 where bucket=? and used<?", bucket, limit) != 1)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "地图查询额度已用完，请稍后再试", wait);
    }
}
