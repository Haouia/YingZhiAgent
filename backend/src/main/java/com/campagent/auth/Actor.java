package com.campagent.auth;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
public record Actor(String id,String role,String name) {
    public static Actor current() {
        var token=(JwtAuthenticationToken)SecurityContextHolder.getContext().getAuthentication();
        return new Actor(token.getName(),token.getToken().getClaimAsString("role"),token.getToken().getClaimAsString("nickname"));
    }
    public boolean staff() { return role.equals("STAFF") || role.equals("OPS"); }
}

