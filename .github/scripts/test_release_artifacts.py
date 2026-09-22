#!/usr/bin/env python3

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

SCRIPT = Path(__file__).with_name("release_artifacts.py")
spec = importlib.util.spec_from_file_location("release_artifacts", SCRIPT)
release_artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_artifacts)


class ReleaseArtifactsTest(unittest.TestCase):

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repository = self.root / "source"
        self.repository.mkdir()
        self.git("init", "-q")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("config", "user.name", "Release Test")
        (self.repository / "gradle.properties").write_text("projectVersion=1.2.3\n")
        (self.repository / "source.txt").write_text("final source\n")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "finalize release")
        self.staging = self.repository / "build" / "repo" / "example" / "component" / "1.2.3"
        self.staging.mkdir(parents=True)
        self.write_artifact("component-1.2.3.jar", b"checked jar bytes")
        self.write_artifact("component-1.2.3.pom", b"<project/>\n")
        self.write_artifact("component-1.2.3.module", b"{}\n")
        self.evidence = self.repository / "build" / "release"
        self.state = self.repository / ".release" / "source-state.json"

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *arguments):
        return subprocess.run(
            ["git", *arguments],
            cwd=self.repository,
            check=True,
            text=True,
            capture_output=True,
        ).stdout.strip()

    def write_artifact(self, name, content):
        artifact = self.staging / name
        artifact.write_bytes(content)
        for suffix, algorithm in ((".sha256", "sha256"), (".sha512", "sha512")):
            digest = hashlib.new(algorithm, content).hexdigest()
            artifact.with_name(artifact.name + suffix).write_text(digest + "\n")

    def prepare(self):
        release_artifacts.record_source(self.repository, self.state, "1.2.3")
        release_artifacts.prepare_bundle(
            self.repository,
            self.state,
            self.repository / "build" / "repo",
            self.evidence,
            require_signatures=False,
        )

    def test_prepared_bundle_contains_exact_checked_bytes_and_verifies(self):
        self.prepare()

        release_artifacts.verify_release(
            self.repository,
            self.state,
            self.repository / "build" / "repo",
            self.evidence,
        )
        with zipfile.ZipFile(self.evidence / "central-bundle.zip") as bundle:
            self.assertEqual(
                b"checked jar bytes",
                bundle.read("example/component/1.2.3/component-1.2.3.jar"),
            )
        evidence = json.loads((self.evidence / "release-evidence.json").read_text())
        self.assertEqual(self.state.read_bytes(), (self.evidence / "source-state.json").read_bytes())
        self.assertEqual(self.git("rev-parse", "HEAD"), evidence["source"]["commit"])
        self.assertEqual(9, evidence["artifact_count"])

    def test_source_mutation_after_finalization_fails_closed(self):
        release_artifacts.record_source(self.repository, self.state, "1.2.3")
        (self.repository / "source.txt").write_text("mutated source\n")

        with self.assertRaisesRegex(release_artifacts.ReleaseIntegrityError, "tracked source tree is dirty"):
            release_artifacts.prepare_bundle(
                self.repository,
                self.state,
                self.repository / "build" / "repo",
                self.evidence,
                require_signatures=False,
            )

    def test_artifact_mutation_after_verification_fails_closed(self):
        self.prepare()
        (self.staging / "component-1.2.3.jar").write_bytes(b"different bytes")

        with self.assertRaisesRegex(release_artifacts.ReleaseIntegrityError, "checksum mismatch|artifact manifest mismatch"):
            release_artifacts.verify_release(
                self.repository,
                self.state,
                self.repository / "build" / "repo",
                self.evidence,
            )

    def test_bundle_mutation_blocks_upload_before_network_access(self):
        self.prepare()
        with (self.evidence / "central-bundle.zip").open("ab") as bundle:
            bundle.write(b"tamper")

        with mock.patch.object(release_artifacts, "upload_bundle") as upload:
            with self.assertRaisesRegex(release_artifacts.ReleaseIntegrityError, "bundle digest mismatch"):
                release_artifacts.promote(
                    self.repository,
                    self.state,
                    self.evidence,
                    "https://central.example.invalid/api/v1/publisher/upload",
                    "AUTOMATIC",
                    "user",
                    "password",
                    poll_seconds=0,
                    timeout_seconds=0,
                )
        upload.assert_not_called()

    def test_required_signatures_are_enforced(self):
        release_artifacts.record_source(self.repository, self.state, "1.2.3")

        with self.assertRaisesRegex(release_artifacts.ReleaseIntegrityError, "missing detached signature"):
            release_artifacts.prepare_bundle(
                self.repository,
                self.state,
                self.repository / "build" / "repo",
                self.evidence,
                require_signatures=True,
            )

    def test_upload_uses_automatic_policy_and_records_published_status(self):
        self.prepare()
        completed = [
            subprocess.CompletedProcess([], 0, stdout="deployment-123\n", stderr=""),
            subprocess.CompletedProcess([], 0, stdout='{"deploymentState":"PUBLISHED"}', stderr=""),
        ]
        with mock.patch.object(release_artifacts, "_run_curl", side_effect=completed) as run:
            release_artifacts.promote(
                self.repository,
                self.state,
                self.evidence,
                "https://central.example.invalid/api/v1/publisher/upload",
                "AUTOMATIC",
                "user",
                "password",
                poll_seconds=0,
                timeout_seconds=1,
            )

        upload_command = run.call_args_list[0].args[0]
        self.assertIn("publishingType=AUTOMATIC", upload_command[-1])
        self.assertIn("bundle=@" + str(self.evidence / "central-bundle.zip") + ";type=application/octet-stream", upload_command)
        receipt = json.loads((self.evidence / "promotion-receipt.json").read_text())
        self.assertEqual("deployment-123", receipt["deployment_id"])
        self.assertEqual("PUBLISHED", receipt["status"]["deploymentState"])

    def test_curl_failure_does_not_disclose_authorization(self):
        authorization = release_artifacts._authorization_header("central-user", "central-password")
        failure = subprocess.CalledProcessError(
            22,
            ["curl", "--config", "-", "https://central.example.invalid/status"],
            stderr="request failed",
        )

        with mock.patch.object(subprocess, "run", side_effect=failure):
            with self.assertRaises(release_artifacts.ReleaseIntegrityError) as raised:
                release_artifacts._run_curl(
                    ["https://central.example.invalid/status"],
                    authorization,
                )

        message = str(raised.exception)
        self.assertNotIn("central-user", message)
        self.assertNotIn("central-password", message)
        self.assertNotIn(authorization, message)

    def test_accepted_deployment_is_recorded_when_status_polling_fails(self):
        self.prepare()
        completed_upload = subprocess.CompletedProcess(
            [],
            0,
            stdout="deployment-456\n",
            stderr="",
        )
        polling_failure = release_artifacts.ReleaseIntegrityError("Central request failed with exit status 22")

        with mock.patch.object(
            release_artifacts,
            "_run_curl",
            side_effect=[completed_upload, polling_failure],
        ):
            with self.assertRaisesRegex(release_artifacts.ReleaseIntegrityError, "exit status 22"):
                release_artifacts.promote(
                    self.repository,
                    self.state,
                    self.evidence,
                    "https://central.example.invalid/api/v1/publisher/upload",
                    "AUTOMATIC",
                    "user",
                    "password",
                    poll_seconds=0,
                    timeout_seconds=1,
                )

        receipt = json.loads((self.evidence / "promotion-receipt.json").read_text())
        self.assertEqual("deployment-456", receipt["deployment_id"])
        self.assertEqual("UPLOADED", receipt["status"]["deploymentState"])


if __name__ == "__main__":
    unittest.main()
