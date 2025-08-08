## TickLoom: Signing and Publishing Guide

This guide explains how to create a PGP key, configure Gradle signing, build a Central Portal bundle, and publish to Maven Central.

### 1) Install prerequisites (macOS)

```bash
brew install gnupg pinentry-mac
```

### 2) Create your PGP signing key

```bash
gpg --full-generate-key
# Choose: (1) RSA and RSA, 4096 bits, set expiry if desired, enter your name/email, and a strong passphrase

# Find your (LONG) key ID
gpg --list-secret-keys --keyid-format LONG
```

Export keys (optional backups):

```bash
# Private key (ASCII armored) – keep secret
gpg --armor --export-secret-keys <LONG_KEY_ID> > ~/private-key.asc

# Public key
gpg --armor --export <LONG_KEY_ID> > ~/public-key.asc

# Revocation certificate
gpg --output ~/revoke.asc --gen-revoke <LONG_KEY_ID>
```

Publish your public key so Central can verify signatures:

```bash
gpg --keyserver hkps://keys.openpgp.org --send-keys <LONG_KEY_ID>
# Check your inbox and verify the email with keys.openpgp.org

# Optional: mirror to another keyserver (helps propagation)
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <LONG_KEY_ID>
```

### 3) Configure credentials and signing for Gradle

Put secrets in your user Gradle properties file (preferred):

`/Users/unmeshjoshi/.gradle/gradle.properties`

Minimal OSSRH credentials (for legacy s01 staging from Gradle):

```
ossrhUsername=YOUR_SONATYPE_USER_TOKEN_USERNAME
ossrhPassword=YOUR_SONATYPE_USER_TOKEN_PASSWORD
```

Signing options (pick one method):

- In-memory key (recommended):
```
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK-----
... your ASCII-armored private key ...
-----END PGP PRIVATE KEY BLOCK-----
signingPassword=YOUR_KEY_PASSPHRASE
```

- In-memory key via file (avoids newline escaping):
```
signingKeyFile=/absolute/path/to/private-key.asc
signingPassword=YOUR_KEY_PASSPHRASE
```

- Classic keyring file:
```
signing.keyId=ABCD1234EF567890
signing.password=YOUR_KEY_PASSPHRASE
signing.secretKeyRingFile=/absolute/path/to/secring.gpg
```

Environment variable equivalents (useful in CI):

```
export OSSRH_USERNAME=...
export OSSRH_PASSWORD=...

export OSSRH_GPG_SECRET_KEY="$(cat /absolute/path/private-key.asc)"
export OSSRH_GPG_SECRET_KEY_FILE=/absolute/path/private-key.asc
export OSSRH_GPG_SECRET_KEY_PASSWORD=YOUR_KEY_PASSPHRASE
```

Notes:
- For the new Central Portal (bundle upload), you will use a Central “Publishing Token” in the web UI; the OSSRH user token is specifically for the legacy `s01.oss.sonatype.org` Gradle deploy endpoint.

### 4) Sign artifacts and build the Central bundle

Generate signatures and the bundle zip:

```bash
./gradlew signMavenJavaPublication prepareCentralBundle createCentralBundle -x test
```

Outputs:
- Staged files: `build/central-bundle/io/github/unmeshjoshi/tickloom/0.1.0-alpha.1/`
  - `tickloom-0.1.0-alpha.1.pom` and `.pom.asc` and `.md5` and `.sha1`
  - `tickloom-0.1.0-alpha.1.jar` and `.jar.asc` and `.md5` and `.sha1`
  - `tickloom-0.1.0-alpha.1-sources.jar` and `.asc/.md5/.sha1`
  - `tickloom-0.1.0-alpha.1-javadoc.jar` and `.asc/.md5/.sha1`
- Bundle zip: `build/distributions/tickloom-0.1.0-alpha.1-bundle-0.1.0-alpha.1.zip`

Local verification of signatures (optional):

```bash
gpg --verify build/central-bundle/.../tickloom-0.1.0-alpha.1.jar.asc \
    build/central-bundle/.../tickloom-0.1.0-alpha.1.jar
```

### 5) Publish

Option A — Central Portal bundle upload (recommended):
- Upload the bundle zip at [Central Portal Deployments](https://central.sonatype.com/publishing/deployments)
- Use your Central “Publishing Token” (generated in the portal) if using the API.

Option B — Legacy OSSRH staging from Gradle (s01):
```bash
./gradlew publish -x test
```
- Requires `ossrhUsername/ossrhPassword` to be the Sonatype “User Token” (not your login password).
- Then close and release the staging repository in `https://s01.oss.sonatype.org/`.

### 6) Troubleshooting

- 401 “Content access is protected by token” on s01:
  - Use Sonatype User Token, not your login password.
  - Set in `~/.gradle/gradle.properties`:
    ```
    ossrhUsername=USER_TOKEN_USERNAME
    ossrhPassword=USER_TOKEN_PASSWORD
    ```

- Missing signature (.asc) in bundle:
  - Ensure signing is configured (see section 3) and run:
    ```
    ./gradlew signMavenJavaPublication prepareCentralBundle createCentralBundle -x test
    ```

- Invalid signature in Central Portal:
  - Publish your public key and verify the email with keys.openpgp.org (see section 2).
  - Ensure Gradle used the same key ID you published.
  - Do not manually re-sign files; use the Gradle signing task so signatures match artifacts.

- Javadoc errors blocking build:
  - The build disables strict doclint. For malformed HTML in Javadoc, escape comparison operators using `{@code ...}`.

- Checksums missing in bundle:
  - The bundle task generates `.md5` and `.sha1` for POM/JARs automatically during `prepareCentralBundle`.

### 7) Quick command reference

```bash
# Build local artifacts
./gradlew assemble javadocJar sourcesJar -x test

# Sign and bundle for Central Portal upload
./gradlew signMavenJavaPublication prepareCentralBundle createCentralBundle -x test

# Publish to local Maven repo (for quick testing)
./gradlew publishToMavenLocal -x test

# Publish to OSSRH (legacy s01 staging)
./gradlew publish -x test
```


