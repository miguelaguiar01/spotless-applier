package com.github.miguelaguiar01.spotless.worker.maven;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Child-first loader for a Spotless Maven plugin classpath plus the adapter jar. Everything is
 * loaded from the child URLs first so the user's exact Spotless/plexus-utils/JGit versions win over
 * whatever the worker itself uses; only the shared API packages are forced parent-first so that
 * Maven/Resolver/Plexus-configurator/protocol types are the same {@code Class} objects on both sides.
 */
final class SpotlessClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private static final List<String> PARENT_FIRST = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.apache.maven.",            // maven-core / plugin-api / model / settings
            "org.eclipse.aether.",          // resolver API + impl (session & system are shared objects)
            "org.codehaus.plexus.component.",       // Plexus configurator (sisu-plexus)
            "org.codehaus.plexus.configuration.",
            "org.codehaus.plexus.classworlds.",
            "org.codehaus.plexus.util.xml.",        // Xpp3Dom used by the configurator (plexus-utils 3)
            "org.codehaus.plexus.util.StringUtils", // keep configurator helpers consistent
            "org.eclipse.sisu.",
            "com.google.inject.",
            "javax.inject.",
            "org.slf4j.",
            "com.github.miguelaguiar01.spotless.protocol.",
            "com.github.miguelaguiar01.spotless.worker.spi.");

    SpotlessClassLoader(List<URL> urls, ClassLoader parent) {
        super("spotless", urls.toArray(new URL[0]), parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentFirst(name)) {
                    c = super.loadClass(name, false);
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        return url != null ? url : super.getResource(name);
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
