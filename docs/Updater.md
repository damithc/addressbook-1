# Updater
Updater has the main responsibility of updating the application to a newer version. In doing so,
it does several things:

- update the application and the components it depends on, i.e. the libraries JAR that it uses
- create a backup of the application before updating to ensure that user can use the application even if an update fails
- maintain dependencies of current version and backup versions and clean up dependencies no longer used

## How Updater updates the application
<img src="images/How Updater Works.jpg" width="600">