// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

/// Elapsed time since the first timestamped line, for every line of one log.
///
/// Lines without a timestamp of their own (stack frames, wrapped messages) inherit the time of
/// the closest earlier line that has one; [#hasOwnTimestamp(int)] tells them apart. Lines before
/// the first timestamp have no time at all ([#UNKNOWN]).
///
/// Bare times of day (`08:49:03`) that go backwards by more than twelve hours are taken to have
/// crossed midnight.
public final class Timeline {

    /// Offset of a line that precedes every timestamp.
    public static final long UNKNOWN = -1;

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long HALF_DAY = DAY / 2;

    private final long[] offsets;
    private final boolean[] own;
    private final int timestamped;

    private Timeline(long[] offsets, boolean[] own, int timestamped) {
        this.offsets = offsets;
        this.own = own;
        this.timestamped = timestamped;
    }

    /// Builds a timeline from raw timestamps as returned by [TimestampParser#parse(String)].
    public static Timeline of(long[] timestamps) {
        long[] offsets = new long[timestamps.length];
        boolean[] own = new boolean[timestamps.length];
        long first = TimestampParser.NONE;
        long previous = TimestampParser.NONE;
        long dayShift = 0;
        long current = UNKNOWN;
        int count = 0;
        for (int i = 0; i < timestamps.length; i++) {
            long t = timestamps[i];
            if (t != TimestampParser.NONE) {
                t += dayShift;
                if (previous != TimestampParser.NONE && t < previous - HALF_DAY && t < DAY + dayShift) {
                    dayShift += DAY; // a bare time of day wrapped around midnight
                    t += DAY;
                }
                if (first == TimestampParser.NONE) {
                    first = t;
                }
                previous = t;
                current = t - first;
                own[i] = true;
                count++;
            }
            offsets[i] = current;
        }
        return new Timeline(offsets, own, count);
    }

    /// Milliseconds since the first timestamp, or [#UNKNOWN].
    public long offset(int line) {
        return offsets[line];
    }

    /// Whether the line carries its own timestamp (rather than inheriting one).
    public boolean hasOwnTimestamp(int line) {
        return own[line];
    }

    /// Number of lines.
    public int size() {
        return offsets.length;
    }

    /// Whether enough lines carry timestamps for timing analysis to be meaningful.
    public boolean isUsable() {
        return timestamped >= 2;
    }

    /// Share of lines that carry their own timestamp, from 0 to 1.
    public double coverage() {
        return offsets.length == 0 ? 0 : timestamped / (double) offsets.length;
    }

    /// Time between the first and the last timestamp, in milliseconds (0 if unknown).
    public long duration() {
        for (int i = offsets.length - 1; i >= 0; i--) {
            if (offsets[i] != UNKNOWN) {
                return offsets[i];
            }
        }
        return 0;
    }

    /// Formats a duration compactly: `850 ms`, `5.2 s`, `3 min 4 s`, `1 h 2 min`.
    public static String format(long millis) {
        long ms = Math.abs(millis);
        String sign = millis < 0 ? "-" : "";
        if (ms < 1000) {
            return sign + ms + " ms";
        }
        if (ms < 60_000) {
            double s = ms / 1000.0;
            return sign + (s < 10 ? String.format(java.util.Locale.ROOT, "%.2f s", s)
                    : String.format(java.util.Locale.ROOT, "%.1f s", s));
        }
        if (ms < 3_600_000) {
            return sign + (ms / 60_000) + " min " + (ms % 60_000) / 1000 + " s";
        }
        return sign + (ms / 3_600_000) + " h " + (ms % 3_600_000) / 60_000 + " min";
    }

    /// Formats an offset as shown next to lines: `+5.203s`, `+12:04.5` for longer runs.
    public static String formatOffset(long millis) {
        if (millis == UNKNOWN) {
            return "";
        }
        if (millis < 60_000) {
            return String.format(java.util.Locale.ROOT, "+%d.%03ds", millis / 1000, millis % 1000);
        }
        long minutes = millis / 60_000;
        return String.format(java.util.Locale.ROOT, "+%d:%02d.%d", minutes, (millis / 1000) % 60, (millis % 1000) / 100);
    }
}
