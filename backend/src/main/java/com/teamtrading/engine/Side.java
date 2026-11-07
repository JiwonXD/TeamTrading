package com.teamtrading.engine;

public enum Side {
    BUY, SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
