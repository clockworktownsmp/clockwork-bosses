"""Small negative check: no server/build is launched and no shared state changes."""
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('core_guard', Path(__file__).with_name('api_guard.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
with patch.dict(os.environ, JAVA_HOME='/verified/java'):
    plan = guard.command('build')
    assert '-Pmod_version=0.3.0-beta.7-api.1' in plan and '--max-workers=2' in plan and 'encounterApiChecks' in plan
    try:
        guard.command('deploy')
        raise AssertionError('Arbitrary launch plan accepted')
    except ValueError:
        pass

with tempfile.TemporaryDirectory(prefix='clockwork-core-api4-guard-') as temporary:
    root = Path(temporary)
    state = root / 'shared-state'
    with patch.object(guard, 'ROOT', root), patch.object(guard, 'STATE', state), \
         patch.object(guard, 'command', return_value=['intentional-missing-executable']), \
         patch.object(guard, 'validate_inputs', return_value='0' * 64), \
         patch.object(guard, 'memory', return_value={'MemAvailable': 8 * guard.GIB}), \
         patch.object(guard.os, 'sched_getaffinity', return_value={0, 1, 2, 3}), \
         patch.object(guard.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 20 * guard.GIB})()), \
         patch.object(guard.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')), \
         patch.object(guard.subprocess, 'Popen', side_effect=OSError('intentional launch failure')), \
         patch.object(guard, 'kill_owned') as stop:
        try:
            guard.main('build')
            raise AssertionError('Launch failure was hidden')
        except OSError:
            pass
        records = list((state / 'owners').glob('*.json'))
        evidence = list((root / 'vps/evidence').glob('resources-*.json'))
        assert len(records) == 1 and len(evidence) == 1
        record = json.loads(records[0].read_text())
        assert record['status'] == 'fail' and record['reason'] == 'intentional launch failure'
        assert record['applied_verified'] is False and record['cleanup_verified'] is False
        assert json.loads(evidence[0].read_text()) == record
        stop.assert_not_called()
    with (state / 'execution.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(lock, fcntl.LOCK_UN)
print('PASS: fixed plans, launch failure evidence, no unowned stop, shared-lock release')
