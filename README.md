# ditherDroid

## GitHub Actions Release (APK)

Der Workflow `.github/workflows/release.yml` baut bei einem Tag `v*` ein **signiertes** Release-APK und lädt es als GitHub Release Asset hoch.

### Benötigte GitHub Secrets

Lege in **Repository → Settings → Secrets and variables → Actions** diese Secrets an:

- **`KEYSTORE_BASE64`**: Base64-kodierter Keystore (ohne Zeilenumbrüche)
- **`KEYSTORE_PASSWORD`**: Keystore-Passwort
- **`KEY_ALIAS`**: Key-Alias (case-sensitive), z.B. `release`
- **`KEY_PASSWORD`**: Key-Passwort (oft identisch mit `KEYSTORE_PASSWORD`)

### Was ist der Alias und woher kommt er?

Ein Keystore (`.jks`/`.keystore`) ist ein **Container**. Darin liegt mindestens ein **Key-Eintrag** (Private Key + Zertifikat), mit dem Android das Release signiert.

Der **Alias** ist der **Name dieses Key-Eintrags im Keystore** (nicht der Dateiname). Du legst ihn beim Erstellen fest:

- Mit `keytool`: über `-alias ...`
- In Android Studio: beim “Generate Signed Bundle / APK” im Feld **Key alias**

Wenn du z.B. den Keystore so erzeugst:

```bash
keytool -genkeypair -alias release ...
```

dann ist der Alias **`release`** und genau diesen Wert musst du als GitHub Secret **`KEY_ALIAS`** setzen.

### Wann muss ich `KEY_ALIAS` setzen?

**Bevor** du den Release auslöst (also bevor du den Tag `v*` pushst). Der Workflow prüft den Keystore und bricht ab, wenn der Alias nicht zum Keystore passt.

### Worauf muss ich achten?

- **Case-sensitive**: `release` ist nicht `Release`.
- **Keystore und Alias gehören zusammen**: Wenn du `KEYSTORE_BASE64` austauschst (anderer Keystore), kann der richtige Alias ein anderer sein.
- **Passwörter nicht verwechseln**:
  - `KEYSTORE_PASSWORD`: Passwort des Keystores (Container)
  - `KEY_PASSWORD`: Passwort des Keys (Eintrag im Keystore)
- **Mehrere Aliases möglich**: Ein Keystore kann mehrere Einträge enthalten. Dann musst du den richtigen wählen (den fürs Release-Signing).

### Keystore erstellen

```bash
keytool -genkeypair \
  -alias <KEY_ALIAS> \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore release.keystore \
  -storepass <KEYSTORE_PASSWORD> \
  -keypass <KEY_PASSWORD>
```

### Keystore Alias prüfen

```bash
keytool -list -keystore release.keystore
```

Der Wert hinter **`Alias name:`** muss exakt in `KEY_ALIAS` gesetzt werden.

### Keystore als Base64 kodieren

Wichtig: **ohne** Zeilenumbrüche (damit GitHub Secrets/CI korrekt decodieren kann).

```bash
base64 -i release.keystore | tr -d '\n' | pbcopy
```

### Release auslösen

```bash
git tag v1.0.1
git push origin v1.0.1
```

### Troubleshooting

- **`KEY_ALIAS does not match this keystore`**: `KEY_ALIAS` ist falsch (oder zeigt auf einen anderen Keystore als `KEYSTORE_BASE64`).
  - Lokal prüfen: `keytool -list -keystore release.keystore`
  - Den Wert hinter `Alias name:` 1:1 als Secret `KEY_ALIAS` setzen.
- **Keystore lässt sich nicht öffnen / “Could not open keystore”**: meist `KEYSTORE_PASSWORD` falsch oder `KEYSTORE_BASE64` kaputt (z.B. durch Zeilenumbrüche).
  - Base64 neu erzeugen (ohne Umbrüche): `base64 -i release.keystore | tr -d '\n' | pbcopy`

