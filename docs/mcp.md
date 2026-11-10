# MCP access for agents

Infra Hub exposes a stateless Streamable HTTP MCP endpoint at `https://YOUR_DOMAIN/mcp`. Create a key in Settings while logged in as owner. Copy it once, store it as a client secret and revoke it when no longer required. The server stores a SHA-256 hash of a random 256-bit key, expiry, permissions and project scope; the raw key cannot be recovered.

Example client settings (adapt to your client's schema):

```json
{
  "mcpServers": {
    "infra-hub": {
      "url": "https://hub.example.com/mcp",
      "headers": {"Authorization": "Bearer <MCP_TOKEN>"}
    }
  }
}
```

Use the Bearer header, not a token in the URL. Browser owner sessions do not authenticate MCP, and MCP keys do not authenticate browser `/api` routes. Key creation/revocation requires owner session and CSRF. Project restrictions apply to logs, artifacts and writes. CONFIGURE includes RELEASE and READ because configuration can enable deployment.

## Tools

| Permission | Tools |
| --- | --- |
| READ | `hub_catalog`, `hub_release_context`, `hub_get_build`, `hub_build_logs`, `hub_get_deployment`, `hub_deployment_logs`, `hub_runner_settings` |
| RELEASE | `hub_run_build`, `hub_ensure_release`, `hub_cancel_build`, `hub_cancel_deployment`, `hub_deploy_build`, `hub_retry_deployment`, `hub_rollback` |
| CONFIGURE | `hub_configure_pipeline`, `hub_create_application`, `hub_create_pipeline` |
| CONFIGURE, all projects | `hub_create_project`, `hub_configure_runner` |

The endpoint returns only tools permitted by the key. It also provides `infra-hub://release-guide` and the `release_workflow` prompt. Authorized artifact downloads use `/mcp/artifacts/<deployment UUID>/<allowed filename>` and the same Bearer credential. Keys cannot create other keys or read platform secrets. Servers must already be connected and READY by the owner.

Every mutation requires a UUID `request_id`. After a lost reply, retry the same ID with the same values; object key order is ignored. Reusing it for different values is rejected. Audit records retain a hash and safe result, not complete secret-bearing inputs.

## Release protocol

1. Read the catalog/context; use actual pipeline/environment links instead of guessing from branch names.
2. Push source to GitHub, then supply the full published 40-character SHA to `hub_ensure_release`. Dirty local source is not a release input.
3. If the branch has moved, the release is rejected. Compatible current jobs may be reused instead of duplicated.
4. Poll build and deployment separately. `build=SUCCEEDED` confirms an image, not a live application. Check the expected SHA against current deployment state.
5. Reconcile INTERRUPTED using the original deployment UUID. A late cancellation can still finish successfully after the remote switch begins; report the actual outcome.

Start agents with READ and the smallest project scope. Grant RELEASE or CONFIGURE only when intended. See [security](../SECURITY.md) and [pipelines](managed-pipelines.md).
