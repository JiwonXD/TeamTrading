package com.teamtrading.ad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 구글이 공개하는 AdMob 검증키 목록을 받아 둔다. 키는 가끔 교체되므로 모르는 key_id가 오면 다시 받는다.
 * 위조 콜백으로 키 서버를 두드리지 않도록 다시 받기는 1분에 한 번까지만 한다.
 */
@Component
class HttpAdMobKeys implements AdMobKeys {

    private static final Duration REFETCH = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(HttpAdMobKeys.class);

    private final AdProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Long, PublicKey> keys = new ConcurrentHashMap<>();
    private volatile Instant fetchedAt = Instant.EPOCH;

    HttpAdMobKeys(AdProperties props, ObjectMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<PublicKey> find(long keyId) {
        PublicKey key = keys.get(keyId);
        if (key == null) {
            refetch();
            key = keys.get(keyId);
        }
        return Optional.ofNullable(key);
    }

    private synchronized void refetch() {
        Instant now = clock.instant();
        if (fetchedAt.plus(REFETCH).isAfter(now)) return;
        fetchedAt = now;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(props.admobKeysUrl())
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            parse(json.readTree(res.body())).forEach(keys::put);
        } catch (Exception e) {
            log.warn("could not fetch AdMob verifier keys: {}", e.toString());
        }
    }

    /** {"keys":[{"keyId":123,"pem":"...","base64":"MFkw..."}]} */
    static Map<Long, PublicKey> parse(JsonNode root) throws Exception {
        KeyFactory ec = KeyFactory.getInstance("EC");
        Map<Long, PublicKey> out = new ConcurrentHashMap<>();
        for (JsonNode k : root.path("keys")) {
            byte[] der = Base64.getDecoder().decode(k.path("base64").asString());
            out.put(k.path("keyId").asLong(), ec.generatePublic(new X509EncodedKeySpec(der)));
        }
        return out;
    }
}
