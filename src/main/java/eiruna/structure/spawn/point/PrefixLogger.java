package eiruna.structure.spawn.point;

import org.slf4j.Logger;

public class PrefixLogger
{
    private final Logger logger;
    private final String prefix;

    public PrefixLogger(Logger logger, String prefix){
        this.logger = logger;
        this.prefix = prefix;
    }

    public void info(String message, Object... arguments){
        logger.info(prefix + message, arguments);
    }

    public void error(String message, Object... arguments){
        logger.error(prefix + message, arguments);
    }

    public void warn(String message, Object... arguments){
        logger.warn(prefix + message, arguments);
    }
}
