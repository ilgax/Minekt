# Data and the web

## Settings

Values the player can change without editing the script:

```kotlin
var range by setting(16, 1..64)                              // a slider
var autoEat by setting(true)                                 // a toggle
var speed by setting(1.0, 0.5..3.0, step = 0.25)             // a decimal slider
var mode by setting("walk", listOf("walk", "sprint"))        // a choice
var greeting by setting("Hello", label = "Greeting text")    // a text box

onSettingChange { (key, value) -> echo("$key is now $value") }
```

- They show up in the script manager (the script's **Settings** button) and with `\settings <script>`, also while
  the script isn't running, once it has run once. The label comes from the property name (`autoEat` → "Auto
  eat") unless you pass `label`.
- Changes are saved in `minekt/settings/<script>.json` and reach a running script right away: the property
  always reads the current value, and `onSettingChange` tells the script (on its own thread) when the player
  changes one. Assigning the property in the script saves it too.
- Values are kept within their range or options. A value edited by hand that doesn't fit any more goes back to
  the default; **Reset to defaults** on the screen does that for all of them.
- Settings a script no longer declares are dropped from the file the next time it ends normally.

## Remembering things between runs

```kotlin
val homes by persistent { mutableMapOf<String, Vec3>() }               // in-place changes are saved too
var runs by persistent { 0 }; runs++
val seen by persistent(perWorld = true) { mutableSetOf<String>() }     // one value per world/server
var motd by persistent(store = "shared") { "" }                         // shared between scripts
```

- The value is restored when the script starts, or created by the `{ default }` the first time.
- Saved to `.minecraft/minekt/data/<script>.json` (readable JSON). That happens when you assign a `var`, every
  few seconds for in-place changes, when the script ends, and when the game closes. Call `save()` to force it.
- Lists, sets, maps, strings, numbers, booleans, Minekt's types (`Vec3`, `BlockPos`, ...) and your own data
  classes all work, and come back as their declared types.
- `perWorld` keys singleplayer worlds by save folder and servers by address.
- If a saved value no longer fits the declared type (you changed it), the script warns and starts from the
  default. An unreadable file is moved aside to `<name>.json.broken`, not deleted.

## Web requests

```kotlin
httpPost(webhookUrl, mapOf("content" to "Found diamonds at ${player.position}"))   // a Discord webhook
data class Weather(val temperature: Double, val description: String)
val weather = httpGet("https://api.example.com/weather?city=Oslo").json<Weather>()
val page = httpGet("https://example.com", headers = mapOf("Accept" to "text/html"))
if (!page.ok) echo("status ${page.status}")
http("DELETE", "https://api.example.com/items/3", headers = mapOf("Authorization" to "Bearer $token"))
```

- `httpPost` sends a String as plain text and anything else (maps, lists, data classes) as JSON.
- Every request returns its `status`, `body` and `headers` whatever the status: check `ok` or call `orThrow()`.
  Only network failures throw (no internet, unknown host, timeout; the default `timeout` is 30 seconds).
- `json<T>()` parses the body into a data class, `Map<String, Any?>`, `List<…>`, …
- Requests block the script until the answer arrives. Use `launch { }` to keep going meanwhile; `\kill`
  stops a waiting request.
- Redirects are followed, and the JVM's proxy settings (`-Dhttps.proxyHost=…`) are used if you have any.

## Libraries from Maven

Any JVM library on Maven Central (or another Maven repository) can be used from a script:

```kotlin
@file:DependsOn("org.apache.commons:commons-text:1.12.0")
@file:Repository("https://jitpack.io")                 // optional: more repositories

import org.apache.commons.text.WordUtils
echo(WordUtils.capitalizeFully("hello from maven"))
```

- Libraries are resolved with Kotlin's own Maven resolver, so the full Maven rules apply: the libraries they
  need in turn, parent POMs, BOMs, version conflicts, and your `~/.m2/settings.xml` (mirrors, private
  repositories with passwords).
- Downloads go into the standard Maven cache, `~/.m2/repository`: once per version, shared with any Java or
  Kotlin tools on the computer, and available offline afterwards.
- Each script sees only the libraries it asked for, so two scripts can use different versions of one library.
  Things the game already has (Gson, Guava, the Kotlin standard library, …) come from the game.
- Use exact versions (`1.12.0`, not `1.+`), so a script behaves the same everywhere.
- Kotlin multiplatform libraries need their JVM artifact: `org.jetbrains.kotlinx:kotlinx-datetime-jvm:…`.
- The resolver itself is a few MB of Maven code that's only loaded when a script first uses `@DependsOn`.
  The full jar has it built in; the small jar downloads it on first use (pinned and hash-checked, like the
  compiler).
- A library is code from the internet, like a script. The `\install` preview lists `@DependsOn` among what a
  script can do.

## Sharing code between scripts

```kotlin
@file:Import("lib/util.kts")   // path relative to this script

echo(shout("hello"))
```
