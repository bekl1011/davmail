# DavMail auf Raspberry Pi OS Lite

Der Raspberry Pi 1 verwendet eine ARMv6-CPU. Die offiziellen Docker-Pakete unterstuetzen ARMv6 nicht, deshalb laeuft DavMail auf diesem Modell direkt als `systemd`-Dienst. `compose.yml` verwendet dieselben externen Secret-Dateien, ist aber fuer einen spaeteren amd64-, ARM64- oder unterstuetzten ARMv7-Host gedacht.

## Dateien

Auf dem Pi liegen anschliessend:

```text
/opt/davmail/davmail                 Startskript
/opt/davmail/davmail.jar             DavMail
/opt/davmail/lib/*.jar                Laufzeitbibliotheken
/etc/davmail/davmail.properties      Konfiguration
/etc/davmail/secrets/client.p12      Identitaet Pi -> Exchange
/etc/davmail/secrets/client-p12-password
/etc/davmail/secrets/server.p12      Identitaet Endgeraet -> Pi
/etc/davmail/secrets/server-p12-password
/etc/systemd/system/davmail.service
/var/lib/davmail/                    Arbeitsverzeichnis
/var/log/davmail/davmail.log
```

`client.p12` und `server.p12` haben verschiedene Aufgaben. Das erste wird aus dem macOS-Schluesselbund exportiert und bei Exchange verwendet. Das zweite wird auf dem Pi erzeugt und verschluesselt CalDAV, IMAP und SMTP im Heimnetz.

## 1. Paket auf dem Mac bauen

Im Wurzelverzeichnis dieses Repositories:

```sh
ant clean dist-platform
```

Das Ergebnis ist `dist/davmail-7.0.0-trunk.zip`. Der neue Target baut nur das plattformunabhaengige Archiv und laedt keine fremden Komplett-JREs herunter.

In der Schluesselbundverwaltung unter **Meine Zertifikate** die persoenliche Identitaet mit dem darunterliegenden privaten Schluessel markieren und als `client.p12` exportieren. Dabei ein starkes Exportpasswort setzen. Die P12-Datei und ihr Passwort niemals in Git ablegen.

Die Dateien auf den Pi kopieren:

```sh
scp dist/davmail-7.0.0-trunk.zip \
  src/raspberrypi/davmail.service \
  src/raspberrypi/davmail.properties.example \
  client.p12 \
  pi@davmailpi.local:/tmp/
```

## 2. Java und DavMail auf dem Pi installieren

```sh
sudo apt update
sudo apt install default-jre-headless unzip ufw
java -version
```

DavMail benoetigt Java 11 oder neuer. Dann:

```sh
sudo useradd --system --home-dir /var/lib/davmail --shell /usr/sbin/nologin davmail
sudo install -d -o root -g root -m 0755 /opt/davmail
sudo unzip -o /tmp/davmail-7.0.0-trunk.zip -d /opt/davmail
sudo chown -R root:root /opt/davmail
sudo chmod 0755 /opt/davmail/davmail

sudo install -d -o davmail -g davmail -m 0750 /var/lib/davmail /var/log/davmail
sudo install -d -o root -g davmail -m 0750 /etc/davmail /etc/davmail/secrets
sudo install -o root -g davmail -m 0640 /tmp/davmail.properties.example /etc/davmail/davmail.properties
sudo install -o root -g davmail -m 0640 /tmp/client.p12 /etc/davmail/secrets/client.p12
sudo install -o root -g root -m 0644 /tmp/davmail.service /etc/systemd/system/davmail.service
```

Das Exportpasswort sicher und ohne Shell-History in eine Datei schreiben:

```sh
sudo bash -c 'umask 027; read -rsp "Passwort fuer client.p12: " pw; echo; printf %s "$pw" > /etc/davmail/secrets/client-p12-password; chown root:davmail /etc/davmail/secrets/client-p12-password'
```

Nun `/etc/davmail/davmail.properties` bearbeiten und insbesondere `davmail.mode`, `davmail.authentication` und `davmail.url` aus der funktionierenden Mac-Konfiguration uebernehmen:

```sh
sudoedit /etc/davmail/davmail.properties
```

## 3. TLS-Zertifikat fuer die Endgeraete erzeugen

Dem Pi im Router zuerst eine feste DHCP-Zuordnung geben. Im folgenden Beispiel sind Hostname und IP anzupassen:

```sh
sudo keytool -genkeypair \
  -alias davmail \
  -keyalg RSA \
  -keysize 3072 \
  -validity 825 \
  -storetype PKCS12 \
  -keystore /etc/davmail/secrets/server.p12 \
  -dname "CN=davmailpi.local" \
  -ext "SAN=dns:davmailpi.local,ip:192.168.178.50"

sudo chown root:davmail /etc/davmail/secrets/server.p12
sudo chmod 0640 /etc/davmail/secrets/server.p12
sudo bash -c 'umask 027; read -rsp "Passwort fuer server.p12: " pw; echo; printf %s "$pw" > /etc/davmail/secrets/server-p12-password; chown root:davmail /etc/davmail/secrets/server-p12-password'
```

In die Passwortdatei muss dasselbe Passwort, das `keytool` fuer den PKCS#12-Keystore erhalten hat. Den oeffentlichen Teil fuer die Endgeraete exportieren:

```sh
sudo keytool -exportcert -rfc \
  -alias davmail \
  -keystore /etc/davmail/secrets/server.p12 \
  -storepass:file /etc/davmail/secrets/server-p12-password \
  -file /tmp/davmailpi.crt
```

`davmailpi.crt` auf jedem Endgeraet als vertrauenswuerdige lokale Zertifizierungsstelle beziehungsweise als vertrauenswuerdiges Serverzertifikat importieren. Der private Schluessel in `server.p12` verlaesst den Pi nicht.

## 4. Dienst starten und pruefen

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now davmail
sudo systemctl status davmail
sudo journalctl -u davmail -n 100 --no-pager
sudo ss -ltnp | grep -E ':(1080|1143|1025)\b'
```

Ein CalDAV-Test vom Mac:

```sh
curl --cacert davmailpi.crt \
  --user 'ADS\bekl0002' \
  --output /tmp/kollege.ics \
  --write-out 'HTTP %{http_code}, %{size_download} Bytes\n' \
  'https://davmailpi.local:1080/users/oliver.waldhorst@h-ka.de/calendar-availability.ics'
```

## 5. Nur im Heimnetz freigeben

Das eigene Netz mit `ip -4 route` bestimmen. Erst SSH erlauben, dann nur die benoetigten DavMail-Ports aus diesem Netz; `192.168.178.0/24` entsprechend ersetzen:

```sh
sudo ufw allow from 192.168.178.0/24 to any port 22 proto tcp
sudo ufw allow from 192.168.178.0/24 to any port 1080 proto tcp
sudo ufw allow from 192.168.178.0/24 to any port 1143 proto tcp
sudo ufw allow from 192.168.178.0/24 to any port 1025 proto tcp
sudo ufw enable
sudo ufw status verbose
```

Keine Portweiterleitung im Internet-Router einrichten. Fuer Thunderbird und andere Geraete gelten danach:

- CalDAV: `https://davmailpi.local:1080/...`
- IMAP: `davmailpi.local`, Port `1143`, SSL/TLS
- SMTP: `davmailpi.local`, Port `1025`, SSL/TLS

Nicht benoetigte Listener in `davmail.properties` durch einen leeren Portwert abschalten und den entsprechenden UFW-Port nicht freigeben.

## Docker auf unterstuetzter Hardware

Im Verzeichnis `src/raspberrypi`:

```sh
cp davmail-docker.properties.example config/davmail.properties
install -m 0600 /pfad/zu/client.p12 secrets/client.p12
install -m 0600 /pfad/zu/server.p12 secrets/server.p12
read -rsp 'Passwort fuer client.p12: ' pw; echo; printf %s "$pw" > secrets/client-p12-password; unset pw
read -rsp 'Passwort fuer server.p12: ' pw; echo; printf %s "$pw" > secrets/server-p12-password; unset pw
printf 'DAVMAIL_BIND_IP=192.168.178.50\n' > .env
docker compose build
docker compose up -d
```

`DAVMAIL_BIND_IP` auf die feste LAN-Adresse des Docker-Hosts setzen. Ohne diese Angabe bindet Compose aus Sicherheitsgruenden nur an `127.0.0.1`. Die beiden Passwortdateien `secrets/client-p12-password` und `secrets/server-p12-password` interaktiv oder mit einem Secret-Manager erzeugen. `config/`, `secrets/` und `.env` werden durch `.gitignore` vom Repository ferngehalten. Normale Docker-Compose-Secrets sind read-only in den Container gemountet, aber nicht automatisch auf dem Host verschluesselt.
