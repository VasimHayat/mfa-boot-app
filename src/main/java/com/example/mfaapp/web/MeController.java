package com.example.mfaapp.web;

import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.service.LearningService;
import com.example.mfaapp.service.UserService;
import com.example.mfaapp.web.dto.AuthDtos.MeResponse;
import com.example.mfaapp.web.dto.LearningDtos.LearningSummaryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identity and personal learning stats. Requires a session that has cleared MFA. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserService userService;
    private final LearningService learningService;

    public MeController(UserService userService, LearningService learningService) {
        this.userService = userService;
        this.learningService = learningService;
    }

    @GetMapping
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        User user = userService.require(authentication.getName());
        return ResponseEntity.ok(new MeResponse(
                user.getUsername(),
                user.getRoles().stream().map(Role::authority).toList(),
                userService.isMfaEnabled(user.getId())));
    }

    @GetMapping("/learning/summary")
    public ResponseEntity<LearningSummaryDto> summary(Authentication authentication) {
        User user = userService.require(authentication.getName());
        return ResponseEntity.ok(learningService.summary(user));
    }
}
