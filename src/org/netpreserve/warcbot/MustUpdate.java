package org.netpreserve.warcbot;

import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.StatementCustomizer;
import org.jdbi.v3.sqlobject.customizer.SqlStatementCustomizer;
import org.jdbi.v3.sqlobject.customizer.SqlStatementCustomizerFactory;
import org.jdbi.v3.sqlobject.customizer.SqlStatementCustomizingAnnotation;

import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Throws an exception if an SQL statement didn't update any rows.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@SqlStatementCustomizingAnnotation(value = MustUpdate.Handler.class)
public @interface MustUpdate {
    /**
     * Number of rows that should be updated. (-1 means any except 0)
     */
    int value() default -1;

    class Handler implements SqlStatementCustomizerFactory {
        @Override
        public SqlStatementCustomizer createForMethod(Annotation annotation, Class<?> sqlObjectType, Method method) {
            return stmt -> {
                stmt.addCustomizer(new StatementCustomizer() {
                    @Override
                    public void afterExecution(PreparedStatement stmt, StatementContext ctx) throws SQLException {
                        long updateCount = stmt.getUpdateCount();
                        int expectedCount = ((MustUpdate)annotation).value();
                        if (expectedCount == -1) {
                            if (updateCount == 0) {
                                throw new Exception(method.getDeclaringClass().getSimpleName() + "." + method.getName() + "() didn't update any rows");
                            }
                        } else if (expectedCount != updateCount) {
                            throw new Exception(method.getDeclaringClass().getSimpleName() + "." + method.getName() + "() expected to update " + expectedCount + " rows but only updated " + updateCount);
                        }
                    }
                });
            };
        }
    }

    class Exception extends RuntimeException {
        public Exception(String message) {
            super(message);
        }
    }
}
