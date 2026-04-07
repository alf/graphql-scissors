# graphql-scissors

## Build environment

- Maven 3.9.11 at `/opt/maven`, Java 21 as the default JVM.
- `JAVA_TOOL_OPTIONS` is pre-set with proxy credentials, so plain `java` commands pick them up automatically.
- Maven needs a separate proxy config in `~/.m2/settings.xml` (not tracked in git). Create it once per new session if it's missing:

```bash
PROXY_USER=$(echo "$http_proxy" | sed 's|http://||' | sed 's|@.*||' | sed 's|:.*||')
PROXY_PASS=$(echo "$http_proxy" | sed 's|http://||' | sed 's|@.*||' | sed 's|^[^:]*:||')

cat > ~/.m2/settings.xml << XMLEOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <proxies>
    <proxy>
      <id>https-proxy</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>21.0.0.129</host>
      <port>15004</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>http-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>21.0.0.129</host>
      <port>15004</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
XMLEOF
```

The wagon transport (configured in `.mvn/maven.config`) is committed and avoids intermittent 407 errors from the proxy.

## Running tests

```bash
mvn test
```

Test fixtures go in `src/test/resources/`. The parameterized test discovers any `*.query.graphql` file there and expects two siblings:
- `schema.graphql` — full schema for that test case
- `<name>.expected.graphql` — expected subset output
