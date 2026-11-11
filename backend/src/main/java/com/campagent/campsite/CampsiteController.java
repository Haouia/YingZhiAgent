package com.campagent.campsite;
import com.campagent.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api")
public class CampsiteController {
    private final Db db;private final Campsites service;
    public CampsiteController(Db db,Campsites service){this.db=db;this.service=service;}
    @GetMapping({"/campsites","/ops/campsites"}) public List<?> search(@RequestParam(defaultValue="") String city,@RequestParam(defaultValue="") String facility,@RequestParam(defaultValue="") String keyword){return service.search(city,facility,keyword);}
    @GetMapping("/campsites/{id}") public Map<?,?> get(@PathVariable String id){return service.get(id);}
    public record Edit(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=80) String city,@NotBlank @Size(max=40) String terrain,@NotNull @Size(max=300) String facilities,@NotBlank @Size(max=500) String feeDesc,@NotBlank @Size(max=5000) String description){}
    @PreAuthorize("hasRole('OPS')") @PutMapping("/ops/campsites/{id}") public Map<?,?> edit(@PathVariable String id,@Valid @RequestBody Edit r) {
        service.get(id);db.update("update campsite set name=?,city=?,terrain=?,facilities=?,fee_desc=?,description=?,updated_at=? where id=?",r.name(),r.city(),r.terrain(),r.facilities(),r.feeDesc(),r.description(),Db.now(),id);return service.get(id);
    }
    @PreAuthorize("hasRole('OPS')") @PostMapping("/ops/campsites") public Map<?,?> create(@Valid @RequestBody Edit r){
        String id=Db.id();db.update("insert into campsite values(?,?,?,?,?,?,?,?)",id,r.name(),r.city(),r.terrain(),r.facilities(),r.feeDesc(),r.description(),Db.now());return service.get(id);
    }
}

