# Spotless Applier Native

[![Build](https://github.com/miguelaguiar01/spotless-applier/workflows/Build/badge.svg)](https://github.com/miguelaguiar01/spotless-applier/actions)

<!-- Plugin description -->
**Spotless Applier Native** applies [Spotless](https://github.com/diffplug/spotless) formatting
inside IntelliJ IDEA in milliseconds, using the exact Spotless version and configuration declared
in your `pom.xml` or `build.gradle(.kts)`, without launching Maven or Gradle for every file.

A long-running worker JVM loads your build's Spotless plugin and formats the in-memory document.
When a configuration cannot be handled natively, the plugin falls back to running the build tool
and tells you why.

### Features

| Actions                                                     | Shortcuts (Windows)                                          | Shortcuts (macOS)                                              |
|-------------------------------------------------------------|--------------------------------------------------------------|----------------------------------------------------------------|
| <kbd>Code</kbd> > <kbd>Reformat File With Spotless</kbd>    | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>;</kbd>                  | <kbd>⌘Cmd</kbd>+<kbd>⌥Opt</kbd>+<kbd>;</kbd>                   |
| <kbd>Code</kbd> > <kbd>Reformat Project With Spotless</kbd> | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>;</kbd> | <kbd>⌘Cmd</kbd>+<kbd>⌥Opt</kbd>+<kbd>⇧Shift</kbd>+<kbd>;</kbd> |

- Native engine for Maven projects (Gradle support in progress).
- Run on save (`Settings` > `Tools` > `Actions on Save` > `Run Spotless`).
- Multi-module projects, Dev Containers.
- Optional "optimize imports before applying".
<!-- Plugin description end -->

This plugin is the successor of [Spotless Applier](https://github.com/lipiridi/spotless-applier)
by Dimitrii Lipiridi (MIT). The build-tool execution path, settings and on-save integration started
from that work. Thank you.

## Status

Under active development. See [docs/PROGRESS.md](docs/PROGRESS.md) for the phase plan,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design and
[docs/DECISIONS.md](docs/DECISIONS.md) for the decision log.

## License

MIT, see [LICENSE](LICENSE). Spotless is Apache-2.0 and is loaded at runtime from your
repositories; it is not bundled.
