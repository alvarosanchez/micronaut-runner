#!/usr/bin/env python3
"""Prepare and promote one immutable, verified Maven Central bundle."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from typing import Callable, Dict, Iterable, List, Optional, Tuple
from urllib.parse import quote, urlencode, urlsplit, urlunsplit
import zipfile


class ReleaseIntegrityError(RuntimeError):
    """Raised when release source or artifact identity no longer matches."""


CHECKSUM_ALGORITHMS = {
    ".md5": "md5",
    ".sha1": "sha1",
    ".sha256": "sha256",
    ".sha512": "sha512",
}
SIGNABLE_SUFFIXES = (".jar", ".pom", ".module", ".toml")
MANIFEST_NAME = "artifacts.sha256"
BUNDLE_NAME = "central-bundle.zip"
EVIDENCE_NAME = "release-evidence.json"


def _run_git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        text=True,
        capture_output=True,
    )
    return result.stdout.strip()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _hash_bytes(content: bytes, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, content).hexdigest()


def _read_version(repository: Path) -> str:
    properties = repository / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "projectVersion":
            return value.strip()
    raise ReleaseIntegrityError("gradle.properties has no projectVersion")


def _assert_clean_source(repository: Path) -> None:
    result = subprocess.run(
        ["git", "diff", "--quiet", "HEAD", "--"],
        cwd=repository,
        check=False,
    )
    if result.returncode != 0:
        raise ReleaseIntegrityError("tracked source tree is dirty after release finalization")


def record_source(
    repository: Path,
    state_path: Path,
    version: str,
    expected_commit: Optional[str] = None,
    expected_tree: Optional[str] = None,
) -> Dict[str, str]:
    repository = repository.resolve()
    _assert_clean_source(repository)
    commit = _run_git(repository, "rev-parse", "HEAD")
    tree = _run_git(repository, "rev-parse", "HEAD^{tree}")
    actual_version = _read_version(repository)
    if actual_version != version:
        raise ReleaseIntegrityError(
            f"finalized projectVersion mismatch: expected {version}, found {actual_version}"
        )
    if expected_commit and commit != expected_commit:
        raise ReleaseIntegrityError(
            f"finalized commit mismatch: expected {expected_commit}, found {commit}"
        )
    if expected_tree and tree != expected_tree:
        raise ReleaseIntegrityError(
            f"finalized tree mismatch: expected {expected_tree}, found {tree}"
        )
    state = {
        "schema": 1,
        "commit": commit,
        "tree": tree,
        "version": version,
    }
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return state


def _load_state(state_path: Path) -> Dict[str, object]:
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseIntegrityError(f"cannot read source state {state_path}: {error}") from error
    if state.get("schema") != 1:
        raise ReleaseIntegrityError("unsupported source-state schema")
    for key in ("commit", "tree", "version"):
        if not isinstance(state.get(key), str) or not state[key]:
            raise ReleaseIntegrityError(f"source state has no valid {key}")
    return state


def verify_source(repository: Path, state_path: Path) -> Dict[str, object]:
    state = _load_state(state_path)
    _assert_clean_source(repository)
    commit = _run_git(repository, "rev-parse", "HEAD")
    tree = _run_git(repository, "rev-parse", "HEAD^{tree}")
    version = _read_version(repository)
    for key, actual in (("commit", commit), ("tree", tree), ("version", version)):
        if state[key] != actual:
            raise ReleaseIntegrityError(
                f"finalized {key} mismatch: expected {state[key]}, found {actual}"
            )
    return state


def _artifact_files(staging: Path) -> List[Path]:
    if not staging.is_dir():
        raise ReleaseIntegrityError(f"staging repository does not exist: {staging}")
    files = sorted(
        (path for path in staging.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(staging).as_posix(),
    )
    if not files:
        raise ReleaseIntegrityError("staging repository is empty")
    for path in files:
        relative = path.relative_to(staging).as_posix()
        if "\n" in relative or "\r" in relative:
            raise ReleaseIntegrityError(f"artifact path contains a line break: {relative!r}")
    return files


def _is_checksum(path: Path) -> bool:
    return any(path.name.endswith(suffix) for suffix in CHECKSUM_ALGORITHMS)


def _verify_repository(staging: Path, require_signatures: bool) -> List[Path]:
    files = _artifact_files(staging)
    relative_names = {path.relative_to(staging).as_posix() for path in files}
    primary = [path for path in files if not _is_checksum(path) and not path.name.endswith(".asc")]
    if not any(path.name.endswith(".jar") for path in primary):
        raise ReleaseIntegrityError("staging repository contains no JAR")
    if not any(path.name.endswith(".pom") for path in primary):
        raise ReleaseIntegrityError("staging repository contains no POM")
    if not any(path.name.endswith(".module") for path in primary):
        raise ReleaseIntegrityError("staging repository contains no Gradle module metadata")

    for artifact in primary:
        relative = artifact.relative_to(staging).as_posix()
        for suffix in (".sha256", ".sha512"):
            sidecar = relative + suffix
            if sidecar not in relative_names:
                raise ReleaseIntegrityError(f"missing {suffix[1:]} checksum for {relative}")
        if require_signatures and artifact.name.endswith(SIGNABLE_SUFFIXES):
            signature = relative + ".asc"
            if signature not in relative_names:
                raise ReleaseIntegrityError(f"missing detached signature for {relative}")

    for sidecar in (path for path in files if _is_checksum(path)):
        suffix, algorithm = next(
            (suffix, algorithm)
            for suffix, algorithm in CHECKSUM_ALGORITHMS.items()
            if sidecar.name.endswith(suffix)
        )
        artifact = sidecar.with_name(sidecar.name[: -len(suffix)])
        if not artifact.is_file():
            raise ReleaseIntegrityError(f"checksum describes a missing artifact: {sidecar}")
        expected = sidecar.read_text(encoding="ascii").strip().split()[0]
        actual = _hash_bytes(artifact.read_bytes(), algorithm)
        if expected.lower() != actual:
            relative = sidecar.relative_to(staging).as_posix()
            raise ReleaseIntegrityError(f"checksum mismatch for {relative}")
    return files


def _manifest_entries(staging: Path, files: Iterable[Path]) -> List[Tuple[str, str]]:
    return [(_sha256(path), path.relative_to(staging).as_posix()) for path in files]


def _manifest_text(entries: Iterable[Tuple[str, str]]) -> str:
    return "".join(f"{digest}  {relative}\n" for digest, relative in entries)


def _write_bundle(staging: Path, bundle_path: Path, entries: Iterable[Tuple[str, str]]) -> None:
    bundle_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(bundle_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for _, relative in entries:
            information = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            information.compress_type = zipfile.ZIP_DEFLATED
            information.external_attr = 0o100644 << 16
            bundle.writestr(information, (staging / relative).read_bytes())


def prepare_bundle(
    repository: Path,
    state_path: Path,
    staging: Path,
    evidence_directory: Path,
    require_signatures: bool,
) -> Dict[str, object]:
    source = verify_source(repository, state_path)
    files = _verify_repository(staging, require_signatures)
    entries = _manifest_entries(staging, files)
    evidence_directory.mkdir(parents=True, exist_ok=True)
    preserved_state = evidence_directory / "source-state.json"
    if state_path.resolve() != preserved_state.resolve():
        shutil.copyfile(state_path, preserved_state)
    manifest_path = evidence_directory / MANIFEST_NAME
    manifest = _manifest_text(entries)
    manifest_path.write_text(manifest, encoding="utf-8")
    (evidence_directory / "artifacts-sha256").write_text(
        base64.b64encode(manifest.encode("utf-8")).decode("ascii") + "\n",
        encoding="ascii",
    )
    bundle_path = evidence_directory / BUNDLE_NAME
    _write_bundle(staging, bundle_path, entries)
    evidence = {
        "schema": 1,
        "source": source,
        "artifact_count": len(entries),
        "manifest_sha256": _sha256(manifest_path),
        "bundle_sha256": _sha256(bundle_path),
        "require_signatures": require_signatures,
    }
    (evidence_directory / EVIDENCE_NAME).write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    verify_release(repository, state_path, staging, evidence_directory)
    return evidence


def _load_evidence(evidence_directory: Path) -> Dict[str, object]:
    path = evidence_directory / EVIDENCE_NAME
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseIntegrityError(f"cannot read release evidence {path}: {error}") from error
    if evidence.get("schema") != 1:
        raise ReleaseIntegrityError("unsupported release-evidence schema")
    return evidence


def _parse_manifest(manifest_path: Path) -> List[Tuple[str, str]]:
    entries: List[Tuple[str, str]] = []
    for line in manifest_path.read_text(encoding="utf-8").splitlines():
        digest, separator, relative = line.partition("  ")
        if not separator or len(digest) != 64 or not relative or relative.startswith("/") or ".." in Path(relative).parts:
            raise ReleaseIntegrityError(f"invalid artifact manifest line: {line!r}")
        entries.append((digest, relative))
    if not entries:
        raise ReleaseIntegrityError("artifact manifest is empty")
    return entries


def verify_bundle(evidence_directory: Path) -> Dict[str, object]:
    evidence = _load_evidence(evidence_directory)
    manifest_path = evidence_directory / MANIFEST_NAME
    bundle_path = evidence_directory / BUNDLE_NAME
    if _sha256(manifest_path) != evidence.get("manifest_sha256"):
        raise ReleaseIntegrityError("artifact manifest digest mismatch")
    if _sha256(bundle_path) != evidence.get("bundle_sha256"):
        raise ReleaseIntegrityError("bundle digest mismatch")
    entries = _parse_manifest(manifest_path)
    if len(entries) != evidence.get("artifact_count"):
        raise ReleaseIntegrityError("artifact count mismatch")
    expected = {relative: digest for digest, relative in entries}
    with zipfile.ZipFile(bundle_path) as bundle:
        actual_names = bundle.namelist()
        if len(actual_names) != len(set(actual_names)) or set(actual_names) != set(expected):
            raise ReleaseIntegrityError("bundle entries do not match the checked artifact manifest")
        for relative, digest in expected.items():
            if _hash_bytes(bundle.read(relative)) != digest:
                raise ReleaseIntegrityError(f"bundle artifact digest mismatch: {relative}")
    return evidence


def verify_release(
    repository: Path,
    state_path: Path,
    staging: Path,
    evidence_directory: Path,
) -> Dict[str, object]:
    source = verify_source(repository, state_path)
    evidence = verify_bundle(evidence_directory)
    if evidence.get("source") != source:
        raise ReleaseIntegrityError("release evidence names a different finalized source tree")
    files = _verify_repository(staging, bool(evidence.get("require_signatures")))
    current_manifest = _manifest_text(_manifest_entries(staging, files))
    expected_manifest = (evidence_directory / MANIFEST_NAME).read_text(encoding="utf-8")
    if current_manifest != expected_manifest:
        raise ReleaseIntegrityError("artifact manifest mismatch after verification")
    return evidence


def _authorization_header(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Authorization: Bearer {token}"


def _status_url(upload_url: str, deployment_id: str) -> str:
    parts = urlsplit(upload_url)
    if not parts.path.endswith("/upload"):
        raise ReleaseIntegrityError("Central upload URL must end in /upload")
    return urlunsplit(
        (parts.scheme, parts.netloc, parts.path[: -len("/upload")] + "/status", urlencode({"id": deployment_id}), "")
    )


def _run_curl(arguments: List[str], authorization: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["curl", "--config", "-", *arguments],
            input=f'header = "{authorization}"\n',
            check=True,
            text=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError as error:
        raise ReleaseIntegrityError(
            f"Central request failed with exit status {error.returncode}"
        ) from None


def upload_bundle(
    bundle_path: Path,
    upload_url: str,
    publishing_type: str,
    username: str,
    password: str,
    poll_seconds: int,
    timeout_seconds: int,
    on_uploaded: Callable[[str], object],
) -> Tuple[str, Dict[str, object]]:
    if publishing_type not in {"AUTOMATIC", "USER_MANAGED"}:
        raise ReleaseIntegrityError(f"unsupported Central publishing policy: {publishing_type}")
    separator = "&" if "?" in upload_url else "?"
    endpoint = upload_url + separator + urlencode(
        {"name": bundle_path.name, "publishingType": publishing_type}
    )
    authorization = _authorization_header(username, password)
    upload = _run_curl(
        [
            "--fail-with-body",
            "--silent",
            "--show-error",
            "--request",
            "POST",
            "--form",
            f"bundle=@{bundle_path};type=application/octet-stream",
            endpoint,
        ],
        authorization,
    )
    deployment_id = upload.stdout.strip()
    if not deployment_id:
        raise ReleaseIntegrityError("Central returned no deployment ID")
    on_uploaded(deployment_id)

    deadline = time.monotonic() + timeout_seconds
    status_url = _status_url(upload_url, deployment_id)
    while True:
        status_result = _run_curl(
            [
                "--fail-with-body",
                "--silent",
                "--show-error",
                "--request",
                "POST",
                status_url,
            ],
            authorization,
        )
        try:
            status = json.loads(status_result.stdout)
        except json.JSONDecodeError as error:
            raise ReleaseIntegrityError("Central returned an invalid deployment status") from error
        state = status.get("deploymentState")
        if state == "PUBLISHED" or (publishing_type == "USER_MANAGED" and state == "VALIDATED"):
            return deployment_id, status
        if state == "FAILED":
            raise ReleaseIntegrityError(f"Central deployment failed: {json.dumps(status, sort_keys=True)}")
        if time.monotonic() >= deadline:
            raise ReleaseIntegrityError(f"Central deployment did not complete before timeout: {state}")
        time.sleep(poll_seconds)


def promote(
    repository: Path,
    state_path: Path,
    evidence_directory: Path,
    upload_url: str,
    publishing_type: str,
    username: str,
    password: str,
    poll_seconds: int,
    timeout_seconds: int,
) -> Dict[str, object]:
    source = verify_source(repository, state_path)
    evidence = verify_bundle(evidence_directory)
    if evidence.get("source") != source:
        raise ReleaseIntegrityError("release evidence names a different finalized source tree")
    if not username or not password:
        raise ReleaseIntegrityError("Central credentials are required only for promotion")

    receipt_path = evidence_directory / "promotion-receipt.json"

    def write_receipt(deployment_id: str, status: Dict[str, object]) -> Dict[str, object]:
        receipt = {
            "schema": 1,
            "deployment_id": deployment_id,
            "publishing_type": publishing_type,
            "bundle_sha256": evidence["bundle_sha256"],
            "source": source,
            "status": status,
        }
        receipt_path.write_text(
            json.dumps(receipt, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return receipt

    deployment_id, status = upload_bundle(
        evidence_directory / BUNDLE_NAME,
        upload_url,
        publishing_type,
        username,
        password,
        poll_seconds,
        timeout_seconds,
        lambda accepted_id: write_receipt(accepted_id, {"deploymentState": "UPLOADED"}),
    )
    return write_receipt(deployment_id, status)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    record = subparsers.add_parser("record-source")
    record.add_argument("--repository", type=Path, default=Path.cwd())
    record.add_argument("--state", type=Path, required=True)
    record.add_argument("--version", required=True)
    record.add_argument("--expected-commit")
    record.add_argument("--expected-tree")

    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--repository", type=Path, default=Path.cwd())
    prepare.add_argument("--state", type=Path, required=True)
    prepare.add_argument("--staging", type=Path, required=True)
    prepare.add_argument("--evidence", type=Path, required=True)
    prepare.add_argument("--require-signatures", action="store_true")

    verify = subparsers.add_parser("verify")
    verify.add_argument("--repository", type=Path, default=Path.cwd())
    verify.add_argument("--state", type=Path, required=True)
    verify.add_argument("--staging", type=Path, required=True)
    verify.add_argument("--evidence", type=Path, required=True)

    promotion = subparsers.add_parser("promote")
    promotion.add_argument("--repository", type=Path, default=Path.cwd())
    promotion.add_argument("--state", type=Path, required=True)
    promotion.add_argument("--evidence", type=Path, required=True)
    promotion.add_argument(
        "--upload-url",
        default="https://central.sonatype.com/api/v1/publisher/upload",
    )
    promotion.add_argument(
        "--publishing-type",
        choices=("AUTOMATIC", "USER_MANAGED"),
        default="AUTOMATIC",
    )
    promotion.add_argument("--poll-seconds", type=int, default=30)
    promotion.add_argument("--timeout-seconds", type=int, default=1800)
    return parser


def main(arguments: Optional[List[str]] = None) -> int:
    parser = _parser()
    options = parser.parse_args(arguments)
    try:
        if options.command == "record-source":
            state = record_source(
                options.repository,
                options.state,
                options.version,
                options.expected_commit,
                options.expected_tree,
            )
            print(json.dumps(state, sort_keys=True))
        elif options.command == "prepare":
            evidence = prepare_bundle(
                options.repository,
                options.state,
                options.staging,
                options.evidence,
                options.require_signatures,
            )
            print(json.dumps(evidence, sort_keys=True))
        elif options.command == "verify":
            evidence = verify_release(
                options.repository,
                options.state,
                options.staging,
                options.evidence,
            )
            print(json.dumps(evidence, sort_keys=True))
        else:
            receipt = promote(
                options.repository,
                options.state,
                options.evidence,
                options.upload_url,
                options.publishing_type,
                os.environ.get("SONATYPE_USERNAME", ""),
                os.environ.get("SONATYPE_PASSWORD", ""),
                options.poll_seconds,
                options.timeout_seconds,
            )
            print(json.dumps(receipt, sort_keys=True))
    except (ReleaseIntegrityError, subprocess.CalledProcessError, OSError, zipfile.BadZipFile) as error:
        print(f"release integrity check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
