-- 대사가 전표마다 자산별 합이 0인지 보는 쿼리(GROUP BY journal_id, asset)가 원장 전체를 임시 테이블로 묶지 않고
-- 인덱스만 순서대로 읽게 한다. 원장 98만 줄에서 쿼리 한 번 5.9초 → 측정 0.3초(docs/troubleshooting.md 51)
CREATE INDEX ix_ledger_entries_journal ON ledger_entries (journal_id, asset, amount);
