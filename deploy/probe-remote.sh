#!/usr/bin/env bash
# 远端环境探测（只读，不改动任何东西）
echo "===== host ====="
uname -a
head -3 /etc/os-release
echo "user: $(id -un) $(id -u)"
echo
echo "===== resources ====="
echo "cpus: $(nproc)"
free -h | head -2
df -h / | tail -1
echo
echo "===== docker ====="
docker version --format 'client={{.Client.Version}} server={{.Server.Version}}' 2>&1
docker compose version 2>&1 | head -1
docker info --format '{{json .RegistryConfig.Mirrors}}' 2>&1
echo "existing images:"
docker images --format '  {{.Repository}}:{{.Tag}} ({{.Size}})' 2>&1 | head -20
echo
echo "===== network reachability ====="
for u in \
  https://registry.npmjs.org/ \
  https://binaries.prisma.sh/ \
  https://github.com/ \
  https://objects.githubusercontent.com/ \
  https://registry-1.docker.io/v2/ \
  https://docker.1panel.live/v2/ \
  https://hub.1panel.dev/v2/ \
  https://docker.1ms.run/v2/ \
  https://hf-mirror.com/ ; do
  code=$(curl -sS -m 12 -o /dev/null -w '%{http_code}' "$u" 2>/dev/null)
  rc=$?
  printf '  %-45s http=%s curl_rc=%s\n' "$u" "$code" "$rc"
done
echo
echo "===== docker pull smoke test ====="
timeout 180 docker pull hello-world:latest 2>&1 | tail -4
echo
echo "===== ports in use (host) ====="
ss -lnt 2>/dev/null | awk 'NR==1 || /:(4311|5432|8080|80|443)\s/'
echo
echo "===== sudo ====="
if sudo -n true 2>/dev/null; then echo "SUDO-NOPASS"; else echo "SUDO-NEEDS-PASS"; fi
