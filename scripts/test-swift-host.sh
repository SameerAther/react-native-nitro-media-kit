#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
media_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/media-host-tests.XXXXXX")
trap 'rm -rf "$media_test_dir"' EXIT
swiftc -module-cache-path "$media_test_dir/modules" -parse-as-library \
  ios/LoudnessMeter.swift ios/AssetStorage.swift ios/EditingPlan.swift \
  ios/ExportPlanner.swift ios/PipelineControl.swift ios/MediaJob.swift \
  ios/BoundedDownload.swift tests/host/PlannerTypes.swift \
  tests/host/PipelineTests.swift -o "$media_test_dir/pipeline-tests"
"$media_test_dir/pipeline-tests"
