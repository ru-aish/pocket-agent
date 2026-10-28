#!/usr/bin/env python3
"""Evaluate the shipped MiniLM model and actual Java parser/dialogue code without a phone.

No messages/calls are sent: the Java harness uses isolated fake Android backends.
Dependencies are checksum-pinned and cached outside the repository.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/quickcommands"
ARTIFACTS = {
    "onnxruntime.jar": (
        "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.21.0/onnxruntime-1.21.0.jar",
        "5b57b8c6303303f8d8cbed7f787f31611718b746ab2fa45c888c9df153f1b3c2",
    ),
    "json.jar": (
        "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar",
        "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed",
    ),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", action="store_true", help="Regenerate shipped prototype vectors after intentional corpus/parser changes")
    parser.add_argument("--report", type=Path, default=ROOT / "artifacts/quick-command-evaluation.json")
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "pocket-quick-commands")
    args = parser.parse_args()
    expected = {"model.onnx": "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474", "vocab.txt": "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"}
    for name, sha in expected.items():
        if digest(ASSETS / name) != sha:
            raise RuntimeError(f"Shipped {name} checksum mismatch")
    args.cache.mkdir(parents=True, exist_ok=True)
    for name, (url, sha) in ARTIFACTS.items():
        target = args.cache / name
        if not target.exists() or digest(target) != sha:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != sha:
                raise RuntimeError(f"Dependency checksum mismatch: {name}")
            target.write_bytes(data)
    source = ROOT / "app/src/main/java/dev/pocket/agent"
    sources = [source / (name + ".java") for name in ("Json", "QuickCommandParser", "QuickCommandFacts", "QuickCommandEncoder", "QuickCommandEngine")]
    sources.append(ROOT / "scripts/quick_commands/QuickCommandEvaluation.java")
    with tempfile.TemporaryDirectory(prefix="pocket-quick-") as temporary:
        classpath = os.pathsep.join(str(args.cache / name) for name in ARTIFACTS)
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", temporary, *map(str, sources)], check=True)
        java = ["java", "-cp", temporary + os.pathsep + classpath, "dev.pocket.agent.QuickCommandEvaluation", str(ASSETS)]
        if args.generate:
            subprocess.run(java + ["--generate"], check=True)
            provenance = {**expected, "prototypes.tsv": digest(ASSETS / "prototypes.tsv"), "prototypes.bin": digest(ASSETS / "prototypes.bin")}
            (ASSETS / "checksums.json").write_text(json.dumps(provenance, indent=2) + "\n")
        provenance = json.loads((ASSETS / "checksums.json").read_text())
        for name, sha in provenance.items():
            if digest(ASSETS / name) != sha:
                raise RuntimeError(f"Prototype provenance changed: {name}; regenerate deliberately with --generate")
        args.report.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(java + [str(ROOT / "scripts/quick_commands/requests.json"), str(args.report)], check=True)


if __name__ == "__main__":
    main()
