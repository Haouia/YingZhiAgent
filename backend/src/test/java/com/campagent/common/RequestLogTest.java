package com.campagent.common;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestLogTest {
    @Test void errorBodyAndHeaderShareAnIdWithoutIncludingTheExceptionMessage() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/discovery/campsites");
        var response=new MockHttpServletResponse();
        new RequestLog().doFilter(request,response,(req,res) -> {
            var error=new Errors().error(new RuntimeException("private-fixture-do-not-log"));
            response.setStatus(error.getStatusCode().value());
            assertTrue(MDC.get("requestId").matches("[a-f0-9-]{36}"));
            assertEquals(response.getHeader("X-Request-Id"),((java.util.Map<?,?>)error.getBody()).get("requestId"));
            assertFalse(error.getBody().toString().contains("private-fixture"));
        });
        assertEquals(500,response.getStatus());
        assertNull(MDC.get("requestId"));
    }
}
