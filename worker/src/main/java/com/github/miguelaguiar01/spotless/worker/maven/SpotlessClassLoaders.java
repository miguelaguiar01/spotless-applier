package com.github.miguelaguiar01.spotless.worker.maven;

import java.net.URL;
import java.util.List;

/** Factory so other engines can reuse the child-first loader without widening its visibility. */
public final class SpotlessClassLoaders {
    private SpotlessClassLoaders() {}

    public static SpotlessClassLoader childFirst(List<URL> urls, ClassLoader parent) {
        return new SpotlessClassLoader(urls, parent);
    }
}
