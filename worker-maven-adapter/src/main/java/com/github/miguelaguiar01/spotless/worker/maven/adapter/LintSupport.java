package com.github.miguelaguiar01.spotless.worker.maven.adapter;

import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.Lint;
import com.diffplug.spotless.LintState;
import com.diffplug.spotless.LintSuppression;
import com.github.miguelaguiar01.spotless.protocol.Message;
import java.io.File;
import java.util.List;
import java.util.Map;

/** Spotless 3.x only (classes absent in 2.x); keep every reference to lint classes in here. */
final class LintSupport {
    private LintSupport() {}

    @SuppressWarnings("unchecked")
    static DirtyState dirtyStateCollectingLints(Formatter formatter, File file, byte[] raw, String relativeUnixPath,
                                                List<Object> suppressions, List<Message.Lint> out, String formatName) {
        LintState state = LintState.of(formatter, file, raw);
        if (!suppressions.isEmpty()) {
            state = state.withRemovedSuppressions(formatter, relativeUnixPath.replace('\\', '/'),
                    (List<LintSuppression>) (List<?>) suppressions);
        }
        if (state.isHasLints()) {
            for (Map.Entry<String, List<Lint>> e : state.getLintsByStep(formatter).entrySet()) {
                for (Lint lint : e.getValue()) {
                    out.add(new Message.Lint(formatName + "/" + e.getKey(), lint.getLineStart(),
                            lint.getShortCode() + ": " + lint.getDetail()));
                }
            }
        }
        return state.getDirtyState();
    }
}
