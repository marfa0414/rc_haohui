package com.rc.notification.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CP06 TC-AUTH-07/10: only a syntactically valid Bearer challenge grants the one recovery opportunity. */
class BearerChallengeTest {
    static Stream<Arguments> challenges() {
        return Stream.of(
                Arguments.of(List.of("Bearer error=\"invalid_token\""), true),
                Arguments.of(List.of("bEaReR ERROR=invalid_token"), true),
                Arguments.of(List.of("Basic abc== , Bearer error=\"invalid_token\""), true),
                Arguments.of(List.of("Basic abc==\t, Bearer error=\"invalid_token\""), true),
                Arguments.of(List.of("Basic realm=\"other\", Bearer realm=\"supplier\", error=\"invalid_token\""), true),
                Arguments.of(List.of("Bearer realm=\"quoted,realm\", error=\"invalid_token\""), true),
                Arguments.of(List.of(", Bearer error=\"invalid_token\",,"), true),
                Arguments.of(List.of("Bearer realm=\"x\"", "error=\"invalid_token\""), true),
                Arguments.of(List.of("Bearer , error=\"invalid_token\""), true),
                Arguments.of(List.of("Bearer,error=\"invalid_token\""), false),
                Arguments.of(List.of("Basic error=\"invalid_token\""), false),
                Arguments.of(List.of("Bearer realm=\"invalid_token\""), false),
                Arguments.of(List.of("Bearer error=\"insufficient_scope\""), false),
                Arguments.of(List.of("Bearer error=\"INVALID_TOKEN\""), false),
                Arguments.of(List.of("Bearer realm=\"fake\\\" error=\\\"invalid_token\""), false),
                Arguments.of(List.of("Bearer error=\"invalid_token\", error=\"invalid_token\""), false),
                Arguments.of(List.of("Bearer error=\"invalid_token\", ERROR=\"other\""), false),
                Arguments.of(List.of("Bearer error=\"invalid_token\"", "Bearer error=\"invalid_token\""), false),
                Arguments.of(List.of("Bearer error=\"invalid_token"), false),
                Arguments.of(List.of("not-a-challenge"), false),
                Arguments.of(List.of(), false));
    }
    @ParameterizedTest @MethodSource("challenges")
    void followsChallengeGrammarAndCannotInferAnErrorFromRealmOrAnotherScheme(List<String> values, boolean expected) {
        assertEquals(expected, BearerChallenge.invalidToken(values));
        assertEquals(expected, BearerChallenge.invalidToken(Map.of("wWw-AuThEnTiCaTe", values)));
    }
}
