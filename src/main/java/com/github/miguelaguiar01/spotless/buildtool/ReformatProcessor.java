package com.github.miguelaguiar01.spotless.buildtool;


import com.github.miguelaguiar01.spotless.imports.SynchronousOptimizeImportsProcessor;
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings;
import com.intellij.execution.Executor;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Version;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.eel.EelDescriptor;
import com.intellij.platform.eel.provider.EelNioBridgeServiceKt;
import com.intellij.platform.eel.provider.EelProviderUtil;
import com.intellij.platform.eel.provider.LocalEelDescriptor;
import com.intellij.psi.PsiFile;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.Optional;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;
import org.jetbrains.idea.maven.project.MavenHomeType;
import org.jetbrains.idea.maven.project.MavenInSpecificPath;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jetbrains.plugins.gradle.util.GradleUtil;

public class ReformatProcessor {

    private static final Logger LOG = Logger.getInstance(ReformatProcessor.class);
    private static final Version NO_CONFIG_CACHE_MIN_GRADLE_VERSION = new Version(6, 6, 0);
    private static final NotificationGroup NOTIFICATION_GROUP =
            NotificationGroupManager.getInstance().getNotificationGroup("Spotless Applier Native");
    private final SpotlessApplierSettings spotlessSettings = SpotlessApplierSettings.getInstance();
    private final Project project;
    private ReformatTaskCallback reformatTaskCallback;
    private Document document;
    private PsiFile psiFile;
    private boolean reformatSpecificFile;
    private Module module;
    private BuildTool buildTool;
    private String modulePath;

    public ReformatProcessor(@NotNull Project project, @NotNull ModuleInfo moduleInfo) {
        this.project = project;
        this.module = moduleInfo.module();
        this.buildTool = moduleInfo.buildTool();
        this.modulePath = moduleInfo.path();
    }

    public ReformatProcessor(@NotNull Project project, @NotNull Document document, @NotNull PsiFile psiFile) {
        this.project = project;
        this.reformatSpecificFile = true;
        this.document = document;
        this.psiFile = psiFile;
        this.reformatTaskCallback = new ReformatTaskCallback(project, NOTIFICATION_GROUP, document);

        Module module = ModuleUtil.findModuleForFile(psiFile);
        if (module != null) {
            this.buildTool = BuildTool.resolveBuildTool(module);

            if (buildTool != null) {
                this.modulePath = buildTool.getModulePath(module);
            }

            if (buildTool == BuildTool.GRADLE && !hasSpotlessTask(module)) {
                this.modulePath = project.getBasePath();
            }
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private boolean hasSpotlessTask(Module module) {
        DataNode<ModuleData> gradleModuleData = GradleUtil.findGradleModuleData(module);
        if (gradleModuleData == null) {
            return false;
        }
        Collection<DataNode<?>> children = gradleModuleData.getChildren();
        for (DataNode<?> child : children) {
            if (child.getData().toString().equals("spotlessApply")) {
                return true;
            }
        }
        return false;
    }

    public void run() {
        if (buildTool == null) {
            NOTIFICATION_GROUP
                    .createNotification("Unable to resolve build tool", NotificationType.ERROR)
                    .notify(project);
            return;
        }

        if (modulePath == null) {
            return;
        }

        optimizeImports();

        switch (Objects.requireNonNull(buildTool)) {
            case GRADLE -> executeGradleTask();
            case MAVEN -> executeMavenTask();
        }
    }

    private void executeGradleTask() {
        ExternalSystemTaskExecutionSettings externalSettings = getGradleSystemTaskExecutionSettings();

        ToolEnvExternalSystemUtil.runTask(
                externalSettings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                getModuleName(),
                GradleConstants.SYSTEM_ID,
                reformatTaskCallback,
                document);
    }

    private ExternalSystemTaskExecutionSettings getGradleSystemTaskExecutionSettings() {
        String scriptParameters = shouldAddNoConfigCacheOption() ? "--no-configuration-cache" : "";

        ExternalSystemTaskExecutionSettings externalSettings = new ExternalSystemTaskExecutionSettings();
        externalSettings.setExternalProjectPath(modulePath);
        externalSettings.setTaskNames(Collections.singletonList("spotlessApply"));
        externalSettings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.getId());
        if (reformatSpecificFile) {
            scriptParameters =
                    String.format("-PspotlessIdeHook=\"%s\" %s", resolveSpotlessIdeHookPath(), scriptParameters);
        }

        externalSettings.setScriptParameters(scriptParameters);

        return externalSettings;
    }

    private boolean shouldAddNoConfigCacheOption() {
        if (!spotlessSettings.prohibitGradleCache) {
            return false;
        }

        Optional<GradleProjectSettings> maybeProjectSettings =
                Optional.ofNullable(GradleSettings.getInstance(project).getLinkedProjectSettings(modulePath));

        if (maybeProjectSettings.isEmpty()) {
            LOG.warn("Unable to parse linked project settings, leaving off `--no-configuration-cache` argument");
            return false;
        }

        GradleVersion gradleVersion = maybeProjectSettings.get().resolveGradleVersion();

        return Objects.requireNonNull(Version.parseVersion(gradleVersion.getVersion()))
                .isOrGreaterThan(NO_CONFIG_CACHE_MIN_GRADLE_VERSION.major, NO_CONFIG_CACHE_MIN_GRADLE_VERSION.minor);
    }

    private void executeMavenTask() {
        List<String> commands = new ArrayList<>();
        commands.add("spotless:apply");
        if (reformatSpecificFile) {
            final String rawPath = resolveSpotlessIdeHookPath();
            final String adjusted;
            final String rootPath = File.listRoots()[0].getPath();
            if (rootPath.equals("/")) { // Unix / Linux / macOS
                adjusted = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
            } else { // Windows and others
                adjusted = rawPath;
            }
            if (isMavenDaemonHome()) {
                // mvnd 1.0.x crashes after the IDE hook closes System.out (apache/maven-mvnd#1271);
                // -DspotlessFiles takes a regex over absolute paths and avoids the hook entirely.
                commands.add("-DspotlessFiles=" + Pattern.quote(adjusted));
            } else {
                commands.add("-DspotlessIdeHook=" + adjusted);
            }
        }

        ExternalSystemTaskExecutionSettings externalSettings = new ExternalSystemTaskExecutionSettings();
        externalSettings.setTaskNames(commands);

        ExecutionEnvironment environment = getMavenExecutionEnvironment(commands);

        ToolEnvExternalSystemUtil.runTask(
                externalSettings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                getModuleName(),
                MavenUtil.SYSTEM_ID,
                reformatTaskCallback,
                document,
                environment);
    }

    @NotNull private ExecutionEnvironment getMavenExecutionEnvironment(List<String> commands) {
        MavenRunner mavenRunner = MavenRunner.getInstance(project);

        MavenRunnerSettings settings = mavenRunner.getState().clone();

        MavenRunnerParameters params = new MavenRunnerParameters();
        params.setWorkingDirPath(modulePath);
        params.setGoals(commands);

        RunnerAndConfigurationSettings configSettings = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
                null, settings, params, project, MavenRunConfigurationType.generateName(project, params), false);

        configSettings.setActivateToolWindowBeforeRun(false);

        ProgramRunner<?> runner = DefaultJavaProgramRunner.getInstance();
        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        return new ExecutionEnvironment(executor, runner, configSettings, project);
    }

    /** True when the configured Maven home points into a Maven Daemon (mvnd) distribution. */
    boolean isMavenDaemonHome() {
        try {
            MavenHomeType home = MavenProjectsManager.getInstance(project).getGeneralSettings().getMavenHomeType();
            if (home instanceof MavenInSpecificPath specific) {
                return specific.getMavenHome().toLowerCase(Locale.ROOT).contains("mvnd");
            }
        } catch (RuntimeException e) {
            LOG.debug("Cannot determine Maven home type", e);
        }
        return false;
    }

    private void optimizeImports() {
        if (!spotlessSettings.optimizeImportsBeforeApplying) {
            return;
        }

        var synchronousOptimizeImportsProcessor = reformatSpecificFile
                ? new SynchronousOptimizeImportsProcessor(
                        project, spotlessSettings.prohibitImportsWithAsterisk, psiFile)
                : new SynchronousOptimizeImportsProcessor(
                        project, spotlessSettings.prohibitImportsWithAsterisk, module);

        synchronousOptimizeImportsProcessor.run();
    }

    private String getModuleName() {
        return module == null ? project.getName() : module.getName();
    }

    /**
     * Returns the absolute path of the file being reformatted, expressed in the filesystem of the
     * build tool process. For local projects this is just the host path. For projects opened via
     * Gateway's ijent-based Dev Container mode, {@link VirtualFile#toNioPath()} hands back a path
     * inside the host-side Eel virtual filesystem (rendered as
     * {@code //$devcontainer.ij/<hash>@/workspaces/...}), which is meaningless to the in-container
     * {@code mvn}/{@code gradlew} child process. The Eel bridge translates it to the
     * container-internal absolute path the child can resolve.
     */
    @SuppressWarnings("UnstableApiUsage")
    private String resolveSpotlessIdeHookPath() {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        Path nioPath = virtualFile.toNioPath();
        EelDescriptor descriptor = EelProviderUtil.getEelDescriptor(nioPath);
        if (LocalEelDescriptor.INSTANCE.equals(descriptor)) {
            String canonical = virtualFile.getCanonicalPath();
            return canonical != null ? canonical : nioPath.toString();
        }
        return EelNioBridgeServiceKt.asEelPath(nioPath).toString();
    }
}
