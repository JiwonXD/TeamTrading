package com.fanchart.ledger;

import com.fanchart.ledger.Journal.Bucket;

/** 잔고 한 칸. 예: 어떤 계정의 POINT LOCKED, 어떤 계정의 lg AVAILABLE */
public record Slot(String ownerId, String asset, Bucket bucket) {
}
