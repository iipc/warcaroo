package org.netpreserve.warcaroo.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

import static ch.qos.logback.classic.Level.*;
import static ch.qos.logback.core.pattern.color.ANSIConstants.*;

public class LogHighlighter extends ForegroundCompositeConverterBase<ILoggingEvent> {
    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        return switch (event.getLevel().toInt()) {
            case ERROR_INT -> RED_FG;
            case WARN_INT -> MAGENTA_FG;
            case INFO_INT -> CYAN_FG;
            case DEBUG_INT -> DEFAULT_FG;
            case TRACE_INT -> WHITE_FG;
            default -> DEFAULT_FG;
        };
    }
}
