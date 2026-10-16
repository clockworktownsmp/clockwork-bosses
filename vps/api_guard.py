#!/usr/bin/env python3
"""Fixed disposable core API plan; adapted from Observatory's existing guard.

The same user/state/flock and scope namespace serialize both projects. No input
is taken from a live/prepared server. This does not modify Observatory's launcher.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

GIB = 1024 ** 3
ROOT = Path(__file__).resolve().parents[1]
STATE = Path(os.environ.get('XDG_STATE_HOME', str(Path.home() / '.local/state'))) / 'clockwork-observatory'
VERSION = '0.3.0-beta.7-api.1'
ARCHIVE = 'clockwork_bosses_api4_candidate'
WRAPPER_HASH = '7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d'
BASELINE_HASH = 'd917fbe4af296e5b54d839b6c04f0db9336f98549b1eff508f5f56b6f58473de'


def atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.partial')
    with temp.open('w') as stream:
        json.dump(value, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    temp.replace(path)


def memory():
    return {key: int(value.split()[0]) * 1024 for key, value in
            (line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
            if key in ('MemTotal', 'MemAvailable')}


def budget(mode):
    server = mode == 'native'
    return dict(cpu_count=4, memory_high_bytes=int((5 if server else 2.5) * GIB),
                memory_max_bytes=int((5.5 if server else 3) * GIB), reserve_bytes=int(1.5 * GIB),
                tasks_max=512, wall_seconds=900 if server else 600, stop_grace_seconds=25,
                disk_reserve_bytes=10 * GIB)


def properties(unit):
    result = subprocess.run(['systemctl', '--user', 'show', unit, '--no-pager', '-p', 'InvocationID',
                             '-p', 'ControlGroup', '-p', 'ActiveState'], capture_output=True, text=True, timeout=3)
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def unit_name(execution):
    # Observatory's existing admission check sees this shared prefix even if a
    # controller dies and drops its lock while owned descendants are still alive.
    return 'clockwork-observatory-core-' + str(uuid.UUID(execution)) + '.scope'


def kill_owned(execution, sig='SIGKILL'):
    execution = str(uuid.UUID(execution))
    receipt = STATE / 'owners' / (execution + '.json')
    if not receipt.is_file() or receipt.is_symlink():
        return False
    record = json.loads(receipt.read_text())
    unit = unit_name(execution)
    current = properties(unit)
    if (record.get('execution_id') != execution or record.get('project') != 'clockwork-bosses.api4'
            or record.get('uid') != os.getuid() or record.get('scope_unit') != unit
            or not record.get('invocation_id') or current.get('InvocationID') != record['invocation_id']):
        return False
    subprocess.run(['systemctl', '--user', 'kill', '--kill-whom=all', '--signal=' + sig, unit],
                   capture_output=True, check=True, timeout=3)
    return True


def validate_inputs():
    manifest_path = ROOT / 'work/api4-input.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('disposable') is not True or manifest.get('candidate_version') != VERSION:
        raise ValueError('Missing exact disposable candidate manifest')
    if not ROOT.name.startswith('clockwork-core-api4-'):
        raise ValueError('Execution must use a separate named core snapshot')
    for item in manifest['files']:
        relative = Path(item['path'])
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Unsafe source manifest path')
        path = ROOT / relative
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError('Source input escaped snapshot')
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Source fingerprint changed: ' + item['path'])
    wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
    if hashlib.sha256(wrapper.read_bytes()).hexdigest() != WRAPPER_HASH:
        raise ValueError('Unverified Gradle wrapper')
    baseline = Path(os.environ['CLOCKWORK_CORE_BASELINE'])
    if hashlib.sha256(baseline.read_bytes()).hexdigest() != BASELINE_HASH:
        raise ValueError('Legacy API check baseline differs from preserved beta.6')
    return hashlib.sha256(manifest_path.read_bytes()).hexdigest()


def command(mode):
    if mode not in ('build', 'native'):
        raise ValueError('Only the fixed build/native plans are supported')
    tasks = (['compileJava', 'compileClientJava', 'compileGametestJava', 'encounterApiChecks',
              'auditChecks', 'workshopCoreSchemaChecks', 'workshopApiChecks', 'jar']
             if mode == 'build' else ['runGametest'])
    heap = '512m' if mode == 'native' else '1g'
    return [str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-Xmx128m', '-classpath',
            str(ROOT / 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
            '--no-daemon', '--max-workers=2', '--no-parallel', '--console=plain',
            '-Dorg.gradle.jvmargs=-Xmx' + heap + ' -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8',
            '--init-script', str(ROOT / 'vps/limits.gradle'), '-Pmod_version=' + VERSION,
            '-Parchives_base_name=' + ARCHIVE, '-Pworkshop_build_channel=beta'] + tasks


def main(mode):
    launch = command(mode)  # Validate the fixed plan before admission.
    fingerprint = validate_inputs()
    policy = budget(mode)
    heartbeat = ROOT / 'build/combat-gametest/api4-heartbeat.txt'
    if mode == 'native' and heartbeat.exists():
        raise RuntimeError('Native check needs a fresh heartbeat input')
    STATE.mkdir(parents=True, exist_ok=True)
    # Never remove, replace or change ownership of the shared lock.
    with (STATE / 'execution.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        lock_stat = os.fstat(lock.fileno())
        if lock_stat.st_uid != os.getuid() or lock_stat.st_nlink != 1:
            raise RuntimeError('Shared lock ownership cannot be verified')
        if len(os.sched_getaffinity(0)) != 4:
            raise RuntimeError('Expected the approved four-CPU allocation')
        usable = (memory()['MemAvailable'] - policy['reserve_bytes']) // (1024 ** 2) * (1024 ** 2)
        policy['memory_max_bytes'] = min(policy['memory_max_bytes'], usable)
        if policy['memory_max_bytes'] < int((4.5 if mode == 'native' else 3) * GIB):
            raise RuntimeError('Insufficient measured RAM headroom')
        policy['memory_high_bytes'] = min(policy['memory_high_bytes'], int(policy['memory_max_bytes'] * .9)) // (1024 ** 2) * (1024 ** 2)
        if shutil.disk_usage(ROOT).free < policy['disk_reserve_bytes']:
            raise RuntimeError('Insufficient disk headroom')
        active = subprocess.run(['systemctl', '--user', 'list-units', '--type=scope', '--state=running',
                                 '--no-legend', '--plain'], capture_output=True, text=True, check=True, timeout=3)
        if any(line.strip().startswith('clockwork-observatory-') for line in active.stdout.splitlines()):
            raise RuntimeError('An existing shared workload scope is still active')
        execution = str(uuid.uuid4())
        unit = unit_name(execution)
        record_path = STATE / 'owners' / (execution + '.json')
        evidence = ROOT / 'vps/evidence' / ('resources-' + execution + '.json')
        record = dict(schema='clockwork.core-api4-owned-execution', project='clockwork-bosses.api4',
                      execution_id=execution, uid=os.getuid(), wrapper_pid=os.getpid(), scope_unit=unit,
                      input_manifest_sha256=fingerprint, candidate_version=VERSION, mode=mode, budget=policy,
                      applied_verified=False, status='starting', samples=[], peak_cgroup_bytes=0,
                      started_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
        atomic(record_path, record)
        atomic(evidence, record)
        launch = ['systemd-run', '--user', '--scope', '--quiet', '--collect', '--unit=' + unit.removesuffix('.scope'),
                  '-p', 'CPUQuota=400%', '-p', 'MemoryHigh=' + str(policy['memory_high_bytes']),
                  '-p', 'MemoryMax=' + str(policy['memory_max_bytes']), '-p', 'MemorySwapMax=0', '-p', 'TasksMax=512'] + launch
        reason = None
        stopped_at = None
        parent = os.getppid()
        started = time.monotonic()
        low = high = 0
        cgroup = None

        def interrupted(signum, frame):
            nonlocal reason
            reason = reason or 'owner_signal'

        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        print('CORE_API4_EXECUTION=' + execution, flush=True)
        process = None
        try:
            process = subprocess.Popen(launch, cwd=ROOT, stdin=subprocess.DEVNULL)
            while process.poll() is None:
                now = time.monotonic()
                current = properties(unit)
                if current.get('ControlGroup') and cgroup is None:
                    cgroup = (Path('/sys/fs/cgroup') / current['ControlGroup'].lstrip('/')).resolve()
                    if not cgroup.is_relative_to('/sys/fs/cgroup'):
                        raise RuntimeError('Invalid owned cgroup')
                    record['invocation_id'] = current['InvocationID']
                    limits = {name: (cgroup / name).read_text().strip() for name in
                              ['memory.max', 'memory.high', 'memory.swap.max', 'pids.max', 'cpu.max']}
                    cpu = limits['cpu.max'].split()
                    record['applied_limits'] = limits
                    record['applied_verified'] = (int(limits['memory.max']) == policy['memory_max_bytes']
                        and int(limits['memory.high']) == policy['memory_high_bytes'] and limits['memory.swap.max'] == '0'
                        and limits['pids.max'] == '512' and cpu[0] != 'max' and int(cpu[0]) / int(cpu[1]) == 4)
                    atomic(record_path, record)
                    if not record['applied_verified']:
                        reason = 'resource_controls_not_applied'
                sample = dict(wall_seconds=round(now - started, 3), available_bytes=memory()['MemAvailable'],
                              disk_free_bytes=shutil.disk_usage(ROOT).free, cgroup_bytes=0)
                if cgroup is not None and cgroup.is_dir():
                    try:
                        sample['cgroup_bytes'] = int((cgroup / 'memory.current').read_text())
                        sample['cpu_stat'] = (cgroup / 'cpu.stat').read_text()
                        sample['memory_events'] = (cgroup / 'memory.events').read_text()
                        sample['guest_cpu_counters'] = Path('/proc/stat').read_text().splitlines()[0]
                        record['peak_cgroup_bytes'] = max(record['peak_cgroup_bytes'], sample['cgroup_bytes'])
                    except FileNotFoundError:
                        if process.poll() is not None:
                            break
                        raise
                low = low + 1 if sample['available_bytes'] < policy['reserve_bytes'] else 0
                high = high + 1 if sample['cgroup_bytes'] > policy['memory_high_bytes'] else 0
                if low >= 3 or sample['available_bytes'] < GIB:
                    reason = reason or 'host_memory_headroom'
                if high >= 10 or sample['disk_free_bytes'] < policy['disk_reserve_bytes']:
                    reason = reason or 'memory_pressure_or_disk_headroom'
                if now - started > policy['wall_seconds']:
                    reason = reason or 'execution_wall_deadline'
                actual_lock = (STATE / 'execution.lock').stat()
                if os.getppid() != parent or (actual_lock.st_dev, actual_lock.st_ino) != (lock_stat.st_dev, lock_stat.st_ino):
                    reason = reason or 'owner_or_lock_lost'
                if cgroup is not None and current.get('InvocationID') != record.get('invocation_id'):
                    reason = reason or 'scope_ownership_lost'
                if mode == 'native':
                    if heartbeat.is_file():
                        if time.time() - heartbeat.stat().st_mtime > 20:
                            reason = reason or 'native_heartbeat_stall'
                    elif now - started > 150:
                        reason = reason or 'native_startup_deadline'
                if len(record['samples']) < 1800:
                    record['samples'].append(sample)
                if reason and stopped_at is None:
                    stopped_at = now
                    record.update(status='stopping', stop_reason=reason)
                    if mode == 'native':
                        (ROOT / 'build/combat-gametest/api4-stop.request').write_text('stop\n')
                    elif not kill_owned(execution, 'SIGTERM'):
                        raise RuntimeError('Cannot verify ownership for native stop')
                if stopped_at is not None and now - stopped_at > policy['stop_grace_seconds']:
                    if not kill_owned(execution):
                        raise RuntimeError('Cannot verify ownership for bounded stop')
                    record['forced_termination'] = True
                    break
                atomic(record_path, record)
                atomic(evidence, record)
                time.sleep(1)
            code = process.wait(timeout=10)
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and properties(unit).get('ActiveState') == 'active':
                time.sleep(.1)
            record['owned_scope_inactive'] = properties(unit).get('ActiveState') != 'active'
            if not record['owned_scope_inactive']:
                reason = reason or 'owned_descendants_remain'
                kill_owned(execution)
            if mode == 'native':
                shutdown = ROOT / 'build/combat-gametest/api4-shutdown-result.txt'
                if not shutdown.is_file() or not shutdown.read_text().startswith('PASS:'):
                    reason = reason or 'native_shutdown_receipt_missing_or_failed'
                report = ROOT / 'build/api4-candidate/combat-gametest.xml'
                if report.is_file():
                    cases = ET.parse(report).findall('.//testcase')
                    record['native_tests'] = [{'name': item.get('name'), 'passed': item.find('failure') is None
                                               and item.find('error') is None and item.find('skipped') is None} for item in cases]
                    if len(cases) != 7 or not all(item['passed'] for item in record['native_tests']):
                        reason = reason or 'native_test_report_incomplete_or_failed'
                else:
                    reason = reason or 'native_test_report_missing'
            record.update(exit_code=code, elapsed_seconds=round(time.monotonic() - started, 3), stop_reason=reason,
                          status='stopped' if code == 0 and not reason and record['applied_verified'] else 'fail',
                          cleanup_verified=False)
            return 0 if record['status'] == 'stopped' else (code or 1)
        except BaseException as error:
            record.update(status='fail', reason=str(error), cleanup_verified=False)
            if process is not None:
                try:
                    kill_owned(execution)
                    process.wait(timeout=10)
                except Exception:
                    pass
            raise
        finally:
            atomic(record_path, record)
            atomic(evidence, record)


if __name__ == '__main__':
    try:
        if len(sys.argv) == 3 and sys.argv[1] == '--kill-owned':
            raise SystemExit(0 if kill_owned(sys.argv[2]) else 75)
        if len(sys.argv) != 2:
            raise ValueError('Usage: api_guard.py build|native')
        raise SystemExit(main(sys.argv[1]))
    except Exception as error:
        print('CORE RESOURCE REFUSAL: ' + str(error), file=sys.stderr)
        raise SystemExit(75)
