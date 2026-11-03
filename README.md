# openfire-usertune-presence

Openfire plugin that fixes **XEP-0118 User Tune** delivery to roster contacts.

By default, Openfire only sends PEP notifications to contacts whose CAPS advertise
`http://jabber.org/protocol/tune+notify`. In practice this means most clients never
receive tune events from local users, while the same clients work correctly via
federation with ejabberd servers.

This plugin bypasses the CAPS check and replicates ejabberd's exact behavior:

- **On tune publish** — broadcasts a `type="headline"` PEP event message directly
  to every online resource of every roster contact.
- **On contact coming online** — sends them the publisher's last known tune
  immediately (`send_last_published_item: on_sub_and_presence`).
- **On publisher going offline** — sends an empty `<tune/>` to all contacts,
  clearing the displayed track.
- **Multi-resource aware** — the tune is tied to the resource that published it.
  If that resource disconnects but others remain online, the tune is re-bound to
  another resource and not cleared.

---

## Compatibility

| Openfire | Java |
|----------|------|
| 4.6.x – 4.9.x | 11+ |
| 5.0.x+ | 17+ |

Compatible clients: **Gajim**, **Monocles Chat**, **Dino**, **Conversations**,
**Psi+**, **Vacuum-IM** — any client that implements XEP-0118.

---

## Installation

### Via Admin Console (recommended)

1. Download `usertunepresence.jar` from [Releases](../../releases).
2. Open `Admin Console → Plugins → Upload Plugin`.
3. Upload the JAR. No restart required.

### Manual

```bash
mkdir -p $OPENFIRE_HOME/plugins/usertunepresence
cp usertunepresence.jar $OPENFIRE_HOME/plugins/usertunepresence/
```

---

## Build from source

Requires Java 11+ and Maven 3.5+.

```bash
git clone https://github.com/XHub157/openfire-usertune-presence.git
cd openfire-usertune-presence
mvn clean package
```

Output: `target/usertunepresence.jar`

---

## How it works

```
Client (Gajim / Monocles)         Openfire + plugin          Roster contacts
        │                               │                           │
        │  IQ pubsub publish tune       │                           │
        │ ────────────────────────────► │                           │
        │                               │  normalize tune fields    │
        │                               │  cache tune + resource    │
        │                               │                           │
        │                               │  message headline ───────►│ (each online resource)
        │                               │                           │
        │                    ┌──────────┴──────────┐               │
        │  presence available│                     │               │
        │ ───────────────────►  send last tune     │──────────────►│
        │                    └─────────────────────┘               │
        │                               │                           │
        │  presence unavailable         │                           │
        │ ────────────────────────────► │  no other resources?      │
        │                               │  remove from cache        │
        │                               │  send empty <tune/> ─────►│
```

### Tune normalization

Only standard XEP-0118 fields are forwarded in the correct order:
`artist`, `title`, `album`, `track`, `length`, `rating`, `uri`.

Non-standard fields (e.g. `<source>` added by Mail.ru, VK) are stripped
to ensure maximum client compatibility.

---

## Logging

```
INFO  UserTunePresencePlugin: зарегистрирован интерцептор.
INFO  UserTunePresencePlugin: sigma@example.com публикует tune, рассылаем контактам
INFO  UserTunePresencePlugin: tune-нотификация отправлена 5 ресурсам
INFO  UserTunePresencePlugin: sigma@example.com офлайн, tune очищен
```

To enable DEBUG level: `Admin Console → Server → Server Settings → Log Settings`.

---

## Known limitations

- The in-memory tune cache is cleared on server restart. Clients will receive
  the current tune on their next presence update or tune publish.
- The plugin does not register a PEP node — it works alongside Openfire's
  built-in PEP engine, not instead of it.

---

## License
GNU General Public License v3.0 — see [LICENSE](LICENSE).

Free to use and modify. Derivative works must be distributed
under the same GPLv3 license with attribution to the original author.
