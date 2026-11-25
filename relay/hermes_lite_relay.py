#!/usr/bin/env python3
"""Sends a wake ping to the Hermes Lite app when a cron run ends.

The relay reads the cron jobs of the gateway every few seconds. When the latest execution
of a job has a finish time, the relay sends one data message through Firebase Cloud
Messaging (HTTP v1) to each device that the app registered. The message carries no
content of the run. It names the job and, when there is one, the thread of the run.

The gateway changes nothing. The relay reads the dashboard token and the device files
below the gateway home. When its user cannot read them, it reads them with
``docker exec hermes cat``.

Settings come from the environment with the prefix ``HERMES_LITE_``:

=====================  =============================================  =========================
Name                   Default                                        Meaning
=====================  =============================================  =========================
GATEWAY_URL            http://127.0.0.1:9119                          The REST API of the gateway.
TOKEN_FILE             /opt/hermes/home/dashboard.token               The dashboard token.
DEVICES_DIR            /opt/hermes/home/hermes-lite-push              The device files of the app.
SERVICE_ACCOUNT        /etc/hermes-lite-push/service-account.json     The key for Firebase.
STATE_FILE             /var/lib/hermes-lite-push/state.json          The last ping of each job.
POLL_SECONDS           5                                              The time between two reads.
DRY_RUN                0                                              1 logs each message. It sends nothing.
=====================  =============================================  =========================
"""

import base64
import hashlib
import json
import logging
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

LOG = logging.getLogger("hermes-lite-push")

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
TOKEN_URL = "https://oauth2.googleapis.com/token"

# The DigestInfo prefix of SHA-256, without the hash (RFC 8017, section 3.3).
SHA256_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")

DEFAULTS = {
    "GATEWAY_URL": "http://127.0.0.1:9119",
    "TOKEN_FILE": "/opt/hermes/home/dashboard.token",
    "DEVICES_DIR": "/opt/hermes/home/hermes-lite-push",
    "SERVICE_ACCOUNT": "/etc/hermes-lite-push/service-account.json",
    "STATE_FILE": "/var/lib/hermes-lite-push/state.json",
    "POLL_SECONDS": "5",
    "DRY_RUN": "0",
}

GATEWAY_HOME = "/opt/hermes/home"
CONTAINER_HOME = "/opt/data"
CONTAINER = "hermes"


def setting(name):
    return os.environ.get("HERMES_LITE_" + name, DEFAULTS[name])


def read_gateway_file(path):
    """Reads a file below the gateway home. The fallback reads it inside the container."""
    try:
        with open(path, "rb") as handle:
            return handle.read()
    except PermissionError:
        inside = CONTAINER_HOME + "/" + os.path.relpath(path, GATEWAY_HOME)
        return subprocess.run(
            ["docker", "exec", CONTAINER, "cat", inside], check=True, capture_output=True
        ).stdout


def list_gateway_dir(path):
    """Lists the names in a folder below the gateway home. The fallback lists it inside the container."""
    try:
        return os.listdir(path)
    except PermissionError:
        inside = CONTAINER_HOME + "/" + os.path.relpath(path, GATEWAY_HOME)
        listing = subprocess.run(
            ["docker", "exec", CONTAINER, "ls", "-1", inside], check=True, capture_output=True
        ).stdout
        return listing.decode("utf-8").split()


# ---- Firebase ----


def _b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def _der_value(data, pos):
    """Reads one DER element at pos. Returns its content bytes and the position after it."""
    pos += 1  # the tag
    length = data[pos]
    pos += 1
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[pos:pos + count], "big")
        pos += count
    return data[pos:pos + length], pos + length


def _rsa_numbers(pkcs8_pem):
    """Reads the modulus and the private exponent from a PKCS#8 PEM key."""
    body = "".join(line for line in pkcs8_pem.strip().splitlines() if not line.startswith("-----"))
    outer, _ = _der_value(base64.b64decode(body), 0)
    _, pos = _der_value(outer, 0)  # INTEGER 0
    _, pos = _der_value(outer, pos)  # SEQUENCE: the algorithm
    key, _ = _der_value(outer, pos)  # OCTET STRING
    key, _ = _der_value(key, 0)  # SEQUENCE: RSAPrivateKey
    numbers = []
    pos = 0
    while pos < len(key) and len(numbers) < 4:
        value, pos = _der_value(key, pos)
        numbers.append(int.from_bytes(value, "big"))
    # The fields are version, modulus, public exponent, private exponent.
    return numbers[1], numbers[3]


def sign_rs256(pkcs8_pem, message):
    """Signs with RSASSA-PKCS1-v1_5 and SHA-256.

    The math is plain Python. So the relay needs no package on the server.
    """
    modulus, exponent = _rsa_numbers(pkcs8_pem)
    size = modulus.bit_length() // 8
    digest = hashlib.sha256(message).digest()
    padding = b"\xff" * (size - len(SHA256_PREFIX) - len(digest) - 3)
    block = b"\x00\x01" + padding + b"\x00" + SHA256_PREFIX + digest
    signature = pow(int.from_bytes(block, "big"), exponent, modulus)
    return _b64url(signature.to_bytes(size, "big"))


class Firebase:
    """Makes access tokens for the service account and sends messages with them."""

    def __init__(self, service_account_path):
        with open(service_account_path, encoding="utf-8") as handle:
            account = json.load(handle)
        self._email = account["client_email"]
        self._key = account["private_key"]
        self._token = ""
        self._token_until = 0

    def access_token(self):
        if self._token and time.time() < self._token_until - 60:
            return self._token
        now = int(time.time())
        header = _b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = _b64url(json.dumps({
            "iss": self._email,
            "scope": FCM_SCOPE,
            "aud": TOKEN_URL,
            "iat": now,
            "exp": now + 3600,
        }).encode())
        assertion = header + b"." + claims + b"." + sign_rs256(self._key, header + b"." + claims)
        body = urllib.parse.urlencode({
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion.decode(),
        }).encode()
        request = urllib.request.Request(
            TOKEN_URL, data=body, headers={"Content-Type": "application/x-www-form-urlencoded"}
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            answer = json.loads(response.read())
        self._token = answer["access_token"]
        self._token_until = now + int(answer.get("expires_in", 3600))
        return self._token

    def send(self, device_token, data, target_project_id):
        """Sends one data message. Returns the HTTP status of Firebase."""
        message = {"message": {"token": device_token, "data": data, "android": {"priority": "HIGH"}}}
        request = urllib.request.Request(
            "https://fcm.googleapis.com/v1/projects/%s/messages:send" % target_project_id,
            data=json.dumps(message).encode(),
            method="POST",
            headers={
                "Authorization": "Bearer " + self.access_token(),
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status
        except urllib.error.HTTPError as error:
            LOG.warning("Firebase answered %s: %s", error.code, error.read().decode("utf-8", "replace")[:200])
            return error.code


# ---- The relay ----


def gateway_json(url, token):
    """Calls the REST API of the gateway with the dashboard token."""
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def finished(job):
    """The id and the status of the latest execution of the job, when it has a finish time."""
    execution = job.get("latest_execution") or {}
    if not execution.get("finished_at") or not execution.get("id"):
        return None
    return execution["id"], execution.get("status") or "completed"


def devices():
    """The devices that the app registered at the gateway."""
    folder = setting("DEVICES_DIR")
    found = []
    for name in list_gateway_dir(folder):
        if not name.endswith(".json"):
            continue
        try:
            device = json.loads(read_gateway_file(os.path.join(folder, name)))
        except (ValueError, OSError, subprocess.CalledProcessError) as error:
            LOG.warning("The device file %s is not readable: %s", name, error)
            continue
        if device.get("token") and device.get("project_id"):
            found.append(device)
    return found


class Relay:
    """Watches the jobs. Each finished execution causes one wake ping."""

    def __init__(self, state_path):
        self.state_path = state_path
        self.notified = {}
        self.armed = False
        try:
            with open(state_path, encoding="utf-8") as handle:
                stored = json.load(handle)
            self.notified = stored.get("notified", {})
            self.armed = stored.get("armed", False)
        except (OSError, ValueError):
            # No state yet. The first read arms the relay.
            pass

    def save(self):
        temporary = self.state_path + ".new"
        with open(temporary, "w", encoding="utf-8") as handle:
            json.dump({"notified": self.notified, "armed": self.armed}, handle)
        os.replace(temporary, self.state_path)

    def step(self, jobs):
        """Reads the jobs. Returns the jobs with a finish that the relay did not report yet."""
        fresh = []
        for job in jobs:
            done = finished(job)
            if done is None:
                continue
            execution_id, status = done
            if self.armed and self.notified.get(job["id"]) != execution_id:
                fresh.append((job, status))
            self.notified[job["id"]] = execution_id
        self.armed = True
        return fresh


def run_session(base, token, job_id, execution_id):
    """The stored id of the thread of one execution, or None for a run without a thread."""
    try:
        runs = gateway_json("%s/api/cron/jobs/%s/runs?limit=5" % (base, job_id), token)
    except (OSError, ValueError, urllib.error.URLError) as error:
        LOG.warning("The runs of job %s are not readable: %s", job_id, error)
        return None
    for run in runs.get("runs", []):
        if run.get("cron_execution_id") == execution_id and run.get("id"):
            return run["id"]
    return None


def ping(firebase, targets, job, status, session_id):
    """Sends the wake ping for one job to each device of the Firebase project."""
    data = {"job_id": job["id"], "name": job.get("name") or job["id"], "status": status}
    if session_id:
        data["session_id"] = session_id
    for device in targets:
        code = firebase.send(device["token"], data, device["project_id"])
        if code == 200:
            LOG.info("Sent the wake ping for job %s to %s.", job["id"], device.get("device"))
        else:
            LOG.warning("The wake ping for job %s did not reach %s.", job["id"], device.get("device"))


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    base = setting("GATEWAY_URL").rstrip("/")
    poll = max(1, int(setting("POLL_SECONDS")))
    dry_run = setting("DRY_RUN") == "1"
    relay = Relay(setting("STATE_FILE"))
    firebase = None if dry_run else Firebase(setting("SERVICE_ACCOUNT"))
    if dry_run:
        LOG.info("Dry run: the relay logs each wake ping. It sends nothing.")
    LOG.info("The relay reads %s every %s seconds.", base, poll)
    while True:
        try:
            token = read_gateway_file(setting("TOKEN_FILE")).decode("utf-8").strip()
            fresh = relay.step(gateway_json(base + "/api/cron/jobs?profile=all", token))
        except (OSError, ValueError, urllib.error.URLError, subprocess.CalledProcessError) as error:
            LOG.warning("The gateway is not readable now: %s", error)
            time.sleep(poll)
            continue
        for job, status in fresh:
            name = job.get("name") or job["id"]
            session_id = run_session(base, token, job["id"], relay.notified[job["id"]])
            if dry_run:
                LOG.info("Dry run: job %s ended with %s. Thread: %s", name, status, session_id)
                continue
            ping(firebase, devices(), job, status, session_id)
        relay.save()
        time.sleep(poll)


if __name__ == "__main__":
    sys.exit(main())
