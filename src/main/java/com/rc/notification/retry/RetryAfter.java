package com.rc.notification.retry;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** RFC 9110 sections 5.6.7 and 10.2.3, using caller-supplied database time rather than a JVM clock. */
public final class RetryAfter {
    private static final DateTimeFormatter IMF = strict("EEE, dd MMM uuuu HH:mm:ss 'GMT'");
    private static final DateTimeFormatter RFC850_DATE = strict("dd-MMM-uuuu HH:mm:ss 'GMT'");
    private static final DateTimeFormatter ASCTIME = new DateTimeFormatterBuilder()
            .appendPattern("EEE MMM ").optionalStart().appendLiteral(' ').optionalEnd()
            .appendPattern("d HH:mm:ss uuuu").toFormatter(Locale.US)
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);
    private static final Pattern RFC850 = Pattern.compile(
            "^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), ([0-9]{2}-[A-Z][a-z]{2}-)([0-9]{2})( [0-9]{2}:[0-9]{2}:[0-9]{2} GMT)$");

    private RetryAfter() { }

    /** Invalid values are empty; valid past dates become now; excessive valid delays become Instant.MAX. */
    public static Optional<Instant> parse(String value, Instant now) {
        Objects.requireNonNull(now, "now");
        if (value == null) return Optional.empty();
        value = trimOws(value);
        if (value.isEmpty()) return Optional.empty();

        long seconds = 0;
        boolean overflow = false;
        boolean digits = true;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') { digits = false; break; }
            int digit = c - '0';
            if (seconds > (Long.MAX_VALUE - digit) / 10) overflow = true;
            if (!overflow) seconds = seconds * 10 + digit;
        }
        if (digits) {
            if (overflow) return Optional.of(Instant.MAX);
            try { return Optional.of(now.plusSeconds(seconds)); }
            catch (DateTimeException | ArithmeticException excessive) { return Optional.of(Instant.MAX); }
        }

        // All three HTTP date forms fit inside this bound; no unbounded parser work on malformed fields.
        if (value.length() > 64) return Optional.empty();
        Instant date = parseDate(value, now);
        return date == null ? Optional.empty() : Optional.of(date.isBefore(now) ? now : date);
    }

    private static Instant parseDate(String value, Instant now) {
        try { return LocalDateTime.parse(value, IMF).toInstant(ZoneOffset.UTC); }
        catch (DateTimeException ignored) { }
        var oldDate = RFC850.matcher(value);
        if (oldDate.matches()) {
            try {
                LocalDateTime upperBound = LocalDateTime.ofInstant(now, ZoneOffset.UTC).plusYears(50);
                int shortYear = Integer.parseInt(oldDate.group(3));
                int year = Math.floorDiv(upperBound.getYear(), 100) * 100 + shortYear;
                if (year > upperBound.getYear()) year -= 100;
                // Choose the century before validating leap days; shifting an already parsed date
                // can silently turn February 29 into February 28 in a non-leap century.
                LocalDateTime reference = LocalDateTime.parse(oldDate.group(2) + "2000" + oldDate.group(4), RFC850_DATE);
                if (year == upperBound.getYear() && reference.isAfter(upperBound.withYear(2000))) year -= 100;
                LocalDateTime parsed = LocalDateTime.parse(oldDate.group(2)
                        + String.format(Locale.ROOT, "%04d", year) + oldDate.group(4), RFC850_DATE);
                // Resolve the century before checking weekday, which differs across centuries.
                if (!parsed.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US).equals(oldDate.group(1))) return null;
                return parsed.toInstant(ZoneOffset.UTC);
            } catch (DateTimeException | NumberFormatException invalid) { return null; }
        }
        try { return LocalDateTime.parse(value, ASCTIME).toInstant(ZoneOffset.UTC); }
        catch (DateTimeException ignored) { return null; }
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);
    }

    private static String trimOws(String value) {
        int start = 0, end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        return value.substring(start, end);
    }
}
