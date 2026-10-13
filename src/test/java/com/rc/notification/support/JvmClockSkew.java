package com.rc.notification.support;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;

/** Optional child-JVM java.time fault injection; never changes the OS, parent process or monotonic clock. */
public final class JvmClockSkew {
    public static final String PROPERTY = "test.process.clock-offset-seconds";
    private static long configuredOffset;
    private JvmClockSkew() { }

    static void install(long seconds) throws Exception {
        if (seconds < -86400 || seconds > 86400) throw new IllegalArgumentException("Clock test offset exceeds one day");
        configuredOffset = seconds;
        if (seconds != 0) {
            System.setProperty("rc.test.java-time-offset", Long.toString(seconds));
            var instrumentation = ByteBuddyAgent.getInstrumentation(); // Supplied by -javaagent, never dynamic attach.
            if (!instrumentation.isRedefineClassesSupported() || !instrumentation.isModifiableClass(Clock.class)) {
                throw new IllegalStateException("This JVM cannot apply the required clock fault");
            }
            new ByteBuddy().redefine(Clock.class).visit(Advice.to(OffsetAdvice.class)
                    .on(named("currentInstant").and(takesArguments(0))))
                    .make().load(null, ClassReloadingStrategy.of(instrumentation));
        }
        verify();
    }
    private static void verify() {
        long real = System.currentTimeMillis();
        long instantOffset = Instant.now().toEpochMilli() - real;
        long clockOffset = Clock.systemUTC().instant().toEpochMilli() - real;
        if (Math.abs(instantOffset - configuredOffset * 1000) > 1000
                || Math.abs(clockOffset - configuredOffset * 1000) > 1000) {
            throw new IllegalStateException("Clock fault did not produce its requested real java.time offset");
        }
    }
    static ObjectNode observation() {
        long real = System.currentTimeMillis();
        Instant instant = Instant.now(), clock = Clock.systemUTC().instant();
        return ProcessProtocol.JSON.createObjectNode().put("clockInjectionLayer", configuredOffset == 0 ? "none" : "java.time.Clock.currentInstant")
                .put("clockOffsetSeconds", configuredOffset).put("jvmInstantNow", instant.toString())
                .put("jvmSystemClockInstant", clock.toString()).put("systemCurrentTimeMillis", real)
                .put("measuredInstantOffsetMillis", instant.toEpochMilli() - real)
                .put("measuredSystemClockOffsetMillis", clock.toEpochMilli() - real);
    }
    public static class OffsetAdvice {
        @Advice.OnMethodExit
        public static void offset(@Advice.Return(readOnly = false) Instant result) {
            // Inlined into java.base: use only bootstrap classes, with no test-class loader dependency.
            result = result.plusSeconds(Long.parseLong(System.getProperty("rc.test.java-time-offset", "0")));
        }
    }
}
