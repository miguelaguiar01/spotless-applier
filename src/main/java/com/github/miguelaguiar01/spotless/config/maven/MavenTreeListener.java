package com.github.miguelaguiar01.spotless.config.maven;

import com.intellij.openapi.util.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectChanges;
import org.jetbrains.idea.maven.project.MavenProjectsTree;

/**
 * Forwards Maven project-tree events to the config cache.
 *
 * <p>Deliberately written in Java: {@link MavenProjectsTree.Listener} is a Kotlin interface with
 * default methods, and a Kotlin implementation gets compiler-generated bridge stubs for <em>every</em>
 * default method, each calling {@code super}. When the platform removes one of those defaults (the
 * deprecated two-argument {@code projectResolved} is gone in 2026.2) such a stub throws
 * {@code NoSuchMethodError}. A Java implementation only references the methods it overrides.
 */
public final class MavenTreeListener implements MavenProjectsTree.Listener {
    private final Consumer<Collection<MavenProject>> onChanged;
    private final Consumer<Collection<MavenProject>> onDeleted;

    public MavenTreeListener(
            @NotNull Consumer<Collection<MavenProject>> onChanged,
            @NotNull Consumer<Collection<MavenProject>> onDeleted) {
        this.onChanged = onChanged;
        this.onDeleted = onDeleted;
    }

    @Override
    public void projectsUpdated(
            @NotNull List<? extends Pair<MavenProject, MavenProjectChanges>> updated,
            @NotNull List<MavenProject> deleted) {
        if (!deleted.isEmpty()) onDeleted.accept(deleted);
        List<MavenProject> changed = new ArrayList<>(updated.size());
        for (Pair<MavenProject, MavenProjectChanges> p : updated) changed.add(p.first);
        if (!changed.isEmpty()) onChanged.accept(changed);
    }

    // No @Override on the next two: they exist (and are called) on 2026.1, but 2026.3 removed
    // projectResolved(Pair) and deprecated pluginsResolved(MavenProject). Without the annotation the
    // source compiles against either IDE; where the platform still declares the method, this still
    // overrides it. On newer IDEs projectsUpdated and MavenImportListener.importFinished cover it.
    @SuppressWarnings("unused")
    public void projectResolved(@NotNull Pair<MavenProject, MavenProjectChanges> projectWithChanges) {
        onChanged.accept(List.of(projectWithChanges.first));
    }

    @SuppressWarnings("unused")
    public void pluginsResolved(@NotNull MavenProject project) {
        onChanged.accept(List.of(project));
    }
}
