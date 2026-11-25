"""Tests for the push relay. Run them with: python3 -m unittest relay/test_relay.py

The signature test needs the openssl program. Without it, the test is skipped.
"""

import base64
import os
import shutil
import subprocess
import tempfile
import unittest

from unittest.mock import patch

from hermes_lite_relay import Firebase, Relay, ping, sign_rs256


def job(job_id, execution_id, finished, status="completed"):
    execution = {"id": execution_id, "status": status}
    if finished:
        execution["finished_at"] = "2026-11-24T11:07:43+00:00"
    return {"id": job_id, "name": "job " + job_id, "latest_execution": execution}


class RelayTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.folder, ignore_errors=True)
        self.state = os.path.join(self.folder, "state.json")

    def test_each_finish_causes_one_ping(self):
        relay = Relay(self.state)

        # The first read arms the relay. It sends nothing for old runs.
        self.assertEqual(relay.step([job("a", "1", True)]), [])
        self.assertEqual(relay.step([job("a", "1", True)]), [])
        self.assertEqual(len(relay.step([job("a", "2", True, "failed")])), 1)
        self.assertEqual(relay.step([job("a", "2", True, "failed")]), [])

    def test_a_running_job_never_pings(self):
        relay = Relay(self.state)

        self.assertEqual(relay.step([job("a", "9", False), job("b", "5", False)]), [])
        self.assertEqual(relay.step([job("a", "9", False)]), [])
        self.assertEqual(len(relay.step([job("a", "9", True)])), 1)

    def test_the_state_survives_a_restart(self):
        first = Relay(self.state)
        first.step([job("a", "1", True)])
        first.save()

        again = Relay(self.state)
        self.assertTrue(again.armed)
        self.assertEqual(again.step([job("a", "1", True)]), [])


class FcmTargetProjectTests(unittest.TestCase):
    def test_sender_project_can_target_a_device_firebase_project(self):
        with tempfile.TemporaryDirectory() as folder:
            service_account_path = os.path.join(folder, "service-account.json")
            with open(service_account_path, "w", encoding="utf-8") as handle:
                handle.write(
                    '{"project_id":"citric-expanse-408122",'
                    '"client_email":"sender@example.iam.gserviceaccount.com",'
                    '"private' + '_key":"unused"}'
                )
            firebase = Firebase(service_account_path)
            firebase.access_token = lambda: "access-token"

            class Response:
                status = 200

                def __enter__(self):
                    return self

                def __exit__(self, *_):
                    return False

            with patch("hermes_lite_relay.urllib.request.urlopen", return_value=Response()) as urlopen:
                ping(
                    firebase,
                    [{"token": "device-token", "project_id": "hermes-push-94038", "device": "a52"}],
                    {"id": "cron", "name": "cron"},
                    "completed",
                    None,
                )

        request = urlopen.call_args.args[0]
        self.assertEqual(
            request.full_url,
            "https://fcm.googleapis.com/v1/projects/hermes-push-94038/messages:send",
        )


@unittest.skipUnless(shutil.which("openssl"), "openssl is not installed")
class SignatureTests(unittest.TestCase):
    def test_openssl_accepts_the_signature(self):
        folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        key = os.path.join(folder, "key.pem")
        public = os.path.join(folder, "public.pem")
        message = os.path.join(folder, "message")
        signature = os.path.join(folder, "signature")

        # genpkey writes the key in the PKCS#8 form that Firebase also uses.
        pem = subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
            check=True, capture_output=True,
        ).stdout.decode()
        with open(key, "w", encoding="utf-8") as handle:
            handle.write(pem)
        subprocess.run(["openssl", "pkey", "-in", key, "-pubout", "-out", public], check=True, capture_output=True)

        signed = sign_rs256(pem, b"the header and the claims")
        with open(message, "wb") as handle:
            handle.write(b"the header and the claims")
        with open(signature, "wb") as handle:
            handle.write(base64.urlsafe_b64decode(signed + b"=" * (-len(signed) % 4)))

        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", public, "-signature", signature, message],
            capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
