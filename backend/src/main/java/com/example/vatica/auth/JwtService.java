package com.example.vatica.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.example.vatica.secret.MasterKeyProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 最小 JWT（迭代 13 I13-2）：HS256，签名密钥由主密钥派生（{@code HMAC(master.key)}），
 * 不引入额外依赖；token 只含 userId/orgId/role/exp。
 */
public final class JwtService {

    private final MasterKeyProvider masterKey;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final byte[] signingKey;

    public JwtService(MasterKeyProvider masterKey, ObjectMapper mapper, Duration ttl) {
        this.masterKey = masterKey;
        this.mapper = mapper;
        this.ttl = ttl;
        this.signingKey = derive(masterKey.rawKey());
    }

    public record Claims(Long userId, Long orgId, String role, String username) {
    }

    public String issue(AppUser user) {
        try {
            Instant now = Instant.now();
            String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64url(("{\"sub\":" + user.getId() + ",\"org\":" + user.getOrgId()
                    + ",\"role\":\"" + user.getRole() + "\",\"name\":\"" + escape(user.getUsername())
                    + "\",\"iat\":" + now.getEpochSecond() + ",\"exp\":"
                    + now.plus(ttl).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            return signingInput + "." + b64url(hmac(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    public Claims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("操作失败：缺少登录凭证。");
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("token 格式非法");
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = hmac(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actual;
            try {
                actual = Base64.getUrlDecoder().decode(parts[2]);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("token 签名非法");
            }
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("token 签名非法");
            }
            JsonNode node = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long exp = node.path("exp").asLong(0);
            if (exp < Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("token 已过期");
            }
            long userId = node.path("sub").asLong(-1);
            long orgId = node.path("org").asLong(-1);
            if (userId < 0 || orgId < 0) {
                throw new IllegalArgumentException("token 内容非法");
            }
            return new Claims(userId, orgId, node.path("role").asText("MEMBER"),
                    node.path("name").asText(""));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("token 解析失败");
        }
    }

    private byte[] hmac(byte[] input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private static byte[] derive(byte[] masterKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(masterKey);
        } catch (Exception e) {
            throw new IllegalStateException("签名密钥派生失败", e);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
