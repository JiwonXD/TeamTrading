-- 내 체결 내역 조회용
CREATE INDEX ix_trades_buyer ON trades (buyer_id, id);
CREATE INDEX ix_trades_seller ON trades (seller_id, id);
