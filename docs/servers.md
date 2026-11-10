# SSH server connections

The Servers page records and checks a Linux Docker host. Enter a reachable hostname/IP, SSH port and account. A local SSH alias is not available on the hub: use `ssh -G YOUR_ALIAS` locally to find hostname/port/user. Password auth, ProxyJump and IPv6 literals are not supported in the initial connection path.

Infra Hub generates a dedicated ED25519 key. Install its public key using an existing trusted target session; preserve other authorized keys. Confirm the scanned ED25519 fingerprint only after independently comparing it with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` on that host. Scan output alone does not prove identity. A changed host key must not be automatically accepted.

Run the connection check. READY means the last SSH/Docker/Compose check passed, not continuous monitoring or a completed deployment. The account needs appropriate container permissions; root and Docker-group membership have broad administrative power. Deployment also needs Python 3 and write access to `/opt/infra-hub/applications`.

Metadata/public keys/pinned host keys are in PostgreSQL; private keys are files under `HUB_SSH_DIRECTORY` (local `.local/ssh`, server `/work/ssh`, volume `ssh_keys`). Directories use 700, files 600. API creates keys; deployer reads them; repository worker does not receive them. Back up the database and key volume together. SSH disables agent/forwarding and requires the pinned key. To revoke target access, remove only the matching `infra-hub:<UUID>` entry from authorized_keys. Address editing/deletion/key replacement UI is not yet provided.
