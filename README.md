# Azure Service Bus Connector for WSO2 MI

Connector for Azure Service Bus

### Compatibility

| Connector Version | Supported WSO2 MI Version |
|-------------------|---------------------------|
| 1.0.0             | MI 4.5.0, MI 4.4.0        |


## Documentation

Please refer to the [Azure Service Bus Connector Overview](https://mi.docs.wso2.com/en/latest/reference/connectors/) documentation.

### Prerequisites

- JDK 17
- Maven 3.8+

### Building From the Source

This project depends on Ballerina packages hosted on GitHub Packages, which requires authentication.

#### Configure Maven settings

Add the following server entry to your `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-ballerina</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

The personal access token needs the `read:packages` scope.


#### Build

1. Get a clone or download the source from [Github](https://github.com/wso2-extensions/mi-connector-asb/).
2. Run the following Maven command from the `mi-connector-asb` directory: `mvn clean install`.
3. The ZIP with the Azure Service Bus connector is created in the `mi-connector-asb/target` directory.

### Updating the `ballerinax-asb` JAR

When a new version of the underlying `ballerinax/asb` Ballerina package is released and **only the bundled JAR needs to be refreshed** (no connector logic changes), use the `Update ballerinax-asb JAR` GitHub Actions workflow instead of updating the JAR by hand.

The workflow regenerates the connector JAR with the [`migen`](https://central.ballerina.io/) tool and opens a PR with the updated JAR, its checksums/POM, and the version bumps in `pom.xml`.

**Steps:**

1. Go to **Actions → Update ballerinax-asb JAR → Run workflow**.
2. Select the branch to run against (the PR is opened against this branch).
3. Provide the inputs:
   - **`asb_version`** (required) – the target `ballerinax/asb` version to generate, e.g. `3.8.5`.
   - **`ballerina_version`** (optional) – defaults to `2201.10.5`.
   - **`migen_version`** (optional) – defaults to `0.9.0`.
4. Run the workflow. It will:
   - Set up Ballerina and pull the `migen` tool.
   - Generate the connector via `bal migen connector --package ballerinax/asb:<asb_version>`.
   - Replace the JAR under `repo/io/ballerina/ballerinax-asb/<version>/` (with its `.sha1` and `.pom`), remove the previous version, and update the version references in `pom.xml`.
   - Run `mvn clean install` to verify the build.
   - Open a pull request with the changes.
5. Review and merge the generated PR.


### Running Integration Tests

The project includes a comprehensive test suite for validating all connector operations.

**Prerequisites:**
- Docker
- Azure Service Bus namespace with connection string

**Setup and run:**

1. Configure your Azure Service Bus credentials in `tests/.env`:
   ```properties
   connection_string=Endpoint=sb://<namespace>.servicebus.windows.net/;SharedAccessKeyName=<policy>;SharedAccessKey=<key>
   topic_name=<your-topic-name>
   subscription_name=<your-subscription-name>
   ```

2. Run the integration tests:
   ```bash
   mvn clean install -Pintegration-test
   ```

This builds the connector, starts a WSO2 MI instance in Docker with the connector deployed, runs all API tests, and cleans up automatically.

For more details, see [tests/README.md](tests/README.md).
