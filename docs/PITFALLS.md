# Itara — Common Pitfalls

This document captures implementation mistakes that are easy to make and hard
to debug. Each entry describes the symptom, the root cause, and the fix.

If you hit something that took you more than ten minutes to figure out and
is not listed here, add it.

---

## 1. SPI implementation not recognized — "does not implement ItaraTransport"

### Symptom

The agent finds the descriptor file and loads the jar, but logs a warning like:

```
[Itara] WARNING: io.itara.transport.http.HttpTransport does not implement ItaraTransport — skipping.
```

followed by:

```
[Itara] FATAL: No transport registered for type 'http'. Available transports: []
```

This happens even though the class clearly implements the interface.

### Root cause

Java's `instanceof` and `Class.isAssignableFrom()` use classloader identity,
not structural equality. Two classes with the same fully qualified name loaded
by different classloaders are different classes as far as the JVM is concerned.

Itara loads SPI jars from `itara.lib.dir` via `ItaraClassLoader`, a child-first
`URLClassLoader`. If `itara-common` is bundled (shaded) inside the SPI jar, the
SPI jar contains its own private copy of `ItaraTransport`, `ItaraSerializer`,
`ItaraObserver`, and all other common interfaces. These copies are loaded by
the child classloader. The agent holds references to the same interfaces loaded
by the parent (system) classloader. When the agent checks whether the loaded
implementation implements its copy of the interface, the answer is no — the
implementation implements a different copy.

The result is a silent skip with a misleading warning, followed by a fatal
error when the missing transport is referenced in the wiring config.

### Fix

**Never shade `itara-common` into any SPI jar.**

`itara-common` is the shared interface boundary. It must always be loaded by
the parent classloader and exist as exactly one copy in the JVM. Every SPI
jar must exclude it from shading explicitly.

In the Maven Shade plugin configuration of every SPI module:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <configuration>
        <artifactSet>
            <excludes>
                <exclude>io.itara:itara-common</exclude>
            </excludes>
        </artifactSet>
    </configuration>
</plugin>
```

### Verification

After building a SPI jar, verify that no `itara-common` classes are present:

```bash
jar tf your-spi-jar.jar | grep "io/itara/common"
```

This should return nothing. Any output means `itara-common` classes are still
shaded in and the implementation will not be recognized at runtime.

### Rule

> `itara-common` is never shaded into anything. Every other dependency of a
> SPI implementation may be shaded. `itara-common` may not.

---

## 2. SPI dependencies not found at runtime — ClassNotFoundException

### Symptom

The agent loads the SPI jar but fails during instantiation with:

```
Caused by: java.lang.ClassNotFoundException: com.fasterxml.jackson.databind.Module
```

or similar, for a class that belongs to a dependency of the SPI implementation.

### Root cause

`mvn package` produces a thin jar containing only the classes of the module
itself. Dependencies like Jackson, a JMS client, or a Kafka client are present
in the local Maven repository but are not bundled into the jar. When
`ItaraClassLoader` loads the SPI jar, it finds the implementation class but
cannot find the dependency classes.

This is the opposite problem to pitfall 1. Pitfall 1 is caused by shading too
much (`itara-common` included). This is caused by shading too little (external
dependencies not included).

### Fix

Shade all external dependencies into the SPI jar, while explicitly excluding
`itara-common`. The SPI jar must be self-contained with respect to everything
except `itara-common`.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <artifactSet>
                    <excludes>
                        <!-- Never shade itara-common — see pitfall 1 -->
                        <exclude>io.itara:itara-common</exclude>
                    </excludes>
                </artifactSet>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Verification

After building, verify that the dependency classes are present in the jar:

```bash
# For itara-serializer-json — Jackson should be present
jar tf itara-serializer-json.jar | grep "com/fasterxml/jackson"

# itara-common should not be present
jar tf itara-serializer-json.jar | grep "io/itara/common"
```

First command should produce output. Second should produce nothing.

---

## 3. META-INF descriptor missing from shaded jar

### Symptom

The SPI jar is on the classpath, dependencies are present, but the
implementation is never registered. No warning is logged — the descriptor
is simply not found.

### Root cause

The Maven Shade plugin can drop or merge `META-INF` files unpredictably,
especially when multiple jars are merged. The `META-INF/itara/transport`
(or `serializer`, `observer`, `activator`) descriptor file that tells the
agent which class to load may be silently lost during shading.

### Fix

After every build, verify the descriptor is present in the shaded jar:

```bash
jar tf your-spi-jar.jar | grep "META-INF/itara"
```

Expected output for a transport jar:

```
META-INF/itara/transport
```

If the descriptor is missing, add a `ServicesResourceTransformer` or verify
that the `src/main/resources/META-INF/itara/` directory and descriptor file
exist in the module source tree before shading.

---

## 4. PowerShell curl alias intercepts real curl on Windows

### Symptom

Running a curl command on Windows PowerShell produces an error like:

```
Invoke-WebRequest : Cannot bind parameter 'Headers'. Cannot convert the
"Content-Type: application/json" value of type "System.String" to type
"System.Collections.IDictionary".
```

### Root cause

PowerShell aliases `curl` to `Invoke-WebRequest`, its own HTTP cmdlet with a
completely different parameter syntax. The real `curl.exe` installed with
Windows 10 and later is shadowed by this alias.

### Fix

Use `curl.exe` explicitly to bypass the alias:

```powershell
curl.exe -X POST http://localhost:8081/itara/calculator/add `
         -H "Content-Type: application/json" `
         -d "[3, 4]"
```

To verify which curl you are calling:

```powershell
Get-Command curl
```

If the output shows `CommandType: Alias`, you are hitting PowerShell's version.
If it shows `CommandType: Application`, you are using the real curl.

---

*Last updated: April 2026*
*Add new entries as you find them. Symptom first, root cause second, fix third.*
