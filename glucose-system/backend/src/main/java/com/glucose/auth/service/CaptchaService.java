package com.glucose.auth.service;

import com.glucose.security.AuthStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 一次性图形验证码:4 位字符、2 分钟有效、验证后立即删除。
 */
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthStateService authStateService;

    public record CaptchaVO(String captchaId, String image) {
    }

    public CaptchaVO generate() throws Exception {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            code.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        authStateService.saveCaptcha(captchaId, code.toString());

        int width = 120, height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 247, 246));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(220, 228, 225));
        for (int i = 0; i < 4; i++) {
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(RANDOM.nextInt(width), RANDOM.nextInt(height),
                    RANDOM.nextInt(width), RANDOM.nextInt(height));
        }
        g.setFont(new Font("Arial", Font.BOLD, 24));
        int[] colors = {0x2F6F73, 0x3B6EA8, 0xB7791F, 0x3E7C68};
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(colors[RANDOM.nextInt(colors.length)]));
            g.drawString(String.valueOf(code.charAt(i)), 14 + i * 26,
                    26 + RANDOM.nextInt(8) - 4);
        }
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new CaptchaVO(captchaId, "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()));
    }

    public boolean verify(String captchaId, String code) {
        if (captchaId == null || captchaId.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        return authStateService.verifyCaptcha(captchaId, code);
    }
}
