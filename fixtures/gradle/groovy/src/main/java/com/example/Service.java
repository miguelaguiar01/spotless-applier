package com.example;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;

/**
 *   Service with   odd javadoc
 *   spacing.
 */
public class Service {
    private final Function<String, Optional<String>> lookup;
    public Service(Function<String, Optional<String>> lookup) { this.lookup = lookup; }
    public List<String> resolveAll(List<String> keys) { return keys.stream().map(lookup).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList()); }
}
