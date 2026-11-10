package com.github.miguelaguiar01.spotless.worker.resolve;

import java.io.File;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecrypter;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;

/** Reads user/global settings.xml the way Maven does (incl. ~/.m2/settings-security.xml decryption). */
public final class MavenSettingsLoader {
    private MavenSettingsLoader() {}

    public static Settings load(Path userSettings, Path globalSettings, java.util.function.Consumer<String> warn) {
        DefaultSettingsBuilder builder = new DefaultSettingsBuilderFactory().newInstance();
        DefaultSettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
        if (userSettings != null && userSettings.toFile().isFile()) {
            request.setUserSettingsFile(userSettings.toFile());
        }
        if (globalSettings != null && globalSettings.toFile().isFile()) {
            request.setGlobalSettingsFile(globalSettings.toFile());
        }
        Properties system = new Properties();
        system.putAll(System.getProperties());
        for (var e : System.getenv().entrySet()) system.put("env." + e.getKey(), e.getValue());
        request.setSystemProperties(system);
        request.setUserProperties(new Properties());

        Settings settings;
        try {
            SettingsBuildingResult result = builder.build(request);
            for (SettingsProblem p : result.getProblems()) warn.accept("settings.xml: " + p);
            settings = result.getEffectiveSettings();
        } catch (SettingsBuildingException e) {
            warn.accept("Could not read settings.xml, continuing without it: " + e.getMessage());
            settings = new Settings();
        }

        try {
            String securityFile = new File(System.getProperty("user.home"), ".m2/settings-security.xml").getAbsolutePath();
            DefaultSecDispatcher dispatcher = new DefaultSecDispatcher(new DefaultPlexusCipher(), java.util.Map.of(), securityFile);
            DefaultSettingsDecrypter decrypter = new DefaultSettingsDecrypter(dispatcher);
            SettingsDecryptionResult decrypted = decrypter.decrypt(new DefaultSettingsDecryptionRequest(settings));
            for (SettingsProblem p : decrypted.getProblems()) warn.accept("settings decryption: " + p);
            settings.setServers(decrypted.getServers());
            settings.setProxies(decrypted.getProxies());
        } catch (RuntimeException e) {
            warn.accept("Settings decryption unavailable: " + e.getMessage());
        }
        return settings;
    }
}
