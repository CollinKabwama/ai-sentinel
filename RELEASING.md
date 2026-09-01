# Releasing AI-Sentinel to Maven Central

This project publishes to the **Sonatype Central Portal** (not legacy OSSRH) using the `release` Maven profile and `org.sonatype.central:central-publishing-maven-plugin`.

**Published artifacts**

| Artifact | Published? |
|----------|------------|
| `dev.aisentinel:ai-sentinel` (parent POM) | Yes |
| `dev.aisentinel:ai-sentinel-core` | Yes |
| `dev.aisentinel:ai-sentinel-spring-boot-starter` | Yes |
| `ai-sentinel-trainer` | No |
| `ai-sentinel-demo` | No |

Consumers depend on the starter only:

```xml
<dependency>
  <groupId>dev.aisentinel</groupId>
  <artifactId>ai-sentinel-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

---

## Prerequisites (one-time)

### 1. Namespace

1. Own and verify namespace **`dev.aisentinel`** at [central.sonatype.com](https://central.sonatype.com) (DNS for `aisentinel.dev`).
2. Generate a **user token** under Account → Generate User Token.

### 2. `~/.m2/settings.xml`

Server id must be **`central`** (matches the plugin configuration):

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.CENTRAL_USERNAME}</username>
      <password>${env.CENTRAL_PASSWORD}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.passphrase>${env.GPG_PASSPHRASE}</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
</settings>
```

Do **not** hardcode tokens or passphrases in the POM or in git.

### 3. GPG signing key

1. Create a key if you do not have one (`gpg --full-generate-key`).
2. Note the key id: `gpg --list-secret-keys --keyid-format LONG`
3. Upload the **public** key to a Sonatype-supported keyserver (private key stays local):

```bash
# Prefer HTTPS when HKP is blocked
gpg --armor --export YOUR_KEY_ID > /tmp/pubkey.asc
curl -sS -X POST "https://keys.openpgp.org/vks/v1/upload" \
  -H "Content-Type: application/json" \
  --data-binary @<(python3 -c 'import json; print(json.dumps({"keytext": open("/tmp/pubkey.asc").read()}))')
curl -sS -X POST "https://keyserver.ubuntu.com/pks/add" \
  --data-urlencode "keytext@/tmp/pubkey.asc"
```

4. Confirm the key is resolvable (expect HTTP 200):

```bash
curl -sS -o /dev/null -w "%{http_code}\n" \
  "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0xYOUR_KEY_ID"
```

5. If using keys.openpgp.org, confirm the verification email so the identity is fully published.

Uploading the **public** key is required and safe. Never upload the private key or passphrase.

### 4. Environment variables

In the same shell session used for deploy:

```bash
export CENTRAL_USERNAME="…"   # portal token username
export CENTRAL_PASSWORD="…"   # portal token password
export GPG_PASSPHRASE='…'    # use ASCII quotes; avoid curly/smart quotes
```

---

## Release steps

Run all commands from the **repository root** (the directory that contains the parent `pom.xml`).

### 1. Ensure a clean, release-ready tree

```bash
git checkout main
git pull
mvn clean verify
mvn -Papi-compatibility -pl ai-sentinel-core,ai-sentinel-spring-boot-starter -am verify -DskipTests
```

Fix any test or build failures before continuing. Run `mvn clean verify` **twice**. Characterization
and architecture gates are included in that command — see [`docs/testing.md`](docs/testing.md).
The `api-compatibility` profile compares published library modules against
`aisentinel.api.compatibility.oldVersion` (default **0.2.0** until **0.3.0** ships) via japicmp. After **0.3.0**
is published, retarget that property to the new baseline and remove the intentional quarantine-method exclude.

Confirm MONITOR-first guidance is still accurate in [`docs/deployment.md`](docs/deployment.md) and
that [`CHANGELOG.md`](CHANGELOG.md) / [`docs/migration.md`](docs/migration.md) match the tag you are
about to cut. Do not publish release notes that claim production-ready ENFORCE from synthetic suites alone.

### 2. Set a non-SNAPSHOT version

Central **release** publishing rejects `-SNAPSHOT` versions. Update the parent and every module parent reference to the release version (for example bump `0.2.0` → `0.3.0`):

```bash
# Example with the versions plugin (adjust newVersion as needed):
# mvn versions:set -DnewVersion=0.3.0 -DgenerateBackupPoms=false
# Or edit the <version> in the parent pom.xml and each module's parent reference.
```

Confirm no `SNAPSHOT` remains:

```bash
grep -R "SNAPSHOT" --include='pom.xml' .
```

### 3. Deploy library modules only

```bash
mvn clean deploy -Prelease -pl ai-sentinel-core,ai-sentinel-spring-boot-starter -am
```

| Flag | Meaning |
|------|---------|
| `-Prelease` | Attach sources, javadoc, GPG signatures; use Central Portal plugin |
| `-pl …` | Only core + starter |
| `-am` | Also build the parent POM |

Trainer and demo are skipped via module properties and `excludeArtifacts` in the parent POM.

Default `mvn clean verify` (without `-Prelease`) packages module JARs but does **not** attach
sources/javadoc or publish. Sources/javadoc JARs are produced only under `-Prelease`.

### 4. Publish on the Central Portal (manual)

On success the build reports a **deployment id** and:

```text
Deployment … has been validated. To finish publishing visit
https://central.sonatype.com/publishing/deployments
```

1. Open [Deployments](https://central.sonatype.com/publishing/deployments).
2. Select the validated deployment.
3. Click **Publish**.

Artifacts usually appear on Maven Central within minutes; search indexing can take longer.

### 5. Tag and push

```bash
git tag -a v0.3.0 -m "Release 0.3.0"
git push origin v0.3.0
```

Commit the version bump on `main` if it is not already committed. Use the same version string you publish (**0.3.0** on `dev`; latest published Maven Central release before this tag is **0.2.0**).

### 6. Bump to the next development version (optional)

After the tag, set versions to the next development line (for example `0.3.1-SNAPSHOT` if you enable SNAPSHOT publishing, or simply `0.3.1`) so day-to-day work does not reuse a published release version.

---

## SNAPSHOT publishing (optional)

SNAPSHOT uploads go to `https://central.sonatype.com/repository/maven-snapshots/` and return **403** unless SNAPSHOTs are enabled for the namespace:

1. Central Portal → **Namespaces** → `dev.aisentinel` → ⋮ → **Enable SNAPSHOTs**.
2. Deploy with a `-SNAPSHOT` version and `-Prelease` as above.

For public releases, prefer a stable version (no `-SNAPSHOT`).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `403 Forbidden` on `maven-snapshots` | SNAPSHOT version without namespace SNAPSHOTs enabled | Use a release version, or enable SNAPSHOTs |
| `Could not find a public key by the key fingerprint` | Public key not on a supported keyserver | Upload public key; wait; redeploy |
| Deploy fails on `ai-sentinel-demo` | Full reactor deploy | Use `-pl ai-sentinel-core,ai-sentinel-spring-boot-starter -am` |
| GPG passphrase / pinentry errors | Env not set or smart quotes in export | Re-export `GPG_PASSPHRASE` with ASCII quotes |
| Auth failures against Central | Wrong server id or token | Ensure server id is `central`; regenerate portal token |

Inspect a failed deployment via the Portal UI, or:

```bash
TOKEN=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')
curl -sS --request POST \
  --header "Authorization: Bearer $TOKEN" \
  "https://central.sonatype.com/api/v1/publisher/status?id=DEPLOYMENT_ID"
```

---

## Related docs

- [`CHANGELOG.md`](CHANGELOG.md) — user-facing release notes
- [`docs/README.md`](docs/README.md) — docs layout and operator reading order
- [`docs/migration.md`](docs/migration.md) — upgrade from the previous published line
- [`docs/testing.md`](docs/testing.md) — characterization and release gates
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — runtime design
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — branching and local builds
- [`SECURITY.md`](SECURITY.md) — vulnerability reporting
- [Central Portal Maven publishing](https://central.sonatype.org/publish/publish-portal-maven/)
- [Central GPG requirements](https://central.sonatype.org/publish/requirements/gpg/)
