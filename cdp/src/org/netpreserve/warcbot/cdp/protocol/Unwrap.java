package org.netpreserve.warcbot.cdp.protocol;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * When a CDP domain method returns an JSON object with a single field this annotation causes the Java method
 * returns the value of that field rather than whole object.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Unwrap {
    /**
     * Name of the field to unwrap. Can be omitted if the field name is the same as type name.
     */
    String value() default "";
}
