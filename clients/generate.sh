#!/usr/bin/env bash
# Regenerates the client code under clients/*/generated from api/openapi.yaml.
# Generated code is never edited by hand: CI runs this script and fails if the
# result differs from what is committed (contract drift).
set -euo pipefail

cd "$(dirname "$0")/.."
GENERATOR_IMAGE="openapitools/openapi-generator-cli:v7.16.0"
# Application-facing API only: no admin, provider callbacks or webhook descriptions.
FILTER='FILTER=tag:Payments|Refunds|Payouts|Events|Ledger|Exports|Meta'
WORK="target/client-gen"

rm -rf "$WORK" && mkdir -p "$WORK"

generate() {
  docker run --rm -u "$(id -u):$(id -g)" \
    -v "$PWD/api:/spec:ro" -v "$PWD/$WORK:/out" \
    "$GENERATOR_IMAGE" generate -i /spec/openapi.yaml --openapi-normalizer "$FILTER" \
    --skip-validate-spec "$@" > "$WORK/generator.log" 2>&1 || { cat "$WORK/generator.log"; exit 1; }
}

# PHP: models and API classes under Yoon\Generated.
generate -g php -o /out/php --additional-properties='invokerPackage=Yoon\Generated,srcBasePath=lib,hideGenerationTimestamp=true'
rm -rf clients/php/generated && mkdir -p clients/php/generated
cp -r "$WORK/php/lib/." clients/php/generated/
# The spec's `webhooks` section describes what Yoon sends; it is not an API to call.
rm -f clients/php/generated/Api/WebhooksToYourAppApi.php
# Guzzle 8 (Laravel 13) removed GuzzleHttp\Utils::jsonEncode: point the generated code at an
# equivalent helper (src/Internal/Json.php) so the package works with Guzzle 7 and 8.
find clients/php/generated -name '*.php' -exec sed -i 's/\\GuzzleHttp\\Utils::jsonEncode(/\\Yoon\\Internal\\Json::encode(/g' {} +
if grep -rq 'GuzzleHttp\\Utils::' clients/php/generated; then echo "unpatched Guzzle helper in generated PHP" >&2; exit 1; fi

# Java: java.net.http client, Jackson, no extra nullable library.
generate -g java -o /out/java --additional-properties='library=native,invokerPackage=dev.yoonpay.client.generated,apiPackage=dev.yoonpay.client.generated.api,modelPackage=dev.yoonpay.client.generated.model,openApiNullable=false,hideGenerationTimestamp=true,useJakartaEe=true,annotationLibrary=none,documentationProvider=none'
rm -rf clients/java/generated && mkdir -p clients/java/generated
cp -r "$WORK/java/src/main/java/." clients/java/generated/
rm -f clients/java/generated/dev/yoonpay/client/generated/api/WebhooksToYourAppApi.java

# JavaScript / TypeScript: typescript-fetch on the platform's global fetch and Web Crypto,
# so it runs on Node, Bun, Deno and edge runtimes. Without runtime checks: a newer server's
# unknown enum values or extra fields must not crash parsing (models stay plain interfaces).
# Property names stay as the contract spells them (checkout_url, not checkoutUrl): the
# typescript-fetch runtime never renames keys, so interfaces must match the wire format.
generate -g typescript-fetch -o /out/js --additional-properties='npmName=@yoonpay/yoon,supportsES6=true,hideGenerationTimestamp=true,withoutRuntimeChecks=true,modelPropertyNaming=original,paramNaming=original'
rm -rf clients/js/generated && mkdir -p clients/js/generated
# Keep only the TypeScript sources; package scaffolding is hand-written at clients/js.
rm -rf "$WORK/js/.openapi-generator" "$WORK/js/.openapi-generator-ignore" "$WORK/js/README.md"
rm -f "$WORK"/js/package.json "$WORK"/js/tsconfig.json "$WORK"/js/tsconfig.esm.json \
      "$WORK"/js/.gitignore "$WORK"/js/.npmignore "$WORK"/js/LICENSE
cp -r "$WORK/js/." clients/js/generated/
# The spec's `webhooks` section describes what Yoon sends; it is not an API to call.
rm -f clients/js/generated/src/apis/WebhooksToYourAppApi.ts

# Python: the `python` generator (pydantic v2, urllib3) as the package yoonpay.generated; the
# hand-written layer is clients/python/src/yoonpay.
generate -g python -o /out/python --additional-properties='packageName=yoonpay.generated,projectName=yoonpay,hideGenerationTimestamp=true'
rm -rf clients/python/generated && mkdir -p clients/python/generated/yoonpay
# Keep only the module; packaging (pyproject.toml), README and tests are hand-written.
cp -r "$WORK/python/yoonpay/generated" clients/python/generated/yoonpay/
# The spec's `webhooks` section describes what Yoon sends; it is not an API to call.
rm -f clients/python/generated/yoonpay/generated/api/webhooks_to_your_app_api.py
if grep -rq 'webhooks_to_your_app_api' clients/python/generated; then echo "generated Python still imports the webhooks API" >&2; exit 1; fi
# Query parameters of type date-time: the generator formats them with strftime("...%z"), which
# writes the offset as "+0000", which the server refuses (400); isoformat() writes the RFC 3339
# "+00:00".
perl -0pi -e 's/(\w+)\.strftime\(\s*self\.api_client\.configuration\.datetime_format\s*\)/$1.isoformat()/g' \
  clients/python/generated/yoonpay/generated/api/*.py
if grep -rq 'configuration.datetime_format' clients/python/generated/yoonpay/generated/api; then
  echo "unpatched date-time formatting in generated Python" >&2; exit 1
fi
# A newer server may send an enum value this client does not know (a new status or event
# type): parsing must not fail. Each generated enum gets a _missing_ hook that keeps the raw
# string as a pseudo-member, so `payment.status == "new_value"` still works.
enums=$(grep -l '^class [A-Za-z]*(str, Enum):$' clients/python/generated/yoonpay/generated/models/*.py)
[ -n "$enums" ] || { echo "no generated Python enums found: the unknown-value patch no longer applies" >&2; exit 1; }
for f in $enums; do
  [ "$(grep -c '^class ' "$f")" = 1 ] && tail -n 3 "$f" | grep -q 'return cls(json.loads(json_str))' \
    || { echo "unexpected enum layout in $f: the unknown-value patch no longer applies" >&2; exit 1; }
  cat >> "$f" <<'PY'
    @classmethod
    def _missing_(cls, value):
        # Added by clients/generate.sh: unknown values from a newer server must not crash parsing.
        if not isinstance(value, str):
            return None
        member = str.__new__(cls, value)
        member._name_ = value.upper()
        member._value_ = value
        return member
PY
done

echo "Clients regenerated from api/openapi.yaml"
