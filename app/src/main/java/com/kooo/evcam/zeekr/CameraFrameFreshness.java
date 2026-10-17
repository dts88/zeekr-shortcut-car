package com.kooo.evcam.zeekr;

/** Consumer observations prove progression, not producer exposure age or per-lane freshness. */
final class CameraFrameFreshness {
    static final long LIMIT_MS = 500;
    private Object identity;
    private long timestamp, changedAt = -1;
    void reset() { identity = null; timestamp = 0; changedAt = -1; }
    void observe(Object source, long value, long now) {
        if (source == null || value <= 0 || now < 0) { reset(); return; }
        if (source != identity || value < timestamp) {
            identity = source; timestamp = value; changedAt = -1;
        } else if (value > timestamp) {
            timestamp = value; changedAt = now;
        }
    }
    boolean fresh(long now) { return freshAt(changedAt, now); }
    long changedAt() { return changedAt; }
    long timestamp() { return timestamp; }
    static boolean freshAt(long observedAt, long now) {
        return observedAt >= 0 && now >= observedAt && now - observedAt < LIMIT_MS;
    }
}
