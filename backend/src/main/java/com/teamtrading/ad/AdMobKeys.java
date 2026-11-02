package com.teamtrading.ad;

import java.security.PublicKey;
import java.util.Optional;

/** AdMob 서버 측 검증(SSV) 콜백 서명용 공개키 */
public interface AdMobKeys {

    Optional<PublicKey> find(long keyId);
}
