# Unstable Launcher keystore setup

This project expects a local release keystore at:

`ZalithLauncher/unstable-launcher.jks`

and the environment variable:

`UNSTABLE_KEYSTORE_PASSWORD`

## Generate a local keystore

Run from repository root:

```bash
keytool -genkeypair -v \
  -keystore ZalithLauncher/unstable-launcher.jks \
  -storepass "<your-password>" \
  -keypass "<your-password>" \
  -alias unstable \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Unstable Launcher, OU=Dev, O=Unstable, L=NA, ST=NA, C=US"
```

Then export:

```bash
export UNSTABLE_KEYSTORE_PASSWORD="<your-password>"
```

The `.jks` file is intentionally gitignored and must not be committed.
