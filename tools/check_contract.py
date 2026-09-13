#!/usr/bin/env python3
"""Run the original pinned harness with a temporary Java registration."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract', required=True, type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/lm15.jar')
    args, rest = parser.parse_known_args()
    contract = args.contract.resolve()
    pin = (ROOT / 'CONTRACT_PIN').read_text().strip()
    head = subprocess.check_output(['git', '-C', str(contract), 'rev-parse', 'HEAD'], text=True).strip()
    if head != pin:
        raise SystemExit(f'Contract must be at {pin}, found {head}')
    if subprocess.check_output(['git', '-C', str(contract), 'status', '--porcelain'], text=True):
        raise SystemExit('Contract checkout must be clean')
    if not args.jar.is_file():
        raise SystemExit('Build the Java package before running the contract checks')
    spec = importlib.util.spec_from_file_location('java_contract_check', contract / 'harness/check.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix='lm15-java-check-') as temporary:
        registry = Path(temporary) / 'shims.json'
        registry.write_text(json.dumps({'java': {'cwd': str(ROOT), 'command': [args.java, '-jar', str(args.jar.resolve())]}}))
        module.SHIMS_FILE = registry
        return module.main(['--shim', 'java', '--report-dir', str(ROOT / 'harness-reports'), *rest])


if __name__ == '__main__':
    raise SystemExit(main())
