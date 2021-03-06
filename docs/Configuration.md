# Configuration

Certain properties of the application can be customized through the configuration file (default: config.ini).
`Ini` format is used for configuration since it is easily understood even by non-tech-savvy users.
- We will refer to the pairing in the file as `field:value`

The application creates a config file with default values if no existing config file can be found.
- Logging level
- Update interval
- Cloud mode (unreliability)

Most of the variable names are rather straightforward. However, the logging section is slightly more complex and will be elaborated on.

Behaviour to note:
- If missing fields are found in an existing config file, those fields will be added back into the config file, with default values.
  - This also means that existing values in the config file will not be overwritten

# Logging
There are many variables for the different logging levels in the config file:
- Adding class names to the variables will impose a special logging level for that class (priority over the application-wide `loggingLevel`)

For example:
```
...
[Logging]
loggingLevel = INFO
TRACE = ModelManager, SyncManager
ALL =
ERROR =
...
```
Such a configuration will log messages at the `INFO` level throughout the application, except `ModelManager` and `SyncManager` which will log messages at the `TRACE` level.

**Note that these settings do not apply to most of `Config` since these configurations are not yet read during initialization. Therefore the logging will usually be at the default level for `Config`.**

# Adding config variables (For developers)
- Ensure that a default value is assigned to the desired variable.
- Ensure that the config file value is read if it exists (in `set<SectionName>SectionValues`)
- Access the read config value from the global config object obtainable through `Config.getConfig()`
