package com.fanchart.ad;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AdMob 보상형 광고의 서버 측 검증(SSV) 콜백을 확인한다.
 * 콜백 쿼리 문자열에서 signature 앞까지가 서명 대상이고, signature와 key_id가 마지막 두 파라미터다.
 * 서명은 ECDSA P-256 + SHA-256(DER), URL-safe Base64.
 * 받은 그대로의(디코딩 전) 쿼리 문자열로 검증해야 한다.
 */
@Component
public class AdMobVerifier {

    /** @param sessionId 앱이 custom_data로 넘긴 광고 세션 id */
    public record Callback(String adUnit, String transactionId, String sessionId) {
    }

    public static class InvalidCallbackException extends RuntimeException {
        InvalidCallbackException(String message) {
            super(message);
        }
    }

    private static final String SIGNATURE = "&signature=";

    private final AdMobKeys keys;

    AdMobVerifier(AdMobKeys keys) {
        this.keys = keys;
    }

    public Callback verify(String rawQuery) {
        if (rawQuery == null) throw new InvalidCallbackException("no query");
        int at = rawQuery.indexOf(SIGNATURE);
        if (at < 0) throw new InvalidCallbackException("no signature");
        String signed = rawQuery.substring(0, at);
        Map<String, String> tail = params(rawQuery.substring(at + 1));
        Map<String, String> p = params(signed);
        try {
            long keyId = Long.parseLong(tail.getOrDefault("key_id", ""));
            PublicKey key = keys.find(keyId).orElseThrow(() -> new InvalidCallbackException("unknown key " + keyId));
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initVerify(key);
            ecdsa.update(signed.getBytes(StandardCharsets.UTF_8));
            if (!ecdsa.verify(Base64.getUrlDecoder().decode(tail.getOrDefault("signature", ""))))
                throw new InvalidCallbackException("bad signature");
        } catch (NumberFormatException | GeneralSecurityException e) {
            throw new InvalidCallbackException("bad signature: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new InvalidCallbackException("bad signature encoding");
        }
        String adUnit = p.get("ad_unit");
        String tx = p.get("transaction_id");
        String session = p.get("custom_data");
        if (adUnit == null || tx == null || tx.isBlank() || session == null)
            throw new InvalidCallbackException("missing ad_unit, transaction_id or custom_data");
        return new Callback(adUnit, tx, session);
    }

    private static Map<String, String> params(String query) {
        Map<String, String> out = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }
}
