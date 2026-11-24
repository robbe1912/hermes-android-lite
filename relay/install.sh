#!/bin/sh
# Installs the push relay on the gateway server. Run it as root from the repository folder:
#
#   sudo sh relay/install.sh
#
# The relay runs as the user "hermes" and needs the Docker group for the fallback read.
#
# Before you start the relay, put the key of a Firebase service account at
# /etc/hermes-lite-push/service-account.json. Make the file root:hermes 640.
# Firebase console: Project settings > Service accounts > Generate new private key.
# The service account needs the role "Firebase Cloud Messaging API Admin".
#
#   install -o root -g hermes -m 640 <downloaded key>.json /etc/hermes-lite-push/service-account.json
#   systemctl enable --now hermes-lite-push
#
# To see what the relay does without Firebase, set HERMES_LITE_DRY_RUN=1 in
# /etc/hermes-lite-push/env and add "EnvironmentFile=-/etc/hermes-lite-push/env" to the unit.
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run it as root: sudo sh relay/install.sh" >&2
    exit 1
fi
if ! id hermes >/dev/null 2>&1; then
    echo "The user hermes is missing on this server." >&2
    exit 1
fi

here=$(dirname "$0")
install -d -o hermes -g hermes -m 750 /var/lib/hermes-lite-push
install -d -o root -g hermes -m 750 /etc/hermes-lite-push
install -d -o root -g root -m 755 /opt/hermes-lite-push
install -m 755 "$here/hermes_lite_relay.py" /opt/hermes-lite-push/hermes_lite_relay.py
install -m 644 "$here/hermes-lite-push.service" /etc/systemd/system/hermes-lite-push.service
systemctl daemon-reload

if systemctl is-active --quiet hermes-lite-push; then
    systemctl restart hermes-lite-push
    echo "The relay restarted with the new files."
else
    echo "The relay is installed. Put the service account key in /etc/hermes-lite-push/, then run:"
    echo "  systemctl enable --now hermes-lite-push"
fi
