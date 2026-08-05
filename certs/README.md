# certs/

Public certificates the app must trust. Nothing secret lives here — a certificate is a
public key plus a signature. No private key belongs in this directory, ever.

## smtp-relay.pem

The internal Exchange relay (`mbox2.puregold.local`, the MX for `puregold.com.ph`)
presents a **self-signed** certificate, so no public CA vouches for it and the JVM
refuses the STARTTLS upgrade by default:

```
MessagingException: Could not convert socket to TLS
  caused by SSLHandshakeException: PKIX path building failed
```

Point `SMTP_TRUSTED_CERT` at this file and the relay must present **exactly** this
certificate — not one signed by it, not one merely valid for the name. See `SmtpTrust`
for why this is done in-process rather than with `-Djavax.net.ssl.trustStore`.

| | |
|---|---|
| Subject / Issuer | `CN=MBOX2` (self-signed) |
| SANs | `MBOX2`, `MBOX2.puregold.local` |
| Expires | **2031-05-31** |
| SHA-256 | `A7:2A:ED:55:E5:25:D7:AF:1A:3C:88:42:E1:E9:B0:61:1A:CE:0C:ED:1D:6F:04:B6:52:FE:B2:A3:20:48:09:B1` |

**Confirm that fingerprint with IT before trusting this file.** Reading it off the wire
is exactly what an attacker in the middle would want you to do; a value read back from
the Exchange box itself is what makes it a pin rather than a guess.

### Why `SMTP_HOST` need not appear in the SANs

The certificate names only `MBOX2.puregold.local`, yet `SMTP_HOST` is
`mbox2.puregold.com.ph` — the canonical name for the same box (`192.168.200.170`, and it
serves this identical certificate under both names).

That works because a pin is stronger than a name check, not weaker. Hostname
verification exists because a CA vouches for many hosts, so "signed by someone we trust"
alone would let any of them impersonate this one. Here exactly one certificate is
accepted, and only whoever holds its private key can complete a handshake — so an
attacker who hijacks DNS for `mbox2.puregold.com.ph` still cannot produce a session.
Identity is settled by the key, and the name it was reached under stops mattering.
`CA:FALSE` on this certificate is what keeps that true: it cannot issue others.

The honest trade: **rotation now breaks sends.** When IT reissues, the pin stops matching
and every notification fails until this file is replaced. It fails closed and says so —
`relay certificate does not match the pin in SMTP_TRUSTED_CERT` — rather than quietly
trusting whatever turned up. Re-export it, and re-confirm the new fingerprint:

```bash
openssl s_client -starttls smtp -connect mbox2.puregold.local:587 </dev/null 2>/dev/null | openssl x509 -outform PEM > certs/smtp-relay.pem
```
