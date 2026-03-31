# Azure Service Bus Connector for WSO2 MI

Connector for Azure Service Bus

### Compatibility

| Connector Version | Supported WSO2 MI Version |
|-------------------|---------------------------|
| 0.1.0             | MI 4.5.0, MI 4.4.0        |


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
