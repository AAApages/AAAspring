#!/usr/bin/env python3
"""Make a VAPID key pair for Web Push (phone notifications for direct messages).

Run once per deployment and paste the three lines it prints into spring/.env.
Browsers remember the PUBLIC key when they register, so changing the pair later
means every device has to turn notifications on again.

    python3 scripts/push_vapid_keys.py
"""
import base64
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:  # pragma: no cover
    sys.exit("needs the 'cryptography' package: pip install cryptography")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


private = ec.generate_private_key(ec.SECP256R1())
public_raw = private.public_key().public_bytes(
    serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
private_raw = private.private_numbers().private_value.to_bytes(32, "big")

print("# Web Push (paste into spring/.env; keep the private key secret)")
print(f"PUSH_VAPID_PUBLIC_KEY={b64url(public_raw)}")
print(f"PUSH_VAPID_PRIVATE_KEY={b64url(private_raw)}")
print("PUSH_VAPID_SUBJECT=mailto:admin@opencodingsociety.com")
