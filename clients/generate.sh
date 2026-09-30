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

echo "Clients regenerated from api/openapi.yaml"
