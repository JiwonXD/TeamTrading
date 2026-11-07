package com.fanchart.offering;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공모 배정. 청약이 공모 수량보다 적으면 모두 청약한 만큼 받는다. 넘치면 두 단계로 나눈다.
 * <ol>
 * <li>균등배정: 공모 수량의 절반을 청약자 모두에게 똑같이 나눈다. 청약 수량보다 많이 주지 않고, 남는 몫은 아직 덜 받은 사람끼리 다시 나눈다.
 * 한 주씩도 돌아가지 않으면 추첨 순서대로 한 주씩 준다.</li>
 * <li>비례배정: 나머지를 아직 못 받은 청약 수량에 비례해 나눈다. 내림으로 남는 주식은 나머지가 큰 순서, 같으면 추첨 순서로 한 주씩 준다.</li>
 * </ol>
 * 추첨 순서는 SHA-256(공모 id:계정 id)의 오름차순이다. 공모 id와 계정 id만 알면 누구나 같은 순서를 다시 계산할 수 있다.
 */
final class Allocation {

    record Request(String accountId, long quantity) {
    }

    private Allocation() {
    }

    /** 계정 → 배정 수량. 청약한 계정은 모두 들어 있다(0주 포함) */
    static Map<String, Long> allocate(long offeringId, long totalShares, List<Request> requests) {
        List<Request> order = new ArrayList<>(requests);
        order.sort(Comparator.comparing(r -> lotteryKey(offeringId, r.accountId())));
        Map<String, Long> out = new LinkedHashMap<>();
        long demand = 0;
        for (Request r : order) {
            out.put(r.accountId(), 0L);
            demand += r.quantity();
        }
        if (demand <= totalShares) {
            for (Request r : order) out.put(r.accountId(), r.quantity());
            return out;
        }

        // 균등배정: 물 채우기. 매 단계 남은 몫을 덜 받은 사람 수로 나눈다
        long pool = totalShares / 2;
        while (pool > 0) {
            List<Request> wanting = order.stream().filter(r -> out.get(r.accountId()) < r.quantity()).toList();
            if (wanting.isEmpty()) break;
            long each = pool / wanting.size();
            if (each == 0) {
                for (Request r : wanting) {
                    if (pool == 0) break;
                    out.merge(r.accountId(), 1L, Long::sum);
                    pool--;
                }
                break;
            }
            for (Request r : wanting) {
                long give = Math.min(each, r.quantity() - out.get(r.accountId()));
                out.merge(r.accountId(), give, Long::sum);
                pool -= give;
            }
        }

        // 비례배정: 남은 수량을 남은 청약에 비례해서
        long rest = totalShares - out.values().stream().mapToLong(Long::longValue).sum();
        long residual = order.stream().mapToLong(r -> r.quantity() - out.get(r.accountId())).sum();
        if (rest <= 0 || residual == 0) return out;
        record Share(Request r, long remainder) {
        }
        List<Share> remainders = new ArrayList<>();
        long given = 0;
        for (Request r : order) {
            long want = r.quantity() - out.get(r.accountId());
            long numerator = Math.multiplyExact(rest, want);
            long give = numerator / residual;
            out.merge(r.accountId(), give, Long::sum);
            given += give;
            remainders.add(new Share(r, numerator % residual));
        }
        // 정렬은 안정 정렬이라 나머지가 같으면 추첨 순서가 유지된다
        remainders.sort(Comparator.comparingLong(Share::remainder).reversed());
        for (Share s : remainders) {
            if (given == rest) break;
            if (out.get(s.r().accountId()) >= s.r().quantity()) continue;
            out.merge(s.r().accountId(), 1L, Long::sum);
            given++;
        }
        return out;
    }

    static String lotteryKey(long offeringId, String accountId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((offeringId + ":" + accountId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
