package com.github.miguelaguiar01.spotless.worker.resolve;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;

/**
 * A {@link RepositorySystem} plus a session configured like a Maven build would be: local repo,
 * offline flag, and mirrors/proxies/servers from settings.xml. One per worker; sessions are cheap.
 */
public final class ResolverEnvironment implements Closeable {
    public record Repo(String id, String url, boolean releases, boolean snapshots) {}

    private final RepositorySystem system;
    private final RepositorySystemSession.CloseableSession session;
    private final Settings settings;

    private ResolverEnvironment(RepositorySystem system, RepositorySystemSession.CloseableSession session, Settings settings) {
        this.system = system;
        this.session = session;
        this.settings = settings;
    }

    public static ResolverEnvironment create(Path localRepository, Path userSettings, Path globalSettings, boolean offline,
                                             Consumer<String> warn) {
        Settings settings = MavenSettingsLoader.load(userSettings, globalSettings, warn);
        RepositorySystem system = new RepositorySystemSupplier().get();

        Path localRepo = localRepository;
        if (localRepo == null && settings.getLocalRepository() != null) localRepo = Path.of(settings.getLocalRepository());
        if (localRepo == null) localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");

        DefaultMirrorSelector mirrors = new DefaultMirrorSelector();
        for (Mirror m : settings.getMirrors()) {
            mirrors.add(m.getId(), m.getUrl(), m.getLayout(), false, m.isBlocked(), m.getMirrorOf(), m.getMirrorOfLayouts());
        }
        DefaultProxySelector proxies = new DefaultProxySelector();
        for (Proxy p : settings.getProxies()) {
            if (!p.isActive()) continue;
            Authentication auth = p.getUsername() == null ? null
                    : new AuthenticationBuilder().addUsername(p.getUsername()).addPassword(p.getPassword()).build();
            proxies.add(new org.eclipse.aether.repository.Proxy(p.getProtocol(), p.getHost(), p.getPort(), auth), p.getNonProxyHosts());
        }
        DefaultAuthenticationSelector auths = new DefaultAuthenticationSelector();
        for (Server s : settings.getServers()) {
            AuthenticationBuilder b = new AuthenticationBuilder();
            if (s.getUsername() != null) b.addUsername(s.getUsername()).addPassword(s.getPassword());
            if (s.getPrivateKey() != null) b.addPrivateKey(s.getPrivateKey(), s.getPassphrase());
            auths.add(s.getId(), b.build());
        }

        Map<String, Object> config = new HashMap<>();
        config.put("aether.connector.userAgent", "spotless-applier-native-worker");
        Map<String, String> sysProps = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) sysProps.put(name, System.getProperty(name));

        RepositorySystemSession.CloseableSession session = new SessionBuilderSupplier(system).get()
                .withLocalRepositoryBaseDirectories(localRepo)
                .setOffline(offline || settings.isOffline())
                .setMirrorSelector(mirrors)
                .setProxySelector(proxies)
                .setAuthenticationSelector(auths)
                .setSystemProperties(sysProps)
                .setConfigProperties(config)
                .build();
        return new ResolverEnvironment(system, session, settings);
    }

    public RepositorySystem system() {
        return system;
    }

    public RepositorySystemSession session() {
        return session;
    }

    public Settings settings() {
        return settings;
    }

    public Path localRepository() {
        return session.getLocalRepository().getBasePath();
    }

    /** Applies mirrors, proxies and authentication to the given repositories, as Maven does. */
    public List<RemoteRepository> remoteRepositories(List<Repo> repos) {
        List<RemoteRepository> raw = new ArrayList<>();
        for (Repo r : repos) {
            RemoteRepository.Builder b = new RemoteRepository.Builder(r.id(), "default", r.url())
                    .setReleasePolicy(new RepositoryPolicy(r.releases(), RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_WARN))
                    .setSnapshotPolicy(new RepositoryPolicy(r.snapshots(), RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_WARN));
            raw.add(b.build());
        }
        if (raw.isEmpty()) {
            raw.add(new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build());
        }
        return system.newResolutionRepositories(session, raw);
    }

    @Override
    public void close() {
        session.close();
        system.shutdown();
    }
}
