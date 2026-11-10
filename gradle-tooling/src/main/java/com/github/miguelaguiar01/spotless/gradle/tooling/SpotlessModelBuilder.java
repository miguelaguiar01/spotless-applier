package com.github.miguelaguiar01.spotless.gradle.tooling;

import org.gradle.api.Project;
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService;

/** IntelliJ sync hook: runs inside the Gradle daemon for every project; see {@link SpotlessTaskInspector}. */
public final class SpotlessModelBuilder implements ModelBuilderService {
    @Override
    public boolean canBuild(String modelName) {
        return SpotlessGradleModel.class.getName().equals(modelName);
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        return SpotlessTaskInspector.inspect(project);
    }
}
