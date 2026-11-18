#!/data/data/com.termux/files/usr/bin/bash
# Nova: sets up Termux + Ubuntu + OpenCode. Run by setup-termux.ps1 through adb (run-as com.termux).
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH=$PREFIX/bin TMPDIR=$PREFIX/tmp LANG=en_US.UTF-8 TERM=xterm-256color
for f in libtermux-exec-ld-preload.so libtermux-exec.so; do
  [ -f "$PREFIX/lib/$f" ] && export LD_PRELOAD="$PREFIX/lib/$f" && break
done
cd "$HOME" || exit 1
set -e
APT="-y -o Dpkg::Options::=--force-confnew -o Dpkg::Options::=--force-confdef"

echo "== 1/5  Termux settings (let Nova send commands)"
mkdir -p ~/.termux
grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo "allow-external-apps = true" >> ~/.termux/termux.properties

echo "== 2/5  Updating Termux packages"
apt-get update
apt-get $APT upgrade
apt-get $APT install proot-distro

echo "== 3/5  Installing Ubuntu (a few minutes)"
ROOTFS=$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu
[ -d "$ROOTFS" ] || proot-distro install ubuntu

echo "== 4/5  Installing OpenCode inside Ubuntu"
proot-distro login ubuntu -- bash -c '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update && apt-get install -y curl git ca-certificates
  command -v opencode >/dev/null || [ -x "$HOME/.opencode/bin/opencode" ] || curl -fsSL https://opencode.ai/install | bash
  [ -x "$HOME/.opencode/bin/opencode" ] && ln -sf "$HOME/.opencode/bin/opencode" /usr/local/bin/opencode
  mkdir -p "$HOME/work" "$HOME/.config/opencode"
  opencode --version
'
CFG=$ROOTFS/root/.config/opencode/opencode.json
if [ ! -f "$CFG" ]; then
cat > "$CFG" <<'JSON'
{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "edit": "allow",
    "bash": { "*": "allow", "su *": "deny", "sudo *": "deny", "*magisk*": "deny" }
  }
}
JSON
fi

echo "== 5/5  Sign in to models (choose OpenCode for the free ones; run again later for DeepSeek)"
proot-distro login ubuntu -- opencode auth login || true
echo
echo "Models OpenCode can use:"
proot-distro login ubuntu -- opencode models 2>/dev/null | head -40 || true
echo
echo "DONE. Nova can now use the terminal and OpenCode."
