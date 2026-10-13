package com.shen.hospital.auth.controller;

import com.shen.hospital.auth.dto.*;
import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserView>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(auth.register(request)));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(auth.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(auth.me(CurrentUser.get().id()));
    }
}
