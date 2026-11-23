# Higress Console Helm Chart

Installs the console components of cloud-native gateway [Higress](http://higress.io/)

**Note:** It is highly recommended to install the whole package of Higress. Please visit https://higress.io/docs/user/quickstart/ for details.

## Swagger / OpenAPI

The chart defaults to `swagger.enabled=false`. Set `swagger.enabled=true` to
enable the authenticated Swagger UI and OpenAPI endpoints. Both are controlled
by the application's `HIGRESS_CONSOLE_API_DOCS_ENABLED` environment variable.

An explicit `podEnvs.HIGRESS_CONSOLE_API_DOCS_ENABLED` overrides `swagger.enabled`.
For example, `--set swagger.enabled=true` enables documentation, while
`--set-string podEnvs.HIGRESS_CONSOLE_API_DOCS_ENABLED=false` disables it even if
the chart setting is true. The template emits this environment variable once.

See `docs/swagger-openapi.md` in the repository for authentication and endpoint
details. Enabling documentation does not bypass console authentication.
