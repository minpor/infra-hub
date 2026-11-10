# GitHub integration

Source code stays on GitHub. Authentication and source authorization are independent:

- GitHub OAuth App: production browser login; only `HUB_OWNER_GITHUB_ID`, a numeric ID, may enter. Configure the exact HTTPS callback `/login/oauth2/code/github`.
- GitHub App: optional access to private repositories; Contents/Metadata read-only, installed on explicitly selected repositories. API reads a PKCS8 key and generates a token for one repository.

A manual or polled branch is resolved to an exact full SHA before enqueue. Worker downloads a tarball of that commit through an internal API, requiring its shared secret, worker UUID and a current job lease. GitHub tokens do not enter job containers or logs. Access is checked again at download time. Revocation blocks future access but cannot recall already delivered source.

Public repositories can use unauthenticated GitHub API access, subject to limits. The tarball is bounded to 32 MiB and lacks `.git`, Git LFS and submodules. This is not a full Git clone or GitHub Actions workflow engine. Current branch pipelines poll rather than consume webhooks. PR check publishing and release tags are not implemented.

Setup: [self-hosting steps 3 and 5](self-hosting.md). Execution: [repository builds](repository-builds.md).
