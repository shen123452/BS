package com.glucose.auth.controller;

import com.glucose.auth.service.CaptchaService;
import com.glucose.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/common")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @GetMapping("/captcha")
    public Result<CaptchaService.CaptchaVO> captcha() throws Exception {
        return Result.ok(captchaService.generate());
    }
}
