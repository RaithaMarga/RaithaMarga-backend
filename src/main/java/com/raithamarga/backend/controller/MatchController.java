package com.raithamarga.backend.controller;

import com.raithamarga.backend.security.Actor;
import com.raithamarga.backend.security.FirebaseAuthenticationFilter;
import com.raithamarga.backend.service.MatchingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final MatchingService matching;

    public MatchController(MatchingService matching) {
        this.matching = matching;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestAttribute(FirebaseAuthenticationFilter.ACTOR_ATTRIBUTE) Actor actor) {
        return matching.listFor(actor);
    }
}
