# Gradle plugin

This plugin requires you have [sqlc] installed.

On Mac and Linux, any recent version will do.

On Windows, you must have version [1.25], as anything newer is [bugged].
Extract the binary to `C:\Program Files\sqlc\sqlc.exe`.

## Development

### Generating Golden Files

Test scenarios in [test-scenarios](../test-scenarios) contain golden files (expected generator output).
To regenerate them after changing the generator:

```bash
# Generate all scenarios
./gradlew :gradle-plugin:generateGoldenFiles

# Generate a specific scenario
./gradlew :gradle-plugin:generateGoldenFiles -Pscenario=basic_embeds
```

The task runs the generator and copies output to [test-scenarios](../test-scenarios), regardless of
whether the generated code compiles. This allows iterative development.

[sqlc]: https://docs.sqlc.dev/en/latest/overview/install.html
[1.25]: https://github.com/sqlc-dev/sqlc/releases/download/v1.25.0/sqlc_1.25.0_windows_amd64.zip
[bugged]: https://github.com/sqlc-dev/sqlc/issues/3612
