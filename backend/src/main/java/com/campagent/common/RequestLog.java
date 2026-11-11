package com.campagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RequestLog extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLog.class);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id=UUID.randomUUID().toString();
        response.setHeader("X-Request-Id",id);
        long start=System.nanoTime();
        try (var context=MDC.putCloseable("requestId",id)) {
            try { chain.doFilter(request,response); }
            finally {
                log.info("httpRequest id={} method={} path={} status={} elapsedMs={} limited={}",id,
                        request.getMethod(),request.getRequestURI(),response.getStatus(),
                        (System.nanoTime()-start)/1_000_000,response.getStatus()==429);
            }
        }
    }
}
