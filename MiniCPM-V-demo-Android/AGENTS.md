# Android build and installation rules

## Stable application signing

- Never install `com.example.minicpm_v_demo` with Gradle's generated debug key.
- Every device install and connected Android test must first run `verifyInstallationSigning`.
- Use the canonical certificate pinned in `app/build.gradle.kts`; keep the keystore and credentials outside Git via `signing.local.properties` or Gradle properties.
- Before changing the pinned certificate, compare it with the installed package certificate and obtain explicit approval for any uninstall that could erase application data.
- On `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop. Do not uninstall automatically and do not generate another key.
- Do not run Gradle `connected*AndroidTest` tasks: AGP cleanup can uninstall the target package and erase app-private models, conversations, and knowledge bases even when tests pass.
- For device tests, build and verify signing first, install both APKs with `adb install -r`, then invoke the selected test with `adb shell am instrument`; never uninstall the target package as test cleanup.

## Canonical Windows Android environment

- Run Gradle through `gradlew.bat`; it loads `android-env.bat` and the ignored machine-specific `environment.local.bat`.
- Keep the single Gradle user home at the workspace root `.gradle-user-home` and the single Android user home at `.android`.
- Do not create `.gradle-user-home`, `.gradle-local`, `.android-local`, `.android`, or `.android-user-home` inside `MiniCPM-V-demo-Android`.
- Use JDK 21 and `D:\Android\Sdk`; do not rely on the older outer `D:\Android\platform-tools` PATH entry.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- After modifying plans, ADRs, threat models, READMEs, or other documents, use the installed graphify skill to refresh semantic extraction before finishing the task; `graphify update .` alone is insufficient because it only refreshes code AST.
- Before reporting a local task complete, run `graphify check-update .`. If semantic changes are pending, update them with an available configured LLM backend or an explicitly approved semantic-extraction sub-agent; never stamp a failed or omitted document as current.
- Keep `graphify-out/graph.json`, `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.html`, labels, manifest, and cost audit in the workspace as persistent project artifacts.
