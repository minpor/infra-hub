# Image publication

Set `HUB_IMAGE_NAMESPACE` and a Java 21 `HUB_RUNTIME_IMAGE` pinned with `@sha256:<digest>` on worker. The [installation guide](self-hosting.md#7-configure-image-publication) shows the loopback registry path.

In the build form, set one exact relative executable JAR path, e.g. `backend/build/libs/app.jar`. No glob, absolute path or `..`. Empty means test-only. Worker saves a regular file up to 256 MiB, records its checksum/size and packages it with a platform-owned Dockerfile: pinned runtime, `/app/app.jar`, UID 10001 and `java -jar`. Repository Dockerfiles are not executed.

Tag format: `<namespace>/<project UUID>:<build UUID>`. After push, worker reads RepoDigests and stores an immutable reference. Packaging or push failure fails the build. A lost acknowledgement can leave a registry image without a confirmed successful job; there is no automatic blind retry. A successful image build is separate from a successful application deployment.

The local registry runs on the same rootless daemon, bound to 127.0.0.1:5000. Back up its data volume. External registries need separately configured authentication/TLS; worker can receive a mounted Docker config via `HUB_REGISTRY_CONFIG_DIRECTORY`. Default generic deployment assumes a registry accessible without additional deployer credentials.

Temporary packaging tags are removed after the attempt. Registry blobs, BuildKit layers and crash leftovers need operator retention/cleanup. A bare JAR download is not provided by the general publisher; the JAR is in the published image. See [deployments](deployments.md).
