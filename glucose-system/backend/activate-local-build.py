"""Activate a tested jar on the existing local service, preserving its in-memory provider settings.

Only loopback database/Redis hosts are accepted. Never prints or persists process credentials.
The existing server PID must be supplied, and must be a Java jar process in this backend directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import psutil

backend = Path(__file__).resolve().parent
root = backend.parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--pid', type=int, required=True)
parser.add_argument('--jar', default='target/glucose-next.jar')
args = parser.parse_args()
process = psutil.Process(args.pid)
command = process.cmdline()
if process.name().lower() != 'java.exe' or '-jar' not in command or Path(process.cwd()).resolve() != backend:
    raise SystemExit('拒绝更新：指定进程不是本项目的本地 Java 服务。')
old_jar = Path(command[command.index('-jar') + 1])
if not old_jar.is_absolute():
    old_jar = backend / old_jar
if not old_jar.resolve().is_relative_to(backend):
    raise SystemExit('拒绝更新：旧程序文件不在本项目中。')
settings = process.environ()  # Memory only; no dump, logging, command interpolation, or configuration file.
if any(c.startswith(('--spring.datasource.', '--spring.data.redis.')) for c in command):
    raise SystemExit('拒绝更新：检测到命令行覆盖数据服务，需先核对实际连接配置。')
port = int(next((c.split('=',1)[1] for c in command if c.startswith('--server.port=')),settings.get('SERVER_PORT','8083')))
if port != 8083:
    raise SystemExit('仅更新既有8083本地服务，拒绝切换其他进程。')
for key in ['DB_HOST', 'REDIS_HOST']:
    if settings.get(key, '127.0.0.1') not in ['127.0.0.1', 'localhost']:
        raise SystemExit('拒绝更新：仅支持本机数据库和缓存。')
dev = (backend / 'src/main/resources/application-dev.yml').read_text(encoding='utf-8')
def setting(key):
    if key in settings:
        return settings[key]
    match = re.search(re.escape(key) + r':([^}]*)\}', dev)
    if not match:
        raise RuntimeError('缺少本机配置项：' + key)
    return match.group(1)
db_name = setting('DB_NAME')
if not re.fullmatch(r'[A-Za-z0-9_]+', db_name):
    raise SystemExit('数据库名称格式不合法。')
mysql = os.environ.get('MYSQL_CLIENT', r'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe')
db_env = {**os.environ, 'MYSQL_PWD': setting('DB_PASSWORD')}
def sql(query):
    r = subprocess.run([mysql, '--host=127.0.0.1', '--port=' + setting('DB_PORT'), '--user=' + setting('DB_USER'), '--database=' + db_name,
                        '--default-character-set=utf8mb4', '--batch', '--skip-column-names'], input=query, text=True, encoding='utf-8',
                       env=db_env, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW, timeout=30)
    if r.returncode:
        raise RuntimeError('本机迁移操作失败，请检查数据库结构。服务尚未切换。')
    return r.stdout.strip()
jar = (backend / args.jar).resolve()
if not jar.is_relative_to(backend / 'target') or not jar.is_file():
    raise SystemExit('拒绝更新：新程序必须为本项目 target 目录下的已验收 jar。')
runtime = backend / '.runtime'
runtime.mkdir(exist_ok=True)
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
release = runtime / ('glucose-' + digest[:16] + '.jar')
if release.exists():
    if hashlib.sha256(release.read_bytes()).hexdigest() != digest:
        raise SystemExit('发布文件校验失败。')
else:
    shutil.copyfile(jar, release)
has_plan = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='medication_plan'") == '1'
has_status = sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_log' AND column_name='status'") == '1'
migrated = False
if has_plan != has_status:
    raise SystemExit('发现未完成的用药迁移，保留当前运行服务，请先核对结构。')
if not has_plan:
    # Back up only the table touched by this migration, as reversible local data protection.
    dump = str(Path(mysql).with_name('mysqldump.exe'))
    backup = runtime / ('medication-before-' + time.strftime('%Y%m%d-%H%M%S') + '.sql')
    r = subprocess.run([dump, '--host=127.0.0.1', '--port=' + setting('DB_PORT'), '--user=' + setting('DB_USER'), '--default-character-set=utf8mb4',
                        '--single-transaction', '--skip-lock-tables', db_name, 'medication_log'], capture_output=True, env=db_env,
                       creationflags=subprocess.CREATE_NO_WINDOW, timeout=30)
    if r.returncode:
        raise SystemExit('本机用药表备份未完成，未执行迁移或停止服务。')
    backup.write_bytes(r.stdout)
    sql((root / 'sql/upgrade-medication-2026-10-25.sql').read_text(encoding='utf-8'))
    migrated = True
new_command = list(command)
new_command[new_command.index('-jar') + 1] = str(release)
new_command = [c for c in new_command if not c.startswith('--logging.level.com.glucose=')]
new_command.append('--logging.level.com.glucose=INFO')
def launch(cmd, label):
    # Suppress output that could contain provider configuration; logs stay private to this local workspace.
    log = open(runtime / (label + '.log'), 'ab', buffering=0)
    try:
        return subprocess.Popen(cmd, cwd=backend, env=settings, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                creationflags=subprocess.CREATE_NO_WINDOW | subprocess.DETACHED_PROCESS)
    finally:
        log.close()
def ready(child):
    for _ in range(60):
        if child.poll() is not None:
            return False
        try:
            with urllib.request.urlopen('http://127.0.0.1:8083/api/common/captcha', timeout=2) as r:
                if json.load(r).get('code') == 0:
                    return True
        except Exception:
            pass
        time.sleep(.5)
    return False
process.terminate()
process.wait(timeout=15)
child = launch(new_command, 'service-' + digest[:16])
if not ready(child):
    child.terminate()
    child.wait(timeout=10)
    rollback = launch(command, 'rollback')
    ready(rollback)
    raise SystemExit('新版本未就绪，已尝试恢复原本地服务。')
report = {'activated': True, 'jarSha256': digest, 'pid': child.pid, 'port': port, 'localMigrationApplied': migrated,
          'providerSettingsPreservedInMemory': bool(settings.get('LLM_API_KEY')), 'productionAccessed': False}
(runtime / 'activation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
