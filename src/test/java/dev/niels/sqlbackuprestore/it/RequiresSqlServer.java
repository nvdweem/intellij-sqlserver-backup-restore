package dev.niels.sqlbackuprestore.it;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips the annotated tests, with a reason that says where it looked, when there is no server to talk to - so that a
 * checkout without a SQL Server (or CI) still gets a green build from the tests that don't need one.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresSqlServer.Condition.class)
public @interface RequiresSqlServer {
    class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return SqlServer.isAvailable()
                    ? ConditionEvaluationResult.enabled("SQL Server reachable at " + SqlServer.URL)
                    : ConditionEvaluationResult.disabled(SqlServer.unavailableReason());
        }
    }
}
