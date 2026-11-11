package com.campagent.auth;
import com.campagent.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api")
public class AuthController {
    private final Db db; private final BCryptPasswordEncoder passwords; private final JwtEncoder encoder; private final String mode; private final com.campagent.knowledge.Cloud cloud; private final com.campagent.knowledge.Retrieval retrieval;
    public AuthController(Db db,BCryptPasswordEncoder passwords,JwtEncoder encoder,@Value("${camp.mode}") String mode,com.campagent.knowledge.Cloud cloud,com.campagent.knowledge.Retrieval retrieval) {this.db=db;this.passwords=passwords;this.encoder=encoder;this.mode=mode;this.cloud=cloud;this.retrieval=retrieval;}
    public record Login(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=200) String password){}
    @PostMapping("/auth/login") public Map<String,Object> login(@Valid @RequestBody Login req) {
        var list=db.rows("select * from app_user where username=?",req.username());
        if(list.isEmpty()||!passwords.matches(req.password(),list.getFirst().get("password_hash").toString())) throw new ApiException(HttpStatus.UNAUTHORIZED,"账号或密码不正确");
        var u=list.getFirst(); var now=Instant.now();
        var claims=JwtClaimsSet.builder().subject(u.get("id").toString()).issuedAt(now).expiresAt(now.plusSeconds(3600)).claim("role",u.get("role")).claim("nickname",u.get("nickname")).build();
        var token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return Map.of("token",token,"user",Map.of("id",u.get("id"),"role",u.get("role"),"name",u.get("nickname")));
    }
    @GetMapping("/auth/me") public Actor me(){return Actor.current();}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","UP","mode",mode,"fictionalData",true,"chatProvider",cloud.chatProvider(),"chatModel",cloud.chatModel(),"retrievalMode",retrieval.mode());}
}

