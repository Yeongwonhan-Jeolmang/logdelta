// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.core.timing;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A time range to compare, such as "from 2 minutes into the run until 5 minutes into it".
///
/// Each bound is one of:
///
/// - **relative**: `+90s`, `+5m`, `+1h30m`, `+250ms`, measured from the first timestamp
///   of each log. Two runs that started at different times are compared over the same stretch of
///   their lifetime, which is usually what you want.
/// - **absolute**: `2026-11-12T08:49:03`, `2026-11-12 08:49`; a trailing `Z` or `+02:00` is
///   accepted and ignored, like time zones everywhere in LogDelta's timing.
/// - **time of day**: `08:49` or `08:49:03.500`, matched against the clock time of each line.
///   A window may cross midnight (`23:50` to `00:10`); a start more than twelve hours before the
///   log's first line is taken to mean the next day.
///
/// Logs are assumed to be roughly chronological: the window is the stretch of lines from the
/// first line at or after `since` up to the line before the first one after `until`. Lines
/// without a timestamp (stack traces, wrapped messages) belong to the line above them. Reading
/// stops as soon as the window has ended, which keeps huge files cheap.
///
/// @param since start of the window, or `null` for the beginning of the log
/// @param until end of the window (inclusive), or `null` for the end of the log
public record TimeWindow(Bound since, Bound until) {

    private static final long DAY = 86_400_000L;
    private static final Pattern RELATIVE = Pattern.compile("\\+((?:\\d+(?:\\.\\d+)?(?:ms|h|m|s))+)");
    private static final Pattern RELATIVE_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");
    private static final Pattern DATE_TIME = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?"
                    + "(?:\\s*(?:Z|[+-]\\d{2}(?::?\\d{2})?))?)?");
    private static final Pattern TIME_OF_DAY = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?");

    private static final long HALF_DAY = DAY / 2;

    /// Validates that at least one bound is set and that the end is not before the start.
    public TimeWindow {
        if (since == null && until == null) {
            throw new IllegalArgumentException("A time window needs a start, an end or both");
        }
        boolean reversed = switch (since) {
            case Relative s when until instanceof Relative u -> u.millis() < s.millis();
            case Absolute s when until instanceof Absolute u -> u.millis() < s.millis();
            case null, default -> false;
        };
        if (reversed) {
            throw new IllegalArgumentException("The time window ends (" + until.text() + ") before it starts ("
                    + since.text() + ")");
        }
    }

    /// One end of a window.
    public sealed interface Bound permits Relative, Absolute, TimeOfDay {

        /// Text that [TimeWindow#parseBound] turns back into this bound.
        String text();
    }

    /// Milliseconds after the first timestamp of the log.
    ///
    /// @param millis offset, `>= 0`
    public record Relative(long millis) implements Bound {
        @Override
        public String text() {
            StringBuilder sb = new StringBuilder("+");
            long rest = millis;
            long h = rest / 3_600_000;
            rest %= 3_600_000;
            long m = rest / 60_000;
            rest %= 60_000;
            long s = rest / 1000;
            long ms = rest % 1000;
            if (h > 0) {
                sb.append(h).append('h');
            }
            if (m > 0) {
                sb.append(m).append('m');
            }
            if (s > 0) {
                sb.append(s).append('s');
            }
            if (ms > 0 || sb.length() == 1) {
                sb.append(ms).append("ms");
            }
            return sb.toString();
        }
    }

    /// A date and time, in milliseconds as produced by [TimestampParser].
    ///
    /// @param millis point in time
    public record Absolute(long millis) implements Bound {
        @Override
        public String text() {
            LocalDateTime t = LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000), 0, ZoneOffset.UTC);
            long ms = Math.floorMod(millis, 1000);
            return t.toLocalDate() + "T" + t.toLocalTime().withNano(0) + (ms == 0 ? "" : String.format(Locale.ROOT, ".%03d", ms));
        }
    }

    /// A clock time, in milliseconds after midnight.
    ///
    /// @param millisOfDay time of day
    public record TimeOfDay(long millisOfDay) implements Bound {
        @Override
        public String text() {
            LocalTime t = LocalTime.ofNanoOfDay(millisOfDay * 1_000_000);
            return t.toString();
        }
    }

    /// Parses `since` and `until`; either may be `null` or blank.
    ///
    /// @return the window, or `null` when both are empty
    /// @throws IllegalArgumentException if a bound cannot be parsed
    public static TimeWindow parse(String since, String until) {
        Bound s = since == null || since.isBlank() ? null : parseBound(since);
        Bound u = until == null || until.isBlank() ? null : parseBound(until);
        return s == null && u == null ? null : new TimeWindow(s, u);
    }

    /// Parses one bound (see the class description for the accepted forms).
    ///
    /// @throws IllegalArgumentException if the text is not a bound
    public static Bound parseBound(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher m = RELATIVE.matcher(t);
        if (m.matches()) {
            Matcher part = RELATIVE_PART.matcher(m.group(1));
            double total = 0;
            while (part.find()) {
                double v = Double.parseDouble(part.group(1));
                total += switch (part.group(2)) {
                    case "h" -> v * 3_600_000;
                    case "m" -> v * 60_000;
                    case "s" -> v * 1000;
                    default -> v;
                };
            }
            return new Relative(Math.round(total));
        }
        m = DATE_TIME.matcher(text.strip());
        if (m.matches()) {
            try {
                LocalDate d = LocalDate.of(num(m, 1), num(m, 2), num(m, 3));
                LocalTime time = m.group(4) == null ? LocalTime.MIDNIGHT
                        : LocalTime.of(num(m, 4), num(m, 5), m.group(6) == null ? 0 : num(m, 6));
                long seconds = LocalDateTime.of(d, time).toEpochSecond(ZoneOffset.UTC);
                return new Absolute(seconds * 1000 + millis(m.group(7)));
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Invalid date/time '" + text + "': " + e.getMessage(), e);
            }
        }
        m = TIME_OF_DAY.matcher(text.strip());
        if (m.matches()) {
            int h = num(m, 1);
            int min = num(m, 2);
            int s = m.group(3) == null ? 0 : num(m, 3);
            if (h > 23 || min > 59 || s > 59) {
                throw new IllegalArgumentException("Invalid time of day '" + text + "'");
            }
            return new TimeOfDay(((h * 60L + min) * 60 + s) * 1000 + millis(m.group(4)));
        }
        throw new IllegalArgumentException("Cannot read time '" + text
                + "': use +5m (into the run), 08:49:03 (time of day) or 2026-11-12T08:49:03");
    }

    private static int num(Matcher m, int g) {
        return Integer.parseInt(m.group(g));
    }

    private static int millis(String fraction) {
        if (fraction == null) {
            return 0;
        }
        String f = fraction.length() >= 3 ? fraction.substring(0, 3) : (fraction + "00").substring(0, 3);
        return Integer.parseInt(f);
    }

    /// A short description, e.g. `"+2m → +5m"`.
    public String describe() {
        return (since == null ? "start" : since.text()) + " → " + (until == null ? "end" : until.text());
    }

    /// Creates a stateful filter for one log.
    public Slicer slicer() {
        return new Slicer(this);
    }

    /// Decides, line by line, which lines of one log fall inside the window. Feed every line in
    /// order to [#accept(String)] until it returns [Decision#STOP].
    public static final class Slicer {

        /// What to do with a line.
        public enum Decision {
            /// The line is before the window.
            SKIP,
            /// The line is inside the window.
            KEEP,
            /// The window has ended; this and all later lines are outside it.
            STOP
        }

        private final TimeWindow window;
        private long first = TimestampParser.NONE;
        private long last = TimestampParser.NONE;
        private boolean started;
        /// For time-of-day bounds: a clock that keeps counting past midnight (time of day plus
        /// 24 h per midnight passed), and the bounds placed on it.
        private long clock = TimestampParser.NONE;
        private long dayBase;
        private long sinceClock;
        private long untilClock;

        private Slicer(TimeWindow window) {
            this.window = window;
            this.started = window.since() == null;
        }

        /// Classifies the next line.
        public Decision accept(String line) {
            long t = TimestampParser.parse(line);
            if (t != TimestampParser.NONE) {
                if (first == TimestampParser.NONE) {
                    first = t;
                    placeTimesOfDay(Math.floorMod(t, DAY));
                }
                last = t;
                long tod = Math.floorMod(t, DAY);
                if (clock != TimestampParser.NONE && dayBase + tod < clock - HALF_DAY) {
                    dayBase += DAY; // the clock went past midnight
                }
                clock = dayBase + tod;
            }
            if (last == TimestampParser.NONE) {
                return started ? Decision.KEEP : Decision.SKIP;
            }
            if (!started) {
                if (t == TimestampParser.NONE || compare(t, window.since(), false) < 0) {
                    return Decision.SKIP;
                }
                started = true;
            }
            if (window.until() != null && t != TimestampParser.NONE && compare(t, window.until(), true) > 0) {
                return Decision.STOP;
            }
            return Decision.KEEP;
        }

        /// Whether any line has been kept so far.
        public boolean started() {
            return started;
        }

        /// Puts time-of-day bounds on the clock that starts at the first line's time of day `start`:
        /// a start long before the first line means the next day, and an end before the start
        /// (`23:50` to `00:10`) means the day after the start.
        private void placeTimesOfDay(long start) {
            long ref = start;
            if (window.since() instanceof TimeOfDay s) {
                sinceClock = s.millisOfDay() < start - HALF_DAY ? s.millisOfDay() + DAY : s.millisOfDay();
                ref = sinceClock;
            }
            if (window.until() instanceof TimeOfDay u) {
                untilClock = u.millisOfDay();
                while (untilClock < ref - HALF_DAY || window.since() instanceof TimeOfDay && untilClock < ref) {
                    untilClock += DAY;
                }
            }
        }

        private int compare(long t, Bound b, boolean end) {
            return switch (b) {
                case Relative r -> Long.compare(t - first, r.millis());
                case Absolute a -> Long.compare(t, a.millis());
                case TimeOfDay _ -> Long.compare(clock, end ? untilClock : sinceClock);
            };
        }
    }
}
