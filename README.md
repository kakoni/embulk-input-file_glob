# embulk-standards: Embulk's standard plugins

These are Embulk's "standard" plugins which are embedded in Embulk's executable binary distributions.

Their source code had been managed in the same [main repository of Embulk](https://github.com/embulk/embulk) until [`v0.10.33`](https://github.com/embulk/embulk/tree/v0.10.33). They have been split from the main repository since `v0.10.34`.

### Release

#### Prerequisite: Sonatype OSSRH

You need an account in [Sonatype OSSRH](https://central.sonatype.org/pages/ossrh-guide.html), and configure it in your `~/.gradle/gradle.properties`.

```
ossrhUsername=(your Sonatype OSSRH username)
ossrhPassword=(your Sonatype OSSRH password)
```

#### Prerequisite: PGP signatures

You need your [PGP signatures to release artifacts into Maven Central](https://central.sonatype.org/pages/working-with-pgp-signatures.html), and [configure Gradle to use your key to sign](https://docs.gradle.org/current/userguide/signing_plugin.html).

```
signing.keyId=(the last 8 symbols of your keyId)
signing.password=(the passphrase used to protect your private key)
signing.secretKeyRingFile=(the absolute path to the secret key ring file containing your private key)
```

#### Release

Modify `version` in `build.gradle` at a detached commit to bump up the versions of Embulk standard plugins.

```
git checkout --detach master
(Remove "-SNAPSHOT" in "version" in build.gradle.)
git add build.gradle
git commit -m "Release vX.Y.Z"
git tag -a vX.Y.Z
(Write the release note for vX.Y.Z in the tag annotation.)
./gradlew clean && ./gradlew release
git push -u origin vX.Y.Z
```
