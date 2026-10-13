package com.rc.notification.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small HTTP challenge parser: quoted commas and escapes are data, never new challenges. */
public final class BearerChallenge {
    private BearerChallenge() { }
    public static boolean invalidToken(Map<String, List<String>> headers) {
        List<String> values = new ArrayList<>();
        headers.forEach((name, value) -> { if (name.equalsIgnoreCase("www-authenticate")) values.addAll(value); });
        return invalidToken(values);
    }
    public static boolean invalidToken(List<String> values) {
        try {
            List<Challenge> challenges = new ArrayList<>();
            Challenge current = null;
            int emptyElements = 0;
            for (String value : values) {
                for (String segment : split(value)) {
                    if (segment.isEmpty()) {
                        if (++emptyElements > 32) return false;
                        continue;
                    }
                    int end = tokenEnd(segment, 0);
                    if (end == 0) return false;
                    int next = ows(segment, end);
                    if (next < segment.length() && segment.charAt(next) == '=') {
                        if (current == null || current.token68 || !current.parameterList) return false;
                        parameter(current, segment);
                    } else {
                        String scheme = segment.substring(0, end);
                        if (next < segment.length() && (next == end || !segment.substring(end, next).chars().allMatch(c -> c == ' '))) return false;
                        current = new Challenge(scheme);
                        current.parameterList = next > end && segment.substring(end, next).chars().allMatch(c -> c == ' ');
                        challenges.add(current);
                        String remainder = trailingOws(segment.substring(next));
                        if (!remainder.isEmpty()) {
                            if (remainder.matches("[A-Za-z0-9._~+/-]+=*")) current.token68 = true;
                            else parameter(current, remainder);
                        }
                    }
                }
            }
            List<Challenge> bearer = challenges.stream().filter(challenge -> challenge.scheme.equalsIgnoreCase("Bearer")).toList();
            return bearer.size() == 1 && "invalid_token".equals(bearer.get(0).parameters.get("error"));
        } catch (IllegalArgumentException malformed) { return false; }
    }
    private static final class Challenge {
        final String scheme;
        final Map<String, String> parameters = new HashMap<>();
        boolean token68;
        boolean parameterList;
        Challenge(String scheme) { this.scheme = scheme; }
    }
    private static List<String> split(String header) {
        if (header == null) throw invalid();
        List<String> segments = new ArrayList<>();
        boolean quoted = false, escaped = false;
        int start = 0;
        for (int index = 0; index < header.length(); index++) {
            char c = header.charAt(index);
            if (c == 127 || c < 32 && c != '\t' || c > 255) throw invalid();
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            else if (!quoted && c == ',') { segments.add(trim(header.substring(start, index))); start = index + 1; }
        }
        if (quoted || escaped) throw invalid();
        segments.add(trim(header.substring(start)));
        return segments;
    }
    private static void parameter(Challenge challenge, String text) {
        int end = tokenEnd(text, 0);
        if (end == 0) throw invalid();
        String key = text.substring(0, end).toLowerCase(Locale.ROOT);
        int position = ows(text, end);
        if (position >= text.length() || text.charAt(position++) != '=') throw invalid();
        position = ows(text, position);
        if (position >= text.length()) throw invalid();
        String value;
        if (text.charAt(position) == '"') {
            StringBuilder result = new StringBuilder();
            boolean ended = false;
            for (position++; position < text.length(); position++) {
                char c = text.charAt(position);
                if (c == '"') { position++; ended = true; break; }
                if (c == '\\') { if (++position >= text.length()) throw invalid(); c = text.charAt(position); }
                result.append(c);
            }
            if (!ended) throw invalid();
            value = result.toString();
        } else {
            int finish = tokenEnd(text, position);
            if (finish == position) throw invalid();
            value = text.substring(position, finish);
            position = finish;
        }
        if (ows(text, position) != text.length() || challenge.parameters.putIfAbsent(key, value) != null) throw invalid();
    }
    private static int tokenEnd(String text, int position) {
        while (position < text.length() && token(text.charAt(position))) position++;
        return position;
    }
    private static boolean token(char c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
    private static int ows(String text, int position) { while (position < text.length() && (text.charAt(position) == ' ' || text.charAt(position) == '\t')) position++; return position; }
    private static String trim(String text) {
        // Leading list OWS is ignorable. Preserve trailing SP so a bare scheme is
        // distinguishable from a scheme followed by its (possibly empty) #auth-param list.
        return text.substring(ows(text, 0));
    }
    private static String trailingOws(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) end--;
        return text.substring(0, end);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Malformed authentication challenge"); }
}
