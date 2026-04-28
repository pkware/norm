# Contributing to Norm

## Commit Message Format

This project follows [Conventional Commits](https://www.conventionalcommits.org/). CI validates each commit on your PR branch individually.

### Format

```
<type>(<scope>)?: <description>
```

### Valid Types

- `feat` — new feature
- `fix` — bug fix
- `chore` — non-code changes (CI, configs, tooling); Renovate generates `chore(deps):` for dependency updates
- `docs` — documentation changes
- `ci` — CI/CD changes
- `refactor` — code refactoring without behavior change
- `test` — test-only changes

### Examples

✅ Valid:
- `feat: add support for UUID columns`
- `fix(generator): handle nullable enum columns correctly`
- `chore(deps): update Gradle to 8.14`
- `docs: add CRUD method examples to README`

❌ Invalid:
- `Update dependencies` (missing type)
- `feat Update feature` (missing colon)
- `FEAT: add feature` (uppercase type)

### Merge Strategy

Both squash+merge and rebase+merge are used:

- **Squash+merge:** The PR title becomes the single commit on `main`. Ensure your PR title follows the conventional commits format.
- **Rebase+merge:** Individual commits land on `main`. CI validates each commit. `fixup!` and `squash!` commits are skipped automatically.

## Release Process

Releases are automated via [release-please](https://github.com/googleapis/release-please):

1. Merge conventional commits to `main`
2. release-please opens or updates a release PR (bumps version in `gradle.properties`)
3. Maintainer reviews and merges the release PR
4. release-please creates a semver tag and GitHub Release with auto-generated notes
5. The publish workflow publishes the release to Maven Central
6. A follow-up PR is automatically created to bump to the next SNAPSHOT version
