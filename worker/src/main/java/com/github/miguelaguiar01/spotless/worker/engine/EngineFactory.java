package com.github.miguelaguiar01.spotless.worker.engine;

import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.WorkerContext;
import com.github.miguelaguiar01.spotless.worker.WorkerLog;
import com.github.miguelaguiar01.spotless.worker.gradle.GradleEngine;
import com.github.miguelaguiar01.spotless.worker.maven.MavenEngine;

public final class EngineFactory {
    private EngineFactory() {}

    public static FormatEngine create(SpotlessConfig config, WorkerContext context, WorkerLog log)
            throws FormatEngine.UnsupportedConfigException {
        return switch (config.kind()) {
            case BUILTIN_TEST -> new BuiltinTestEngine(config, log);
            case MAVEN -> new MavenEngine(config, context, log);
            case GRADLE -> new GradleEngine(config, context, log);
        };
    }
}
