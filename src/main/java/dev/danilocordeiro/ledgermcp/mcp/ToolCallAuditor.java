package dev.danilocordeiro.ledgermcp.mcp;

import dev.danilocordeiro.ledgermcp.audit.AuditLog;
import dev.danilocordeiro.ledgermcp.audit.AuditOutcome;
import dev.danilocordeiro.ledgermcp.security.Caller;
import dev.danilocordeiro.ledgermcp.security.CallerResolver;
import dev.danilocordeiro.ledgermcp.security.LocalOperatorAuthentication;
import dev.danilocordeiro.ledgermcp.security.RequiredScope;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps every {@link McpTool} method, on both transports:
 * <ol>
 *   <li>on stdio, installs the local operator identity for the duration of the call;</li>
 *   <li>writes a STARTED audit row, and refuses the call if that write fails;</li>
 *   <li>checks the method's {@link RequiredScope} and records DENIED without running it;</li>
 *   <li>runs the tool and records OK with the result, or ERROR with the message.</li>
 * </ol>
 * Outermost advice, so nothing runs before the audit row exists.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ToolCallAuditor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallAuditor.class);
    private static final int MAX_RESULT_CHARS = 16_000;

    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;
    private final Supplier<LocalOperatorAuthentication> localIdentity;

    /** @param localIdentity supplies the stdio identity, or null on the HTTP transport */
    public ToolCallAuditor(AuditLog audit, JsonMapper json, Clock clock, Supplier<LocalOperatorAuthentication> localIdentity) {
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.localIdentity = localIdentity;
    }

    // No argument binding in the pointcut: Spring AI invokes tools reflectively, and a
    // bound annotation parameter fails there ("JoinPointMatch was NOT bound").
    @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
    public Object around(ProceedingJoinPoint call) throws Throwable {
        Method method = ((MethodSignature) call.getSignature()).getMethod();
        McpTool tool = AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class);
        boolean installedLocalIdentity = false;
        if (localIdentity != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(localIdentity.get());
            installedLocalIdentity = true;
        }
        try {
            return audited(call, method, tool == null || tool.name().isBlank() ? method.getName() : tool.name());
        } finally {
            if (installedLocalIdentity) {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private Object audited(ProceedingJoinPoint call, Method method, String toolName) throws Throwable {
        Caller caller = CallerResolver.current();
        String arguments = argumentsJson(call);
        UUID callId = UUID.randomUUID();
        long startedNanos = System.nanoTime();

        audit.started(callId, clock.instant(), caller, toolName, arguments);

        RequiredScope required = AnnotatedElementUtils.findMergedAnnotation(method, RequiredScope.class);
        if (required == null || !caller.hasScope(required.value())) {
            String message = required == null
                    ? "Tool " + toolName + " declares no required scope and is refused."
                    : "Permission denied: " + toolName + " requires the '" + required.value() + "' scope and "
                            + caller.principal() + " does not have it. Do not retry. Tell the user which scope is "
                            + "missing; a person who holds it has to do this step.";
            complete(callId, caller, toolName, arguments, AuditOutcome.DENIED, null, message, startedNanos);
            throw new ToolAccessDeniedException(message);
        }

        try {
            Object result = call.proceed();
            complete(callId, caller, toolName, arguments, AuditOutcome.OK, resultJson(result), null, startedNanos);
            return result;
        } catch (Throwable e) {
            complete(callId, caller, toolName, arguments, AuditOutcome.ERROR, null, String.valueOf(e.getMessage()), startedNanos);
            throw e;
        }
    }

    /**
     * A failure here is logged, not thrown: the tool has already run, and reporting it as
     * failed would invite the agent to repeat a write. The STARTED row still records the call.
     */
    private void complete(UUID callId, Caller caller, String tool, String arguments, AuditOutcome outcome,
                          String result, String error, long startedNanos) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
        try {
            audit.completed(callId, clock.instant(), caller, tool, arguments, outcome, result, error, durationMs);
        } catch (RuntimeException e) {
            log.atError().setCause(e).addKeyValue("callId", callId).addKeyValue("tool", tool)
                    .log("failed to write COMPLETED audit row");
        }
    }

    private String argumentsJson(ProceedingJoinPoint call) {
        String[] names = ((MethodSignature) call.getSignature()).getParameterNames();
        Object[] values = call.getArgs();
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            arguments.put(names != null ? names[i] : "arg" + i, values[i]);
        }
        return json.writeValueAsString(arguments);
    }

    private String resultJson(Object result) {
        String serialized = json.writeValueAsString(result);
        if (serialized.length() <= MAX_RESULT_CHARS) {
            return serialized;
        }
        return json.writeValueAsString(Map.of("truncated", true, "length", serialized.length(),
                "prefix", serialized.substring(0, MAX_RESULT_CHARS)));
    }
}
