package com.teamtrading.offering;

import com.teamtrading.offering.Allocation.Request;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationTest {

    @Test
    void everyoneGetsWhatTheyAskedWhenUndersubscribed() {
        var out = Allocation.allocate(1, 100, List.of(new Request("a", 30), new Request("b", 70)));
        assertThat(out).containsExactlyInAnyOrderEntriesOf(Map.of("a", 30L, "b", 70L));
    }

    @Test
    void halfIsSharedEquallyAndTheRestByRemainingDemand() {
        // 균등 50주: 1인당 16주(c는 1주, b는 10주까지) → 남은 23주를 a에게. 비례 50주: 남은 청약은 a뿐
        var out = Allocation.allocate(1, 100, List.of(new Request("a", 100), new Request("b", 10), new Request("c", 1)));
        assertThat(out).containsExactlyInAnyOrderEntriesOf(Map.of("a", 89L, "b", 10L, "c", 1L));
    }

    @Test
    void smallInvestorsAreNotCrowdedOutByLargeOnes() {
        // 균등 50주: 1인당 16 → 4주씩 더. 비례 50주: 남은 청약 30:30
        var out = Allocation.allocate(1, 100, List.of(new Request("a", 50), new Request("b", 50), new Request("c", 10)));
        assertThat(out).containsExactlyInAnyOrderEntriesOf(Map.of("a", 45L, "b", 45L, "c", 10L));
    }

    @Test
    void sharesFewerThanSubscribersGoByAReproducibleLottery() {
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) requests.add(new Request("acct-" + i, 1));
        var out = Allocation.allocate(7, 3, requests);
        assertThat(out.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(3);
        assertThat(out.values()).allMatch(q -> q == 0 || q == 1);
        // 공모 id와 계정 id만으로 다시 계산해도 같은 결과
        assertThat(Allocation.allocate(7, 3, requests.reversed())).isEqualTo(out);
        String first = requests.stream().map(Request::accountId)
                .min((x, y) -> Allocation.lotteryKey(7, x).compareTo(Allocation.lotteryKey(7, y))).orElseThrow();
        assertThat(out.get(first)).isEqualTo(1);
    }

    @Test
    void neverOverAllocatesOrExceedsARequest() {
        Random random = new Random(42);
        for (int round = 0; round < 2_000; round++) {
            long total = 1 + random.nextInt(5_000);
            List<Request> requests = new ArrayList<>();
            int n = 1 + random.nextInt(60);
            long demand = 0;
            for (int i = 0; i < n; i++) {
                long q = 1 + random.nextInt(random.nextBoolean() ? 20 : 3_000);
                requests.add(new Request("a" + i, q));
                demand += q;
            }
            var out = Allocation.allocate(round, total, requests);
            assertThat(out.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(Math.min(total, demand));
            for (Request r : requests) assertThat(out.get(r.accountId())).isBetween(0L, r.quantity());
            if (demand > total && total / 2 >= n) {
                // 균등배정 몫(공모 수량 절반 / 청약자 수)까지는 모두 받는다
                long floor = total / 2 / n;
                for (Request r : requests) assertThat(out.get(r.accountId())).isGreaterThanOrEqualTo(Math.min(floor, r.quantity()));
            }
        }
    }
}
