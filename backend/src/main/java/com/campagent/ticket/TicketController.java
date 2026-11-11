package com.campagent.ticket;
import com.campagent.auth.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api")
public class TicketController {
    private final Tickets service;
    public TicketController(Tickets service){this.service=service;}
    @PostMapping("/ticket-drafts/{id}/confirm") public Map<?,?> confirm(@PathVariable String id){return service.confirm(Actor.current(),id);}
    @GetMapping("/tickets") public List<?> mine(){return service.mine(Actor.current());}
    @GetMapping("/tickets/{id}") public Map<?,?> own(@PathVariable String id){return service.own(Actor.current(),id);}
    @PreAuthorize("hasRole('STAFF')") @GetMapping("/staff/tickets") public List<?> all(){return service.all();}
    @PreAuthorize("hasRole('STAFF')") @GetMapping("/staff/tickets/{id}") public Map<?,?> detail(@PathVariable String id){return service.detail(id);}
    public record Handle(@NotBlank String status,@NotBlank @Size(max=3000) String reply,@Min(0) int revision){}
    @PreAuthorize("hasRole('STAFF')") @PatchMapping("/staff/tickets/{id}") public Map<?,?> handle(@PathVariable String id,@Valid @RequestBody Handle r){return service.handle(Actor.current(),id,r.status(),r.reply(),r.revision());}
}

