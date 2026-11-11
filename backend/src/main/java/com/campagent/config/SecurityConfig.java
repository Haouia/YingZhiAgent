package com.campagent.config;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean SecretKeySpec jwtKey(@Value("${camp.jwt-secret}") String secret,@Value("${camp.mode}") String mode) {
        if(secret.isBlank() && mode.equals("demo")) { byte[] bytes=new byte[48]; new java.security.SecureRandom().nextBytes(bytes); return new SecretKeySpec(bytes,"HmacSHA256"); }
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");
    }
    @Bean JwtEncoder encoder(SecretKeySpec key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean JwtDecoder decoder(SecretKeySpec key) { return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); }
    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        var converter=new JwtGrantedAuthoritiesConverter(); converter.setAuthoritiesClaimName("role"); converter.setAuthorityPrefix("ROLE_");
        var jwt=new JwtAuthenticationConverter(); jwt.setJwtGrantedAuthoritiesConverter(converter);
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(a->a.requestMatchers("/api/auth/login","/api/health").permitAll().requestMatchers("/api/ops/**").hasRole("OPS").requestMatchers("/api/staff/**").hasRole("STAFF").anyRequest().authenticated())
          .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(jwt)))
          .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"请先登录\"}");})
            .accessDeniedHandler((req,res,x)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"没有操作权限\"}");})).build();
    }
}

