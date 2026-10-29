#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
SOURCES=(src/progressivedelivery/*.groovy test/Fake*.groovy test/stubs/org/jenkinsci/plugins/workflow/steps/*.groovy)

if [ -n "${GROOVY_LIB:-}" ]; then
  CP="$(ls "$GROOVY_LIB"/groovy-3*.jar "$GROOVY_LIB"/groovy-json-3*.jar "$GROOVY_LIB"/asm*.jar "$GROOVY_LIB"/antlr4-runtime*.jar | tr '\n' ':')"
  java -cp "$CP" org.codehaus.groovy.tools.FileSystemCompiler -d "$OUT" "${SOURCES[@]}"
  exec java -cp "$CP:$OUT" groovy.ui.GroovyMain -Drepo.root=. test/RunTests.groovy
fi

command -v groovyc >/dev/null || { echo "groovyc not found; install Groovy or set GROOVY_LIB" >&2; exit 2; }
groovyc -d "$OUT" "${SOURCES[@]}"
exec groovy -cp "$OUT" -Drepo.root=. test/RunTests.groovy
