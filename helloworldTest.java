///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.junit.jupiter:junit-jupiter-api:5.10.0
//DEPS org.junit.jupiter:junit-jupiter-engine:5.10.0
//DEPS org.junit.platform:junit-platform-launcher:1.10.0
//DEPS org.junit.platform:junit-platform-reporting:1.10.0

//SOURCES helloworld.java

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.LoggingListener;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

import static java.lang.System.out;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.PrintWriter;
import java.nio.file.Path;

// JUnit5 Test class for helloworld
public class helloworldTest {

    // Define each Unit test here and run them separately in the IDE
    @Test
    public void testhelloworld() {
            assertEquals(1,2, "You should add some testing code for helloworld here!");
    }   

    // Run all Unit tests with JBang with ./helloworld.java
    public static void main(final String... args) throws Exception {
        final LauncherDiscoveryRequest request =
                LauncherDiscoveryRequestBuilder.request()
                        .selectors(selectClass(helloworldTest.class))
                        .build();
        final Launcher launcher = LauncherFactory.create();
        final LoggingListener logListener = LoggingListener.forBiConsumer((t,m) -> {
            System.out.println(m.get());
            if(t!=null) {
                t.printStackTrace();
            };
        });
        final SummaryGeneratingListener execListener = new SummaryGeneratingListener();
        final LegacyXmlReportGeneratingListener xmlListener = new LegacyXmlReportGeneratingListener(
                Path.of("."), new PrintWriter(System.out));
        launcher.registerTestExecutionListeners(execListener, logListener, xmlListener);
        launcher.execute(request);
        execListener.getSummary().printTo(new java.io.PrintWriter(out));
    }
}
