package com.teamtrading.ad;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAdMobKeysTest {

    @Test
    void parsesTheGoogleVerifierKeyList() throws Exception {
        String der = Base64.getEncoder().encodeToString(AdRewardTest.GOOGLE.getPublic().getEncoded());
        String body = "{\"keys\":[{\"keyId\":3335741209,\"pem\":\"-----BEGIN PUBLIC KEY-----\\n...\",\"base64\":\""
                + der + "\"}]}";
        var keys = HttpAdMobKeys.parse(JsonMapper.builder().build().readTree(body));
        assertThat(keys).containsOnlyKeys(3335741209L);
        assertThat(keys.get(3335741209L)).isEqualTo(AdRewardTest.GOOGLE.getPublic());
    }
}
