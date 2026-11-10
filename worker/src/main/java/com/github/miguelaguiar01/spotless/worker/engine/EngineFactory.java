package com.github.miguelaguiar01.spotless.worker.engine;

import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.WorkerLog;

public final class EngineFactory {
    private EngineFactory() {}

    public static FormatEngine create(SpotlessConfig config, WorkerLog log) throws FormatEngine.UnsupportedConfigException {
        return switch (config.kind()) {
            case BUILTIN_TEST -> new BuiltinTestEngine(config, log);
            case MAVEN -> throw new FormatEngine.UnsupportedConfigException("NOT_IMPLEMENTED",
                    "Maven mode is not implemented yet");
            case GRADLE -> throw new FormatEngine.UnsupportedConfigException("NOT_IMPLEMENTED",
                    "Gradle mode is not implemented yet");
        };
    }
}
