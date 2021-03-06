package address.updater;

import address.MainApp;
import address.events.UpdaterFinishedEvent;
import address.events.UpdaterInProgressEvent;
import address.main.ComponentManager;
import address.updater.model.UpdateData;
import address.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Checks for update to application
 */
public class UpdateManager extends ComponentManager {
    public static final String UPDATE_DIR = "update";
    private static final AppLogger logger = LoggerManager.getLogger(UpdateManager.class);

    // --- Messages
    private static final String MSG_FAIL_DELETE_UPDATE_SPEC = "Failed to delete previous update spec file";
    private static final String MSG_FAIL_DOWNLOAD_UPDATE = "Downloading update failed";
    private static final String MSG_FAIL_CREATE_UPDATE_SPEC = "Failed to create update specification";
    private static final String MSG_FAIL_EXTRACT_JAR_UPDATER = "Failed to extract JAR updater";
    private static final String MSG_FAIL_MAIN_APP_URL = "Main app download link is broken";
    private static final String MSG_NO_UPDATE_DATA = "There is no update data to be processed";
    private static final String MSG_NO_UPDATE = "There is no update";
    private static final String MSG_NO_VERSION_AVAILABLE = "No version to be downloaded";
    private static final String MSG_NO_NEWER_VERSION = "Version has been downloaded before; will not download again";
    private static final String MSG_DIFF_CHANNEL = "UpdateData is not for this release channel";
    // --- End of Messages

    private static final String JAR_UPDATER_RESOURCE_PATH = "updater/jarUpdater.jar";
    private static final String JAR_UPDATER_APP_PATH = UPDATE_DIR + File.separator + "jarUpdater.jar";
    private static final File DOWNLOADED_VERSIONS_FILE = new File(UPDATE_DIR + File.separator + "downloaded_versions");
    private static final String UPDATE_DATA_ON_SERVER_STABLE =
            "https://raw.githubusercontent.com/HubTurbo/addressbook/stable/UpdateData.json";
    private static final String UPDATE_DATA_ON_SERVER_EARLY =
            "https://raw.githubusercontent.com/HubTurbo/addressbook/early-access/UpdateData.json";
    private static final File UPDATE_DATA_FILE = new File(UPDATE_DIR + File.separator + "UpdateData.json");

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final DependencyTracker dependencyTracker;
    private final BackupManager backupManager;
    private final List<Version> downloadedVersions;

    private boolean isUpdateApplicable;

    public UpdateManager() {
        super();
        this.isUpdateApplicable = false;
        dependencyTracker = new DependencyTracker();
        backupManager = new BackupManager(dependencyTracker);
        downloadedVersions = readDownloadedVersionsFromFile();
    }

    public List<String> getMissingDependencies() {
        return dependencyTracker.getMissingDependencies();
    }

    public void start() {
        logger.info("Starting update manager.");
        pool.execute(backupManager::cleanupBackups);
        pool.execute(this::checkForUpdate);
    }

    private void checkForUpdate() {
        raise(new UpdaterInProgressEvent("Clearing local update specification file", -1));
        try {
            LocalUpdateSpecificationHelper.clearLocalUpdateSpecFile();
        } catch (IOException e) {
            raise(new UpdaterFinishedEvent(MSG_FAIL_DELETE_UPDATE_SPEC));
            logger.debug(MSG_FAIL_DELETE_UPDATE_SPEC);
            return;
        }

        raise(new UpdaterInProgressEvent("Getting data from server", -1));
        Optional<UpdateData> updateData = getUpdateDataFromServer();

        if (!updateData.isPresent()) {
            raise(new UpdaterFinishedEvent(MSG_NO_UPDATE_DATA));
            logger.debug(MSG_NO_UPDATE_DATA);
            return;
        }

        Optional<Version> latestVersion = getLatestVersion(updateData.get());

        if (!latestVersion.isPresent()) {
            raise(new UpdaterFinishedEvent(MSG_NO_VERSION_AVAILABLE));
            logger.debug(MSG_NO_VERSION_AVAILABLE);
            return;
        }

        if (latestVersion.get().isEarlyAccess() != MainApp.IS_EARLY_ACCESS) {
            raise(new UpdaterFinishedEvent(MSG_DIFF_CHANNEL));
            logger.debug(MSG_DIFF_CHANNEL);
            return;
        }

        if (downloadedVersions.contains(latestVersion.get()) ||
                Version.getCurrentVersion().equals(latestVersion.get())) {
            raise(new UpdaterFinishedEvent(MSG_NO_NEWER_VERSION));
            logger.debug(MSG_NO_NEWER_VERSION);
            return;
        }

        raise(new UpdaterInProgressEvent(
                "Collecting all update files that are to be downloaded", -1));
        HashMap<String, URL> filesToBeUpdated;
        try {
            filesToBeUpdated = collectAllUpdateFilesToBeDownloaded(updateData.get());
        } catch (MalformedURLException e) {
            raise(new UpdaterFinishedEvent(MSG_FAIL_MAIN_APP_URL));
            logger.debug(MSG_FAIL_MAIN_APP_URL);
            return;
        }


        if (filesToBeUpdated.isEmpty()) {
            raise(new UpdaterFinishedEvent(MSG_NO_UPDATE));
            logger.debug(MSG_NO_UPDATE);
            return;
        }

        raise(new UpdaterInProgressEvent("Downloading updates", 0.5));
        try {
            downloadAllFilesToBeUpdated(new File(UPDATE_DIR), filesToBeUpdated);
        } catch (IOException e) {
            raise(new UpdaterFinishedEvent(MSG_FAIL_DOWNLOAD_UPDATE));
            logger.debug(MSG_FAIL_DOWNLOAD_UPDATE);
            return;
        }

        raise(new UpdaterInProgressEvent("Finalizing updates", 0.85));

        try {
            createUpdateSpecification(filesToBeUpdated);
        } catch (IOException e) {
            raise(new UpdaterFinishedEvent(MSG_FAIL_CREATE_UPDATE_SPEC));
            logger.debug(MSG_FAIL_CREATE_UPDATE_SPEC);
            return;
        }

        try {
            extractJarUpdater();
        } catch (IOException e) {
            raise(new UpdaterFinishedEvent(MSG_FAIL_EXTRACT_JAR_UPDATER));
            logger.debug(MSG_FAIL_EXTRACT_JAR_UPDATER);
            return;
        }

        raise(new UpdaterFinishedEvent("Update will be applied on next launch"));
        this.isUpdateApplicable = true;

        updateDownloadedVersionsData(latestVersion.get());
    }

    /**
     * Get update data
     */
    private Optional<UpdateData> getUpdateDataFromServer() {
        URL updateDataUrl;

        try {
            if (MainApp.IS_EARLY_ACCESS) {
                updateDataUrl = new URL(UPDATE_DATA_ON_SERVER_EARLY);
            } else {
                updateDataUrl = new URL(UPDATE_DATA_ON_SERVER_STABLE);
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
            logger.debug("Update data URL is invalid");
            return Optional.empty();
        }

        try {
            downloadFile(UPDATE_DATA_FILE, updateDataUrl);
        } catch (IOException e) {
            logger.debug("Failed to download update data");
            return Optional.empty();
        }

        try {
            return Optional.of(JsonUtil.fromJsonString(FileUtil.readFromFile(UPDATE_DATA_FILE), UpdateData.class));
        } catch (IOException e) {
            logger.debug("Failed to parse update data from json file.");
            e.printStackTrace();
        }

        return Optional.empty();
    }

    private Optional<Version> getLatestVersion(UpdateData updateData) {
        try {
            return Optional.of(Version.fromString(updateData.getVersion()));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        return Optional.empty();
    }

    private HashMap<String, URL> collectAllUpdateFilesToBeDownloaded(UpdateData updateData)
            throws MalformedURLException {
        OsDetector.Os machineOs = OsDetector.getOs();

        if (machineOs == OsDetector.Os.UNKNOWN) {
            logger.debug("OS not supported for updating");
            return new HashMap<>();
        }

        HashMap<String, URL> filesToBeDownloaded = new HashMap<>();

        URL mainAppDownloadLink;

        mainAppDownloadLink = updateData.getDownloadLinkForMainApp();

        filesToBeDownloaded.put("addressbook.jar", mainAppDownloadLink);

        updateData.getLibraries().stream()
                .filter(libDesc -> libDesc.getOs() == OsDetector.Os.ANY || libDesc.getOs() == OsDetector.getOs())
                .filter(libDesc -> !FileUtil.isFileExists("lib/" + libDesc.getFilename()))
                .forEach(libDesc -> filesToBeDownloaded.put("lib/" + libDesc.getFilename(), libDesc.getDownloadLink()));

        return filesToBeDownloaded;
    }

    /**
     * @param updateDir directory to store downloaded updates
     */
    private void downloadAllFilesToBeUpdated(File updateDir, HashMap<String, URL> filesToBeUpdated)
            throws IOException {
        if (!FileUtil.isDirExists(updateDir)) {
            try {
                Files.createDirectory(updateDir.toPath());
            } catch (IOException e) {
                logger.debug("Failed to create update directory");
                e.printStackTrace();
            }
        }

        for (String destFile : filesToBeUpdated.keySet()) {
            try {
                downloadFile(new File(updateDir.toString(), destFile), filesToBeUpdated.get(destFile));
            } catch (IOException e) {
                logger.debug("Failed to download an update file, aborting update.");
                throw e;
            }
        }
    }

    private void downloadFile(File targetFile, URL source) throws IOException {
        try (InputStream in = source.openStream()) {
            if (!FileUtil.createFile(targetFile)) {
                logger.debug("File '{}' already exists", targetFile.getName());
            }
            Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.debug("Failed to download update for {}",
                    targetFile.toString());
            e.printStackTrace();
            throw e;
        }
    }

    private void createUpdateSpecification(HashMap<String, URL> filesToBeUpdated) throws IOException {
        LocalUpdateSpecificationHelper.saveLocalUpdateSpecFile(
                filesToBeUpdated.keySet().stream().collect(Collectors.toList())
        );
    }

    private void extractJarUpdater() throws IOException {
        File jarUpdaterFile = new File(JAR_UPDATER_APP_PATH);

        if (!jarUpdaterFile.exists() && !jarUpdaterFile.createNewFile()) {
            throw new IOException("Failed to create Jar Updater empty file");
        }

        try (InputStream in = UpdateManager.class.getClassLoader().getResourceAsStream(JAR_UPDATER_RESOURCE_PATH)) {
            Files.copy(in, jarUpdaterFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.debug("Failed to extract jar updater");
            throw e;
        }
    }

    private void updateDownloadedVersionsData(Version latestVersionDownloaded) {
        downloadedVersions.add(latestVersionDownloaded);
        writeDownloadedVersionsToFile();
    }

    private void writeDownloadedVersionsToFile() {
        try {
            if (FileUtil.isFileExists(DOWNLOADED_VERSIONS_FILE.toString())) {
                FileUtil.createFile(DOWNLOADED_VERSIONS_FILE);
            }

            FileUtil.writeToFile(DOWNLOADED_VERSIONS_FILE, JsonUtil.toJsonString(downloadedVersions));
        } catch (JsonProcessingException e) {
            logger.debug("Failed to convert downloaded version to JSON");
            e.printStackTrace();
        } catch (IOException e) {
            logger.debug("Failed to write downloaded version to file");
            e.printStackTrace();
        }
    }

    private List<Version> readDownloadedVersionsFromFile() {
        try {
            return JsonUtil.fromJsonStringToList(FileUtil.readFromFile(DOWNLOADED_VERSIONS_FILE), Version.class);
        } catch (IOException e) {
            logger.warn("Failed to read downloaded version from file: {}", e);
        }

        return new ArrayList<>();
    }

    public void applyUpdate() {
        if (!this.isUpdateApplicable) {
            return;
        }

        if (!backupManager.createBackupOfCurrentApp()) {
            return;
        }

        String restarterAppPath = JAR_UPDATER_APP_PATH;
        String localUpdateSpecFilepath = System.getProperty("user.dir") + File.separator +
                LocalUpdateSpecificationHelper.getLocalUpdateSpecFilepath();
        String cmdArg = String.format("--update-specification=%s --source-dir=%s",
                localUpdateSpecFilepath, UPDATE_DIR);

        String command = String.format("java -jar %1$s %2$s", restarterAppPath, cmdArg);

        try {
            Runtime.getRuntime().exec(command);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        applyUpdate();
    }
}
