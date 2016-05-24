package address.guitests;

import address.TestApp;
import javafx.stage.Stage;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;

import java.util.concurrent.TimeoutException;

public class GuiTestBase extends FxRobot {
    @BeforeClass
    public static void setupSpec() {
        try {
            FxToolkit.registerPrimaryStage();
            FxToolkit.hideStage();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }
    }

    @Before
    public void setup() throws Exception {
        FxToolkit.setupApplication(TestApp.class);

        // since we cannot handle custom test data files
        // we assume that the default data file exists, and we append data to it
        clickOn("File").clickOn("Append Sample Data");
    }

    @After
    public void cleanup() throws TimeoutException {
        FxToolkit.cleanupStages();
    }
}
