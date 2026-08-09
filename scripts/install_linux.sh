#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/grxt-ws-proxy"
VENV="$APP_DIR/venv"
BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"

mkdir -p "$APP_DIR" "$BIN_DIR" "$DESKTOP_DIR"
python3 -m venv "$VENV"
"$VENV/bin/python" -m pip install --upgrade pip
"$VENV/bin/pip" install "git+https://github.com/Gr1xzz11/GRXT-WS-Proxy.git@main"

cat > "$BIN_DIR/grxt-ws-proxy" <<EOF
#!/usr/bin/env bash
exec "$VENV/bin/grxt-ws-proxy" "\$@"
EOF
chmod +x "$BIN_DIR/grxt-ws-proxy"

cat > "$DESKTOP_DIR/grxt-ws-proxy.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=GRXT WS Proxy
Comment=Telegram MTProto over WebSocket proxy
Exec=$BIN_DIR/grxt-ws-proxy
Terminal=false
Categories=Network;
StartupNotify=true
EOF

echo "Installed. Start GRXT WS Proxy from the applications menu or run: $BIN_DIR/grxt-ws-proxy"
