package bot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. */
public final class MutableClock extends Clock {
    private long millis;

    public MutableClock(long millis) {
        this.millis = millis;
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant).toEpochMilli());
    }

    public void advanceMs(long ms) {
        millis += ms;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
        return millis;
    }
}
