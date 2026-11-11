package dev.danilocordeiro.ledgermcp.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The OAuth scope a tool requires. Checked by {@code ToolCallAuditor} before the tool
 * body runs, on both transports, and a refusal is audited as DENIED. A tool method
 * without this annotation is refused outright, so forgetting it fails closed.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredScope {

    String value();
}
