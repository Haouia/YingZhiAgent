package com.campagent.campsite;

import com.campagent.auth.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
    private final PublicCampsites campsites;

    public DiscoveryController(PublicCampsites campsites) {
        this.campsites = campsites;
    }

    @GetMapping("/campsites")
    public PublicCampsites.Search search(@RequestParam(defaultValue = "") String city,
                                        @RequestParam(defaultValue = "露营地") String keyword) {
        return campsites.search(Actor.current(), city, keyword);
    }

    @GetMapping("/campsites/{poiId}")
    public PublicCampsites.Detail detail(@PathVariable String poiId, @RequestParam(defaultValue = "") String searchId) {
        return campsites.detail(Actor.current(), searchId, poiId);
    }

    public record FollowUp(@NotBlank @Size(max = 36) String searchId,
                           @Size(max = 64) String placeId,
                           @NotBlank @Size(max = 2000) String question) {}

    @PostMapping("/follow-up")
    public PublicCampsites.Answer followUp(@Valid @RequestBody FollowUp request) {
        return campsites.followUp(Actor.current(), request.searchId(), request.placeId(), request.question());
    }
}
